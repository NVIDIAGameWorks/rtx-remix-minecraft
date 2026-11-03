/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Bakes the starfield the end portal and end gateway surfaces are drawn with.
 *
 * <p>Vanilla draws both surfaces with position-only vertices and a shader
 * ({@code rendertype_end_portal.fsh}) that sums layers of
 * {@code textures/entity/end_portal.png}, each scaled, rotated and tinted, over
 * {@code textures/environment/end_sky.png}, projected in screen space: the pattern
 * stays put on screen while the camera moves. A path-traced hit has no screen
 * position to project from, and reflections and refractions see the surface from
 * elsewhere, so Remix gets the same sum baked once into a texture laid over the
 * world instead: {@link #TILE_BLOCKS} blocks per repeat, the tile spanning
 * {@link #SCREENS_PER_TILE} of vanilla's screen widths. Fixed to the world, the
 * pattern matches vanilla's scale at one distance only and does not stay put on
 * screen.
 *
 * <p>For the tile to repeat without a seam, every layer must itself repeat a whole
 * number of times across it. Vanilla's layers are rotated by arbitrary angles, so
 * each layer's scale and rotation is rounded to the nearest integer lattice step
 * over the tile. That moves a layer by up to a third of its scale (layer 15) and up
 * to 14 degrees (layer 14), which on a random speckle texture is indistinguishable
 * from the original. Vanilla also scrolls the layers slowly with the time of day;
 * the bake keeps the pattern at time zero.
 *
 * <p>The pixels come from the textures the resource packs supply, so a pack that
 * replaces either texture changes the surface, and baking reads them off the render
 * thread. The surface ids name these baked textures, never a pack resource.
 */
final class EndPortalSurface {

    static final Identifier PORTAL_TEXTURE_ID =
            Identifier.fromNamespaceAndPath("rtxremix", "end_portal_surface");
    static final Identifier GATEWAY_TEXTURE_ID =
            Identifier.fromNamespaceAndPath("rtxremix", "end_gateway_surface");
    static final Optional<Identifier> PORTAL_TEXTURE = Optional.of(PORTAL_TEXTURE_ID);
    static final Optional<Identifier> GATEWAY_TEXTURE = Optional.of(GATEWAY_TEXTURE_ID);

    /** The {@code PORTAL_LAYERS} shader define of {@code RenderPipelines.END_PORTAL}. */
    static final int PORTAL_LAYERS = 15;
    /** The {@code PORTAL_LAYERS} shader define of {@code RenderPipelines.END_GATEWAY}. */
    static final int GATEWAY_LAYERS = 16;

    /**
     * Blocks the tile spans before it repeats, along both axes of a horizontal face and
     * along the horizontal axis of a vertical one. Vanilla's pattern spans one screen
     * width whatever the distance; at 24 blocks a screen it matches vanilla's speck
     * sizes from about eight blocks away, where a block covers some 60 pixels of a
     * 1600-pixel window. At 4 blocks a screen the specks came out a fraction of
     * vanilla's size and blurred into noise.
     */
    static final int TILE_BLOCKS = 48;
    /**
     * Blocks the tile spans vertically on a vertical face. Vanilla stretches its pattern
     * to the window, so on a 16:9 screen its specks are 16:9 wide; a vertical face's
     * horizontal axis is the screen's for an upright camera, so it is squeezed to match.
     * A horizontal face has no fixed screen axis and stays square, and looked at from
     * above at an angle, perspective widens its specks much as vanilla's are.
     */
    static final int SIDE_TILE_HEIGHT_BLOCKS = TILE_BLOCKS * 9 / 16;
    /**
     * Vanilla screen widths the tile spans. Two keeps every layer's lattice rounding
     * under about ten percent of scale except one; at one the largest layers would
     * round to half or double their size.
     */
    static final int SCREENS_PER_TILE = 2;
    /**
     * Texels along each side of the tile, about 43 per block. Layer 1 repeats 8.5 times
     * across the tile, 2176 of its texels, so a smaller tile drops more of its
     * single-texel specks.
     */
    static final int TILE_SIZE = 2048;
    /**
     * Emissive intensity of the surface material, which reflects nothing. Vanilla's
     * portal reads as a dark field with bright specks wherever it is seen, but here
     * the scene's auto-exposure decides how bright emission looks. At 4 the field at
     * night is about as dark as vanilla's; at 10 it glowed cyan, and at the entity
     * default of 100 the whole surface turned pale blue. Under noon exposure the
     * specks dim to near black at any value that keeps the night field dark.
     */
    static final float EMISSIVE_INTENSITY = 4.0f;
    /**
     * How far each baked texel is pulled toward its own grey. Vanilla's colours are
     * display values; as emission they pass through Remix's exposure and tone mapping,
     * after which the undesaturated field and its specks came out markedly more
     * saturated than vanilla's (0.89 against 0.76 HSV saturation for the night field).
     * A half matched vanilla's saturation but turned the brightest specks white.
     */
    static final float DESATURATION = 0.3f;

    private static final Identifier SKY_TEXTURE =
            Identifier.withDefaultNamespace("textures/environment/end_sky.png");
    private static final Identifier PORTAL_TEXTURE_SOURCE =
            Identifier.withDefaultNamespace("textures/entity/end_portal/end_portal.png");

    /** {@code COLORS} in {@code rendertype_end_portal.fsh}, as RGB triples. */
    private static final float[] COLORS = {
            0.022087f, 0.098399f, 0.110818f,
            0.011892f, 0.095924f, 0.089485f,
            0.027636f, 0.101689f, 0.100326f,
            0.046564f, 0.109883f, 0.114838f,
            0.064901f, 0.117696f, 0.097189f,
            0.063761f, 0.086895f, 0.123646f,
            0.084817f, 0.111994f, 0.166380f,
            0.097489f, 0.154120f, 0.091064f,
            0.106152f, 0.131144f, 0.195191f,
            0.097721f, 0.110188f, 0.187229f,
            0.133516f, 0.138278f, 0.148582f,
            0.070006f, 0.243332f, 0.235792f,
            0.196766f, 0.142899f, 0.214696f,
            0.047281f, 0.315338f, 0.321970f,
            0.204675f, 0.390010f, 0.302066f,
            0.080955f, 0.314821f, 0.661491f
    };

    /** {@link #layerLattice} for each layer, the first at index 0. */
    private static final int[][] LATTICE = new int[GATEWAY_LAYERS][];
    static {
        for (int i = 0; i < GATEWAY_LAYERS; i++) {
            LATTICE[i] = layerLattice(i + 1);
        }
    }

    /** A texture's pixels as packed {@code 0xRRGGBB}, row-major from the image's top row. */
    record Image(int[] rgb, int width, int height) {}

    /**
     * Bakes in flight or done but not yet handed over, by surface id. Render thread
     * only; the futures complete on worker threads. A reload drops them all, so a bake
     * that finishes for the previous packs is never read.
     */
    private final Map<Identifier, CompletableFuture<byte[]>> bakes = new HashMap<>();

    static boolean isSurfaceTexture(Identifier textureId) {
        return PORTAL_TEXTURE_ID.equals(textureId) || GATEWAY_TEXTURE_ID.equals(textureId);
    }

    /**
     * The baked RGBA tile for a surface id, {@link #TILE_SIZE} square, or null while it
     * is still baking. The first call starts the bake. The tile is handed over once and
     * forgotten, since the caller uploads it; asking again bakes it again.
     *
     * @throws IllegalStateException when the bake failed; the caller should not ask again
     */
    byte[] poll(Identifier surfaceId, ResourceManager resources) {
        CompletableFuture<byte[]> bake = bakes.computeIfAbsent(surfaceId, id -> {
            int layers = GATEWAY_TEXTURE_ID.equals(id) ? GATEWAY_LAYERS : PORTAL_LAYERS;
            return CompletableFuture.supplyAsync(() -> desaturate(bake(
                    read(resources, SKY_TEXTURE), read(resources, PORTAL_TEXTURE_SOURCE),
                    layers, TILE_SIZE), DESATURATION));
        });
        if (!bake.isDone()) return null;
        bakes.remove(surfaceId);
        try {
            return bake.get();
        } catch (ExecutionException | CompletionException e) {
            throw new IllegalStateException("Could not bake " + surfaceId, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Forgets every bake, for a resource reload. */
    void clear() {
        bakes.clear();
    }

    private static Image read(ResourceManager resources, Identifier id) {
        Resource resource = resources.getResource(id)
                .orElseThrow(() -> new IllegalStateException("Missing texture " + id));
        try (InputStream in = resource.open(); NativeImage image = NativeImage.read(in)) {
            int width = image.getWidth();
            int height = image.getHeight();
            int[] rgb = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    rgb[y * width + x] = image.getPixel(x, y) & 0xFFFFFF;
                }
            }
            return new Image(rgb, width, height);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + id, e);
        }
    }

    /**
     * The integer lattice step {@code (a, b)} of a layer over the tile: tile coordinates
     * {@code (x, y)} in [0, 1) sample the layer's texture at {@code (a x - b y, b x + a y)}
     * plus the layer's offset, so one tile is a whole number of the layer's repeats.
     *
     * <p>Vanilla's layer {@code l} maps a screen coordinate through a rotation by
     * {@code (4321 l * l + 9 l) * 2} degrees and a scale of {@code (4.5 - l / 4) * 2}, then
     * halves it in {@code SCALE_TRANSLATE}; over {@link #SCREENS_PER_TILE} screens that
     * becomes the vector rounded here.
     */
    static int[] layerLattice(int layer) {
        double scale = (4.5 - layer / 4.0) * SCREENS_PER_TILE;
        // Reduced to one turn in integer math first: the raw angle reaches about two
        // million degrees, and layer 15's whole number of turns must come out exact.
        double angle = Math.toRadians((layer * layer * 4321 + layer * 9) * 2 % 360);
        return new int[] {
                (int) Math.round(scale * Math.cos(angle)),
                (int) Math.round(scale * Math.sin(angle))
        };
    }

    /**
     * Sums the layers over one tile as {@code rendertype_end_portal.fsh} does, with
     * nearest, repeating texture lookups, and returns opaque RGBA bytes.
     */
    static byte[] bake(Image sky, Image portal, int layers, int size) {
        byte[] rgba = new byte[size * size * 4];
        for (int py = 0; py < size; py++) {
            double y = (py + 0.5) / size;
            for (int px = 0; px < size; px++) {
                int rgb = shade(sky, portal, layers, (px + 0.5) / size, y);
                int o = (py * size + px) * 4;
                rgba[o] = (byte) (rgb >> 16);
                rgba[o + 1] = (byte) (rgb >> 8);
                rgba[o + 2] = (byte) rgb;
                rgba[o + 3] = (byte) 0xFF;
            }
        }
        return rgba;
    }

    /**
     * The packed {@code 0xRRGGBB} colour at tile coordinates {@code (x, y)}, where the
     * tile spans [0, 1) and repeats outside it.
     */
    static int shade(Image sky, Image portal, int layers, double x, double y) {
        int skyTexel = sample(sky, x * SCREENS_PER_TILE, y * SCREENS_PER_TILE);
        float r = channel(skyTexel, 16) * COLORS[0];
        float g = channel(skyTexel, 8) * COLORS[1];
        float b = channel(skyTexel, 0) * COLORS[2];
        for (int i = 0; i < layers; i++) {
            int a = LATTICE[i][0];
            int c = LATTICE[i][1];
            // The layer's translation at time zero, through SCALE_TRANSLATE. The
            // vertical term scrolls with GameTime and is zero here.
            double offsetX = 0.5 * (17.0 / (i + 1)) + 0.25;
            int texel = sample(portal, a * x - c * y + offsetX, c * x + a * y + 0.25);
            r += channel(texel, 16) * COLORS[i * 3];
            g += channel(texel, 8) * COLORS[i * 3 + 1];
            b += channel(texel, 0) * COLORS[i * 3 + 2];
        }
        return (unorm(r) << 16) | (unorm(g) << 8) | unorm(b);
    }

    /**
     * Mixes each opaque RGBA texel toward its Rec. 709 luma by {@code amount}, in place,
     * and returns the same array.
     */
    static byte[] desaturate(byte[] rgba, float amount) {
        for (int o = 0; o < rgba.length; o += 4) {
            int r = rgba[o] & 0xFF;
            int g = rgba[o + 1] & 0xFF;
            int b = rgba[o + 2] & 0xFF;
            float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            rgba[o] = (byte) Math.round(r + (luma - r) * amount);
            rgba[o + 1] = (byte) Math.round(g + (luma - g) * amount);
            rgba[o + 2] = (byte) Math.round(b + (luma - b) * amount);
        }
        return rgba;
    }

    private static int sample(Image image, double u, double v) {
        int x = Math.min((int) ((u - Math.floor(u)) * image.width()), image.width() - 1);
        int y = Math.min((int) ((v - Math.floor(v)) * image.height()), image.height() - 1);
        return image.rgb()[y * image.width() + x];
    }

    private static float channel(int rgb, int shift) {
        return ((rgb >> shift) & 0xFF) / 255.0f;
    }

    private static int unorm(float value) {
        return Math.round(Math.min(Math.max(value, 0.0f), 1.0f) * 255.0f);
    }
}

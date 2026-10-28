/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.client.texture.NativeImage;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

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
 * {@link #SCREENS_PER_TILE} of vanilla's screen widths.
 *
 * <p>For the tile to repeat without a seam, every layer must itself repeat a whole
 * number of times across it. Vanilla's layers are rotated by arbitrary angles, so
 * each layer's scale and rotation is rounded to the nearest integer lattice step
 * over the tile; a layer of a random speckle texture that comes out a few percent
 * larger or a few degrees off is indistinguishable from the original. Vanilla also
 * scrolls the layers slowly with the time of day; the bake keeps the pattern at
 * time zero.
 *
 * <p>The pixels come from the textures the resource packs supply, so a pack that
 * replaces either texture changes the surface, and baking reads them off the render
 * thread. The surface ids name these baked textures, never a pack resource.
 */
final class EndPortalSurface {

    static final Identifier PORTAL_TEXTURE_ID = Identifier.of("rtxremix", "end_portal_surface");
    static final Identifier GATEWAY_TEXTURE_ID = Identifier.of("rtxremix", "end_gateway_surface");
    static final Optional<Identifier> PORTAL_TEXTURE = Optional.of(PORTAL_TEXTURE_ID);
    static final Optional<Identifier> GATEWAY_TEXTURE = Optional.of(GATEWAY_TEXTURE_ID);

    /** {@code EndPortalLayers} in {@code rendertype_end_portal.json}. */
    static final int PORTAL_LAYERS = 15;
    /** {@code EndPortalLayers} in {@code rendertype_end_gateway.json}. */
    static final int GATEWAY_LAYERS = 16;

    /** Blocks the tile spans along each axis before it repeats. */
    static final int TILE_BLOCKS = 8;
    /**
     * Vanilla screen widths the tile spans. Two keeps every layer's lattice rounding
     * under about ten percent of scale except one; at one the largest layers would
     * round to half or double their size.
     */
    static final int SCREENS_PER_TILE = 2;
    /** Texels along each side of the tile, 128 per block. */
    static final int TILE_SIZE = 1024;
    /**
     * Emissive intensity of the surface material. Vanilla's portal reads as a dark
     * field with bright specks wherever it is seen; at the entity default the whole
     * surface glows pale blue under the dark-scene exposure of a stronghold or the End.
     */
    static final float EMISSIVE_INTENSITY = 10.0f;

    private static final Identifier SKY_TEXTURE =
            Identifier.ofVanilla("textures/environment/end_sky.png");
    private static final Identifier PORTAL_TEXTURE_SOURCE =
            Identifier.ofVanilla("textures/entity/end_portal.png");

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
     * Bakes in flight or done, by surface id. Render thread only; the futures complete
     * on worker threads. A reload drops them all, so a bake that finishes for the
     * previous packs is never read.
     */
    private final Map<Identifier, CompletableFuture<byte[]>> bakes = new HashMap<>();

    static boolean isSurfaceTexture(Identifier textureId) {
        return PORTAL_TEXTURE_ID.equals(textureId) || GATEWAY_TEXTURE_ID.equals(textureId);
    }

    /**
     * The baked RGBA tile for a surface id, {@link #TILE_SIZE} square, or null while it
     * is still baking. The first call starts the bake.
     *
     * @throws IllegalStateException when the bake failed; the caller should not ask again
     */
    byte[] poll(Identifier surfaceId, ResourceManager resources) {
        CompletableFuture<byte[]> bake = bakes.computeIfAbsent(surfaceId, id -> {
            int layers = GATEWAY_TEXTURE_ID.equals(id) ? GATEWAY_LAYERS : PORTAL_LAYERS;
            return CompletableFuture.supplyAsync(() -> bake(
                    read(resources, SKY_TEXTURE), read(resources, PORTAL_TEXTURE_SOURCE),
                    layers, TILE_SIZE));
        });
        if (!bake.isDone()) return null;
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
        try (InputStream in = resource.getInputStream(); NativeImage image = NativeImage.read(in)) {
            int width = image.getWidth();
            int height = image.getHeight();
            int[] rgb = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int abgr = image.getColor(x, y);
                    rgb[y * width + x] = ((abgr & 0xFF) << 16) | (abgr & 0xFF00) | ((abgr >> 16) & 0xFF);
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
        double angle = Math.toRadians((layer * layer * 4321.0 + layer * 9.0) * 2.0);
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

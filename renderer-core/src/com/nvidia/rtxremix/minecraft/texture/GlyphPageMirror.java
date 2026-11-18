/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.nio.ByteBuffer;

/**
 * CPU copy of one font glyph-atlas page.
 *
 * <p>Minecraft assembles a {@code GlyphAtlasTexture} on the GPU: each glyph is uploaded
 * into its slot the first time it is rendered, from a source image that may be freed the
 * moment the upload returns, so the GL texture is the only complete copy of the page.
 * Remix needs the pixels to build a material for text quads, so this mirror follows
 * every glyph upload. {@code GlyphAtlasTextureMixin} opens an upload scope around the
 * glyph's {@code upload} call, and the upload mixins on {@code NativeImage} and
 * {@code GlStateManager} write the same rectangle here that they hand to GL.
 *
 * <p>The mirror stores what the text shaders read, not what was uploaded. A colour page
 * is an RGBA8 texture and keeps the uploaded channels as {@code glTexSubImage2D}
 * converts them, missing colour channels zero and missing alpha opaque. An intensity
 * page is an R8 texture that {@code rendertype_text_intensity} samples as {@code .rrrr},
 * so red is spread across all four channels here.
 *
 * <p>Glyph uploads run only on the render thread ({@code NativeImage.uploadInternal}
 * asserts it) and every reader of {@link #data()} runs there too, so the page and the
 * upload scope carry no synchronisation.
 */
public final class GlyphPageMirror {

    /** Side of every page in pixels, as {@code GlyphAtlasTexture} hardcodes it for slots and UVs. */
    public static final int PAGE_SIZE = 256;
    private static final int CHANNELS = 4;

    /** Implemented by the glyph atlas texture, so the page is reachable from the texture object. */
    public interface Owner {
        GlyphPageMirror rtxremix$glyphPageMirror();
    }

    /**
     * Shared by every page so that a page re-created under the same texture id after a
     * font reload is newer than the page it replaces: its consumer must re-upload rather
     * than keep the predecessor's slots.
     */
    private static long nextVersion = 1L;
    private static GlyphPageMirror uploadTarget;

    private final boolean intensity;
    private final byte[] data = new byte[PAGE_SIZE * PAGE_SIZE * CHANNELS];
    private long version;

    /**
     * @param intensity whether the page is an R8 texture read as coverage, which is the
     *                  case for a font whose glyphs report no colour, such as TrueType
     */
    public GlyphPageMirror(boolean intensity) {
        this.intensity = intensity;
    }

    /** Tightly packed RGBA rows, {@link #PAGE_SIZE} square. Mutated in place by {@link #blit}. */
    public byte[] data() {
        return data;
    }

    /** Advances with every glyph; 0 until the first glyph lands. */
    public long version() {
        return version;
    }

    /**
     * Copies one uploaded glyph into the page.
     *
     * @param x           slot column in the page
     * @param y           slot row in the page
     * @param width       uploaded rectangle width in pixels
     * @param height      uploaded rectangle height in pixels
     * @param channels    bytes per source pixel, in GL channel order: red, green, blue, alpha
     * @param src         source bytes, positioned at the rectangle's first pixel
     * @param srcRowPitch bytes from one source row to the next
     * @throws IllegalArgumentException if the rectangle does not fit the page; a slot always does
     */
    public void blit(int x, int y, int width, int height, int channels, ByteBuffer src, int srcRowPitch) {
        if (x < 0 || y < 0 || width < 0 || height < 0
                || x + width > PAGE_SIZE || y + height > PAGE_SIZE) {
            throw new IllegalArgumentException("glyph " + width + "x" + height + " at " + x + "," + y
                    + " does not fit a " + PAGE_SIZE + "x" + PAGE_SIZE + " page");
        }
        int srcRow = src.position();
        for (int row = 0; row < height; row++, srcRow += srcRowPitch) {
            int s = srcRow;
            int d = ((y + row) * PAGE_SIZE + x) * CHANNELS;
            for (int col = 0; col < width; col++, s += channels, d += CHANNELS) {
                int r = src.get(s) & 0xFF;
                int g = channels > 1 ? src.get(s + 1) & 0xFF : 0;
                int b = channels > 2 ? src.get(s + 2) & 0xFF : 0;
                int a = channels > 3 ? src.get(s + 3) & 0xFF : 0xFF;
                if (intensity) {
                    g = r;
                    b = r;
                    a = r;
                }
                data[d] = (byte) r;
                data[d + 1] = (byte) g;
                data[d + 2] = (byte) b;
                data[d + 3] = (byte) a;
            }
        }
        version = nextVersion++;
    }

    // ---- Upload scope ----

    /** Marks {@code page} as the destination of the glyph upload about to run. */
    public static void beginUpload(GlyphPageMirror page) {
        uploadTarget = page;
    }

    public static void endUpload() {
        uploadTarget = null;
    }

    /** The page a glyph is being uploaded into, or null outside a glyph upload. */
    public static GlyphPageMirror uploadTarget() {
        return uploadTarget;
    }
}

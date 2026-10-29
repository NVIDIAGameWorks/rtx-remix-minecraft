/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Scope-local sprite alpha proof with a bounded pixel budget shared across that frame's draws. */
public final class SpriteOpacity {
    public static final int UNKNOWN = 0, CUTOUT = 1, OPAQUE = 2;
    static final int MAX_PIXELS = 1024 * 1024;
    private final TextureAtlasSprite[] checked;
    private final int[] coverage;
    private int count, remaining = MAX_PIXELS;

    /** Terrain visits more distinct sprites per frame; pixel work keeps the same shared bound. */
    public SpriteOpacity(int capacity) {
        if (capacity < 1 || capacity > 256) throw new IllegalArgumentException("Sprite proof cache capacity must be 1..256");
        checked = new TextureAtlasSprite[capacity]; coverage = new int[capacity];
    }

    public void beginFrame() { clear(); remaining = MAX_PIXELS; }
    public void clear() {
        for (int i = 0; i < count; i++) checked[i] = null;
        count = 0;
    }

    /** Caller must separately prove all emitted UVs remain inside this sprite's bounds. */
    public int sprite(TextureAtlasSprite sprite) {
        if (sprite == null) return UNKNOWN;
        for (int i = 0; i < count; i++) if (checked[i] == sprite) return coverage[i];
        if (count == checked.length) return UNKNOWN;
        int result = scanSprite(sprite);
        checked[count] = sprite; coverage[count++] = result;
        return result;
    }

    private int scanSprite(TextureAtlasSprite sprite) {
        if (sprite.getClass() != TextureAtlasSprite.class) return UNKNOWN;
        int width = sprite.getIconWidth(), height = sprite.getIconHeight(), frames = sprite.getFrameCount();
        if (width <= 0 || height <= 0 || (long) width * height > remaining || frames <= 0 || frames > 256)
            return UNKNOWN;
        int result = OPAQUE;
        // Authored level-zero fractional alpha always keeps blending. Binary
        // sprites use the existing alpha test as cutouts, including filtered
        // mip edges. No source proof survives its owning draw scope.
        for (int frame = 0; frame < frames; frame++) {
            int[][] levels = sprite.getFrameTextureData(frame);
            if (levels == null || levels.length == 0 || levels.length > 31 || levels[0] == null) return UNKNOWN;
            int frameCoverage = OPAQUE;
            for (int mip = 0; mip < levels.length; mip++) {
                int[] pixels = levels[mip];
                if (pixels == null) continue;
                long expected = (long) Math.max(1, width >> mip) * Math.max(1, height >> mip);
                if (pixels.length != expected || pixels.length > remaining) return UNKNOWN;
                remaining -= pixels.length;
                if (mip == 0) {
                    for (int pixel : pixels) {
                        int alpha = pixel >>> 24;
                        if (alpha == 0) frameCoverage = CUTOUT;
                        else if (alpha != 255) return UNKNOWN;
                    }
                } else if (frameCoverage == OPAQUE) {
                    for (int pixel : pixels) if ((pixel >>> 24) != 255) return UNKNOWN;
                }
            }
            result = Math.min(result, frameCoverage);
        }
        return result;
    }
}

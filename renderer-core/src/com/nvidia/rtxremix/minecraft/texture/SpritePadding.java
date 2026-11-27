/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

/**
 * Fills the border an atlas stitcher reserves around a sprite in a CPU copy of an
 * atlas layer, by extending the sprite's edge texels outward as a GPU atlas blit
 * does. Left empty, the border would bleed into the sprite at the lower mip levels
 * a distant face samples, outlining every block in black. A sprite without padding
 * is left alone.
 */
public final class SpritePadding {
    private SpritePadding() {}

    /**
     * @param x the column where the sprite's own texels start, inside its padding
     * @param y the row where the sprite's own texels start, inside its padding
     * @param bytesPerPixel 4 for an RGBA layer, 1 for a single-channel one
     */
    public static void fill(byte[] layer, int atlasWidth, int atlasHeight, int bytesPerPixel,
                            int x, int y, int width, int height, int padding) {
        if (layer == null || padding <= 0 || width <= 0 || height <= 0) {
            return;
        }
        int x0 = Math.max(0, x - padding), x1 = Math.min(atlasWidth, x + width + padding);
        int y0 = Math.max(0, y - padding), y1 = Math.min(atlasHeight, y + height + padding);
        for (int py = y0; py < y1; py++) {
            int sy = Math.clamp(py, y, y + height - 1);
            for (int px = x0; px < x1; px++) {
                if (py == sy && px >= x && px < x + width) {
                    px = x + width - 1; // skip the interior row span
                    continue;
                }
                int sx = Math.clamp(px, x, x + width - 1);
                System.arraycopy(layer, (sy * atlasWidth + sx) * bytesPerPixel,
                        layer, (py * atlasWidth + px) * bytesPerPixel, bytesPerPixel);
            }
        }
    }
}

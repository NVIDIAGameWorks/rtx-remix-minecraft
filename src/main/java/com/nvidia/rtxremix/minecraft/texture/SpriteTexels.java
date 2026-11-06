/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Where a sprite's own texels start in its atlas. The stitcher reserves a border
 * around every sprite for mipmapping and anisotropic filtering, and a sprite's
 * x and y name the border's corner, so its content starts one padding further in.
 * A copy of the atlas built on the CPU must use these, or it falls out of step
 * with the sprite's UVs.
 */
public final class SpriteTexels {
    private SpriteTexels() {}

    public static int x(TextureAtlasSprite sprite) {
        return sprite.getX() + sprite.padding;
    }

    public static int y(TextureAtlasSprite sprite) {
        return sprite.getY() + sprite.padding;
    }

    /**
     * Fills every sprite's padding border in a CPU copy of an atlas layer by extending
     * the sprite's edge texels outward, as vanilla's atlas blit does. Left empty, the
     * border would bleed into the sprite at the lower mip levels a distant face samples,
     * outlining every block in black.
     *
     * @param bytesPerPixel 4 for an RGBA layer, 1 for a single-channel one
     */
    public static void fillPadding(Iterable<TextureAtlasSprite> sprites, byte[] layer,
                                   int atlasWidth, int atlasHeight, int bytesPerPixel) {
        for (TextureAtlasSprite sprite : sprites) {
            fillPadding(layer, atlasWidth, atlasHeight, bytesPerPixel, x(sprite), y(sprite),
                    sprite.contents().width(), sprite.contents().height(), sprite.padding);
        }
    }

    public static void fillPadding(byte[] layer, int atlasWidth, int atlasHeight, int bytesPerPixel,
                            int x, int y, int width, int height, int padding) {
        if (padding <= 0 || width <= 0 || height <= 0) {
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

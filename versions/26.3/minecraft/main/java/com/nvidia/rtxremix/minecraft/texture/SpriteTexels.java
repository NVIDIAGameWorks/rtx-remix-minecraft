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
     * Fills every sprite's padding border in a CPU copy of an atlas layer; see
     * {@link SpritePadding#fill}.
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
        SpritePadding.fill(layer, atlasWidth, atlasHeight, bytesPerPixel, x, y, width, height, padding);
    }
}

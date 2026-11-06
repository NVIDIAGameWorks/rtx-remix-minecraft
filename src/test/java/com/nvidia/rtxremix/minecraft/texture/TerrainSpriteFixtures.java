/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.nvidia.rtxremix.minecraft.mixin.SpriteContentsAccessor;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.resource.metadata.ResourceMetadata;
import net.minecraft.util.Identifier;

/** Sprite entries with real atlas coordinates and transparency scanned from image pixels. */
public final class TerrainSpriteFixtures {
    private TerrainSpriteFixtures() {}

    public static BlockTextureManager.SpriteEntry sprite(String name, int x, boolean transparent, boolean sss) {
        NativeImage image = new NativeImage(16, 16, false);
        for (int y = 0; y < 16; y++) {
            for (int col = 0; col < 16; col++) {
                image.setColor(col, y, transparent && col < 4 ? 0 : 0xFFFFFFFF);
            }
        }
        return sprite(name, x, image, sss);
    }

    /**
     * A 16x16 sprite drawn from rows of texels: {@code #} opaque, {@code +} partly transparent
     * but kept by the cutout alpha test, {@code -} partly transparent and discarded by it, and
     * {@code .} clear.
     */
    public static BlockTextureManager.SpriteEntry sprite(String name, int x, String[] rows, boolean sss) {
        NativeImage image = new NativeImage(16, 16, false);
        for (int y = 0; y < 16; y++) {
            for (int col = 0; col < 16; col++) {
                int alpha = switch (rows[y].charAt(col)) {
                    case '#' -> 255;
                    case '+' -> 200;
                    case '-' -> 100;
                    default -> 0;
                };
                image.setColor(col, y, alpha << 24 | 0xFFFFFF);
            }
        }
        return sprite(name, x, image, sss);
    }

    /** Marks a fixture sprite as animated, as a pack's {@code .mcmeta} animation would. */
    public static BlockTextureManager.SpriteEntry animated(BlockTextureManager.SpriteEntry sprite) {
        sprite.animated = true;
        return sprite;
    }

    private static BlockTextureManager.SpriteEntry sprite(String name, int x, NativeImage image, boolean sss) {
        Identifier id = Identifier.ofVanilla("block/" + name);
        try (TestContents contents = new TestContents(id, image)) {
            Sprite sprite = new Sprite(BlockTextureManager.BLOCK_ATLAS_ID, contents, 64, 64, x, 16) {};
            BlockTextureManager.SpriteEntry entry = new BlockTextureManager.SpriteEntry(x / 16, id, sprite);
            entry.hasPBR = sss;
            entry.hasSSS = sss;
            return entry;
        }
    }

    /** Supplies the image accessor that Fabric injects into SpriteContents in a running client. */
    private static final class TestContents extends SpriteContents implements SpriteContentsAccessor {
        private final NativeImage pixels;

        TestContents(Identifier id, NativeImage image) {
            super(id, new SpriteDimensions(16, 16), image, ResourceMetadata.NONE);
            pixels = image;
        }

        @Override
        public NativeImage getImage() { return pixels; }

        @Override
        public Animation getAnimation() { return null; }
    }
}

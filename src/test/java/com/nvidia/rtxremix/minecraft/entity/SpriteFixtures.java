/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;

/** Real {@link TextureAtlasSprite}s for key and UV-remap tests, with the atlas rectangle chosen by the test. */
final class SpriteFixtures {

    private static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/test.png");

    private SpriteFixtures() {}

    /**
     * A {@code size}-pixel square sprite at ({@code x}, {@code y}) in a square atlas of
     * {@code atlasSize} pixels. Every call allocates a new sprite, so two calls with the
     * same arguments are two identities, as two atlas stitches would be.
     */
    static TextureAtlasSprite sprite(int x, int y, int size, int atlasSize) {
        SpriteContents contents = new SpriteContents(
                ResourceLocation.fromNamespaceAndPath("minecraft", "test/" + x + "_" + y),
                new FrameSize(size, size),
                new NativeImage(size, size, false),
                ResourceMetadata.EMPTY);
        // The constructor is protected; an anonymous subclass reaches it without
        // widening access for the sake of a test.
        TextureAtlasSprite sprite = new TextureAtlasSprite(ATLAS, contents, atlasSize, atlasSize, x, y) {};
        // The rectangle is fixed at construction and no test reads pixels, so the
        // off-heap image is released at once.
        contents.close();
        return sprite;
    }
}

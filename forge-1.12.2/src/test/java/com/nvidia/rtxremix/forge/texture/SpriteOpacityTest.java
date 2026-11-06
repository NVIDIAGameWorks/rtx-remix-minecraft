/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import java.util.Arrays;
import java.util.Collections;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.ResourceLocation;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpriteOpacityTest {
    @Test public void terrainCacheCoversMoreThanOneModelWithoutIncreasingThePixelBudget() {
        SpriteOpacity proof = new SpriteOpacity(256);
        for (int i = 0; i < 256; i++) assertEquals(SpriteOpacity.OPAQUE, proof.sprite(sprite(1, "sprite" + i)));
        assertEquals(SpriteOpacity.UNKNOWN, proof.sprite(sprite(1, "overflow")));
        proof.beginFrame();
        TextureAtlasSprite large = sprite(1024, "large");
        assertEquals(SpriteOpacity.OPAQUE, proof.sprite(large));
        assertEquals(SpriteOpacity.OPAQUE, proof.sprite(large));
        assertEquals(SpriteOpacity.UNKNOWN, proof.sprite(sprite(1, "next")));
        proof.clear(); assertEquals(SpriteOpacity.UNKNOWN, proof.sprite(sprite(1, "still-exhausted")));
        proof.beginFrame(); assertEquals(SpriteOpacity.OPAQUE, proof.sprite(sprite(1, "new-frame")));
    }

    @Test public void distinguishesBinaryCutoutsFromFractionalAlphaAndRescansEachFrame() {
        SpriteOpacity proof = new SpriteOpacity(4);
        TextureAtlasSprite sprite = sprite(2, "alpha");
        assertEquals(SpriteOpacity.OPAQUE, proof.sprite(sprite));
        sprite.getFrameTextureData(0)[0][0] = 0;
        proof.beginFrame();
        assertEquals(SpriteOpacity.CUTOUT, proof.sprite(sprite));
        sprite.getFrameTextureData(0)[0][0] = 0x7fffffff;
        proof.beginFrame();
        assertEquals(SpriteOpacity.UNKNOWN, proof.sprite(sprite));
        assertEquals(SpriteOpacity.UNKNOWN, proof.sprite(new TextureAtlasSprite("custom") { }));
    }

    private static TextureAtlasSprite sprite(int size, String name) {
        TextureAtlasSprite sprite = VanillaSprite.create(name);
        sprite.setIconWidth(size); sprite.setIconHeight(size); sprite.initSprite(size, size, 0, 0, false);
        int[] pixels = new int[size * size]; Arrays.fill(pixels, -1);
        sprite.setFramesTextureData(Collections.singletonList(new int[][] {pixels}));
        return sprite;
    }
    private static final class VanillaSprite extends TextureAtlasSprite {
        private VanillaSprite() { super("unused"); }
        static TextureAtlasSprite create(String name) { return makeAtlasSprite(new ResourceLocation("test", name)); }
    }

}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnimatedSpritePlaybackTest {
    @Test
    void directUploadsFollowTheUploadedFrameOrigin() {
        AnimatedSpritePlayback playback = new AnimatedSpritePlayback();
        AnimatedSpritePlayback.Sampling sample =
                playback.resolve("sprite", material(), 8, 4, 4, 4, 16, 8);

        assertEquals(8, sample.current.x);
        assertEquals(4, sample.current.y);
        assertEquals(0.0f, sample.nextBlend);
    }

    @Test
    void interpolatedUploadsAdvanceBetweenSequenceFrames() {
        AnimatedSpritePlayback playback = new AnimatedSpritePlayback();
        AnimatedSpritePlayback.Sampling sample =
                playback.resolve("sprite", material(), 0, 0, 4, 4, 4, 4);

        assertEquals(0, sample.current.x);
        assertEquals(0, sample.current.y);
        assertEquals(4, sample.next.x);
        assertEquals(0, sample.next.y);
        assertEquals(0.25f, sample.nextBlend);
    }

    private static AnimatedSpriteMaterialData material() {
        return new AnimatedSpriteMaterialData(
                null, 0, 0,
                null, null, null, null, 0, 0,
                false, false,
                new int[]{0, 1, 3},
                new int[]{4, 2, 1},
                2);
    }
}

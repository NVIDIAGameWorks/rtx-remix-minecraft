/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.util.HashMap;
import java.util.Map;

/** Tracks loader-neutral frame sampling for animated material sidecars. */
public final class AnimatedSpritePlayback {
    private final Map<String, State> states = new HashMap<String, State>();

    public Sampling resolve(
            String spriteId,
            AnimatedSpriteMaterialData material,
            int srcX,
            int srcY,
            int width,
            int height,
            int srcImageWidth,
            int srcImageHeight) {
        if (material == null || material.frameIndices.length == 0) {
            Origin origin = new Origin(srcX, srcY);
            return new Sampling(origin, origin, 0.0f);
        }

        State playback = states.get(spriteId);
        if (playback == null) {
            playback = new State();
            states.put(spriteId, playback);
        }

        boolean interpolatedUpload = srcX == 0 && srcY == 0
                && srcImageWidth == width && srcImageHeight == height;
        if (interpolatedUpload) {
            if (!playback.initialized) {
                playback.sequenceIndex = 0;
                playback.interpolationTick = 0;
                playback.initialized = true;
            }

            int currentSequenceIndex = playback.sequenceIndex;
            int nextSequenceIndex =
                    (currentSequenceIndex + 1) % material.frameIndices.length;
            int frameTime = Math.max(1, material.frameTimes[currentSequenceIndex]);
            int interpolationTick =
                    Math.min(frameTime - 1, playback.interpolationTick + 1);
            playback.interpolationTick = interpolationTick;

            Origin current = origin(
                    material.frameIndices[currentSequenceIndex],
                    material.frameColumns,
                    width,
                    height);
            Origin next = origin(
                    material.frameIndices[nextSequenceIndex],
                    material.frameColumns,
                    width,
                    height);
            float nextBlend = frameTime > 1
                    ? (float) interpolationTick / (float) frameTime
                    : 0.0f;
            return new Sampling(current, next, nextBlend);
        }

        int columns = Math.max(1, srcImageWidth / Math.max(1, width));
        int frameIndex =
                (srcY / Math.max(1, height)) * columns + (srcX / Math.max(1, width));
        int expectedNext = playback.initialized
                ? (playback.sequenceIndex + 1) % material.frameIndices.length
                : -1;
        playback.sequenceIndex =
                findSequenceIndex(material.frameIndices, frameIndex, expectedNext);
        playback.interpolationTick = 0;
        playback.initialized = true;

        Origin origin = new Origin(srcX, srcY);
        return new Sampling(origin, origin, 0.0f);
    }

    public void clear() {
        states.clear();
    }

    private static Origin origin(int frameIndex, int frameColumns, int width, int height) {
        int columns = Math.max(1, frameColumns);
        return new Origin(
                (frameIndex % columns) * width,
                (frameIndex / columns) * height);
    }

    private static int findSequenceIndex(
            int[] frameIndices, int frameIndex, int expectedNext) {
        if (expectedNext >= 0
                && expectedNext < frameIndices.length
                && frameIndices[expectedNext] == frameIndex) {
            return expectedNext;
        }
        for (int i = 0; i < frameIndices.length; i++) {
            if (frameIndices[i] == frameIndex) {
                return i;
            }
        }
        return 0;
    }

    private static final class State {
        int sequenceIndex;
        int interpolationTick;
        boolean initialized;
    }

    public static final class Origin {
        public final int x;
        public final int y;

        private Origin(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    public static final class Sampling {
        public final Origin current;
        public final Origin next;
        public final float nextBlend;

        private Sampling(Origin current, Origin next, float nextBlend) {
            this.current = current;
            this.next = next;
            this.nextBlend = nextBlend;
        }
    }
}

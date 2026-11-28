/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

/**
 * A deterministic 60 Hz virtual clock that advances once per rendered frame.
 *
 * <p>Positions are expressed in integer milliseconds because camera path
 * waypoints use {@code timeMs}. The frame schedule matches benchmark sampling:
 * frame {@code n} is {@code round(n * 1000 / 60)} milliseconds from the
 * current anchor, so each advance is 16 ms or 17 ms on a repeating 17, 16, 17
 * cycle.
 * Integer quotient/remainder arithmetic avoids floating-point drift and
 * saturates safely for impractically long timelines.</p>
 *
 * <p>Raw frame duration is never consulted, so this clock has no stall
 * classification and no presentation smoothing: even a frame taking a second
 * takes exactly one step. Its wall-clock pace therefore follows the achieved
 * frame rate, roughly 2x route speed at 120 FPS and 0.5x at 30 FPS, which is
 * the point: two rendering configurations see the same ordered samples.</p>
 */
final class FixedStepClock {

    static final int RATE_HZ = 60;
    private static final long MILLISECONDS_PER_SECOND = 1_000L;

    private long anchorMs;
    private long frameIndex;
    private long positionMs;

    long advance(long durationMs) {
        long duration = Math.max(0L, durationMs);
        if (positionMs >= duration) {
            positionMs = duration;
            return positionMs;
        }

        frameIndex = saturatingAdd(frameIndex, 1L);
        positionMs = Math.min(duration, saturatingAdd(anchorMs, timeMsForFrame(frameIndex)));
        return positionMs;
    }

    void reset(long positionMs) {
        long position = Math.max(0L, positionMs);
        anchorMs = position;
        frameIndex = 0L;
        this.positionMs = position;
    }

    long positionMs() {
        return positionMs;
    }

    long frameIndex() {
        return frameIndex;
    }

    static long timeMsForFrame(long frameIndex) {
        long frame = Math.max(0L, frameIndex);
        long wholeSeconds = frame / RATE_HZ;
        long remainderFrames = frame % RATE_HZ;
        long wholeMilliseconds = saturatingMultiply(wholeSeconds, MILLISECONDS_PER_SECOND);
        long remainderMilliseconds = (
                remainderFrames * MILLISECONDS_PER_SECOND + RATE_HZ / 2L
        ) / RATE_HZ;
        return saturatingAdd(wholeMilliseconds, remainderMilliseconds);
    }

    private static long saturatingMultiply(long value, long multiplier) {
        if (value <= 0L || multiplier <= 0L) {
            return 0L;
        }
        if (value > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }
        return value * multiplier;
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0L) {
            return left;
        }
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}

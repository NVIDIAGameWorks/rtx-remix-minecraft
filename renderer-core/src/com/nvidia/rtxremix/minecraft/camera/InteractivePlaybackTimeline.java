/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

import java.util.Objects;

/**
 * Owns the two interactive camera clocks and their shared reanchor and
 * diagnostics behavior.
 *
 * <p>The controller supplies one raw delta for each eligible rendered world
 * frame. A start, seek, resume, or suspension leaves an anchor pending, so the
 * next frame presents the current pose without advancing either timeline. The
 * frame after that one takes the first step, which is why an interval when the
 * player or world was unavailable cannot become an immediate jump.</p>
 *
 * <p>Seeking always takes elapsed path time in seconds, never a frame number,
 * and reanchors whichever clock is selected. Pause and resume keep the clock
 * the {@code play} command chose. Reaching the end of the path leaves the
 * player on the final pose.</p>
 *
 * <p>The diagnostics reported by {@code /rtx camera status} are the last raw
 * frame delta, the path advance that frame applied, which is zero on a
 * reanchor frame, and the time-clock lag still owed. Comparing the first two
 * shows stall smoothing at work in {@code TIME} mode; in {@code FIXED_60} the
 * advance stays on the virtual schedule while the raw delta varies, and lag is
 * always zero.</p>
 */
final class InteractivePlaybackTimeline {

    private static final long NANOS_PER_MILLISECOND = 1_000_000L;

    private final PresentationClock timeClock = new PresentationClock();
    private final FixedStepClock fixedClock = new FixedStepClock();

    private CameraPlaybackMode mode = CameraPlaybackMode.TIME;
    private boolean anchorPending = true;
    private boolean lastFrameReanchored;
    private long lastRawFrameNanos;
    private long lastAdvanceNanos;

    void start(CameraPlaybackMode mode, long positionMs) {
        this.mode = Objects.requireNonNull(mode, "mode");
        reset(positionMs);
    }

    void resetToDefault(long positionMs) {
        mode = CameraPlaybackMode.TIME;
        reset(positionMs);
    }

    void reset(long positionMs) {
        long position = Math.max(0L, positionMs);
        timeClock.reset(millisecondsToNanos(position));
        fixedClock.reset(position);
        anchorPending = true;
        lastFrameReanchored = false;
        resetDiagnostics();
    }

    /**
     * Suspends advancement and optionally clears the last-frame diagnostics.
     * Time-clock lag is always discarded so it cannot leak across controls or
     * unavailable-world intervals.
     */
    void reanchor(boolean clearDiagnostics) {
        timeClock.discardLag();
        anchorPending = true;
        lastFrameReanchored = false;
        if (clearDiagnostics) {
            resetDiagnostics();
        }
    }

    long advanceFrame(long rawFrameNanos, long durationMs) {
        long duration = Math.max(0L, durationMs);
        long rawFrame = Math.max(0L, rawFrameNanos);

        if (anchorPending) {
            anchorPending = false;
            resetDiagnostics();
            lastFrameReanchored = true;
            return positionMs(duration);
        }

        lastFrameReanchored = false;
        long beforeNanos;
        long afterNanos;
        if (mode == CameraPlaybackMode.FIXED_60) {
            long beforeMs = fixedClock.positionMs();
            long afterMs = fixedClock.advance(duration);
            beforeNanos = millisecondsToNanos(beforeMs);
            afterNanos = millisecondsToNanos(afterMs);
        } else {
            beforeNanos = timeClock.positionNanos();
            afterNanos = timeClock.advance(rawFrame, millisecondsToNanos(duration));
            lastFrameReanchored = isRendererSuspensionGap(rawFrame);
        }

        lastRawFrameNanos = rawFrame;
        lastAdvanceNanos = Math.max(0L, afterNanos - beforeNanos);
        return positionMs(duration);
    }

    CameraPlaybackMode mode() {
        return mode;
    }

    long positionMs(long durationMs) {
        long position = mode == CameraPlaybackMode.FIXED_60
                ? fixedClock.positionMs()
                : timeClock.positionNanos() / NANOS_PER_MILLISECOND;
        return Math.min(Math.max(0L, durationMs), position);
    }

    long lastRawFrameNanos() {
        return lastRawFrameNanos;
    }

    long lastAdvanceNanos() {
        return lastAdvanceNanos;
    }

    long smoothingLagNanos() {
        return mode == CameraPlaybackMode.TIME ? timeClock.lagNanos() : 0L;
    }

    boolean lastFrameReanchored() {
        return lastFrameReanchored;
    }

    static boolean isRendererSuspensionGap(long rawFrameNanos) {
        return rawFrameNanos >= PresentationClock.HARD_GAP_NANOS;
    }

    private void resetDiagnostics() {
        lastRawFrameNanos = 0L;
        lastAdvanceNanos = 0L;
    }

    private static long millisecondsToNanos(long milliseconds) {
        if (milliseconds <= 0L) {
            return 0L;
        }
        if (milliseconds > Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
            return Long.MAX_VALUE;
        }
        return milliseconds * NANOS_PER_MILLISECOND;
    }
}

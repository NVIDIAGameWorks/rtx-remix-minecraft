/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

/**
 * A bounded interactive-playback clock that turns isolated render stalls into
 * a small amount of temporary presentation lag instead of one large camera
 * jump.
 *
 * <p>Normal frame deltas advance exactly in real time. A long frame advances
 * by a bounded amount, retains a bounded target lead, then repays that lead at
 * no more than 1.25x speed. Elapsed time beyond that lead is discarded rather
 * than becoming an eventual jump. Very large gaps are treated like a
 * suspended renderer and do not advance the presentation timeline.</p>
 *
 * <p>Concretely, with the constants below: a delta of at most 100 ms advances
 * by its exact elapsed time; a longer one is a stall and advances by the 50 ms
 * base step, which the 1.25x catch-up budget raises to 62.5 ms on that frame;
 * outstanding lag is capped at 250 ms and anything past it is dropped; a delta
 * of at least one second advances nothing. Sustained rendering below 10 FPS is
 * therefore a run of stalls: it cannot produce smooth motion, so the route
 * takes longer in wall-clock time rather than jumping.</p>
 */
final class PresentationClock {

    static final long NANOS_PER_MILLISECOND = 1_000_000L;
    static final long STALL_THRESHOLD_NANOS = 100L * NANOS_PER_MILLISECOND;
    static final long STALL_ADVANCE_NANOS = 50L * NANOS_PER_MILLISECOND;
    static final long MAX_LAG_NANOS = 250L * NANOS_PER_MILLISECOND;
    static final long MAX_STEP_NANOS = 100L * NANOS_PER_MILLISECOND;
    static final long HARD_GAP_NANOS = 1_000L * NANOS_PER_MILLISECOND;

    private long presentedNanos;
    private long targetNanos;
    private long droppedNanos;

    long advance(long rawDeltaNanos, long durationNanos) {
        long duration = Math.max(0L, durationNanos);
        if (presentedNanos >= duration) {
            presentedNanos = duration;
            targetNanos = duration;
            return presentedNanos;
        }

        long rawDelta = Math.max(0L, rawDeltaNanos);
        if (rawDelta >= HARD_GAP_NANOS) {
            discardLag();
            return presentedNanos;
        }

        targetNanos = Math.min(duration, saturatingAdd(targetNanos, rawDelta));
        long lag = targetNanos - presentedNanos;
        if (lag > MAX_LAG_NANOS) {
            long dropped = lag - MAX_LAG_NANOS;
            droppedNanos = saturatingAdd(droppedNanos, dropped);
            targetNanos = saturatingAdd(presentedNanos, MAX_LAG_NANOS);
            lag = MAX_LAG_NANOS;
        }

        long baseAdvance = rawDelta > STALL_THRESHOLD_NANOS
                ? STALL_ADVANCE_NANOS
                : rawDelta;
        long budget = Math.min(MAX_STEP_NANOS, catchUpBudget(baseAdvance));
        presentedNanos = Math.min(targetNanos, saturatingAdd(presentedNanos, Math.min(lag, budget)));
        if (presentedNanos >= duration) {
            presentedNanos = duration;
            targetNanos = duration;
        }
        return presentedNanos;
    }

    void reset(long positionNanos) {
        long position = Math.max(0L, positionNanos);
        presentedNanos = position;
        targetNanos = position;
        droppedNanos = 0L;
    }

    void discardLag() {
        targetNanos = presentedNanos;
    }

    long positionNanos() {
        return presentedNanos;
    }

    long lagNanos() {
        return Math.max(0L, targetNanos - presentedNanos);
    }

    long droppedNanos() {
        return droppedNanos;
    }

    private static long catchUpBudget(long baseAdvanceNanos) {
        // 1.25x without floating-point drift.
        return saturatingAdd(baseAdvanceNanos, baseAdvanceNanos / 4L);
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

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentationClockTest {

    private static final long SECOND = milliseconds(1_000);
    private static final long LONG_DURATION = milliseconds(10_000);

    @Test
    void normalDeltasThroughTenFramesPerSecondAdvanceInRealTime() {
        PresentationClock clock = new PresentationClock();

        assertEquals(milliseconds(16), clock.advance(milliseconds(16), LONG_DURATION));
        assertEquals(milliseconds(33), clock.advance(milliseconds(17), LONG_DURATION));
        assertEquals(milliseconds(133), clock.advance(milliseconds(100), LONG_DURATION));
        assertEquals(0L, clock.lagNanos());
        assertEquals(0L, clock.droppedNanos());
    }

    @Test
    void deltaAboveStallThresholdUsesBoundedPresentationAdvance() {
        PresentationClock clock = new PresentationClock();

        assertEquals(milliseconds(62.5), clock.advance(milliseconds(101), LONG_DURATION));
        assertEquals(milliseconds(38.5), clock.lagNanos());
        assertEquals(0L, clock.droppedNanos());
    }

    @Test
    void sustainedSubTenFpsIsIntentionallyTreatedAsRepeatedStalls() {
        PresentationClock clock = new PresentationClock();

        assertEquals(milliseconds(62.5), clock.advance(milliseconds(200), LONG_DURATION));
        assertEquals(milliseconds(137.5), clock.lagNanos());
        assertEquals(milliseconds(125), clock.advance(milliseconds(200), LONG_DURATION));
        assertEquals(milliseconds(187.5), clock.lagNanos());
        assertEquals(milliseconds(87.5), clock.droppedNanos());
    }

    @Test
    void isolatedStallBecomesBoundedLagAndRepaysGradually() {
        PresentationClock clock = new PresentationClock();

        assertEquals(milliseconds(62.5), clock.advance(milliseconds(500), LONG_DURATION));
        assertEquals(milliseconds(187.5), clock.lagNanos());
        assertEquals(milliseconds(250), clock.droppedNanos());

        long previousPosition = clock.positionNanos();
        long nextPosition = clock.advance(milliseconds(16), LONG_DURATION);
        assertEquals(milliseconds(82.5), nextPosition);
        assertEquals(milliseconds(183.5), clock.lagNanos());
        assertTrue(nextPosition > previousPosition);
        assertEquals(milliseconds(20), nextPosition - previousPosition);
    }

    @Test
    void repeatedStallsNeverAccumulateUnboundedLag() {
        PresentationClock clock = new PresentationClock();

        for (int i = 0; i < 10; i++) {
            clock.advance(milliseconds(500), LONG_DURATION);
            assertTrue(clock.lagNanos() <= PresentationClock.MAX_LAG_NANOS);
        }

        assertTrue(clock.droppedNanos() > 0L);
    }

    @Test
    void hardGapActsLikeRendererSuspension() {
        PresentationClock clock = new PresentationClock();
        clock.advance(milliseconds(500), LONG_DURATION);
        long beforeGap = clock.positionNanos();

        assertEquals(beforeGap, clock.advance(SECOND, LONG_DURATION));
        assertEquals(0L, clock.lagNanos());
        assertEquals(beforeGap + milliseconds(16), clock.advance(milliseconds(16), LONG_DURATION));
    }

    @Test
    void resetAndDiscardLagPreventOldDebtFromLeakingAcrossControls() {
        PresentationClock clock = new PresentationClock();
        clock.advance(milliseconds(500), LONG_DURATION);

        clock.discardLag();
        assertEquals(0L, clock.lagNanos());
        assertEquals(milliseconds(78.5), clock.advance(milliseconds(16), LONG_DURATION));

        clock.reset(milliseconds(250));
        assertEquals(milliseconds(250), clock.positionNanos());
        assertEquals(0L, clock.lagNanos());
        assertEquals(0L, clock.droppedNanos());
    }

    @Test
    void durationEndpointIsReachedExactlyWithoutOvershoot() {
        PresentationClock clock = new PresentationClock();
        long duration = milliseconds(100);

        assertEquals(milliseconds(62.5), clock.advance(milliseconds(500), duration));
        assertEquals(duration, clock.advance(milliseconds(30), duration));
        assertEquals(duration, clock.positionNanos());
        assertEquals(0L, clock.lagNanos());
        assertEquals(duration, clock.advance(milliseconds(16), duration));
    }

    @Test
    void negativeDeltaDoesNotMovePlaybackBackwards() {
        PresentationClock clock = new PresentationClock();
        clock.reset(milliseconds(100));

        assertEquals(milliseconds(100), clock.advance(-1L, LONG_DURATION));
        assertEquals(0L, clock.lagNanos());
    }

    private static long milliseconds(long milliseconds) {
        return milliseconds * PresentationClock.NANOS_PER_MILLISECOND;
    }

    private static long milliseconds(double milliseconds) {
        return Math.round(milliseconds * PresentationClock.NANOS_PER_MILLISECOND);
    }
}

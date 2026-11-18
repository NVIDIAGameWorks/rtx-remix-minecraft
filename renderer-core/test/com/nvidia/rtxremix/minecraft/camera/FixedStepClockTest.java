/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FixedStepClockTest {

    @Test
    void frameScheduleMatchesRoundedSixtyHertzBenchmarkTimes() {
        assertEquals(0L, FixedStepClock.timeMsForFrame(-1L));
        assertEquals(0L, FixedStepClock.timeMsForFrame(0L));
        assertEquals(17L, FixedStepClock.timeMsForFrame(1L));
        assertEquals(33L, FixedStepClock.timeMsForFrame(2L));
        assertEquals(50L, FixedStepClock.timeMsForFrame(3L));
        assertEquals(983L, FixedStepClock.timeMsForFrame(59L));
        assertEquals(1_000L, FixedStepClock.timeMsForFrame(60L));
        assertEquals(1_017L, FixedStepClock.timeMsForFrame(61L));
    }

    @Test
    void advanceMovesOneVirtualFrameAndClampsExactlyToEndpoint() {
        FixedStepClock clock = new FixedStepClock();

        assertEquals(17L, clock.advance(40L));
        assertEquals(33L, clock.advance(40L));
        assertEquals(40L, clock.advance(40L));
        assertEquals(40L, clock.advance(40L));
        assertEquals(3L, clock.frameIndex());
    }

    @Test
    void resetAnchorsSubsequentFramesAtSeekPosition() {
        FixedStepClock clock = new FixedStepClock();
        clock.reset(1_234L);

        assertEquals(1_251L, clock.advance(2_000L));
        assertEquals(1_267L, clock.advance(2_000L));

        clock.reset(400L);
        assertEquals(400L, clock.positionMs());
        assertEquals(0L, clock.frameIndex());
        assertEquals(417L, clock.advance(2_000L));
    }

    @Test
    void impracticallyLargeFrameIndicesSaturateWithoutOverflow() {
        assertEquals(Long.MAX_VALUE, FixedStepClock.timeMsForFrame(Long.MAX_VALUE));
    }
}

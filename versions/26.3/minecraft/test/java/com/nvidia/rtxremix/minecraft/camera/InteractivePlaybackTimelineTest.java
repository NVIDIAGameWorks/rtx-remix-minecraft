/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractivePlaybackTimelineTest {

    private static final long DURATION_MS = 10_000L;

    @Test
    void timeModePresentsItsAnchorThenUsesWallTimeAndStallSmoothing() {
        InteractivePlaybackTimeline timeline = new InteractivePlaybackTimeline();
        timeline.start(CameraPlaybackMode.TIME, 0L);

        long anchor = timeline.advanceFrame(milliseconds(500), DURATION_MS);
        assertEquals(0L, anchor);
        assertTrue(timeline.lastFrameReanchored());
        assertEquals(0L, timeline.lastRawFrameNanos());
        assertEquals(0L, timeline.lastAdvanceNanos());

        long normal = timeline.advanceFrame(milliseconds(16), DURATION_MS);
        assertEquals(16L, normal);
        assertFalse(timeline.lastFrameReanchored());
        assertEquals(milliseconds(16), timeline.lastRawFrameNanos());
        assertEquals(milliseconds(16), timeline.lastAdvanceNanos());

        long stall = timeline.advanceFrame(milliseconds(500), DURATION_MS);
        assertEquals(78L, stall);
        assertFalse(timeline.lastFrameReanchored());
        assertEquals(milliseconds(62.5), timeline.lastAdvanceNanos());
        assertEquals(milliseconds(187.5), timeline.smoothingLagNanos());
    }

    @Test
    void fixedModeTakesOneSharedScheduleStepRegardlessOfRawFrameTime() {
        InteractivePlaybackTimeline timeline = new InteractivePlaybackTimeline();
        timeline.start(CameraPlaybackMode.FIXED_60, 0L);

        assertEquals(0L, timeline.advanceFrame(milliseconds(500), DURATION_MS));
        assertEquals(17L, timeline.advanceFrame(milliseconds(1), DURATION_MS));
        assertEquals(milliseconds(17), timeline.lastAdvanceNanos());
        assertEquals(33L, timeline.advanceFrame(milliseconds(500), DURATION_MS));
        assertEquals(milliseconds(16), timeline.lastAdvanceNanos());
        assertEquals(50L, timeline.advanceFrame(milliseconds(1), DURATION_MS));
        assertEquals(milliseconds(17), timeline.lastAdvanceNanos());
        assertEquals(67L, timeline.advanceFrame(milliseconds(1_000), DURATION_MS));
        assertEquals(milliseconds(17), timeline.lastAdvanceNanos());
        assertFalse(timeline.lastFrameReanchored());
        assertEquals(0L, timeline.smoothingLagNanos());
    }

    @Test
    void seekResumeAndRendererSuspensionReanchorWithoutChangingMode() {
        InteractivePlaybackTimeline timeline = new InteractivePlaybackTimeline();
        timeline.start(CameraPlaybackMode.FIXED_60, 0L);
        timeline.advanceFrame(milliseconds(1), DURATION_MS);
        timeline.advanceFrame(milliseconds(1), DURATION_MS);
        assertEquals(17L, timeline.positionMs(DURATION_MS));

        timeline.reset(400L);
        assertEquals(CameraPlaybackMode.FIXED_60, timeline.mode());
        assertEquals(400L, timeline.advanceFrame(milliseconds(700), DURATION_MS));
        assertEquals(417L, timeline.advanceFrame(milliseconds(1), DURATION_MS));

        timeline.reanchor(true);
        assertEquals(417L, timeline.advanceFrame(milliseconds(1_000), DURATION_MS));
        assertTrue(timeline.lastFrameReanchored());
        assertEquals(0L, timeline.lastRawFrameNanos());
        assertEquals(433L, timeline.advanceFrame(milliseconds(1), DURATION_MS));

        timeline.reanchor(true);
        assertEquals(433L, timeline.advanceFrame(milliseconds(1), DURATION_MS));
        assertEquals(450L, timeline.advanceFrame(milliseconds(1), DURATION_MS));
    }

    @Test
    void pauseCanPreserveDiagnosticsUntilResumeClearsThem() {
        InteractivePlaybackTimeline timeline = new InteractivePlaybackTimeline();
        timeline.start(CameraPlaybackMode.TIME, 0L);
        timeline.advanceFrame(milliseconds(1), DURATION_MS);
        timeline.advanceFrame(milliseconds(500), DURATION_MS);
        assertEquals(milliseconds(187.5), timeline.smoothingLagNanos());

        timeline.reanchor(false);
        assertEquals(milliseconds(500), timeline.lastRawFrameNanos());
        assertEquals(milliseconds(62.5), timeline.lastAdvanceNanos());
        assertEquals(0L, timeline.smoothingLagNanos());

        timeline.reanchor(true);
        assertEquals(0L, timeline.lastRawFrameNanos());
        assertEquals(0L, timeline.lastAdvanceNanos());
        assertEquals(62L, timeline.advanceFrame(milliseconds(900), DURATION_MS));
    }

    @Test
    void oneSecondRawGapSuspendsOnlyTheTimeClock() {
        InteractivePlaybackTimeline timeline = new InteractivePlaybackTimeline();
        timeline.start(CameraPlaybackMode.TIME, 0L);
        timeline.advanceFrame(milliseconds(1), DURATION_MS);
        timeline.advanceFrame(milliseconds(500), DURATION_MS);
        assertEquals(milliseconds(187.5), timeline.smoothingLagNanos());

        long beforeGap = timeline.positionMs(DURATION_MS);
        assertEquals(beforeGap, timeline.advanceFrame(milliseconds(1_000), DURATION_MS));
        assertTrue(timeline.lastFrameReanchored());
        assertEquals(milliseconds(1_000), timeline.lastRawFrameNanos());
        assertEquals(0L, timeline.lastAdvanceNanos());
        assertEquals(0L, timeline.smoothingLagNanos());
        assertEquals(beforeGap + 16L, timeline.advanceFrame(milliseconds(16), DURATION_MS));
    }

    @Test
    void endpointIsClampedExactlyAndDefaultResetSelectsTime() {
        InteractivePlaybackTimeline timeline = new InteractivePlaybackTimeline();
        timeline.start(CameraPlaybackMode.FIXED_60, 35L);
        timeline.advanceFrame(milliseconds(1), 40L);
        assertEquals(40L, timeline.advanceFrame(milliseconds(1), 40L));

        timeline.resetToDefault(0L);
        assertEquals(CameraPlaybackMode.TIME, timeline.mode());
        assertEquals(0L, timeline.positionMs(40L));
    }

    @Test
    void oneSecondIsTheRendererSuspensionThreshold() {
        assertFalse(InteractivePlaybackTimeline.isRendererSuspensionGap(999_999_999L));
        assertTrue(InteractivePlaybackTimeline.isRendererSuspensionGap(1_000_000_000L));
        assertTrue(InteractivePlaybackTimeline.isRendererSuspensionGap(Long.MAX_VALUE));
    }

    private static long milliseconds(long milliseconds) {
        return milliseconds * 1_000_000L;
    }

    private static long milliseconds(double milliseconds) {
        return Math.round(milliseconds * 1_000_000L);
    }
}

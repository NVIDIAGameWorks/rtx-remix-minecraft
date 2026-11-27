/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.benchmark;

import com.nvidia.rtxremix.minecraft.camera.StillShot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StillShotSessionTest {

    private static final StillShotSession.Settle SETTLE = new StillShotSession.Settle(10, 3, 20);

    private static StillShot shot(String name) {
        return new StillShot(name, 0, 64, 0, 0f, 0f, 6000, StillShot.Weather.CLEAR, true, false);
    }

    @Test
    void capturesOnceQuietAfterTheMinimumAndNotBefore() {
        // Quiet from the start: the minimum frame count is what gates the capture.
        assertFalse(StillShotSession.shouldCapture(9, 9, SETTLE));
        assertTrue(StillShotSession.shouldCapture(10, 3, SETTLE));
        // Past the minimum but the pipeline is still busy: wait for the quiet run.
        assertFalse(StillShotSession.shouldCapture(15, 2, SETTLE));
        assertTrue(StillShotSession.shouldCapture(15, 3, SETTLE));
    }

    @Test
    void theMaximumCapturesAScenneThatNeverGoesQuiet() {
        assertFalse(StillShotSession.shouldCapture(19, 0, SETTLE));
        assertTrue(StillShotSession.shouldCapture(20, 0, SETTLE));
    }

    @Test
    void loadScreenFramesCountForNothingAndBusyFramesResetTheQuietRun() {
        StillShotSession session = new StillShotSession(List.of(shot("a")), SETTLE);
        assertTrue(session.needsApply());
        session.markApplied();
        assertFalse(session.needsApply());

        for (int i = 0; i < 10; i++) {
            assertFalse(session.onFrameEnd(true, true), "load screen frame " + i);
        }
        assertEquals(0, session.framesSinceApply());

        for (int i = 0; i < 9; i++) {
            assertFalse(session.onFrameEnd(false, true));
        }
        // Frame 10 would capture, but the pipeline woke up; two quiet frames later it still cannot.
        assertFalse(session.onFrameEnd(false, false));
        assertFalse(session.onFrameEnd(false, true));
        assertFalse(session.onFrameEnd(false, true));
        assertTrue(session.onFrameEnd(false, true));
    }

    @Test
    void advancingWalksEveryShotThenReportsDone() {
        StillShotSession session = new StillShotSession(List.of(shot("a"), shot("b")), SETTLE);
        assertEquals("a", session.current().name());
        session.markApplied();
        session.advance();
        assertEquals(1, session.capturedCount());
        assertEquals("b", session.current().name());
        assertTrue(session.needsApply(), "the next shot needs its own apply");
        session.markApplied();
        session.advance();
        assertTrue(session.isDone());
        assertNull(session.current());
        assertFalse(session.needsApply());
        assertFalse(session.onFrameEnd(false, true));
    }

    @Test
    void rejectsAnEmptySetAndAnInvertedBudget() {
        assertThrows(IllegalArgumentException.class, () -> new StillShotSession(List.of(), SETTLE));
        assertThrows(IllegalArgumentException.class, () -> new StillShotSession.Settle(30, 3, 20));
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the throttle's contract: the first failure of an episode is logged, later ones
 * are counted, and the count is reported once {@link FailureLogThrottle#REARM_FRAMES}
 * frames end without a failure.
 */
class FailureLogThrottleTest {

    private static final Exception FAILURE = new IllegalStateException("native call failed");

    private final List<String> errors = new ArrayList<>();
    private final FailureLogThrottle throttle =
            new FailureLogThrottle(recordingReporter(), "Test subject");

    @Test
    void firstFailureOfAnEpisodeIsLoggedWithItsPlaceAndTheRestAreNot() {
        throttle.noteFailure("capturing", "test subject", "A", FAILURE);
        throttle.noteFailure("capturing", "test subject", "B", FAILURE);
        throttle.endFrame();
        throttle.noteFailure("submitting", "test subject", "C", FAILURE);

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).startsWith("Error capturing test subject at A"), errors.get(0));
    }

    @Test
    void episodeEndsAfterRearmFramesWithoutAFailureAndReportsWhatWentUnlogged() {
        throttle.noteFailure("capturing", "test subject", "A", FAILURE);
        throttle.noteFailure("capturing", "test subject", "A", FAILURE);
        throttle.noteFailure("capturing", "test subject", "A", FAILURE);
        endFrames(FailureLogThrottle.REARM_FRAMES - 1);
        assertEquals(1, errors.size());

        throttle.endFrame();
        assertEquals(2, errors.size());
        assertEquals("Test subject failures have stopped; 2 went unlogged", errors.get(1));

        throttle.noteFailure("submitting", "test subject", "B", FAILURE);
        assertEquals(3, errors.size());
        assertTrue(errors.get(2).startsWith("Error submitting test subject at B"), errors.get(2));
    }

    @Test
    void aFailureInsideTheWindowRestartsIt() {
        throttle.noteFailure("capturing", "test subject", "A", FAILURE);
        endFrames(FailureLogThrottle.REARM_FRAMES - 1);
        throttle.noteFailure("capturing", "test subject", "A", FAILURE);
        endFrames(FailureLogThrottle.REARM_FRAMES - 1);
        assertEquals(1, errors.size());

        throttle.endFrame();
        assertEquals(2, errors.size());
        assertEquals("Test subject failures have stopped; 1 went unlogged", errors.get(1));
    }

    @Test
    void anEpisodeOfOneFailureEndsWithoutASummary() {
        throttle.noteFailure("capturing", "test subject", "A", FAILURE);
        endFrames(FailureLogThrottle.REARM_FRAMES);
        throttle.noteFailure("capturing", "test subject", "A", FAILURE);

        assertEquals(2, errors.size());
        assertTrue(errors.get(1).startsWith("Error capturing test subject at A"), errors.get(1));
    }

    @Test
    void framesWithoutAnyFailureLogNothing() {
        endFrames(FailureLogThrottle.REARM_FRAMES * 2);

        assertEquals(0, errors.size());
    }

    private void endFrames(int count) {
        for (int i = 0; i < count; i++) {
            throttle.endFrame();
        }
    }

    private FailureLogThrottle.Reporter recordingReporter() {
        return new FailureLogThrottle.Reporter() {
            @Override
            public void firstFailure(
                    String stage,
                    String what,
                    Object where,
                    int rearmFrames,
                    Exception failure) {
                errors.add("Error " + stage + " " + what + " at " + where
                        + " (further failures are not logged until " + rearmFrames
                        + " frames pass without one)");
            }

            @Override
            public void episodeStopped(String subject, long unlogged) {
                errors.add(subject + " failures have stopped; " + unlogged + " went unlogged");
            }
        };
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

/**
 * Logs the first failure of an episode and counts the rest. A capture pass runs for
 * every subject every frame and continues past a failure, so a persistent one would
 * otherwise print a line per subject per frame. The log re-arms once
 * {@link #REARM_FRAMES} frames end without a failure, and the episode's unlogged
 * count is reported then.
 */
final class FailureLogThrottle {

    /** Frames without a failure before the failure log re-arms. */
    static final int REARM_FRAMES = 300;

    private final Reporter reporter;
    private final String subject;
    private boolean logged;
    private int framesSinceFailure;
    private long unlogged;

    /**
     * @param subject what the pass captures, as it should open the episode summary
     */
    FailureLogThrottle(Reporter reporter, String subject) {
        this.reporter = reporter;
        this.subject = subject;
    }

    /**
     * Records one failure and logs it with its trace if it opens an episode.
     *
     * @param stage the step that failed, "capturing" or "submitting"
     * @param what  the failed subject as it should read in the log line
     * @param where the subject's block position
     */
    void noteFailure(String stage, String what, Object where, Exception e) {
        framesSinceFailure = 0;
        if (logged) {
            unlogged++;
            return;
        }
        logged = true;
        reporter.firstFailure(stage, what, where, REARM_FRAMES, e);
    }

    /** Advances the frame clock and re-arms the log once the episode has ended. */
    void endFrame() {
        if (!logged || ++framesSinceFailure < REARM_FRAMES) {
            return;
        }
        logged = false;
        if (unlogged > 0) {
            reporter.episodeStopped(subject, unlogged);
            unlogged = 0;
        }
    }

    interface Reporter {
        void firstFailure(
                String stage, String what, Object where, int rearmFrames, Exception failure);

        void episodeStopped(String subject, long unlogged);
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.benchmark;

import com.nvidia.rtxremix.minecraft.camera.StillShot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Walks a shot set one shot at a time: apply, settle, capture, advance.
 *
 * <p>The session owns only the schedule. The controller that drives it does
 * the teleport, the scene changes, the per-frame pose hold, and the capture,
 * so this class stays free of Minecraft and can be tested on its own.</p>
 *
 * <p>Settling is a frame count with a quiet requirement: a shot captures once
 * at least {@code minFrames} have been rendered since its pose was applied
 * and the terrain pipeline has been idle for {@code quietFrames} in a row, or
 * when {@code maxFrames} have passed regardless, so a scene that never goes
 * fully quiet (a river, a lot of mobs) still produces its image. Frames under
 * a world-load screen count for nothing.</p>
 */
final class StillShotSession {

    /** Frame budgets for one shot. */
    static final class Settle {
        static final Settle DEFAULT = new Settle(600, 60, 3600);

        private final int minFrames;
        private final int quietFrames;
        private final int maxFrames;

        Settle(int minFrames, int quietFrames, int maxFrames) {
            if (minFrames < 0 || quietFrames < 0 || maxFrames < 1) {
                throw new IllegalArgumentException("settle frame counts must be non-negative and max positive");
            }
            if (maxFrames < minFrames) {
                throw new IllegalArgumentException("maxFrames must be at least minFrames");
            }
            this.minFrames = minFrames;
            this.quietFrames = quietFrames;
            this.maxFrames = maxFrames;
        }

        int minFrames() { return minFrames; }
        int quietFrames() { return quietFrames; }
        int maxFrames() { return maxFrames; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Settle)) return false;
            Settle settle = (Settle) other;
            return minFrames == settle.minFrames
                    && quietFrames == settle.quietFrames
                    && maxFrames == settle.maxFrames;
        }

        @Override
        public int hashCode() {
            int result = minFrames;
            result = 31 * result + quietFrames;
            return 31 * result + maxFrames;
        }
    }

    private final List<StillShot> shots;
    private final Settle settle;
    private int index;
    private boolean applied;
    private int framesSinceApply;
    private int quietRun;

    StillShotSession(List<StillShot> shots, Settle settle) {
        if (shots.isEmpty()) {
            throw new IllegalArgumentException("a still shot session needs at least one shot");
        }
        this.shots = Collections.unmodifiableList(new ArrayList<>(shots));
        this.settle = Objects.requireNonNull(settle, "settle");
    }

    /** The shot being settled, or {@code null} once every shot is captured. */
    StillShot current() {
        return index < shots.size() ? shots.get(index) : null;
    }

    int capturedCount() {
        return index;
    }

    int totalCount() {
        return shots.size();
    }

    boolean isDone() {
        return index >= shots.size();
    }

    /**
     * Whether the current shot's pose still needs its one-time application
     * (teleport and scene); the caller marks it applied afterwards.
     */
    boolean needsApply() {
        return !isDone() && !applied;
    }

    void markApplied() {
        applied = true;
        framesSinceApply = 0;
        quietRun = 0;
    }

    /**
     * Accounts for one rendered frame of the current shot and says whether
     * to capture it now.
     *
     * @param loadScreenUp whether a world-load screen covered the frame
     * @param terrainIdle  whether the terrain pipeline had nothing in flight
     */
    boolean onFrameEnd(boolean loadScreenUp, boolean terrainIdle) {
        if (isDone() || !applied || loadScreenUp) {
            return false;
        }
        framesSinceApply++;
        quietRun = terrainIdle ? quietRun + 1 : 0;
        return shouldCapture(framesSinceApply, quietRun, settle);
    }

    /** Moves to the next shot after a capture. */
    void advance() {
        index++;
        applied = false;
        framesSinceApply = 0;
        quietRun = 0;
    }

    int framesSinceApply() {
        return framesSinceApply;
    }

    static boolean shouldCapture(int framesSinceApply, int quietRun, Settle settle) {
        if (framesSinceApply >= settle.maxFrames()) {
            return true;
        }
        return framesSinceApply >= settle.minFrames() && quietRun >= settle.quietFrames();
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoinBurstHoldTest {

    private static final long MS = 1_000_000L;
    private static final long START = 10_000L * MS;

    /** Feeds one frame boundary per interval and returns the time of the last one. */
    private static long frames(JoinBurstHold hold, long now, long... intervalsMs) {
        for (long ms : intervalsMs) {
            now += ms * MS;
            hold.onFrame(now);
        }
        return now;
    }

    private static JoinBurstHold begun() {
        JoinBurstHold hold = new JoinBurstHold(true);
        hold.begin(START);
        hold.onFrame(START);
        return hold;
    }

    @Test
    void holdsThroughTheBurstAndReleasesAfterEightFastFrames() {
        JoinBurstHold hold = begun();
        assertTrue(hold.keepScreen(START));

        long now = frames(hold, START, 610, 288, 130);
        assertTrue(hold.keepScreen(now), "burst frames keep the screen up");

        now = frames(hold, now, 20, 20, 20, 20, 20, 20, 20);
        assertTrue(hold.keepScreen(now), "seven fast frames are not yet settled");

        now = frames(hold, now, 20);
        assertFalse(hold.keepScreen(now), "the eighth fast frame releases the screen");
    }

    @Test
    void aSlowFrameRestartsTheStreak() {
        JoinBurstHold hold = begun();
        long now = frames(hold, START, 20, 20, 20, 20, 20, 20, 20);
        now = frames(hold, now, 150);
        now = frames(hold, now, 20, 20, 20, 20, 20, 20, 20);
        assertTrue(hold.keepScreen(now));

        now = frames(hold, now, 20);
        assertFalse(hold.keepScreen(now));
    }

    @Test
    void thresholdCountsAHundredMillisecondFrameAsSlow() {
        JoinBurstHold hold = begun();
        long now = frames(hold, START, 100, 100, 100, 100, 100, 100, 100, 100);
        assertTrue(hold.keepScreen(now), "steady 100 ms frames never settle");

        now = frames(hold, now, 99, 99, 99, 99, 99, 99, 99, 99);
        assertFalse(hold.keepScreen(now), "frames just under the threshold settle");
    }

    @Test
    void capReleasesAScreenThatNeverSettles() {
        JoinBurstHold hold = begun();
        long now = START;
        for (int i = 0; i < 29; i++) {
            now = frames(hold, now, 200);
            assertTrue(hold.keepScreen(now), "held until the cap at frame " + i);
        }
        now = frames(hold, now, 200);
        assertFalse(hold.keepScreen(now), "six seconds after begin the screen is released");
    }

    @Test
    void disabledHoldNeverKeepsTheScreen() {
        JoinBurstHold hold = new JoinBurstHold(false);
        hold.begin(START);
        assertFalse(hold.keepScreen(START));
    }

    @Test
    void framesBeforeBeginDoNotCount() {
        JoinBurstHold hold = new JoinBurstHold(true);
        long now = frames(hold, START, 20, 20, 20, 20, 20, 20, 20, 20, 20);
        hold.begin(now);
        hold.onFrame(now);
        now = frames(hold, now, 20, 20, 20, 20, 20, 20, 20);
        assertTrue(hold.keepScreen(now), "the streak starts at begin, not at the first frame ever seen");
    }

    @Test
    void beginRestartsAReleasedHold() {
        JoinBurstHold hold = begun();
        long now = frames(hold, START, 20, 20, 20, 20, 20, 20, 20, 20);
        assertFalse(hold.keepScreen(now));

        hold.begin(now);
        assertTrue(hold.keepScreen(now));
    }
}

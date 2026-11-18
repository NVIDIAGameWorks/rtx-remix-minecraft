/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CopperBeaconAnimationClockPolicyTest {

    private static final long START_NANOS = 1_000_000_000L;

    @AfterEach
    void resetClock() {
        CopperBeaconAnimationClockPolicy.reset();
    }

    @Test
    void monotonicRenderTimeSmoothsAStationaryVanillaTickDelta() {
        assertEquals(
                100.0,
                CopperBeaconAnimationClockPolicy.sample(null, 100L, 0.0f, START_NANOS));
        assertEquals(
                100.5,
                CopperBeaconAnimationClockPolicy.sample(
                        null, 100L, 0.0f, START_NANOS + 25_000_000L));
        assertEquals(
                101.0,
                CopperBeaconAnimationClockPolicy.sample(
                        null, 100L, 0.0f, START_NANOS + 50_000_000L));
    }

    @Test
    void advancingWorldTickReanchorsWithoutAVisibleDiscontinuity() {
        CopperBeaconAnimationClockPolicy.sample(null, 100L, 0.0f, START_NANOS);
        assertEquals(
                100.8,
                CopperBeaconAnimationClockPolicy.sample(
                        null, 100L, 0.8f, START_NANOS + 30_000_000L),
                1.0e-6);
        assertEquals(
                101.0,
                CopperBeaconAnimationClockPolicy.sample(
                        null, 101L, 0.0f, START_NANOS + 50_000_000L));
        assertEquals(
                101.5,
                CopperBeaconAnimationClockPolicy.sample(
                        null, 101L, 0.0f, START_NANOS + 75_000_000L));
    }

    @Test
    void invalidAndOutOfRangeTickDeltaCannotMoveOutsideTheCurrentTick() {
        assertEquals(
                42.0,
                CopperBeaconAnimationClockPolicy.sample(
                        null, 42L, Float.NaN, START_NANOS));
        assertEquals(
                43.0,
                CopperBeaconAnimationClockPolicy.sample(
                        null, 42L, 5.0f, START_NANOS));
    }
}

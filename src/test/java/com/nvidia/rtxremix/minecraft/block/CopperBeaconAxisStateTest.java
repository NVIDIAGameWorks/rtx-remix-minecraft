/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.CompoundTag;

class CopperBeaconAxisStateTest {

    private static final float EPSILON = 1.0e-4f;

    @Test
    void staticAndSignedRotationUseThePersistedAnchor() {
        CopperBeaconAxisState staticAxis = axis(
                135.0f, CopperBeaconAnimationMode.STATIC, 0.0f, 100L);
        assertEquals(135.0f, staticAxis.angle(50_000.0, 1L), EPSILON);

        CopperBeaconAxisState clockwise = axis(
                10.0f, CopperBeaconAnimationMode.ROTATE, 90.0f, 100L);
        CopperBeaconAxisState counterClockwise = axis(
                10.0f, CopperBeaconAnimationMode.ROTATE, -90.0f, 100L);
        assertEquals(100.0f, clockwise.angle(120.0, 1L), EPSILON);
        assertEquals(-80.0f, counterClockwise.angle(120.0, 1L), EPSILON);
    }

    @Test
    void oscillationUsesRelativeBoundsAndExactCycleDuration() {
        CopperBeaconAxisState state = new CopperBeaconAxisState(
                30.0f,
                CopperBeaconAnimationMode.OSCILLATE,
                0.0f,
                -20.0f,
                60.0f,
                4.0f,
                5.0f,
                200L);

        assertEquals(10.0f, state.angle(200.0, 1L), EPSILON);
        assertEquals(50.0f, state.angle(220.0, 1L), EPSILON);
        assertEquals(90.0f, state.angle(240.0, 1L), EPSILON);
        assertEquals(10.0f, state.angle(280.0, 1L), EPSILON);
    }

    @Test
    void patrolIsDeterministicBoundedSmoothAndAxisSeeded() {
        CopperBeaconAxisState state = new CopperBeaconAxisState(
                12.0f,
                CopperBeaconAnimationMode.PATROL,
                0.0f,
                -40.0f,
                80.0f,
                10.0f,
                2.0f,
                0L);

        for (int tick = 0; tick <= 2_000; tick++) {
            float first = state.angle(tick, 1234L);
            float repeated = state.angle(tick, 1234L);
            assertEquals(first, repeated);
            assertTrue(first >= -28.0f && first <= 92.0f, "tick=" + tick);
        }

        float beforeBoundary = state.angle(39.999, 1234L);
        float atBoundary = state.angle(40.0, 1234L);
        float afterBoundary = state.angle(40.001, 1234L);
        assertEquals(atBoundary, beforeBoundary, 0.001f);
        assertEquals(atBoundary, afterBoundary, 0.001f);
        assertTrue(Math.abs(state.angle(13.0, 1234L) - state.angle(13.0, 5678L)) > EPSILON);
    }

    @Test
    void constructorSanitizesEveryNetworkControlledRange() {
        CopperBeaconAxisState state = new CopperBeaconAxisState(
                Float.NaN,
                null,
                Float.POSITIVE_INFINITY,
                900.0f,
                -900.0f,
                0.0f,
                Float.NaN,
                7L);

        assertEquals(0.0f, state.position());
        assertEquals(CopperBeaconAnimationMode.STATIC, state.mode());
        assertEquals(0.0f, state.rotationSpeed());
        assertEquals(-360.0f, state.minimumOffset());
        assertEquals(360.0f, state.maximumOffset());
        assertEquals(CopperBeaconAxisState.MIN_DURATION_SECONDS, state.cycleSeconds());
        assertEquals(CopperBeaconAxisState.DEFAULT_PATROL_SECONDS, state.patrolSeconds());
    }

    @Test
    void nbtRoundTripPreservesAllAnimationInputs() {
        CopperBeaconAxisState original = new CopperBeaconAxisState(
                -270.0f,
                CopperBeaconAnimationMode.PATROL,
                -721.5f,
                -180.0f,
                355.0f,
                18.5f,
                7.25f,
                987_654_321L);
        CompoundTag nbt = new CompoundTag();
        original.writeNbt(nbt);

        assertEquals(original, CopperBeaconAxisState.readNbt(nbt));
        assertEquals(CopperBeaconAxisState.defaults(), CopperBeaconAxisState.readNbt(new CompoundTag()));
    }

    private static CopperBeaconAxisState axis(
            float position,
            CopperBeaconAnimationMode mode,
            float speed,
            long anchor) {
        return new CopperBeaconAxisState(
                position,
                mode,
                speed,
                CopperBeaconAxisState.DEFAULT_MINIMUM_OFFSET,
                CopperBeaconAxisState.DEFAULT_MAXIMUM_OFFSET,
                CopperBeaconAxisState.DEFAULT_CYCLE_SECONDS,
                CopperBeaconAxisState.DEFAULT_PATROL_SECONDS,
                anchor);
    }
}

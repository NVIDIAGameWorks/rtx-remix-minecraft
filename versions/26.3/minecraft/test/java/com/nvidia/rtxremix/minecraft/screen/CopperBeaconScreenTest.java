/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.screen;

import com.nvidia.rtxremix.minecraft.block.CopperBeaconAxisState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CopperBeaconScreenTest {

    private static final double EPSILON = 1.0e-6;

    @Test
    void intensityIsLogarithmicWithOffAndOneAtTheCenter() {
        assertEquals(0.0, CopperBeaconScreen.sliderToIntensity(0.0));
        assertEquals(1.0, CopperBeaconScreen.sliderToIntensity(0.5), EPSILON);
        assertEquals(1000.0, CopperBeaconScreen.sliderToIntensity(1.0), EPSILON);
        assertEquals(0.5, CopperBeaconScreen.intensityToSlider(1.0), EPSILON);
        assertEquals(1.0, CopperBeaconScreen.intensityToSlider(1000.0), EPSILON);
    }

    @Test
    void angleSliderCoversTwoTurnsInBothDirections() {
        assertEquals(
                CopperBeaconAxisState.MIN_ANGLE,
                CopperBeaconScreen.sliderToAngle(0.0));
        assertEquals(0.0, CopperBeaconScreen.sliderToAngle(0.5), EPSILON);
        assertEquals(
                CopperBeaconAxisState.MAX_ANGLE,
                CopperBeaconScreen.sliderToAngle(1.0));
    }

    @Test
    void signedRotationHasZeroCenterAndLogarithmicEnds() {
        assertEquals(
                -CopperBeaconAxisState.MAX_ROTATION_SPEED,
                CopperBeaconScreen.sliderToSignedSpeed(0.0),
                EPSILON);
        assertEquals(0.0, CopperBeaconScreen.sliderToSignedSpeed(0.5), EPSILON);
        assertEquals(
                CopperBeaconAxisState.MAX_ROTATION_SPEED,
                CopperBeaconScreen.sliderToSignedSpeed(1.0),
                EPSILON);
        assertEquals(0.5, CopperBeaconScreen.signedSpeedToSlider(0.0), EPSILON);
    }

    @Test
    void durationMappingPreservesBothLimits() {
        assertEquals(
                CopperBeaconAxisState.MIN_DURATION_SECONDS,
                CopperBeaconScreen.sliderToDuration(0.0),
                EPSILON);
        assertEquals(
                CopperBeaconAxisState.MAX_DURATION_SECONDS,
                CopperBeaconScreen.sliderToDuration(1.0),
                EPSILON);
        assertEquals(
                0.0,
                CopperBeaconScreen.durationToSlider(
                        CopperBeaconAxisState.MIN_DURATION_SECONDS),
                EPSILON);
    }
}

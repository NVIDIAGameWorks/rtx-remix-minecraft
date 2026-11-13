/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LightPowerTest {

    private static final float PI = (float) Math.PI;

    @Test
    void powerIsShapeAreaTimesPiTimesLuminance() {
        // White radiance of 1: luminance 1, so power is area times pi.
        assertEquals(4 * PI * PI, LightPower.of(LightShape.SPHERE, 1f, 0f, 0f, 0f, 1f, 1f, 1f), 1e-3f);
        assertEquals(2 * PI * PI, LightPower.of(LightShape.CYLINDER, 1f, 1f, 0f, 0f, 1f, 1f, 1f), 1e-3f);
        assertEquals(6 * PI, LightPower.of(LightShape.RECT, 0f, 0f, 2f, 3f, 1f, 1f, 1f), 1e-3f);
        assertEquals(PI * PI, LightPower.of(LightShape.DISK, 0f, 0f, 1f, 1f, 1f, 1f, 1f), 1e-3f);
    }

    @Test
    void luminanceIsBt709() {
        assertEquals(0.2126f, LightPower.luminance(1f, 0f, 0f), 1e-6f);
        assertEquals(0.7152f, LightPower.luminance(0f, 1f, 0f), 1e-6f);
        assertEquals(0.0722f, LightPower.luminance(0f, 0f, 1f), 1e-6f);
    }

    @Test
    void theTorchReferenceMatchesTheShippedRule() {
        // Torch: light level 14 x 5 x 3, authored x7.5, colour (1, 0.35, 0.1),
        // thin-sphere radius 0.2 x 0.4. Changing any of those is a deliberate
        // recalibration of every light.reach value, which is why this pins it.
        assertEquals(187.1f, LightPower.torchReference(), 0.5f);
    }
}

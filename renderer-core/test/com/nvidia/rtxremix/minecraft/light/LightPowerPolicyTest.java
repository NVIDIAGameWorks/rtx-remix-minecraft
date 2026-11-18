/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LightPowerPolicyTest {
    private static final float PI = (float) Math.PI;

    @Test
    void powerIsShapeAreaTimesPiTimesLuminance() {
        assertEquals(
                4 * PI * PI,
                LightPowerPolicy.of(
                        LightShape.SPHERE, 1f, 0f, 0f, 0f, 1f, 1f, 1f),
                1e-3f);
        assertEquals(
                2 * PI * PI,
                LightPowerPolicy.of(
                        LightShape.CYLINDER, 1f, 1f, 0f, 0f, 1f, 1f, 1f),
                1e-3f);
        assertEquals(
                6 * PI,
                LightPowerPolicy.of(
                        LightShape.RECT, 0f, 0f, 2f, 3f, 1f, 1f, 1f),
                1e-3f);
        assertEquals(
                PI * PI,
                LightPowerPolicy.of(
                        LightShape.DISK, 0f, 0f, 1f, 1f, 1f, 1f, 1f),
                1e-3f);
    }

    @Test
    void luminanceIsBt709() {
        assertEquals(0.2126f, LightPowerPolicy.luminance(1f, 0f, 0f), 1e-6f);
        assertEquals(0.7152f, LightPowerPolicy.luminance(0f, 1f, 0f), 1e-6f);
        assertEquals(0.0722f, LightPowerPolicy.luminance(0f, 0f, 1f), 1e-6f);
    }
}

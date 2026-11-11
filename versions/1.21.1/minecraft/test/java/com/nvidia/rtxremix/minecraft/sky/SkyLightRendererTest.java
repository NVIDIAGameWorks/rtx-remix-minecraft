/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;




class SkyLightRendererTest {

    @ParameterizedTest
    @CsvSource({
            "1.0, -1.0, 3.0",
            "-1.0, 1.0, 0.06"
    })
    void weatherDomeRadiancePreservesAuthoredSourceScale(
            float sunHeight, float moonHeight, float expected) {
        SkyLightState state = state(
                new float[]{0.0f, sunHeight, 0.0f},
                new float[]{0.0f, moonHeight, 0.0f},
                new float[]{30.0f, 30.0f, 30.0f},
                new float[]{0.6f, 0.6f, 0.6f});

        assertEquals(expected, state.weatherDomeRadiance(), 1.0e-6f);
    }

    @Test
    void weatherInterpolatesDirectLightsWithoutReducingDomeSource() {
        float clearSun = SkyLightRenderer.weatherSunDirectScale(0.0f, 0.0f);
        float rainSun = SkyLightRenderer.weatherSunDirectScale(1.0f, 0.0f);
        float thunderSun = SkyLightRenderer.weatherSunDirectScale(1.0f, 1.0f);
        float clearMoon = SkyLightRenderer.weatherMoonDirectScale(0.0f, 0.0f);
        float rainMoon = SkyLightRenderer.weatherMoonDirectScale(1.0f, 0.0f);
        float thunderMoon = SkyLightRenderer.weatherMoonDirectScale(1.0f, 1.0f);

        assertEquals(1.0f, clearSun, 1.0e-6f);
        assertEquals(0.0025f, rainSun, 1.0e-6f);
        assertEquals(0.001f, thunderSun, 1.0e-6f);
        assertEquals(1.0f, clearMoon, 1.0e-6f);
        assertEquals(0.375f, rainMoon, 1.0e-6f);
        assertEquals(0.625f, thunderMoon, 1.0e-6f);
        assertEquals(
                (clearSun + rainSun) * 0.5f,
                SkyLightRenderer.weatherSunDirectScale(0.5f, 0.0f),
                1.0e-6f);
        assertEquals(
                (rainSun + thunderSun) * 0.5f,
                SkyLightRenderer.weatherSunDirectScale(1.0f, 0.5f),
                1.0e-6f);
        assertEquals(
                (clearMoon + rainMoon) * 0.5f,
                SkyLightRenderer.weatherMoonDirectScale(0.5f, 0.0f),
                1.0e-6f);
        assertEquals(
                (rainMoon + thunderMoon) * 0.5f,
                SkyLightRenderer.weatherMoonDirectScale(1.0f, 0.5f),
                1.0e-6f);
    }

    private static SkyLightState state(
            float[] sunPosition,
            float[] moonPosition,
            float[] sunSource,
            float[] moonSource) {
        return new SkyLightState(
                sunPosition,
                moonPosition,
                sunSource,
                moonSource,
                new float[3],
                new float[3],
                0.0f,
                0.0f);
    }
}

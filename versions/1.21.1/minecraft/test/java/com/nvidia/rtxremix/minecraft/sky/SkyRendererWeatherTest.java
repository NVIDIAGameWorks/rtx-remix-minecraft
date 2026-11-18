/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkyRendererWeatherTest {

    @Test
    void clearWeatherHasNoOverlay() {
        assertWeather(0.0f, 0.0f, 2.0f, 0.5f, 0.0f, 0.0f);
    }

    @Test
    void rainStrengthControlsOpacityWithoutChangingRainIntensity() {
        assertWeather(0.4f, 0.0f, 2.0f, 0.5f, 0.4f, 2.0f);
    }

    @Test
    void thunderStrengthControlsOpacityWithoutChangingThunderIntensity() {
        assertWeather(0.0f, 0.25f, 2.0f, 0.5f, 0.25f, 0.5f);
    }

    @Test
    void thunderComposesOverRainUsingNestedLerp() {
        float expectedOpacity = 0.4f + 0.25f * (1.0f - 0.4f);
        float expectedPremultipliedIntensity = 0.25f * 0.5f
                + (1.0f - 0.25f) * 0.4f * 2.0f;
        assertWeather(
                0.4f,
                0.25f,
                2.0f,
                0.5f,
                expectedOpacity,
                expectedPremultipliedIntensity / expectedOpacity);
    }

    @Test
    void fullThunderOverridesRain() {
        assertWeather(0.7f, 1.0f, 2.0f, 0.5f, 1.0f, 0.5f);
    }

    @Test
    void starOpacityTracksVanillaBrightness() {
        assertEquals(0.5f, SkyRenderer.starOpacity(0.5f), 1.0e-6f);
        assertEquals(0.0f, SkyRenderer.starOpacity(-1.0f));
        assertEquals(1.0f, SkyRenderer.starOpacity(2.0f));
    }

    @Test
    void celestialBodiesFadeAcrossFourDegreesBelowHorizon() {
        float twoDegreesBelow = -(float) Math.sin(Math.toRadians(2.0));
        float fourDegreesBelow = -(float) Math.sin(Math.toRadians(4.0));

        assertEquals(1.0f, SkyRenderer.celestialBodyOpacity(0.0f));
        assertEquals(0.5f, SkyRenderer.celestialBodyOpacity(twoDegreesBelow), 0.01f);
        assertEquals(0.0f, SkyRenderer.celestialBodyOpacity(fourDegreesBelow), 1.0e-6f);
    }

    private static void assertWeather(
            float rain,
            float thunder,
            float rainIntensity,
            float thunderIntensity,
            float expectedOpacity,
            float expectedIntensity) {
        SkyRenderer.WeatherDomeState state = SkyRenderer.weatherDomeState(
                rain,
                thunder,
                rainIntensity,
                thunderIntensity);
        assertEquals(expectedOpacity, state.opacity(), 1.0e-6f);
        assertEquals(expectedIntensity, state.intensity(), 1.0e-6f);
    }
}

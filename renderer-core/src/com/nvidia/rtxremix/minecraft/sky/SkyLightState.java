/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

/** Immutable loader-neutral sun/moon state consumed by the sky scene. */
public final class SkyLightState {
    private static final float WEATHER_DOME_RADIANCE_REFERENCE = 10.0f;
    public static final float SUN_PATH_ROTATION_DEG = 30.0f;
    public static final float THUNDER_SKY_INTENSITY = 0.8f;
    private static final float SUN_PATH_ROTATION_RAD =
            (float) Math.toRadians(SUN_PATH_ROTATION_DEG);

    private final float[] sunPosition;
    private final float[] moonPosition;
    private final float[] sunSourceRadiance;
    private final float[] moonSourceRadiance;
    private final float[] sunDirectRadiance;
    private final float[] moonDirectRadiance;
    private final float sunAngularRadius;
    private final float moonAngularRadius;

    public SkyLightState(
            float[] sunPosition,
            float[] moonPosition,
            float[] sunSourceRadiance,
            float[] moonSourceRadiance,
            float[] sunDirectRadiance,
            float[] moonDirectRadiance,
            float sunAngularRadius,
            float moonAngularRadius) {
        this.sunPosition = sunPosition;
        this.moonPosition = moonPosition;
        this.sunSourceRadiance = sunSourceRadiance;
        this.moonSourceRadiance = moonSourceRadiance;
        this.sunDirectRadiance = sunDirectRadiance;
        this.moonDirectRadiance = moonDirectRadiance;
        this.sunAngularRadius = sunAngularRadius;
        this.moonAngularRadius = moonAngularRadius;
    }

    public float[] sunPosition() { return sunPosition; }
    public float[] moonPosition() { return moonPosition; }
    public float[] sunSourceRadiance() { return sunSourceRadiance; }
    public float[] moonSourceRadiance() { return moonSourceRadiance; }
    public float[] sunDirectRadiance() { return sunDirectRadiance; }
    public float[] moonDirectRadiance() { return moonDirectRadiance; }
    public float sunAngularRadius() { return sunAngularRadius; }
    public float moonAngularRadius() { return moonAngularRadius; }

    public float weatherDomeRadiance() {
        float sunRadiance = luminance(sunSourceRadiance)
                * celestialVisibility(sunPosition[1]);
        float moonRadiance = luminance(moonSourceRadiance)
                * celestialVisibility(moonPosition[1]);
        return (sunRadiance + moonRadiance) / WEATHER_DOME_RADIANCE_REFERENCE;
    }

    public static float celestialVisibility(float height) {
        float t = Math.max(0.0f, Math.min(1.0f, (height + 0.15f) / 0.20f));
        return t * t * (3.0f - 2.0f * t);
    }

    public static float[] computeSunDirection(float skyAngle) {
        float angle = (float) (skyAngle * 2.0 * Math.PI + Math.PI / 2.0);
        float sx = (float) Math.cos(angle);
        float sy = (float) Math.sin(angle);
        float cr = (float) Math.cos(SUN_PATH_ROTATION_RAD);
        float sr = (float) Math.sin(SUN_PATH_ROTATION_RAD);
        return new float[] {sx, sy * cr, sy * sr};
    }

    public static SkyLightState empty(float sunAngularRadius, float moonAngularRadius) {
        return new SkyLightState(
                new float[]{0.0f, 1.0f, 0.0f},
                new float[]{0.0f, -1.0f, 0.0f},
                new float[]{0.0f, 0.0f, 0.0f},
                new float[]{0.0f, 0.0f, 0.0f},
                new float[]{0.0f, 0.0f, 0.0f},
                new float[]{0.0f, 0.0f, 0.0f},
                sunAngularRadius,
                moonAngularRadius);
    }

    public static SkyLightState empty() {
        return empty(
                (float) Math.toRadians(0.53f * 0.5f),
                (float) Math.toRadians(0.52f * 0.5f));
    }

    private static float luminance(float[] radiance) {
        return radiance[0] * 0.2126f + radiance[1] * 0.7152f + radiance[2] * 0.0722f;
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import com.nvidia.rtxremix.LightInfo;
import com.nvidia.rtxremix.LightInfoDistantEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixException;
import com.nvidia.rtxremix.minecraft.light.RetainedLightSlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Canonical sun/moon radiance policy and retained distant-light lifecycle. */
public final class SkyLightRenderer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkyLightRenderer.class);

    private static final float SUN_ANGULAR_DIAMETER_DEGREES = 0.53f;
    private static final float MOON_ANGULAR_DIAMETER_DEGREES = 0.52f;
    private static final float SUN_ANGULAR_RADIUS =
            (float) Math.toRadians(SUN_ANGULAR_DIAMETER_DEGREES * 0.5f);
    private static final float MOON_ANGULAR_RADIUS =
            (float) Math.toRadians(MOON_ANGULAR_DIAMETER_DEGREES * 0.5f);
    private static final float MIN_DIRECT_RADIANCE = 1.0e-6f;
    private static final float BASE_SUN_LUMINANCE = 30.0f;
    private static final float BASE_MOON_LUMINANCE = 0.06f;
    private static final float RAIN_SUN_LUMINANCE = 0.075f;
    private static final float THUNDER_SUN_LUMINANCE = 0.03f;
    private static final float RAIN_MOON_LUMINANCE = 0.0225f;
    private static final float THUNDER_MOON_LUMINANCE = 0.0375f;

    public static final float DEFAULT_SUN_INTENSITY = 1.0f;
    public static final float DEFAULT_MOON_INTENSITY = 1.0f;

    private static float sunIntensity = DEFAULT_SUN_INTENSITY;
    private static float moonIntensity = DEFAULT_MOON_INTENSITY;

    private final RemixAPI api;
    private final RetainedLightSlot sunLight = new RetainedLightSlot();
    private final RetainedLightSlot moonLight = new RetainedLightSlot();
    private SkyLightState state = SkyLightState.empty();

    public SkyLightRenderer(RemixAPI api) {
        if (api == null) {
            throw new NullPointerException("api");
        }
        this.api = api;
    }

    public void update(
            boolean normalSky,
            float skyAngle,
            float rainGradient,
            float thunderGradient) {
        state = normalSky
                ? evaluate(skyAngle, rainGradient, thunderGradient)
                : SkyLightState.empty();
        try {
            updateDistantLight(
                    sunLight,
                    state.sunPosition(),
                    state.sunDirectRadiance(),
                    SUN_ANGULAR_DIAMETER_DEGREES);
            updateDistantLight(
                    moonLight,
                    state.moonPosition(),
                    state.moonDirectRadiance(),
                    MOON_ANGULAR_DIAMETER_DEGREES);
        } catch (RemixException exception) {
            LOGGER.error("Failed to update sky light: {}", exception.getMessage());
        }
    }

    public void draw() throws RemixException {
        sunLight.draw(api);
        moonLight.draw(api);
    }

    public SkyLightState state() {
        return state;
    }

    public int lightCount() {
        return (sunLight.isPresent() ? 1 : 0) + (moonLight.isPresent() ? 1 : 0);
    }

    public static float getSunIntensity() {
        return sunIntensity;
    }

    public static void setSunIntensity(float value) {
        sunIntensity = value;
    }

    public static float getMoonIntensity() {
        return moonIntensity;
    }

    public static void setMoonIntensity(float value) {
        moonIntensity = value;
    }

    static float weatherSunDirectScale(float rainGradient, float thunderGradient) {
        float weatherSunLuminance = lerp(
                RAIN_SUN_LUMINANCE,
                THUNDER_SUN_LUMINANCE,
                thunderGradient);
        return lerp(1.0f, weatherSunLuminance / BASE_SUN_LUMINANCE, rainGradient);
    }

    static float weatherMoonDirectScale(float rainGradient, float thunderGradient) {
        float weatherMoonLuminance = lerp(
                RAIN_MOON_LUMINANCE,
                THUNDER_MOON_LUMINANCE,
                thunderGradient);
        return lerp(1.0f, weatherMoonLuminance / BASE_MOON_LUMINANCE, rainGradient);
    }

    @Override
    public void close() {
        clear();
    }

    public void clear() {
        sunLight.clear();
        moonLight.clear();
        state = SkyLightState.empty();
    }

    private void updateDistantLight(
            RetainedLightSlot light,
            float[] positionDirection,
            float[] radiance,
            float angularDiameterDegrees) throws RemixException {
        LightInfo info = LightInfo.builder()
                .radiance(radiance[0], radiance[1], radiance[2])
                .distantExt(LightInfoDistantEXT.builder()
                        .direction(
                                -positionDirection[0],
                                -positionDirection[1],
                                -positionDirection[2])
                        .angularDiameterDegrees(angularDiameterDegrees)
                        .volumetricRadianceScale(1.0f)
                        .build())
                .build();
        light.update(info);
    }

    private static SkyLightState evaluate(
            float skyAngle,
            float rainGradient,
            float thunderGradient) {
        float[] sunPosition = SkyLightState.computeSunDirection(skyAngle);
        float[] moonPosition = {-sunPosition[0], -sunPosition[1], -sunPosition[2]};

        float sunLuminance = BASE_SUN_LUMINANCE * sunIntensity;
        float moonLuminance = BASE_MOON_LUMINANCE * moonIntensity;
        float[] sunSource = {sunLuminance, sunLuminance, sunLuminance};
        float[] moonSource = {moonLuminance * 0.85f, moonLuminance * 0.9f, moonLuminance};
        float[] sunDirect = applyTransmittance(
                sunSource, directAtmosphereTransmittance(sunPosition[1]));
        float[] moonDirect = applyTransmittance(
                moonSource, directAtmosphereTransmittance(moonPosition[1]));
        sunDirect = scaleRadiance(
                sunDirect, weatherSunDirectScale(rainGradient, thunderGradient));
        moonDirect = scaleRadiance(
                moonDirect, weatherMoonDirectScale(rainGradient, thunderGradient));

        return new SkyLightState(
                sunPosition,
                moonPosition,
                sunSource,
                moonSource,
                thresholdDirectRadiance(sunDirect),
                thresholdDirectRadiance(moonDirect),
                SUN_ANGULAR_RADIUS,
                MOON_ANGULAR_RADIUS);
    }

    private static float[] directAtmosphereTransmittance(float height) {
        if (height <= 0.0f) {
            return new float[]{0.0f, 0.0f, 0.0f};
        }
        float zenithRadians =
                (float) Math.acos(Math.max(0.0f, Math.min(1.0f, height)));
        float zenithDegrees = zenithRadians * (180.0f / (float) Math.PI);
        float airMass = 1.0f
                / (height
                        + 0.15f
                        * (float) Math.pow(93.885f - zenithDegrees, -1.253f));
        float mie = 0.080f;
        return new float[]{
                (float) Math.exp(-(0.145f + mie) * airMass),
                (float) Math.exp(-(0.340f + mie) * airMass),
                (float) Math.exp(-(0.580f + mie) * airMass)
        };
    }

    private static float[] applyTransmittance(float[] radiance, float[] transmittance) {
        return new float[]{
                radiance[0] * transmittance[0],
                radiance[1] * transmittance[1],
                radiance[2] * transmittance[2]
        };
    }

    private static float[] scaleRadiance(float[] radiance, float scale) {
        return new float[]{
                radiance[0] * scale,
                radiance[1] * scale,
                radiance[2] * scale
        };
    }

    private static float[] thresholdDirectRadiance(float[] radiance) {
        return (radiance[0] + radiance[1] + radiance[2]) > MIN_DIRECT_RADIANCE
                ? radiance
                : new float[]{0.0f, 0.0f, 0.0f};
    }

    private static float lerp(float from, float to, float amount) {
        float t = Math.max(0.0f, Math.min(1.0f, amount));
        return from + (to - from) * t;
    }
}

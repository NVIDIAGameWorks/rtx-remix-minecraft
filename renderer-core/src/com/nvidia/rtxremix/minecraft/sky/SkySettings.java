/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

/** Loader-neutral sky rendering settings shared by every Minecraft integration. */
public final class SkySettings {
    public static final float DEFAULT_NIGHT_AMBIENT_BOOST = 2.5f;
    public static final float DEFAULT_DAY_AMBIENT_BOOST = 0.0f;
    public static final boolean DEFAULT_SKY_AMBIENT_RECT_ENABLED = true;
    public static final float DEFAULT_MINIMUM_CELESTIAL_WEATHER_VISIBILITY = 0.0f;
    public static final float DEFAULT_MIE_SCATTERING_ANISOTROPY = 0.9f;
    public static final float MAX_MIE_SCATTERING_ANISOTROPY = 0.99f;

    private static boolean enabled = true;
    private static boolean ambientRectEnabled = DEFAULT_SKY_AMBIENT_RECT_ENABLED;
    private static float domeMultiplier = 1.0f;
    private static float nightAmbientBoost = DEFAULT_NIGHT_AMBIENT_BOOST;
    private static float dayAmbientBoost = DEFAULT_DAY_AMBIENT_BOOST;
    private static float minimumCelestialWeatherVisibility =
            DEFAULT_MINIMUM_CELESTIAL_WEATHER_VISIBILITY;
    private static float mieScatteringAnisotropy = DEFAULT_MIE_SCATTERING_ANISOTROPY;

    private SkySettings() {
    }

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean value) { enabled = value; }
    public static boolean isAmbientRectEnabled() { return ambientRectEnabled; }
    public static void setAmbientRectEnabled(boolean value) { ambientRectEnabled = value; }
    public static float getDomeMultiplier() { return domeMultiplier; }
    public static void setDomeMultiplier(float value) { domeMultiplier = value; }
    public static float getNightAmbientBoost() { return nightAmbientBoost; }
    public static void setNightAmbientBoost(float value) { nightAmbientBoost = value; }
    public static float getDayAmbientBoost() { return dayAmbientBoost; }
    public static void setDayAmbientBoost(float value) { dayAmbientBoost = value; }
    public static float getMinimumCelestialWeatherVisibility() {
        return minimumCelestialWeatherVisibility;
    }
    public static void setMinimumCelestialWeatherVisibility(float value) {
        minimumCelestialWeatherVisibility = clamp(value, 0.0f, 1.0f);
    }
    public static float getMieScatteringAnisotropy() {
        return mieScatteringAnisotropy;
    }
    public static void setMieScatteringAnisotropy(float value) {
        mieScatteringAnisotropy = clamp(value, 0.0f, MAX_MIE_SCATTERING_ANISOTROPY);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

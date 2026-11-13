/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import java.util.function.BiConsumer;

/**
 * Tracks post-processing values exposed by the host's settings screen. The
 * authored defaults live in {@code rtx.conf}, so construction is write-free.
 * Live edits, persisted overrides, and Reset appliers call the writer.
 */
public final class PostProcessingSettings {

    private static final String EXPOSURE_KEY = "rtx.localtonemap.exposure";
    private static final String SHADOWS_KEY = "rtx.localtonemap.shadows";
    private static final String HIGHLIGHTS_KEY = "rtx.localtonemap.highlights";
    private static final String LOW_LIGHT_ADAPTATION_KEY = "rtx.autoExposure.evMinValue";
    private static final String HIGH_LIGHT_ADAPTATION_KEY = "rtx.autoExposure.evMaxValue";

    private static final float DEFAULT_EXPOSURE = 0.21405728f;
    private static final float DEFAULT_SHADOWS = 1.4f;
    private static final float DEFAULT_HIGHLIGHTS = 2.5f;
    private static final float DEFAULT_LOW_LIGHT_ADAPTATION = -2.5f;
    private static final float DEFAULT_HIGH_LIGHT_ADAPTATION = 1.5f;

    private final BiConsumer<String, String> configWriter;

    private float exposure = DEFAULT_EXPOSURE;
    private float shadowLevel = DEFAULT_SHADOWS;
    private float highlightsLevel = DEFAULT_HIGHLIGHTS;
    private float lowLightAdaptation = DEFAULT_LOW_LIGHT_ADAPTATION;
    private float highLightAdaptation = DEFAULT_HIGH_LIGHT_ADAPTATION;

    public PostProcessingSettings(BiConsumer<String, String> configWriter) {
        this.configWriter = configWriter;
    }

    public float getExposure() {
        return exposure;
    }

    public void setExposure(float value) {
        exposure = value;
        apply(EXPOSURE_KEY, value);
    }

    public float getShadowLevel() {
        return shadowLevel;
    }

    public void setShadowLevel(float value) {
        shadowLevel = value;
        apply(SHADOWS_KEY, value);
    }

    public float getHighlightsLevel() {
        return highlightsLevel;
    }

    public void setHighlightsLevel(float value) {
        highlightsLevel = value;
        apply(HIGHLIGHTS_KEY, value);
    }

    public float getLowLightAdaptation() {
        return lowLightAdaptation;
    }

    public void setLowLightAdaptation(float value) {
        lowLightAdaptation = value;
        apply(LOW_LIGHT_ADAPTATION_KEY, value);
    }

    public float getHighLightAdaptation() {
        return highLightAdaptation;
    }

    public void setHighLightAdaptation(float value) {
        highLightAdaptation = value;
        apply(HIGH_LIGHT_ADAPTATION_KEY, value);
    }

    private void apply(String key, float value) {
        configWriter.accept(key, Float.toString(value));
    }
}

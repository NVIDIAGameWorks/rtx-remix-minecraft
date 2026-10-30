/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ARGB;
import net.minecraft.world.attribute.EnvironmentAttributes;

/**
 * The sky and cloud values vanilla draws with, read the way vanilla reads them
 * since 26.x: as environment attributes probed at the camera. They replace the
 * removed {@code ClientLevel} sky getters.
 */
public final class SkyAttributes {

    private SkyAttributes() {
    }

    /**
     * The sun's position as the 0..1 day fraction 1.21.x returned from
     * {@code getTimeOfDay}: 0 at noon. SUN_ANGLE is the same fraction in degrees.
     */
    public static float timeOfDay(float partialTicks) {
        return probeFloat(EnvironmentAttributes.SUN_ANGLE, partialTicks, 0.0f) / 360.0f;
    }

    public static float starBrightness(float partialTicks) {
        return probeFloat(EnvironmentAttributes.STAR_BRIGHTNESS, partialTicks, 0.0f);
    }

    /** The moon phase index, 0 (full) to 7. */
    public static int moonPhase(float partialTicks) {
        var camera = Minecraft.getInstance().gameRenderer.mainCamera();
        return camera.attributeProbe().getValue(EnvironmentAttributes.MOON_PHASE, partialTicks).index();
    }

    /** The cloud colour as packed ARGB. */
    public static int cloudColor(float partialTicks) {
        var camera = Minecraft.getInstance().gameRenderer.mainCamera();
        return ARGB.colorFromVector4f(camera.attributeProbe().getValue(EnvironmentAttributes.CLOUD_COLOR, partialTicks));
    }

    public static float cloudHeight(float partialTicks) {
        return probeFloat(EnvironmentAttributes.CLOUD_HEIGHT, partialTicks, Float.NaN);
    }

    private static float probeFloat(
            net.minecraft.world.attribute.EnvironmentAttribute<Float> attribute, float partialTicks, float fallback) {
        var camera = Minecraft.getInstance().gameRenderer.mainCamera();
        Float value = camera.attributeProbe().getValue(attribute, partialTicks);
        return value == null ? fallback : value;
    }
}

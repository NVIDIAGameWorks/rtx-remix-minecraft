/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

/** Resolves Minecraft light rules into the canonical emitted-power policy. */
public final class LightPower {
    private static final int TORCH_LIGHT_LEVEL = 14;

    private LightPower() {}

    public static float luminance(float r, float g, float b) {
        return LightPowerPolicy.luminance(r, g, b);
    }

    public static float of(
            LightShape shape,
            float radius,
            float axisLength,
            float xSize,
            float ySize,
            float radianceR,
            float radianceG,
            float radianceB) {
        return LightPowerPolicy.of(
                shape,
                radius,
                axisLength,
                xSize,
                ySize,
                radianceR,
                radianceG,
                radianceB);
    }

    public static float of(LightEntry light, float radianceR, float radianceG, float radianceB) {
        return LightPowerPolicy.of(
                light.shape(),
                light.radius(),
                light.axisLength(),
                light.xSize(),
                light.ySize(),
                radianceR,
                radianceG,
                radianceB);
    }

    public static float torchReference() {
        float intensity = TORCH_LIGHT_LEVEL
                * LightPowerPolicy.RADIANCE_PER_LIGHT_LEVEL
                * BlockLightDefaults.THIN_SPHERE_INTENSITY_SCALE
                * BlockLightDefaults.TORCH_AUTHORED_INTENSITY_MULTIPLIER;
        float radius = BlockLightDefaults.TORCH_RADIUS
                * BlockLightDefaults.THIN_SPHERE_RADIUS_SCALE;
        LightEntry.ColorOverride color = BlockLightDefaults.TORCH_COLOR;
        return LightPowerPolicy.of(
                LightShape.SPHERE,
                radius,
                0f,
                0f,
                0f,
                color.r() * intensity,
                color.g() * intensity,
                color.b() * intensity);
    }
}

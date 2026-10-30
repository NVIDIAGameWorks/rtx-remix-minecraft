/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import net.minecraft.world.level.block.Block;

/**
 * Resolved block-attached light discovered during chunk iteration.
 */
public record LightEntry(
        BlockLightKey key,
        int lightLevel,
        Block block,
        LightShape shape,
        float x,
        float y,
        float z,
        float colorR,
        float colorG,
        float colorB,
        boolean hasColor,
        float intensity,
        boolean hasIntensity,
        float radius,
        float axisX,
        float axisY,
        float axisZ,
        float axisLength,
        float xAxisX,
        float xAxisY,
        float xAxisZ,
        float xSize,
        float yAxisX,
        float yAxisY,
        float yAxisZ,
        float ySize,
        float directionX,
        float directionY,
        float directionZ,
        boolean hasShaping,
        float shapingDirectionX,
        float shapingDirectionY,
        float shapingDirectionZ,
        float coneAngleDegrees,
        float coneSoftness,
        float focusExponent,
        float volumetricRadianceScale,
        float authoredIntensityMultiplier) {

    private static final float DEFAULT_VOL_RADIANCE_SCALE = 0.5f;

    public static LightEntry sphere(BlockLightKey key, int lightLevel, Block block,
                                    float x, float y, float z,
                                    float radius,
                                    ColorOverride color,
                                    Float intensity,
                                    float volumetricRadianceScale) {
        return base(key, lightLevel, block, LightShape.SPHERE, x, y, z, color, intensity,
                radius, 0, 1, 0, 1,
                1, 0, 0, 1,
                0, 1, 0, 1,
                0, 0, -1, volumetricRadianceScale, Shaping.NONE);
    }

    /**
     * A sphere light restricted to a cone, a spot light.
     *
     * <p>{@code direction} is the axis the cone opens along and is normalized
     * here. Only sphere, rect, and disk lights support shaping in the Remix API;
     * cylinders are always omnidirectional.
     */
    public static LightEntry shapedSphere(BlockLightKey key, int lightLevel, Block block,
                                          float x, float y, float z,
                                          float radius,
                                          ColorOverride color,
                                          Float intensity,
                                          float directionX, float directionY, float directionZ,
                                          float coneAngleDegrees,
                                          float coneSoftness,
                                          float focusExponent,
                                          float volumetricRadianceScale) {
        Shaping shaping = Shaping.cone(directionX, directionY, directionZ,
                coneAngleDegrees, coneSoftness, focusExponent);
        return base(key, lightLevel, block, LightShape.SPHERE, x, y, z, color, intensity,
                radius, 0, 1, 0, 1,
                1, 0, 0, 1,
                0, 1, 0, 1,
                0, 0, -1, volumetricRadianceScale, shaping);
    }

    public static LightEntry cylinder(BlockLightKey key, int lightLevel, Block block,
                                      float x, float y, float z,
                                      float radius,
                                      float axisX, float axisY, float axisZ,
                                      float axisLength,
                                      ColorOverride color,
                                      Float intensity,
                                      float volumetricRadianceScale) {
        return base(key, lightLevel, block, LightShape.CYLINDER, x, y, z, color, intensity,
                radius, axisX, axisY, axisZ, axisLength,
                1, 0, 0, 1,
                0, 1, 0, 1,
                0, 0, -1, volumetricRadianceScale, Shaping.NONE);
    }

    public static LightEntry rect(BlockLightKey key, int lightLevel, Block block,
                                  float x, float y, float z,
                                  float xAxisX, float xAxisY, float xAxisZ, float xSize,
                                  float yAxisX, float yAxisY, float yAxisZ, float ySize,
                                  float directionX, float directionY, float directionZ,
                                  ColorOverride color,
                                  Float intensity,
                                  float volumetricRadianceScale) {
        return base(key, lightLevel, block, LightShape.RECT, x, y, z, color, intensity,
                0.2f, 0, 1, 0, 1,
                xAxisX, xAxisY, xAxisZ, xSize,
                yAxisX, yAxisY, yAxisZ, ySize,
                directionX, directionY, directionZ, volumetricRadianceScale, Shaping.NONE);
    }

    public static LightEntry disk(BlockLightKey key, int lightLevel, Block block,
                                  float x, float y, float z,
                                  float xAxisX, float xAxisY, float xAxisZ, float xRadius,
                                  float yAxisX, float yAxisY, float yAxisZ, float yRadius,
                                  float directionX, float directionY, float directionZ,
                                  ColorOverride color,
                                  Float intensity,
                                  float volumetricRadianceScale) {
        return base(key, lightLevel, block, LightShape.DISK, x, y, z, color, intensity,
                0.2f, 0, 1, 0, 1,
                xAxisX, xAxisY, xAxisZ, xRadius,
                yAxisX, yAxisY, yAxisZ, yRadius,
                directionX, directionY, directionZ, volumetricRadianceScale, Shaping.NONE);
    }

    public static float defaultVolumetricRadianceScale() {
        return DEFAULT_VOL_RADIANCE_SCALE;
    }

    private static LightEntry base(BlockLightKey key, int lightLevel, Block block,
                                   LightShape shape,
                                   float x, float y, float z,
                                   ColorOverride color,
                                   Float intensity,
                                   float radius,
                                   float axisX, float axisY, float axisZ, float axisLength,
                                   float xAxisX, float xAxisY, float xAxisZ, float xSize,
                                   float yAxisX, float yAxisY, float yAxisZ, float ySize,
                                   float directionX, float directionY, float directionZ,
                                   float volumetricRadianceScale,
                                   Shaping shaping) {
        ColorOverride c = color == null ? ColorOverride.NONE : color;
        Shaping s = shaping == null ? Shaping.NONE : shaping;
        return new LightEntry(key, lightLevel, block, shape, x, y, z,
                c.r, c.g, c.b, c.hasColor,
                intensity == null ? 0.0f : intensity, intensity != null,
                radius,
                axisX, axisY, axisZ, axisLength,
                xAxisX, xAxisY, xAxisZ, xSize,
                yAxisX, yAxisY, yAxisZ, ySize,
                directionX, directionY, directionZ,
                s.has, s.dirX, s.dirY, s.dirZ,
                s.coneAngleDegrees, s.coneSoftness, s.focusExponent,
                volumetricRadianceScale, 1.0f);
    }

    /**
     * Returns this resolved entry with tuning owned by its built-in rule.
     *
     * <p>The multiplier travels with the resolved entry so a resource-pack rule
     * replacing the same identifier does not accidentally inherit built-in tuning.
     */
    LightEntry withAuthoredIntensityMultiplier(float multiplier) {
        return new LightEntry(
                key, lightLevel, block, shape, x, y, z,
                colorR, colorG, colorB, hasColor,
                intensity, hasIntensity,
                radius,
                axisX, axisY, axisZ, axisLength,
                xAxisX, xAxisY, xAxisZ, xSize,
                yAxisX, yAxisY, yAxisZ, ySize,
                directionX, directionY, directionZ,
                hasShaping,
                shapingDirectionX, shapingDirectionY, shapingDirectionZ,
                coneAngleDegrees, coneSoftness, focusExponent,
                volumetricRadianceScale, multiplier);
    }

    /**
     * Cone restriction for a spot light. Mirrors {@code LightInfoLightShaping}
     * in the Remix wrapper.
     */
    public record Shaping(boolean has, float dirX, float dirY, float dirZ,
                          float coneAngleDegrees, float coneSoftness, float focusExponent) {

        public static final Shaping NONE = new Shaping(false, 0, 0, -1, 0, 0, 0);

        /** Builds a cone along {@code (dx, dy, dz)}, normalizing the direction. */
        public static Shaping cone(float dx, float dy, float dz,
                                   float coneAngleDegrees, float coneSoftness, float focusExponent) {
            float len = (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len == 0.0f) {
                return NONE;
            }
            return new Shaping(true, dx / len, dy / len, dz / len,
                    coneAngleDegrees, coneSoftness, focusExponent);
        }
    }

    public record ColorOverride(boolean hasColor, float r, float g, float b) {
        public static final ColorOverride NONE = new ColorOverride(false, 0, 0, 0);

        public static ColorOverride of(float r, float g, float b) {
            return new ColorOverride(true, r, g, b);
        }
    }
}

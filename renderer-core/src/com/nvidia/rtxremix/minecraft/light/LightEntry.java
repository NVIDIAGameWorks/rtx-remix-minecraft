/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import com.nvidia.rtxremix.minecraft.terrain.BlockLightKey;

/**
 * Resolved block-attached light discovered during chunk iteration.
 */
public final class LightEntry {
    private final BlockLightKey key;
    private final int lightLevel;
    private final int sourceToken;
    private final LightShape shape;
    private final float x;
    private final float y;
    private final float z;
    private final float colorR;
    private final float colorG;
    private final float colorB;
    private final boolean hasColor;
    private final float intensity;
    private final boolean hasIntensity;
    private final float radius;
    private final float axisX;
    private final float axisY;
    private final float axisZ;
    private final float axisLength;
    private final float xAxisX;
    private final float xAxisY;
    private final float xAxisZ;
    private final float xSize;
    private final float yAxisX;
    private final float yAxisY;
    private final float yAxisZ;
    private final float ySize;
    private final float directionX;
    private final float directionY;
    private final float directionZ;
    private final boolean hasShaping;
    private final float shapingDirectionX;
    private final float shapingDirectionY;
    private final float shapingDirectionZ;
    private final float coneAngleDegrees;
    private final float coneSoftness;
    private final float focusExponent;
    private final float volumetricRadianceScale;
    private final float authoredIntensityMultiplier;

    private static final float DEFAULT_VOL_RADIANCE_SCALE = 0.5f;

    private LightEntry(
            BlockLightKey key, int lightLevel, int sourceToken,
            LightShape shape,
            float x, float y, float z,
            float colorR, float colorG, float colorB, boolean hasColor,
            float intensity, boolean hasIntensity, float radius,
            float axisX, float axisY, float axisZ, float axisLength,
            float xAxisX, float xAxisY, float xAxisZ, float xSize,
            float yAxisX, float yAxisY, float yAxisZ, float ySize,
            float directionX, float directionY, float directionZ,
            boolean hasShaping,
            float shapingDirectionX, float shapingDirectionY, float shapingDirectionZ,
            float coneAngleDegrees, float coneSoftness, float focusExponent,
            float volumetricRadianceScale, float authoredIntensityMultiplier) {
        this.key = key;
        this.lightLevel = lightLevel;
        this.sourceToken = sourceToken;
        this.shape = shape;
        this.x = x;
        this.y = y;
        this.z = z;
        this.colorR = colorR;
        this.colorG = colorG;
        this.colorB = colorB;
        this.hasColor = hasColor;
        this.intensity = intensity;
        this.hasIntensity = hasIntensity;
        this.radius = radius;
        this.axisX = axisX;
        this.axisY = axisY;
        this.axisZ = axisZ;
        this.axisLength = axisLength;
        this.xAxisX = xAxisX;
        this.xAxisY = xAxisY;
        this.xAxisZ = xAxisZ;
        this.xSize = xSize;
        this.yAxisX = yAxisX;
        this.yAxisY = yAxisY;
        this.yAxisZ = yAxisZ;
        this.ySize = ySize;
        this.directionX = directionX;
        this.directionY = directionY;
        this.directionZ = directionZ;
        this.hasShaping = hasShaping;
        this.shapingDirectionX = shapingDirectionX;
        this.shapingDirectionY = shapingDirectionY;
        this.shapingDirectionZ = shapingDirectionZ;
        this.coneAngleDegrees = coneAngleDegrees;
        this.coneSoftness = coneSoftness;
        this.focusExponent = focusExponent;
        this.volumetricRadianceScale = volumetricRadianceScale;
        this.authoredIntensityMultiplier = authoredIntensityMultiplier;
    }

    public static LightEntry sphere(
                                    BlockLightKey key, int lightLevel,
                                    int sourceToken,
                                    float x, float y, float z,
                                    float radius,
                                    ColorOverride color,
                                    Float intensity,
                                    float volumetricRadianceScale) {
        return base(key, lightLevel, sourceToken,
                LightShape.SPHERE, x, y, z, color, intensity,
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
    public static LightEntry shapedSphere(
                                          BlockLightKey key, int lightLevel,
                                          int sourceToken,
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
        return base(key, lightLevel, sourceToken,
                LightShape.SPHERE, x, y, z, color, intensity,
                radius, 0, 1, 0, 1,
                1, 0, 0, 1,
                0, 1, 0, 1,
                0, 0, -1, volumetricRadianceScale, shaping);
    }

    public static LightEntry cylinder(
                                      BlockLightKey key, int lightLevel,
                                      int sourceToken,
                                      float x, float y, float z,
                                      float radius,
                                      float axisX, float axisY, float axisZ,
                                      float axisLength,
                                      ColorOverride color,
                                      Float intensity,
                                      float volumetricRadianceScale) {
        return base(key, lightLevel, sourceToken,
                LightShape.CYLINDER, x, y, z, color, intensity,
                radius, axisX, axisY, axisZ, axisLength,
                1, 0, 0, 1,
                0, 1, 0, 1,
                0, 0, -1, volumetricRadianceScale, Shaping.NONE);
    }

    public static LightEntry rect(
                                  BlockLightKey key, int lightLevel,
                                  int sourceToken,
                                  float x, float y, float z,
                                  float xAxisX, float xAxisY, float xAxisZ, float xSize,
                                  float yAxisX, float yAxisY, float yAxisZ, float ySize,
                                  float directionX, float directionY, float directionZ,
                                  ColorOverride color,
                                  Float intensity,
                                  float volumetricRadianceScale) {
        return base(key, lightLevel, sourceToken,
                LightShape.RECT, x, y, z, color, intensity,
                0.2f, 0, 1, 0, 1,
                xAxisX, xAxisY, xAxisZ, xSize,
                yAxisX, yAxisY, yAxisZ, ySize,
                directionX, directionY, directionZ, volumetricRadianceScale, Shaping.NONE);
    }

    public static LightEntry disk(
                                  BlockLightKey key, int lightLevel,
                                  int sourceToken,
                                  float x, float y, float z,
                                  float xAxisX, float xAxisY, float xAxisZ, float xRadius,
                                  float yAxisX, float yAxisY, float yAxisZ, float yRadius,
                                  float directionX, float directionY, float directionZ,
                                  ColorOverride color,
                                  Float intensity,
                                  float volumetricRadianceScale) {
        return base(key, lightLevel, sourceToken,
                LightShape.DISK, x, y, z, color, intensity,
                0.2f, 0, 1, 0, 1,
                xAxisX, xAxisY, xAxisZ, xRadius,
                yAxisX, yAxisY, yAxisZ, yRadius,
                directionX, directionY, directionZ, volumetricRadianceScale, Shaping.NONE);
    }

    public static float defaultVolumetricRadianceScale() {
        return DEFAULT_VOL_RADIANCE_SCALE;
    }

    private static LightEntry base(
                                   BlockLightKey key, int lightLevel,
                                   int sourceToken,
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
        return new LightEntry(key, lightLevel, sourceToken,
                shape, x, y, z,
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
                key, lightLevel, sourceToken, shape, x, y, z,
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

    public BlockLightKey key() { return key; }
    public int lightLevel() { return lightLevel; }
    public int sourceToken() { return sourceToken; }
    public LightShape shape() { return shape; }
    public float x() { return x; }
    public float y() { return y; }
    public float z() { return z; }
    public float colorR() { return colorR; }
    public float colorG() { return colorG; }
    public float colorB() { return colorB; }
    public boolean hasColor() { return hasColor; }
    public float intensity() { return intensity; }
    public boolean hasIntensity() { return hasIntensity; }
    public float radius() { return radius; }
    public float axisX() { return axisX; }
    public float axisY() { return axisY; }
    public float axisZ() { return axisZ; }
    public float axisLength() { return axisLength; }
    public float xAxisX() { return xAxisX; }
    public float xAxisY() { return xAxisY; }
    public float xAxisZ() { return xAxisZ; }
    public float xSize() { return xSize; }
    public float yAxisX() { return yAxisX; }
    public float yAxisY() { return yAxisY; }
    public float yAxisZ() { return yAxisZ; }
    public float ySize() { return ySize; }
    public float directionX() { return directionX; }
    public float directionY() { return directionY; }
    public float directionZ() { return directionZ; }
    public boolean hasShaping() { return hasShaping; }
    public float shapingDirectionX() { return shapingDirectionX; }
    public float shapingDirectionY() { return shapingDirectionY; }
    public float shapingDirectionZ() { return shapingDirectionZ; }
    public float coneAngleDegrees() { return coneAngleDegrees; }
    public float coneSoftness() { return coneSoftness; }
    public float focusExponent() { return focusExponent; }
    public float volumetricRadianceScale() { return volumetricRadianceScale; }
    public float authoredIntensityMultiplier() { return authoredIntensityMultiplier; }

    /**
     * Cone restriction for a spot light. Mirrors {@code LightInfoLightShaping}
     * in the Remix wrapper.
     */
    public static final class Shaping {

        public static final Shaping NONE = new Shaping(false, 0, 0, -1, 0, 0, 0);

        private final boolean has;
        private final float dirX;
        private final float dirY;
        private final float dirZ;
        private final float coneAngleDegrees;
        private final float coneSoftness;
        private final float focusExponent;

        private Shaping(boolean has, float dirX, float dirY, float dirZ,
                        float coneAngleDegrees, float coneSoftness, float focusExponent) {
            this.has = has;
            this.dirX = dirX;
            this.dirY = dirY;
            this.dirZ = dirZ;
            this.coneAngleDegrees = coneAngleDegrees;
            this.coneSoftness = coneSoftness;
            this.focusExponent = focusExponent;
        }

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

    public static final class ColorOverride {
        public static final ColorOverride NONE = new ColorOverride(false, 0, 0, 0);

        private final boolean hasColor;
        private final float r;
        private final float g;
        private final float b;

        private ColorOverride(boolean hasColor, float r, float g, float b) {
            this.hasColor = hasColor;
            this.r = r;
            this.g = g;
            this.b = b;
        }

        public static ColorOverride of(float r, float g, float b) {
            return new ColorOverride(true, r, g, b);
        }

        public boolean hasColor() { return hasColor; }
        public float r() { return r; }
        public float g() { return g; }
        public float b() { return b; }
    }
}

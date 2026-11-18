/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

/**
 * Emitted-power proxy for a block light: shape area times pi times the
 * luminance of its radiance, the same quantity the Remix sampler weights a
 * light by before dividing by distance squared.
 */
public final class LightPowerPolicy {

    public static final float RADIANCE_PER_LIGHT_LEVEL = 5.0f;
    private static final float PI = (float) Math.PI;

    private LightPowerPolicy() {}

    /** BT.709 luminance of a linear RGB radiance. */
    public static float luminance(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    /**
     * @param shape      light shape
     * @param radius     sphere or cylinder radius
     * @param axisLength cylinder length
     * @param xSize      rect width or disk x radius
     * @param ySize      rect height or disk y radius
     * @param radianceR  linear RGB radiance after every multiplier
     * @param radianceG  linear RGB radiance after every multiplier
     * @param radianceB  linear RGB radiance after every multiplier
     * @return the emitted-power proxy
     */
    public static float of(LightShape shape, float radius, float axisLength, float xSize, float ySize,
                           float radianceR, float radianceG, float radianceB) {
        final float area;
        switch (shape) {
            case SPHERE:
                area = 4f * PI * radius * radius;
                break;
            case CYLINDER:
                area = 2f * PI * radius * axisLength;
                break;
            case RECT:
                area = xSize * ySize;
                break;
            case DISK:
                area = PI * xSize * ySize;
                break;
            default:
                throw new IllegalArgumentException("Unknown light shape: " + shape);
        }
        return area * PI * luminance(radianceR, radianceG, radianceB);
    }
}

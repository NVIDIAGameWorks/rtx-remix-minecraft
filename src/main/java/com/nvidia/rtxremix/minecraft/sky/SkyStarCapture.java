/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

/**
 * Holds the star-field vertices vanilla's sky renderer generated, captured as it
 * uploads them. Since 26.x the sky renderer is created lazily on the first sky
 * pass and recreated when the sky resets, so the copy lives here rather than in
 * the renderer.
 */
public final class SkyStarCapture {

    private static volatile float[] starVertices = new float[0];

    private SkyStarCapture() {
    }

    /** Records the packed XYZ positions of a freshly built star field. */
    public static void record(float[] positions) {
        starVertices = positions;
    }

    /** A copy of the packed XYZ star positions, empty until the sky renderer first runs. */
    public static float[] copyStarVertices() {
        return starVertices.clone();
    }
}

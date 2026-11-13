/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import java.nio.ByteBuffer;

/** Host implementation of atmospheric texture generation, typically backed by GL. */
public interface SkyAtmosphereSource {
    int WIDTH = 256;
    int HEIGHT = 128;
    int OUTPUT_BYTES = WIDTH * HEIGHT * 4 * 2;

    ByteBuffer generate(
            SkyLightState lightState,
            float[] upperMean,
            float mieScatteringAnisotropy,
            boolean publishedOutputValid);

    void destroy();
}

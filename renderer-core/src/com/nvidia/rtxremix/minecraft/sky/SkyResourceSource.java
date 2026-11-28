/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import java.io.IOException;

/** Host resource extraction used by the canonical sky scene. */
public interface SkyResourceSource {
    String overcastTexturePath();

    Image loadSunTexture() throws IOException;

    Image loadMoonTexture() throws IOException;

    float[] copyStarVertices();

    final class Image {
        public final byte[] rgba;
        public final int width;
        public final int height;

        public Image(byte[] rgba, int width, int height) {
            this.rgba = rgba;
            this.width = width;
            this.height = height;
        }
    }
}

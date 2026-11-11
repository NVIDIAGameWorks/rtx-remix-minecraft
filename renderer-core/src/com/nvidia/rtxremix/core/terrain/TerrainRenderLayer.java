/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core.terrain;

/** Canonical render-layer identities shared by every loader adapter. */
public final class TerrainRenderLayer {
    public static final int SOLID = 0;
    public static final int CUTOUT = 1;
    public static final int CUTOUT_MIPPED = 2;
    public static final int TRANSLUCENT = 3;
    public static final int GLASS = 4;
    public static final int COUNT = 5;

    private TerrainRenderLayer() {
    }

    public static int type(String name) {
        if ("cutout".equals(name)) {
            return CUTOUT;
        }
        if ("cutout_mipped".equals(name)) {
            return CUTOUT_MIPPED;
        }
        if ("translucent".equals(name)) {
            return TRANSLUCENT;
        }
        if ("glass".equals(name)) {
            return GLASS;
        }
        return SOLID;
    }

    public static boolean isBlended(String name) {
        return "translucent".equals(name) || "glass".equals(name);
    }
}

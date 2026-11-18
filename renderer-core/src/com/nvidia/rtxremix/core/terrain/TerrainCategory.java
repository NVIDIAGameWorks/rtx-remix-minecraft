/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core.terrain;

/**
 * Loader-independent terrain routing categories.
 *
 * <p>Adapters report Vanilla render facts; the renderer core owns the mapping
 * from those facts to retained-scene behavior.
 */
public final class TerrainCategory {
    public static final int SOLID = 0;
    public static final int CUTOUT = 1;
    public static final int TRANSLUCENT = 2;
    public static final int OVERLAY = 3;
    public static final int COUNT = 4;
    public static final int ALL_MASK = (1 << COUNT) - 1;

    private TerrainCategory() {
    }

    /**
     * Solid and cutout surfaces can share one retained instance: alpha testing
     * is a no-op for fully opaque solid texels. Translucent and decal surfaces
     * still require distinct instance state.
     */
    public static int publicationCategory(int category) {
        return category == SOLID ? CUTOUT : category;
    }

    /**
     * Expands changed logical categories to the native publication groups that
     * must be replaced atomically.
     */
    public static int publicationMask(int changedMask) {
        int opaqueMask = (1 << SOLID) | (1 << CUTOUT);
        return (changedMask & opaqueMask) != 0
                ? changedMask | opaqueMask
                : changedMask;
    }
}

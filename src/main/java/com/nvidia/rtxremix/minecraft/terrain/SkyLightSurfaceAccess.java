/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Seeded surface refresh of one chunk's sky-light palette, implemented by the ChunkSkyLight mixin.
 */
public interface SkyLightSurfaceAccess {

    /**
     * Recomputes every column's surface from the chunk's sections, starting each column's
     * walk at its {@code WORLD_SURFACE} height. Render thread, client chunk apply only.
     */
    void rtxremix$refreshSurfaceY(ChunkAccess chunk, Heightmap worldSurface);
}

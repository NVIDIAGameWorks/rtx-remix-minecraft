/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.SkyLightSurfaceAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import com.nvidia.rtxremix.minecraft.terrain.SeededSurfaceScan;
import com.nvidia.rtxremix.minecraft.terrain.ChunkSections;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes a seeded variant of {@code refreshSurfaceY} that reuses vanilla's private pair
 * predicate and palette writer, so the seeded walk and the full walk agree block for block.
 * Adds no state; server chunks never reach the new method.
 */
@Mixin(ChunkSkyLightSources.class)
public abstract class ChunkSkyLightMixin implements SkyLightSurfaceAccess {
    @Shadow
    private void set(int index, int y) {
        throw new AssertionError();
    }

    @Shadow
    private static boolean isEdgeOccluded(BlockGetter blockView, BlockPos upperPos, BlockState upperState,
                                           BlockPos lowerPos, BlockState lowerState) {
        throw new AssertionError();
    }

    @Shadow
    private static int index(int localX, int localZ) {
        throw new AssertionError();
    }

    @Override
    public void rtxremix$refreshSurfaceY(ChunkAccess chunk, Heightmap worldSurface) {
        SeededSurfaceScan.refreshSurfaceY(chunk, chunk.getHighestFilledSectionIndex(), worldSurface::getFirstAvailable,
                Blocks.AIR.defaultBlockState(), new ChunkSections(chunk),
                (upperPos, upperState, lowerPos, lowerState) ->
                        isEdgeOccluded(chunk, upperPos, upperState, lowerPos, lowerState),
                (localX, localZ, surfaceY) -> set(index(localX, localZ), surfaceY));
    }
}

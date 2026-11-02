/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.SkyLightSurfaceAccess;
import com.nvidia.rtxremix.minecraft.terrain.SeededSurfaceScan;
import com.nvidia.rtxremix.minecraft.terrain.ChunkSections;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.light.ChunkSkyLight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes a seeded variant of {@code refreshSurfaceY} that reuses vanilla's private pair
 * predicate and palette writer, so the seeded walk and the full walk agree block for block.
 * Adds no state; server chunks never reach the new method.
 */
@Mixin(ChunkSkyLight.class)
public abstract class ChunkSkyLightMixin implements SkyLightSurfaceAccess {
    @Shadow
    private void set(int index, int y) {
        throw new AssertionError();
    }

    @Shadow
    private static boolean faceBlocksLight(BlockState upperState, BlockState lowerState) {
        throw new AssertionError();
    }

    @Shadow
    private static int getPackedIndex(int localX, int localZ) {
        throw new AssertionError();
    }

    @Override
    public void rtxremix$refreshSurfaceY(Chunk chunk, Heightmap worldSurface) {
        SeededSurfaceScan.refreshSurfaceY(chunk, chunk.getHighestNonEmptySection(), worldSurface::get,
                Blocks.AIR.getDefaultState(), new ChunkSections(chunk),
                (upperPos, upperState, lowerPos, lowerState) ->
                        faceBlocksLight(upperState, lowerState),
                (localX, localZ, surfaceY) -> set(getPackedIndex(localX, localZ), surfaceY));
    }
}

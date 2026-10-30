/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/** A chunk's sections as {@link SeededSurfaceScan} reads them. */
public record ChunkSections(ChunkAccess chunk) implements SeededSurfaceScan.Sections<BlockState> {

    @Override
    public boolean isEmpty(int index) {
        return chunk.getSection(index).hasOnlyAir();
    }

    @Override
    public BlockState state(int index, int localX, int localY, int localZ) {
        return chunk.getSection(index).getBlockState(localX, localY, localZ);
    }
}

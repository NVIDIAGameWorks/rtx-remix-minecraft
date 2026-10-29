/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.visibility.VisibilityGraphBuffers;
import com.nvidia.rtxremix.minecraft.terrain.visibility.RenderableChunksBuffers;
import com.nvidia.rtxremix.minecraft.terrain.visibility.ChunkInfoPool;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.render.ChunkRenderingDataPreparer.ChunkInfoList;
import net.minecraft.client.render.ChunkRenderingDataPreparer.RenderableChunks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;


/**
 * Builds vanilla's visibility state on the buffers {@link VisibilityGraphBuffers} keeps
 * across walks instead of fresh per-slot lists and maps. Vanilla constructs its own octree
 * for the current camera section; its ordering and nearby-section classification stay intact.
 */
@Mixin(RenderableChunks.class)
public abstract class RenderableChunksMixin implements RenderableChunksBuffers {

    private ChunkInfoPool rtxremix$infoPool;
    private int rtxremix$visibleSectionCount;
    private long rtxremix$visibleSectionHash;

    @Redirect(
            method = "<init>",
            at = @At(value = "NEW", target = "(I)Lnet/minecraft/client/render/ChunkRenderingDataPreparer$ChunkInfoList;"))
    private ChunkInfoList rtxremix$reusedInfoList(int chunkCount) {
        VisibilityGraphBuffers.Buffers buffers = VisibilityGraphBuffers.current();
        rtxremix$infoPool = buffers.infoPool;
        return buffers.infos;
    }

    @Redirect(
            method = "<init>",
            at = @At(value = "NEW", target = "()Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;", remap = false))
    private Long2ObjectOpenHashMap<?> rtxremix$reusedUnbuiltNeighbours() {
        return VisibilityGraphBuffers.current().unbuiltNeighbours;
    }

    @Override
    public ChunkInfoPool rtxremix$infoPool() {
        return rtxremix$infoPool;
    }
    @Override
    public void rtxremix$recordVisibleSection(long sectionPos) {
        rtxremix$visibleSectionCount++;
        rtxremix$visibleSectionHash += (sectionPos * 0x9E3779B97F4A7C15L) ^ 0x632BE59BD9B4E019L;
    }

    @Override
    public int rtxremix$visibleSectionCount() {
        return rtxremix$visibleSectionCount;
    }

    @Override
    public long rtxremix$visibleSectionHash() {
        return rtxremix$visibleSectionHash;
    }
}

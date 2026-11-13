/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.capture.RegionSnapshotRelease;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionBuilderRelease;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * The builder's reference on its cached snapshots; see {@link RegionBuilderRelease}. The
 * reference itself is taken when a snapshot is created ({@link ClientChunkMixin}).
 */
@Mixin(RenderRegionCache.class)
public abstract class ChunkRendererRegionBuilderMixin implements RegionBuilderRelease {

    @Shadow
    @Final
    private Long2ObjectMap<Object> chunkInfoCache;

    @Override
    public void rtxremix$releaseSnapshots() {
        for (Object clientChunk : chunkInfoCache.values()) {
            RenderChunk snapshot = ((ClientChunkSnapshotAccess) clientChunk).rtxremix$snapshotOrNull();
            if (snapshot != null) {
                ((RegionSnapshotRelease) snapshot).rtxremix$release();
            }
        }
        chunkInfoCache.clear();
    }
}

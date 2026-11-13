/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.capture.RenderRegionStoragePool;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionSnapshotRelease;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Marks the render thread as taking a region snapshot while a {@code RenderedChunk} is
 * constructed, so its block-state copies draw pooled storage, and takes the builder's
 * reference on the new snapshot; see {@link RenderedChunkMixin} and
 * {@link ChunkRendererRegionBuilderMixin}.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.RenderRegionCache$ChunkInfo")
public abstract class ClientChunkMixin {

    @WrapOperation(
            method = "renderChunk",
            at = @At(value = "NEW", target = "(Lnet/minecraft/world/level/chunk/LevelChunk;)Lnet/minecraft/client/renderer/chunk/RenderChunk;")
    )
    private RenderChunk rtxremix$snapshotWithPooledStorage(LevelChunk chunk, Operation<RenderChunk> original) {
        RenderRegionStoragePool.beginCopying();
        RenderChunk snapshot;
        try {
            snapshot = original.call(chunk);
        } finally {
            RenderRegionStoragePool.endCopying();
        }
        ((RegionSnapshotRelease) snapshot).rtxremix$retain();
        return snapshot;
    }
}

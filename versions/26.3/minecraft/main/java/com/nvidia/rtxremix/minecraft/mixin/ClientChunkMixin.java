/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionSnapshotRelease;
import com.nvidia.rtxremix.minecraft.terrain.capture.RenderRegionStoragePool;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionCopy;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Makes a region cache's section snapshot copy its block states into pooled
 * storage, and takes the cache's own hold on it. The copy is made in the
 * {@code computeIfAbsent} lambda of {@code RenderRegionCache.getSectionDataCopy}.
 */
@Mixin(RenderRegionCache.class)
public abstract class ClientChunkMixin {

    @WrapOperation(
            method = "lambda$getSectionDataCopy$0",
            at = @At(value = "NEW", target = "(Lnet/minecraft/world/level/chunk/LevelChunk;I)Lnet/minecraft/client/renderer/chunk/SectionCopy;")
    )
    private static SectionCopy rtxremix$snapshotWithPooledStorage(
            LevelChunk chunk, int sectionIndex, Operation<SectionCopy> original) {
        RenderRegionStoragePool.beginCopying();
        SectionCopy snapshot;
        try {
            snapshot = original.call(chunk, sectionIndex);
        } finally {
            RenderRegionStoragePool.endCopying();
        }
        ((RegionSnapshotRelease) snapshot).rtxremix$retain();
        return snapshot;
    }
}

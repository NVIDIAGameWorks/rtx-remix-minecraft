/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.capture.RegionSnapshotRelease;
import net.minecraft.client.render.chunk.BlockBufferAllocatorStorage;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.render.chunk.ChunkRendererRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Releases a rebuild task's region snapshot once the section build that read it has
 * returned. The worker running {@code run} is the region's only reader: {@code cancel} on the
 * render thread merely drops the task's reference, so the region captured at the start of
 * {@code run} is owned by this worker whichever branch vanilla takes, including the early
 * returns that never read it. A task cancelled before it ever runs leaves its region to the
 * collector, which only forgoes the reuse.
 */
@Mixin(ChunkBuilder.BuiltChunk.RebuildTask.class)
public abstract class RebuildTaskMixin {

    @Shadow
    protected ChunkRendererRegion region;

    private ChunkRendererRegion rtxremix$consumed;

    @Inject(method = "run", at = @At("HEAD"))
    private void rtxremix$captureRegion(BlockBufferAllocatorStorage buffers, CallbackInfoReturnable<CompletableFuture<ChunkBuilder.Result>> cir) {
        rtxremix$consumed = region;
    }

    @Inject(method = "run", at = @At("RETURN"))
    private void rtxremix$releaseRegion(BlockBufferAllocatorStorage buffers, CallbackInfoReturnable<CompletableFuture<ChunkBuilder.Result>> cir) {
        ChunkRendererRegion consumed = rtxremix$consumed;
        rtxremix$consumed = null;
        if (consumed != null) {
            ((RegionSnapshotRelease) consumed).rtxremix$release();
        }
    }
}

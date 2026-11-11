/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.neoforge.mixin;

import com.nvidia.rtxremix.minecraft.terrain.capture.RegionSnapshotOwner;
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retains pooled snapshots for NeoForge's model-data-aware render region. */
@Mixin(RenderChunkRegion.class)
abstract class ChunkRendererRegionNeoForgeMixin {
    @Inject(
            method = "<init>(Lnet/minecraft/world/level/Level;II"
                    + "[Lnet/minecraft/client/renderer/chunk/RenderChunk;"
                    + "Lit/unimi/dsi/fastutil/longs/Long2ObjectFunction;)V",
            at = @At("RETURN"))
    private void rtxremix$retainSnapshots(
            Level level,
            int chunkX,
            int chunkZ,
            RenderChunk[] chunks,
            Long2ObjectFunction<ModelData> modelDataSnapshot,
            CallbackInfo ci) {
        ((RegionSnapshotOwner) this).rtxremix$retainSnapshots();
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.WorldRendererTerrainAccess;
import com.nvidia.rtxremix.minecraft.terrain.SkippedSectionRebuilds;
import com.nvidia.rtxremix.minecraft.terrain.LocalChunkBatchPacing;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Section rebuild bookkeeping raised by the client chunk manager while RTX capture is on.
 */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkManagerMixin {

    /**
     * Drops section rebuilds requested only because the client light engine changed a
     * section's light.
     *
     * <p>Vanilla re-meshes such a section so its baked lightmap follows. While RTX is enabled
     * the section builder captures every section for Remix and reports its blocks invisible
     * to vanilla, so vanilla's section buffers are empty and Remix terrain carries no baked
     * light; the rebuild would only recapture identical geometry, publish an identical page,
     * and retire the old one. The gate is the capture gate SectionBuilderMixin latches, not
     * whether Remix is drawing the world: vanilla has no terrain geometry to relight whenever
     * capture is on, including with world rendering toggled off or the renderer
     * uninitialized. Toggling RTX off reloads the world renderer, which rebuilds every
     * section with vanilla light again.
     *
     * <p>While the client light storage publishes its live map ({@link LightStorageMixin})
     * the notifications never reach this hook: that mixin cancels notifyChanges and counts
     * the pass's pending notifications itself. This hook covers the kill-switch path and the
     * passes that publish a real copy.
     */
    @Inject(method = "onLightUpdate", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipLightOnlyRebuild(LightLayer type, SectionPos pos, CallbackInfo ci) {
        if (RtxRemixMod.isRtxEnabled()) {
            SkippedSectionRebuilds.recordLightOnly();
            ci.cancel();
        }
    }

    /**
     * A same-position reload replaces the WorldChunk's contents in place and fires no
     * CHUNK_UNLOAD, so the neighbour slots whose region snapshot holds the old content must
     * forget the column before the reload's re-flags arrive, or they would be dropped as
     * already captured. Slot reuse by another position and unload() are covered by the Fabric
     * CHUNK_UNLOAD event.
     */
    @Inject(method = "replaceWithPacketData", at = @At("HEAD"))
    private void rtxremix$invalidateNeighboursOnInPlaceReload(
            int x,
            int z,
            FriendlyByteBuf buf,
            CompoundTag nbt,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer,
            CallbackInfoReturnable<LevelChunk> cir) {
        if (!RtxRemixMod.isRtxEnabled()) {
            return;
        }
        ClientChunkCache self = (ClientChunkCache) (Object) this;
        if (self.getChunk(x, z, ChunkStatus.FULL, false) == null) {
            return;
        }
        if (Minecraft.getInstance().levelRenderer instanceof WorldRendererTerrainAccess access) {
            access.rtxremix$onNeighbourColumnInvalidated(x, z);
        }
    }

    // loadChunkFromPacket runs only on the render thread, so one field carries the
    // start time from the head to the return of each apply.
    private static long rtxremix$applyStartNanos;

    /**
     * Times each chunk apply for {@link LocalChunkBatchPacing}, which sizes the
     * integrated server's batches from what a chunk costs this thread.
     */
    @Inject(method = "replaceWithPacketData", at = @At("HEAD"))
    private void rtxremix$beginApplyTiming(
            int x,
            int z,
            FriendlyByteBuf buf,
            CompoundTag nbt,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer,
            CallbackInfoReturnable<LevelChunk> cir) {
        if (LocalChunkBatchPacing.ENABLED) {
            rtxremix$applyStartNanos = System.nanoTime();
        }
    }

    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void rtxremix$endApplyTiming(
            int x,
            int z,
            FriendlyByteBuf buf,
            CompoundTag nbt,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer,
            CallbackInfoReturnable<LevelChunk> cir) {
        if (LocalChunkBatchPacing.ENABLED) {
            LocalChunkBatchPacing.recordApplyNanos(System.nanoTime() - rtxremix$applyStartNanos);
        }
    }

    // unload runs only on the render thread, so one pair of fields carries the start
    // time and whether the column was held from the head to the return of each unload.
    private static long rtxremix$unloadStartNanos;
    private static boolean rtxremix$unloadIsReal;

    /**
     * Times each unload of a column the client holds for {@link LocalChunkBatchPacing},
     * which paces the integrated server's unload packets from what one costs this
     * thread. An unload of a column the client does not hold (already replaced, or
     * outside its ring) does nothing and is not sampled.
     */
    @Inject(method = "drop", at = @At("HEAD"))
    private void rtxremix$beginUnloadTiming(ChunkPos pos, CallbackInfo ci) {
        if (!LocalChunkBatchPacing.UNLOAD_ENABLED) {
            return;
        }
        ClientChunkCache self = (ClientChunkCache) (Object) this;
        rtxremix$unloadIsReal = self.getChunk(pos.x, pos.z, ChunkStatus.FULL, false) != null;
        rtxremix$unloadStartNanos = System.nanoTime();
    }

    @Inject(method = "drop", at = @At("RETURN"))
    private void rtxremix$endUnloadTiming(ChunkPos pos, CallbackInfo ci) {
        if (LocalChunkBatchPacing.UNLOAD_ENABLED && rtxremix$unloadIsReal) {
            LocalChunkBatchPacing.recordUnloadNanos(System.nanoTime() - rtxremix$unloadStartNanos);
        }
    }
}

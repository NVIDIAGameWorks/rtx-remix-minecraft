/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.PacedUnloadQueue;
import com.nvidia.rtxremix.minecraft.terrain.LocalChunkBatchPacing;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.LongConsumer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Makes the integrated server deliver chunks to the local client in paced,
 * nearest-first batches instead of everything pending at once, and its chunk
 * unloads a budgeted few per tick instead of a whole trailing row.
 *
 * <p>Only the local branch of {@code makeBatch} is replaced; remote connections
 * keep vanilla's client-driven batch size. The batch is sized by
 * {@link LocalChunkBatchPacing} from the render thread's measured apply cost.
 *
 * <p>Holding a chunk pending for a few ticks is the state vanilla keeps every
 * remote player's chunks in: {@code ServerChunkLoadingManager.isTracked} treats a
 * chunk still in the sender's next batch as untracked, so block updates for it
 * are not sent to that player ({@code ChunkHolder.flushUpdates} skips them; the
 * payload serialized at send time carries the result) and entity tracking for it
 * begins at the next tracked-status re-evaluation after the send.
 *
 * <p>Vanilla's {@code sendChunkBatches} credits at most the client's requested
 * batch size per tick and skips a tick once a larger batch has driven the credit
 * negative. The paced batch sets its own rate, so it leaves exactly one credit
 * behind after vanilla debits it, and the sender runs every tick.
 *
 * <p>Unloads vanilla would write the moment a column leaves the player's chunk
 * filter are queued instead and drained ahead of each tick's chunk batch. Re-adding a
 * column cancels its queued unload: the data about to follow reloads the client's copy
 * in place. Until that data is written the copy is remembered as held, because vanilla
 * drops a pending column that leaves the filter again without a packet, on the
 * assumption that the client never received it; for a held column the unload is queued
 * again at that moment. A queued column whose chebyshev distance from the player has
 * reached the client ring's radius is sent that tick whatever the budget: the client
 * ignores an unload for a column beyond its ring and would keep the stale column until
 * its slot is reused. Moving the unloads from mid-tick to just before the batch reorders
 * them against the tick's other packets, which is safe: after the untrack the column is
 * already untracked for block updates, and an entity destroy arriving before its
 * column's unload only stops ticking an entity in a section that is emptied a moment
 * later. A removed player (dimension change, respawn, disconnect) is sent nothing by
 * vanilla and the sender outlives the world, so the queue and the held copies are
 * cleared at that point; on a same-dimension respawn, where the client keeps its world,
 * the unloads still queued join vanilla's own class of stale columns from the old filter.
 *
 * <p>The gate is {@code RtxRemixMod.isRtxEnabled()} alone, the same master flag
 * {@code SectionBuilderMixin} and {@code ClientLightSnapshotPolicy} use, read here
 * from the integrated-server thread (the field is volatile for that). It does not
 * consult the renderer: chunk deserialization is render-thread work whether or not
 * Remix came up. Toggling RTX off mid-backlog hands whatever is still pending to
 * vanilla's single batch on the next server tick, alongside the world-renderer
 * reload the toggle already schedules, and sends every queued unload the same tick.
 */
@Mixin(PlayerChunkSender.class)
public abstract class ChunkDataSenderMixin {
    @Shadow
    @Final
    private LongSet pendingChunks;

    @Shadow
    @Final
    private boolean memoryConnection;

    @Shadow
    private float batchQuota;

    private final PacedUnloadQueue rtxremix$unloads = new PacedUnloadQueue();

    @Inject(method = "collectChunksToSend", at = @At("HEAD"), cancellable = true)
    private void rtxremix$paceLocalBatch(ChunkMap chunkLoadingManager, ChunkPos playerPos,
                                         CallbackInfoReturnable<List<LevelChunk>> cir) {
        if (!memoryConnection || !LocalChunkBatchPacing.ENABLED || !RtxRemixMod.isRtxEnabled()) {
            return;
        }
        int batch = LocalChunkBatchPacing.batchSize(pendingChunks.size());
        List<LevelChunk> selected = LocalChunkBatchPacing.takeNearest(pendingChunks, batch,
                chunkLoadingManager::getChunkToSend,
                chunk -> chunk.getPos().toLong(),
                chunk -> playerPos.distanceSquared(chunk.getPos()));
        batchQuota = selected.size() + 1.0f;
        LocalChunkBatchPacing.recordBatch(selected.size(), pendingChunks.size());
        cir.setReturnValue(selected);
    }

    /**
     * Runs before vanilla decides whether the column was still pending. A pending column
     * is dropped without a packet, so a copy the client holds from a cancelled unload owes
     * its unload now; vanilla's own condition applies, a dead player is sent nothing.
     */
    @Inject(method = "dropChunk", at = @At("HEAD"))
    private void rtxremix$rearmHeldCopy(ServerPlayer player, ChunkPos pos, CallbackInfo ci) {
        if (player.isRemoved()) {
            rtxremix$unloads.clear();
            return;
        }
        long key = pos.toLong();
        rtxremix$unloads.dropPending(key, pendingChunks.contains(key) && player.isAlive());
    }

    /**
     * Queues the unload instead of writing it. Only the send is wrapped, so vanilla's
     * checks stay in front of it: a column still pending owes no unload of its own, and a
     * dead or removed player receives none.
     */
    @WrapOperation(
            method = "dropChunk",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void rtxremix$queueLocalUnload(ServerGamePacketListenerImpl handler, Packet<?> packet, Operation<Void> original,
                                           ServerPlayer player, ChunkPos pos) {
        if (!memoryConnection || !LocalChunkBatchPacing.UNLOAD_ENABLED || !RtxRemixMod.isRtxEnabled()) {
            original.call(handler, packet);
            return;
        }
        rtxremix$unloads.enqueue(pos.toLong());
    }

    // Ungated: the queue may still hold keys from before an RTX toggle.
    @Inject(method = "markChunkPendingToSend", at = @At("HEAD"))
    private void rtxremix$cancelQueuedUnload(LevelChunk chunk, CallbackInfo ci) {
        rtxremix$unloads.cancelForResend(chunk.getPos().toLong());
    }

    /** Every chunk-data send, paced or vanilla, passes here: the client's copy is current again. */
    @WrapOperation(
            method = "sendNextChunks",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/network/PlayerChunkSender;sendChunk(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;)V"))
    private void rtxremix$markSent(ServerGamePacketListenerImpl handler, ServerLevel world, LevelChunk chunk,
                                   Operation<Void> original) {
        rtxremix$unloads.markSent(chunk.getPos().toLong());
        original.call(handler, world, chunk);
    }

    /**
     * Drains queued unloads ahead of the tick's chunk batch. At the head, so vanilla's
     * early returns on unacknowledged batches and send credit never hold unloads back.
     */
    @Inject(method = "sendNextChunks", at = @At("HEAD"))
    private void rtxremix$drainQueuedUnloads(ServerPlayer player, CallbackInfo ci) {
        if (!memoryConnection) {
            return;
        }
        if (rtxremix$unloads.size() == 0) {
            // The gauge must follow cancellations that emptied the queue since the last drain.
            LocalChunkBatchPacing.recordUnloadBatch(0, 0);
            return;
        }
        ServerGamePacketListenerImpl handler = player.connection;
        LongConsumer send = key -> handler.send(new ClientboundForgetLevelChunkPacket(new ChunkPos(key)));
        if (!RtxRemixMod.isRtxEnabled()) {
            // Counted so the totals stay right; the terrain counters that read them are
            // off with capture, so the flush shows in the first frame after it returns.
            LocalChunkBatchPacing.recordUnloadBatch(rtxremix$unloads.drainAll(send), 0);
            return;
        }
        ChunkPos centre = player.chunkPosition();
        int ringRadius = LocalChunkBatchPacing.clientRingRadius(player.server.getPlayerList().getViewDistance());
        int sent = rtxremix$unloads.drain(
                LocalChunkBatchPacing.unloadBatchSize(rtxremix$unloads.size()),
                key -> centre.getChessboardDistance(ChunkPos.getX(key), ChunkPos.getZ(key)) >= ringRadius,
                send);
        LocalChunkBatchPacing.recordUnloadBatch(sent, rtxremix$unloads.size());
    }
}

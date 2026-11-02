/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.visibility.VisibilityGraphBuffers;
import com.nvidia.rtxremix.minecraft.terrain.visibility.TimeSlicedWorkQueue;
import com.nvidia.rtxremix.minecraft.terrain.visibility.RenderableChunksBuffers;
import com.nvidia.rtxremix.minecraft.terrain.visibility.FarChunkProbe;
import com.nvidia.rtxremix.minecraft.terrain.visibility.ChunkInfoPool;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.google.common.collect.Queues;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.render.BuiltChunkStorage;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.ChunkRenderingDataPreparer;
import net.minecraft.client.render.ChunkRenderingDataPreparer.ChunkInfo;
import net.minecraft.client.render.ChunkRenderingDataPreparer.ChunkInfoList;
import net.minecraft.client.render.ChunkRenderingDataPreparer.RenderableChunks;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.render.chunk.Octree;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.thread.NameableExecutor;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.Util;
import net.minecraft.world.HeightLimitView;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Runs the allocation-free visibility walk on a low-priority executor and time-slices
 * incremental propagation. Vanilla's octree owns frustum traversal, ordering and the
 * nearby-section list; both the vanilla and allocation-free walks populate that tree.
 */
@Mixin(ChunkRenderingDataPreparer.class)
public abstract class ChunkRenderingDataPreparerMixin {
    /** Vanilla's neighbour order; the walk must visit directions in this order. */
    @Shadow
    @Final
    private static Direction[] DIRECTIONS;

    /** Ray-march step of vanilla's far-chunk probe, {@code ceil(sqrt(3) * 16)} blocks. */
    @Shadow
    @Final
    private static double CHUNK_INNER_DIAGONAL_LENGTH;

    @Shadow
    private BuiltChunkStorage builtChunkStorage;

    /** Vanilla's neighbour lookup: null beyond the view distance or the world's height. */
    @Shadow
    protected abstract ChunkBuilder.BuiltChunk getRenderedChunk(
            long cameraSectionPos, ChunkBuilder.BuiltChunk chunk, Direction direction);

    /** Vanilla's horizontal view-distance test between two section origins. */
    @Shadow
    protected abstract boolean isWithinViewDistance(long cameraSectionPos, long sectionPos);

    /**
     * {@code -Drtxremix.vanillaVisibilityWalk=true} leaves the visibility walk to vanilla with
     * RTX on. The port below must produce vanilla's visible set exactly; this is how to check
     * that on a live world, together with {@code -Drtxremix.logVisibleSet=true}, which logs
     * the size and a position hash of every full walk's result whenever it changes. Run the
     * same camera with and without the switch and compare the last line each logged.
     */
    private static final boolean rtxremix$VANILLA_WALK = Boolean.getBoolean("rtxremix.vanillaVisibilityWalk");
    private static final boolean rtxremix$LOG_VISIBLE_SET = Boolean.getBoolean("rtxremix.logVisibleSet");
    private static long rtxremix$lastVisibleSetSignature;
    private static final NameableExecutor rtxremix$visibilityExecutor =
            new NameableExecutor(Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "RTX Remix chunk visibility");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
                return thread;
            }));

    /**
     * Per-frame budget for vanilla's incremental visibility propagation. After a chunk-load
     * burst the propagation can seed a walk of thousands of sections; unbudgeted, one such
     * walk cost 25 ms on the render thread. The poll floor keeps the fringe advancing even
     * when the frame is already late.
     */
    private static final long rtxremix$PROPAGATION_BUDGET_NANOS = 1_500_000L;
    private static final int rtxremix$PROPAGATION_MINIMUM_POLLS = 64;

    /**
     * Seeds and partially walked sections carried across frames. Its entries belong to the
     * visibility state that produced them, so a publish or a storage swap empties it.
     */
    private final TimeSlicedWorkQueue<Object> rtxremix$propagationQueue = new TimeSlicedWorkQueue<>(
            rtxremix$PROPAGATION_BUDGET_NANOS, rtxremix$PROPAGATION_MINIMUM_POLLS, System::nanoTime);
    /** Seed queue of the state whose entries {@link #rtxremix$propagationQueue} holds. */
    private BlockingQueue<?> rtxremix$propagationSeeds;

    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$storageScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$frustumScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$prepareScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$graphScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$seedScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$walkScope = new ThreadLocal<>();

    @Inject(method = "setStorage", at = @At("HEAD"))
    private void rtxremix$storageHead(BuiltChunkStorage storage, CallbackInfo ci) {
        rtxremix$open(rtxremix$storageScope, "ChunkVisibility.setStorage");
    }

    @Inject(method = "setStorage", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/render/ChunkRenderingDataPreparer;builtChunkStorage:Lnet/minecraft/client/render/BuiltChunkStorage;",
            opcode = 181, shift = At.Shift.AFTER))
    private void rtxremix$configureBuffers(BuiltChunkStorage storage, CallbackInfo ci) {
        // Vanilla has joined the old full walk before assigning the new storage.
        VisibilityGraphBuffers.configure(storage != null ? storage.chunks.length : 0);
    }

    @Inject(method = "setStorage", at = @At("RETURN"))
    private void rtxremix$storageReturn(BuiltChunkStorage storage, CallbackInfo ci) {
        rtxremix$propagationQueue.clear();
        rtxremix$propagationSeeds = null;
        rtxremix$close(rtxremix$storageScope);
    }

    @Inject(method = "collectChunks", at = @At("HEAD"))
    private void rtxremix$frustumHead(Frustum frustum, List<ChunkBuilder.BuiltChunk> builtChunks,
                                       List<ChunkBuilder.BuiltChunk> nearbyChunks, CallbackInfo ci) {
        rtxremix$open(rtxremix$frustumScope, "ChunkVisibility.applyFrustum", rtxremix$sizeOf(builtChunks));
    }

    @Inject(method = "collectChunks", at = @At("RETURN"))
    private void rtxremix$frustumReturn(Frustum frustum, List<ChunkBuilder.BuiltChunk> builtChunks,
                                         List<ChunkBuilder.BuiltChunk> nearbyChunks, CallbackInfo ci) {
        rtxremix$captureCount(rtxremix$frustumScope, rtxremix$sizeOf(builtChunks));
        rtxremix$close(rtxremix$frustumScope);
    }

    @Inject(method = "updateSectionOcclusionGraph", at = @At("HEAD"))
    private void rtxremix$prepareHead(
            boolean spectator,
            Camera camera,
            Frustum frustum,
            List<ChunkBuilder.BuiltChunk> builtChunks,
            LongOpenHashSet emptySections,
            CallbackInfo ci) {
        rtxremix$open(rtxremix$prepareScope, "ChunkVisibility.prepare", rtxremix$sizeOf(builtChunks));
    }

    @Inject(method = "updateSectionOcclusionGraph", at = @At("RETURN"))
    private void rtxremix$prepareReturn(
            boolean spectator,
            Camera camera,
            Frustum frustum,
            List<ChunkBuilder.BuiltChunk> builtChunks,
            LongOpenHashSet emptySections,
            CallbackInfo ci) {
        rtxremix$close(rtxremix$prepareScope);
    }

    @Redirect(
            method = "updateTerrain",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/Util;getMainWorkerExecutor()Lnet/minecraft/util/thread/NameableExecutor;"
            ))
    private NameableExecutor rtxremix$visibilityExecutor() {
        return RtxRemixMod.isRtxEnabled()
                ? rtxremix$visibilityExecutor
                : Util.getMainWorkerExecutor();
    }

    /**
     * A full walk is about to construct its state: hand it the other buffer set. Runs for
     * vanilla's walk too, since the reused info list and map belong to that state.
     */
    @Inject(method = "method_52820", at = @At("HEAD"))
    private void rtxremix$beginWalk(Camera camera, Vec3d cameraPos, boolean spectator,
                                   LongOpenHashSet emptySections, CallbackInfo ci) {
        VisibilityGraphBuffers.rotate();
    }

    @Redirect(method = "method_52825", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/chunk/Octree;add(Lnet/minecraft/client/render/chunk/ChunkBuilder$BuiltChunk;)Z"))
    private boolean rtxremix$recordVanillaVisit(Octree octree, ChunkBuilder.BuiltChunk chunk,
            RenderableChunks chunks, Vec3d cameraPos, Queue<ChunkInfo> queue, boolean culling,
            Consumer<ChunkBuilder.BuiltChunk> consumer, LongOpenHashSet emptySections) {
        return rtxremix$visit(chunks, chunk);
    }

    private boolean rtxremix$visit(RenderableChunks chunks, ChunkBuilder.BuiltChunk chunk) {
        if (!chunks.octree.add(chunk)) {
            return false;
        }
        if (rtxremix$LOG_VISIBLE_SET) {
            ((RenderableChunksBuffers) chunks).rtxremix$recordVisibleSection(chunk.getSectionPos());
        }
        return true;
    }

    @Inject(method = "method_52835", at = @At("HEAD"))
    private void rtxremix$graphHead(
            boolean spectator,
            Frustum frustum,
            List<ChunkBuilder.BuiltChunk> builtChunks,
            Vec3d cameraPos,
            LongOpenHashSet emptySections,
            CallbackInfo ci) {
        rtxremix$open(rtxremix$graphScope, "ChunkVisibility.rebuildGraph", rtxremix$sizeOf(builtChunks));
    }

    @Inject(method = "method_52835", at = @At("RETURN"))
    private void rtxremix$graphReturn(
            boolean spectator,
            Frustum frustum,
            List<ChunkBuilder.BuiltChunk> builtChunks,
            Vec3d cameraPos,
            LongOpenHashSet emptySections,
            CallbackInfo ci) {
        rtxremix$budgetHit(rtxremix$graphScope, rtxremix$propagationQueue.budgetHit());
        rtxremix$close(rtxremix$graphScope);
    }

    // Vanilla only propagates when new seeds arrived. Sections left over from a budgeted
    // slice must keep draining even on frames with no new seeds.
    @Redirect(
            method = "method_52835",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/BlockingQueue;isEmpty()Z",
                    ordinal = 0
            ))
    private boolean rtxremix$noPropagationWork(BlockingQueue<?> seeds) {
        if (!RtxRemixMod.isRtxEnabled()) {
            return seeds.isEmpty();
        }
        rtxremix$dropForeignPropagationWork(seeds);
        return seeds.isEmpty() && !rtxremix$propagationQueue.hasPendingWork();
    }

    // Vanilla drains the seeds into a fresh deque and walks it to completion. Handing it the
    // persistent sliced queue instead bounds the walk to this frame's budget and resumes it
    // from the same entries next frame.
    @Redirect(
            method = "method_52835",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/Queues;newArrayDeque()Ljava/util/ArrayDeque;",
                    remap = false
            ))
    private ArrayDeque<Object> rtxremix$propagationWorkQueue() {
        if (!RtxRemixMod.isRtxEnabled()) {
            rtxremix$propagationQueue.clear();
            return Queues.newArrayDeque();
        }
        rtxremix$propagationQueue.beginSlice();
        return rtxremix$propagationQueue;
    }

    /**
     * Leftover entries index into the state that produced them; a publish invalidates them.
     * Each state owns its seed queue, so the queue vanilla is about to drain identifies the
     * state it loaded for this frame. Re-reading the published state here instead could
     * observe a newer one and leave this frame's leftovers tagged as that state's own.
     */
    private void rtxremix$dropForeignPropagationWork(BlockingQueue<?> seeds) {
        if (rtxremix$propagationSeeds != seeds) {
            rtxremix$propagationQueue.clear();
            rtxremix$propagationSeeds = seeds;
        }
    }

    @Inject(method = "method_52821", at = @At("HEAD"))
    private void rtxremix$seedHead(
            Camera camera,
            Queue<?> queue,
            CallbackInfo ci) {
        rtxremix$open(rtxremix$seedScope, "ChunkVisibility.seedQueue", rtxremix$sizeOf(queue));
    }

    @Inject(method = "method_52821", at = @At("RETURN"))
    private void rtxremix$seedReturn(
            Camera camera,
            Queue<?> queue,
            CallbackInfo ci) {
        rtxremix$close(rtxremix$seedScope);
    }

    /**
     * Vanilla's walk allocates on every step of its far-chunk probe: for each neighbour more
     * than 60 blocks from the camera it ray-marches from the neighbour's near corner back
     * toward the camera in {@link #CHUNK_INNER_DIAGONAL_LENGTH}-block steps, creating a Vec3d for the remaining
     * offset, a Vec3d for the advanced point and a BlockPos for the lookup each time. At render
     * distance 32 that is most of the garbage the collector cleans up while the game runs, and
     * the collector's concurrent work is what stretches frames. This port keeps vanilla's
     * traversal, arithmetic and evaluation order and holds the ray in doubles and one mutable
     * position, so the visible set is identical and the walk allocates only graph nodes.
     */
    @Inject(method = "method_52825", at = @At("HEAD"), cancellable = true)
    private void rtxremix$walkVisibleGraph(
            RenderableChunks renderableChunks,
            Vec3d cameraPos,
            Queue<ChunkInfo> queue,
            boolean culling,
            Consumer<ChunkBuilder.BuiltChunk> chunkConsumer,
            LongOpenHashSet emptySections,
            CallbackInfo ci) {
        rtxremix$open(rtxremix$walkScope, "ChunkVisibility.walkVisibleGraph", rtxremix$sizeOf(queue));
        if (!RtxRemixMod.isRtxEnabled() || rtxremix$VANILLA_WALK) {
            return;
        }
        try {
            rtxremix$walk(renderableChunks, cameraPos, queue, culling, chunkConsumer, emptySections);
        } finally {
            rtxremix$close(rtxremix$walkScope);
        }
        if (rtxremix$LOG_VISIBLE_SET) rtxremix$logVisibleSet("port", renderableChunks);
        ci.cancel();
    }

    @Inject(method = "method_52825", at = @At("RETURN"))
    private void rtxremix$walkReturn(
            RenderableChunks renderableChunks,
            Vec3d cameraPos,
            Queue<ChunkInfo> queue,
            boolean culling,
            Consumer<ChunkBuilder.BuiltChunk> chunkConsumer,
            LongOpenHashSet emptySections,
            CallbackInfo ci) {
        rtxremix$close(rtxremix$walkScope);
        if (rtxremix$LOG_VISIBLE_SET) rtxremix$logVisibleSet("vanilla", renderableChunks);
    }

    /** Order-independent signature of the section positions admitted into this state's octree. */
    private static void rtxremix$logVisibleSet(String walk, RenderableChunks renderableChunks) {
        RenderableChunksBuffers buffers = (RenderableChunksBuffers) renderableChunks;
        long hash = buffers.rtxremix$visibleSectionHash();
        int count = buffers.rtxremix$visibleSectionCount();
        long signature = hash ^ ((long) count << 48);
        if (signature != rtxremix$lastVisibleSetSignature) {
            rtxremix$lastVisibleSetSignature = signature;
            RtxRemixMod.LOGGER.info("Visible octree admissions ({} walk): {} sections, hash {}",
                    walk, count, Long.toHexString(hash));
        }
    }

    private void rtxremix$walk(
            RenderableChunks renderableChunks,
            Vec3d cameraPos,
            Queue<ChunkInfo> queue,
            boolean culling,
            Consumer<ChunkBuilder.BuiltChunk> chunkConsumer,
            LongOpenHashSet emptySections) {
        BuiltChunkStorage storage = builtChunkStorage;
        ChunkInfoList infos = renderableChunks.infoList;
        Long2ObjectMap<List<ChunkBuilder.BuiltChunk>> unbuiltByChunk = renderableChunks.field_45628;
        ChunkInfoPool infoPool = ((RenderableChunksBuffers) renderableChunks).rtxremix$infoPool();
        Direction[] directions = DIRECTIONS;
        HeightLimitView world = storage.getWorld();
        BlockPos cameraSectionOrigin = new BlockPos(
                MathHelper.floor(cameraPos.x / 16.0) * 16,
                MathHelper.floor(cameraPos.y / 16.0) * 16,
                MathHelper.floor(cameraPos.z / 16.0) * 16);
        long cameraSectionPos = ChunkSectionPos.from(cameraSectionOrigin).asLong();
        int cameraSectionCenterX = cameraSectionOrigin.getX() + 8;
        int cameraSectionCenterY = cameraSectionOrigin.getY() + 8;
        int cameraSectionCenterZ = cameraSectionOrigin.getZ() + 8;
        // One position serves every probed step of every probe in the walk, where vanilla
        // allocated a fresh BlockPos per step. Sound while getRenderedChunk only derives
        // array indices from the position it is handed; a lookup that kept or keyed by it
        // would alias every probe to the last step.
        BlockPos.Mutable probe = new BlockPos.Mutable();
        FarChunkProbe.GraphLookup graph = (x, y, z) -> {
            ChunkBuilder.BuiltChunk passed = storage.getRenderedChunk(probe.set(x, y, z));
            return passed != null && infos.getInfo(passed) != null;
        };

        while (!queue.isEmpty()) {
            ChunkInfo chunkInfo = queue.poll();
            ChunkBuilder.BuiltChunk chunk = chunkInfo.chunk;
            if (!emptySections.contains(chunk.getSectionPos())) {
                if (rtxremix$visit(renderableChunks, chunk)) {
                    chunkConsumer.accept(chunk);
                }
            } else {
                chunk.data.compareAndSet(ChunkBuilder.ChunkData.UNPROCESSED, ChunkBuilder.ChunkData.EMPTY);
            }
            BlockPos origin = chunk.getOrigin();
            boolean far = Math.abs(origin.getX() - cameraSectionOrigin.getX()) > 60
                    || Math.abs(origin.getY() - cameraSectionOrigin.getY()) > 60
                    || Math.abs(origin.getZ() - cameraSectionOrigin.getZ()) > 60;

            for (Direction direction : directions) {
                ChunkBuilder.BuiltChunk neighbour = getRenderedChunk(cameraSectionPos, chunk, direction);
                if (neighbour == null) {
                    continue;
                }
                if (culling && chunkInfo.canCull(direction.getOpposite())) {
                    continue;
                }
                if (culling && chunkInfo.hasAnyDirection()) {
                    ChunkBuilder.ChunkData data = chunk.getData();
                    boolean visibleThrough = false;
                    for (int i = 0; i < directions.length; i++) {
                        if (chunkInfo.hasDirection(i)
                                && data.isVisibleThrough(directions[i].getOpposite(), direction)) {
                            visibleThrough = true;
                            break;
                        }
                    }
                    if (!visibleThrough) {
                        continue;
                    }
                }
                if (culling && far) {
                    BlockPos neighbourOrigin = neighbour.getOrigin();
                    boolean reaches = FarChunkProbe.reachesCamera(
                            neighbourOrigin.getX(), neighbourOrigin.getY(), neighbourOrigin.getZ(),
                            direction.getAxis(),
                            cameraSectionCenterX, cameraSectionCenterY, cameraSectionCenterZ,
                            cameraPos.x, cameraPos.y, cameraPos.z,
                            CHUNK_INNER_DIAGONAL_LENGTH,
                            world.getTopYInclusive(), world.getBottomY(),
                            graph);
                    if (!reaches) {
                        continue;
                    }
                }

                ChunkInfo neighbourInfo = infos.getInfo(neighbour);
                if (neighbourInfo != null) {
                    neighbourInfo.addDirection(direction);
                    continue;
                }
                ChunkInfo discovered = infoPool.acquire(neighbour, direction, chunkInfo.propagationLevel + 1);
                discovered.updateCullingState(chunkInfo.cullingState, direction);
                if (neighbour.shouldBuild()) {
                    queue.add(discovered);
                    infos.setInfo(neighbour, discovered);
                } else if (isWithinViewDistance(cameraSectionPos, neighbour.getSectionPos())) {
                    infos.setInfo(neighbour, discovered);
                    long chunkKey = ChunkPos.toLong(neighbour.getOrigin());
                    List<ChunkBuilder.BuiltChunk> unbuilt = unbuiltByChunk.get(chunkKey);
                    if (unbuilt == null) {
                        unbuilt = new ArrayList<>();
                        unbuiltByChunk.put(chunkKey, unbuilt);
                    }
                    unbuilt.add(neighbour);
                }
            }
        }
    }

    private static int rtxremix$sizeOf(List<?> values) {
        return values == null ? -1 : values.size();
    }

    private static int rtxremix$sizeOf(Queue<?> values) {
        return values == null ? -1 : values.size();
    }

    private static void rtxremix$open(ThreadLocal<ArrayDeque<ProfileScope>> holder, String phaseName) {
        rtxremix$open(holder, phaseName, -1);
    }

    private static void rtxremix$open(ThreadLocal<ArrayDeque<ProfileScope>> holder, String phaseName, int captureCount) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            holder.set(stack);
        }
        ProfileScope scope = ProfileScope.begin(phaseName);
        if (captureCount >= 0) {
            scope.captureCount(captureCount);
        }
        stack.push(scope);
    }

    private static void rtxremix$captureCount(ThreadLocal<ArrayDeque<ProfileScope>> holder, int captureCount) {
        if (!ProfileScope.isDeepProfilingEnabled() || captureCount < 0) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            stack.peek().captureCount(captureCount);
        }
    }

    private static void rtxremix$budgetHit(ThreadLocal<ArrayDeque<ProfileScope>> holder, boolean budgetHit) {
        if (!ProfileScope.isDeepProfilingEnabled() || !budgetHit) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            stack.peek().budgetHit(true);
        }
    }

    private static void rtxremix$close(ThreadLocal<ArrayDeque<ProfileScope>> holder) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            ProfileScope scope = stack.pop();
            if (stack.isEmpty()) {
                holder.remove();
            }
            scope.close();
        } else if (stack != null) {
            holder.remove();
        }
    }

}

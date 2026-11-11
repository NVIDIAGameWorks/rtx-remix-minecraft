/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.visibility.VisibilityGraphBuffers;
import com.nvidia.rtxremix.minecraft.terrain.visibility.TimeSlicedWorkQueue;
import com.nvidia.rtxremix.minecraft.terrain.visibility.RenderableChunksBuffers;
import com.nvidia.rtxremix.minecraft.terrain.visibility.HierarchicalFrustumCuller;
import com.nvidia.rtxremix.minecraft.terrain.visibility.FarChunkProbe;
import com.nvidia.rtxremix.minecraft.terrain.visibility.ChunkSlotMoveLog;
import com.nvidia.rtxremix.minecraft.terrain.visibility.ChunkInfoPool;
import com.nvidia.rtxremix.minecraft.terrain.visibility.BuiltChunkFrustumIndexBuild;
import com.nvidia.rtxremix.minecraft.terrain.visibility.BuiltChunkFrustumIndex;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.google.common.collect.Queues;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import org.joml.FrustumIntersection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.SectionOcclusionGraph.GraphStorage;
import net.minecraft.client.renderer.SectionOcclusionGraph.Node;
import net.minecraft.client.renderer.SectionOcclusionGraph.SectionToNodeMap;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.phys.Vec3;

/**
 * Takes over vanilla's chunk visibility preparation on three fronts: it runs
 * the full visibility walk on a dedicated low-priority executor so long view
 * distances cannot starve terrain capture or the render thread, it builds and
 * publishes the coarse {@code BuiltChunkFrustumIndex} that bulk-accepts or
 * rejects section groups before any exact per-chunk frustum test, and it
 * time-slices vanilla's incremental propagation on the render thread through a
 * per-frame budget so a chunk-load burst cannot stall a frame.
 *
 * <p>It also labels each phase for the profiler.
 */
@Mixin(SectionOcclusionGraph.class)
public abstract class ChunkRenderingDataPreparerMixin {
    @Shadow
    @Final
    private AtomicReference<?> currentGraph;

    /** Vanilla's neighbour order; the walk must visit directions in this order. */
    @Shadow
    @Final
    private static Direction[] DIRECTIONS;

    /** Ray-march step of vanilla's far-chunk probe, {@code ceil(sqrt(3) * 16)} blocks. */
    @Shadow
    @Final
    private static double CEILED_SECTION_DIAGONAL;

    @Shadow
    private ViewArea viewArea;

    /** Vanilla's neighbour lookup: null beyond the view distance or the world's height. */
    @Shadow
    protected abstract SectionRenderDispatcher.RenderSection getRelativeFrom(
            BlockPos cameraSectionOrigin, SectionRenderDispatcher.RenderSection chunk, Direction direction);

    /** Vanilla's horizontal view-distance test between two section origins. */
    @Shadow
    protected abstract boolean isInViewDistance(BlockPos cameraSectionOrigin, BlockPos origin);

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
    private static final ExecutorService rtxremix$visibilityExecutor =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "RTX Remix chunk visibility");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
                return thread;
            });

    private static final ThreadLocal<BuiltChunkFrustumIndexBuild> rtxremix$frustumIndexBuild =
            new ThreadLocal<>();

    private volatile BuiltChunkFrustumIndex rtxremix$frustumIndex;

    /**
     * Cullers taken back from retired indices, for the next build to refill. Two suffice: one
     * behind the published index, one being filled. Offered by whichever side wins the retire
     * (see {@link BuiltChunkFrustumIndex#tryRetire}), polled by the visibility thread.
     */
    private final ConcurrentLinkedQueue<HierarchicalFrustumCuller<SectionRenderDispatcher.RenderSection>> rtxremix$cullerPool =
            new ConcurrentLinkedQueue<>();

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

    @Inject(method = "waitAndReset", at = @At("HEAD"))
    private void rtxremix$storageHead(ViewArea storage, CallbackInfo ci) {
        rtxremix$open(rtxremix$storageScope, "ChunkVisibility.setStorage");
        // The initial empty state vanilla builds below takes the current buffers; size them
        // for the new storage first.
        VisibilityGraphBuffers.configure(storage != null ? storage.sections.length : 0);
    }

    @Inject(method = "waitAndReset", at = @At("RETURN"))
    private void rtxremix$storageReturn(ViewArea storage, CallbackInfo ci) {
        BuiltChunkFrustumIndex retired = rtxremix$frustumIndex;
        rtxremix$frustumIndex = null;
        if (retired != null) {
            retired.tryRetire();
        }
        // Capacities were sized for the old storage; let the next builds grow fresh.
        rtxremix$cullerPool.clear();
        rtxremix$frustumIndexBuild.remove();
        rtxremix$propagationQueue.clear();
        rtxremix$propagationSeeds = null;
        rtxremix$close(rtxremix$storageScope);
    }

    @Inject(method = "addSectionsInFrustum", at = @At("HEAD"), cancellable = true)
    private void rtxremix$frustumHead(Frustum frustum, List<SectionRenderDispatcher.RenderSection> builtChunks, CallbackInfo ci) {
        rtxremix$open(rtxremix$frustumScope, "ChunkVisibility.applyFrustum", rtxremix$sizeOf(builtChunks));
        if (RtxRemixMod.isRtxEnabled() && rtxremix$applyFrustumIndex(frustum, builtChunks)) {
            rtxremix$captureCount(rtxremix$frustumScope, rtxremix$sizeOf(builtChunks));
            rtxremix$close(rtxremix$frustumScope);
            ci.cancel();
        }
    }

    @Inject(method = "addSectionsInFrustum", at = @At("RETURN"))
    private void rtxremix$frustumReturn(Frustum frustum, List<SectionRenderDispatcher.RenderSection> builtChunks, CallbackInfo ci) {
        rtxremix$captureCount(rtxremix$frustumScope, rtxremix$sizeOf(builtChunks));
        rtxremix$close(rtxremix$frustumScope);
    }

    @Inject(method = "update", at = @At("HEAD"))
    private void rtxremix$prepareHead(
            boolean spectator,
            Camera camera,
            Frustum frustum,
            List<SectionRenderDispatcher.RenderSection> builtChunks,
            CallbackInfo ci) {
        rtxremix$open(rtxremix$prepareScope, "ChunkVisibility.prepare", rtxremix$sizeOf(builtChunks));
    }

    @Inject(method = "update", at = @At("RETURN"))
    private void rtxremix$prepareReturn(
            boolean spectator,
            Camera camera,
            Frustum frustum,
            List<SectionRenderDispatcher.RenderSection> builtChunks,
            CallbackInfo ci) {
        rtxremix$close(rtxremix$prepareScope);
    }

    @Redirect(
            method = "scheduleFullUpdate",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/Util;backgroundExecutor()Ljava/util/concurrent/ExecutorService;"
            ))
    private ExecutorService rtxremix$visibilityExecutor() {
        return RtxRemixMod.isRtxEnabled()
                ? rtxremix$visibilityExecutor
                : Util.backgroundExecutor();
    }

    /**
     * A full walk is about to construct its state: hand it the other buffer set. Runs for
     * vanilla's walk too, since the reused set, list and map are what its state is made of.
     */
    @Inject(method = "method_52820", at = @At("HEAD"))
    private void rtxremix$beginFrustumIndex(
            Camera camera,
            Vec3 cameraPos,
            boolean spectator,
            CallbackInfo ci) {
        VisibilityGraphBuffers.rotate();
        if (RtxRemixMod.isRtxEnabled()) {
            rtxremix$frustumIndexBuild.set(new BuiltChunkFrustumIndexBuild(
                    ChunkSlotMoveLog.RENDER_THREAD.sequence(), rtxremix$cullerPool.poll()));
        } else {
            rtxremix$frustumIndexBuild.remove();
            rtxremix$frustumIndex = null;
        }
    }

    // The second AtomicReference write publishes field_45624; the writes on
    // either side install and clear the in-progress event sink.
    @Redirect(
            method = "method_52820",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/atomic/AtomicReference;set(Ljava/lang/Object;)V",
                    ordinal = 1
            ))
    private void rtxremix$publishFrustumIndex(
            AtomicReference<Object> stateReference,
            Object state) {
        BuiltChunkFrustumIndexBuild build = rtxremix$frustumIndexBuild.get();
        rtxremix$frustumIndexBuild.remove();
        // Published even when slots were re-originned during the walk: the walk takes tens of
        // milliseconds, and discarding a build that saw a move would leave every chunk on
        // vanilla's exact per-chunk test for several frames.
        //
        // Reconciled from the move sequence the build STARTED at, never the current one:
        // anything that moved mid-walk is indexed against bounds that are already stale, so the
        // first apply must replay those moves. Starting from the current sequence would skip
        // them and silently drop the moved geometry.
        BuiltChunkFrustumIndex superseded = rtxremix$frustumIndex;
        rtxremix$frustumIndex = build != null ? build.publish(state) : null;
        stateReference.set(state);
        rtxremix$recycle(superseded);
    }

    /**
     * Returns a superseded index's culler to the pool unless the render thread is inside a use
     * of it, in which case that use retires it when it ends.
     */
    private void rtxremix$recycle(BuiltChunkFrustumIndex superseded) {
        if (superseded == null) {
            return;
        }
        HierarchicalFrustumCuller<SectionRenderDispatcher.RenderSection> culler = superseded.tryRetire();
        if (culler != null && rtxremix$cullerPool.size() < 2) {
            rtxremix$cullerPool.offer(culler);
        }
    }

    /** Ends a render-thread use; recycles the index if a publish superseded it meanwhile. */
    private void rtxremix$releaseIndex(BuiltChunkFrustumIndex index) {
        index.release();
        if (rtxremix$frustumIndex != index) {
            rtxremix$recycle(index);
        }
    }

    @Redirect(
            method = "runUpdates",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/LinkedHashSet;add(Ljava/lang/Object;)Z"
            ))
    private boolean rtxremix$indexVisibleChunk(LinkedHashSet<Node> chunks, Object chunkInfo) {
        return rtxremix$visit(chunks, (Node) chunkInfo);
    }

    private boolean rtxremix$visit(LinkedHashSet<Node> chunks, Node chunkInfo) {
        if (!chunks.add(chunkInfo)) {
            return false;
        }

        SectionRenderDispatcher.RenderSection chunk = chunkInfo.section;
        BuiltChunkFrustumIndexBuild build = rtxremix$frustumIndexBuild.get();
        if (build != null) {
            build.add(chunks, chunk);
            return true;
        }

        // Indexed unconditionally once the set matches: the entry is built from the chunk's
        // current bounds, so it is accurate regardless of what moved earlier. Gating this on the
        // origin epoch would silently leave the chunk out of an index that the apply path now
        // reconciles and keeps using, and an unindexed chunk is never drawn.
        BuiltChunkFrustumIndex index = rtxremix$frustumIndex;
        if (index != null && index.chunks() == chunks && index.tryAcquire()) {
            try {
                index.add(chunk);
            } finally {
                rtxremix$releaseIndex(index);
            }
        }
        return true;
    }

    @Inject(method = "runPartialUpdate", at = @At("HEAD"))
    private void rtxremix$graphHead(
            boolean spectator,
            Frustum frustum,
            List<SectionRenderDispatcher.RenderSection> builtChunks,
            Vec3 cameraPos,
            CallbackInfo ci) {
        rtxremix$open(rtxremix$graphScope, "ChunkVisibility.rebuildGraph", rtxremix$sizeOf(builtChunks));
    }

    @Inject(method = "runPartialUpdate", at = @At("RETURN"))
    private void rtxremix$graphReturn(
            boolean spectator,
            Frustum frustum,
            List<SectionRenderDispatcher.RenderSection> builtChunks,
            Vec3 cameraPos,
            CallbackInfo ci) {
        rtxremix$budgetHit(rtxremix$graphScope, rtxremix$propagationQueue.budgetHit());
        rtxremix$close(rtxremix$graphScope);
    }

    // Vanilla only propagates when new seeds arrived. Sections left over from a budgeted
    // slice must keep draining even on frames with no new seeds.
    @Redirect(
            method = "runPartialUpdate",
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
            method = "runPartialUpdate",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/Queues;newArrayDeque()Ljava/util/ArrayDeque;"
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

    @Inject(method = "initializeQueueForFullUpdate", at = @At("HEAD"))
    private void rtxremix$seedHead(
            Camera camera,
            Queue<?> queue,
            CallbackInfo ci) {
        rtxremix$open(rtxremix$seedScope, "ChunkVisibility.seedQueue", rtxremix$sizeOf(queue));
    }

    @Inject(method = "initializeQueueForFullUpdate", at = @At("RETURN"))
    private void rtxremix$seedReturn(
            Camera camera,
            Queue<?> queue,
            CallbackInfo ci) {
        rtxremix$close(rtxremix$seedScope);
    }

    /**
     * Vanilla's walk allocates on every step of its far-chunk probe: for each neighbour more
     * than 60 blocks from the camera it ray-marches from the neighbour's near corner back
     * toward the camera in {@link #CEILED_SECTION_DIAGONAL}-block steps, creating a Vec3d for the remaining
     * offset, a Vec3d for the advanced point and a BlockPos for the lookup each time. At render
     * distance 32 that is most of the garbage the collector cleans up while the game runs, and
     * the collector's concurrent work is what stretches frames. This port keeps vanilla's
     * traversal, arithmetic and evaluation order and holds the ray in doubles and one mutable
     * position, so the visible set is identical and the walk allocates only graph nodes.
     */
    @Inject(method = "runUpdates", at = @At("HEAD"), cancellable = true)
    private void rtxremix$walkVisibleGraph(
            GraphStorage renderableChunks,
            Vec3 cameraPos,
            Queue<Node> queue,
            boolean culling,
            Consumer<SectionRenderDispatcher.RenderSection> chunkConsumer,
            CallbackInfo ci) {
        rtxremix$open(rtxremix$walkScope, "ChunkVisibility.walkVisibleGraph", rtxremix$sizeOf(queue));
        if (!RtxRemixMod.isRtxEnabled() || rtxremix$VANILLA_WALK) {
            return;
        }
        try {
            rtxremix$walk(renderableChunks, cameraPos, queue, culling, chunkConsumer);
        } finally {
            rtxremix$close(rtxremix$walkScope);
        }
        if (rtxremix$LOG_VISIBLE_SET) rtxremix$logVisibleSet("port", renderableChunks);
        ci.cancel();
    }

    @Inject(method = "runUpdates", at = @At("RETURN"))
    private void rtxremix$walkReturn(
            GraphStorage renderableChunks,
            Vec3 cameraPos,
            Queue<Node> queue,
            boolean culling,
            Consumer<SectionRenderDispatcher.RenderSection> chunkConsumer,
            CallbackInfo ci) {
        rtxremix$close(rtxremix$walkScope);
        if (rtxremix$LOG_VISIBLE_SET) rtxremix$logVisibleSet("vanilla", renderableChunks);
    }

    /** Order-independent signature of the visible set: size and a hash of every section origin. */
    private static void rtxremix$logVisibleSet(String walk, GraphStorage renderableChunks) {
        long hash = 0;
        for (Node info : renderableChunks.renderSections) {
            hash += (info.section.getOrigin().asLong() * 0x9E3779B97F4A7C15L) ^ 0x632BE59BD9B4E019L;
        }
        long signature = hash ^ ((long) renderableChunks.renderSections.size() << 48);
        if (signature != rtxremix$lastVisibleSetSignature) {
            rtxremix$lastVisibleSetSignature = signature;
            RtxRemixMod.LOGGER.info("Visible set ({} walk): {} sections, hash {}",
                    walk, renderableChunks.renderSections.size(), Long.toHexString(hash));
        }
    }

    private void rtxremix$walk(
            GraphStorage renderableChunks,
            Vec3 cameraPos,
            Queue<Node> queue,
            boolean culling,
            Consumer<SectionRenderDispatcher.RenderSection> chunkConsumer) {
        ViewArea storage = viewArea;
        SectionToNodeMap infos = renderableChunks.sectionToNodeMap;
        LinkedHashSet<Node> chunks = renderableChunks.renderSections;
        Long2ObjectMap<List<SectionRenderDispatcher.RenderSection>> unbuiltByChunk = renderableChunks.chunksWaitingForNeighbors;
        ChunkInfoPool infoPool = ((RenderableChunksBuffers) renderableChunks).rtxremix$infoPool();
        Direction[] directions = DIRECTIONS;
        LevelHeightAccessor world = storage.getLevelHeightAccessor();
        BlockPos cameraSectionOrigin = new BlockPos(
                Mth.floor(cameraPos.x / 16.0) * 16,
                Mth.floor(cameraPos.y / 16.0) * 16,
                Mth.floor(cameraPos.z / 16.0) * 16);
        int cameraSectionCenterX = cameraSectionOrigin.getX() + 8;
        int cameraSectionCenterY = cameraSectionOrigin.getY() + 8;
        int cameraSectionCenterZ = cameraSectionOrigin.getZ() + 8;
        // One position serves every probed step of every probe in the walk, where vanilla
        // allocated a fresh BlockPos per step. Sound while getRenderedChunk only derives
        // array indices from the position it is handed; a lookup that kept or keyed by it
        // would alias every probe to the last step.
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        FarChunkProbe.GraphLookup graph = (x, y, z) -> {
            SectionRenderDispatcher.RenderSection passed = storage.getRenderSectionAt(probe.set(x, y, z));
            return passed != null && infos.get(passed) != null;
        };

        while (!queue.isEmpty()) {
            Node chunkInfo = queue.poll();
            SectionRenderDispatcher.RenderSection chunk = chunkInfo.section;
            if (rtxremix$visit(chunks, chunkInfo)) {
                chunkConsumer.accept(chunk);
            }
            BlockPos origin = chunk.getOrigin();
            boolean far = Math.abs(origin.getX() - cameraSectionOrigin.getX()) > 60
                    || Math.abs(origin.getY() - cameraSectionOrigin.getY()) > 60
                    || Math.abs(origin.getZ() - cameraSectionOrigin.getZ()) > 60;

            for (Direction direction : directions) {
                SectionRenderDispatcher.RenderSection neighbour = getRelativeFrom(cameraSectionOrigin, chunk, direction);
                if (neighbour == null) {
                    continue;
                }
                if (culling && chunkInfo.hasDirection(direction.getOpposite())) {
                    continue;
                }
                if (culling && chunkInfo.hasSourceDirections()) {
                    SectionRenderDispatcher.CompiledSection data = chunk.getCompiled();
                    boolean visibleThrough = false;
                    for (int i = 0; i < directions.length; i++) {
                        if (chunkInfo.hasSourceDirection(i)
                                && data.facesCanSeeEachother(directions[i].getOpposite(), direction)) {
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
                            CEILED_SECTION_DIAGONAL,
                            world.getMaxBuildHeight(), world.getMinBuildHeight(),
                            graph);
                    if (!reaches) {
                        continue;
                    }
                }

                Node neighbourInfo = infos.get(neighbour);
                if (neighbourInfo != null) {
                    neighbourInfo.addSourceDirection(direction);
                    continue;
                }
                Node discovered = infoPool.acquire(neighbour, direction, chunkInfo.step + 1);
                discovered.setDirections(chunkInfo.directions, direction);
                if (neighbour.hasAllNeighbors()) {
                    queue.add(discovered);
                    infos.put(neighbour, discovered);
                } else if (isInViewDistance(cameraSectionOrigin, neighbour.getOrigin())) {
                    infos.put(neighbour, discovered);
                    long chunkKey = ChunkPos.asLong(neighbour.getOrigin());
                    List<SectionRenderDispatcher.RenderSection> unbuilt = unbuiltByChunk.get(chunkKey);
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

    private static void rtxremix$scannedChunkCount(ThreadLocal<ArrayDeque<ProfileScope>> holder, int scanned) {
        if (!ProfileScope.isDeepProfilingEnabled() || scanned < 0) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            stack.peek().scannedChunkCount(scanned);
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

    private boolean rtxremix$applyFrustumIndex(
            Frustum frustum,
            List<SectionRenderDispatcher.RenderSection> builtChunks) {
        BuiltChunkFrustumIndex index = rtxremix$frustumIndex;
        if (index == null || index.state() != currentGraph.get() || !index.tryAcquire()) {
            return false;
        }
        try {
            // Slots re-originned since this index was last reconciled may have left their
            // cached cells; the index re-buckets exactly those sections before it is queried.
            index.reconcile(ChunkSlotMoveLog.RENDER_THREAD);
            rtxremix$scannedChunkCount(rtxremix$frustumScope, index.size());

            FrustumAccessor accessor = (FrustumAccessor) frustum;
            FrustumIntersection intersection = accessor.rtxremix$getFrustumIntersection();
            double frustumX = accessor.rtxremix$getX();
            double frustumY = accessor.rtxremix$getY();
            double frustumZ = accessor.rtxremix$getZ();
            index.forEachVisible(
                    (minX, minY, minZ, maxX, maxY, maxZ) -> {
                        int classification = intersection.intersectAab(
                                (float) (minX - frustumX),
                                (float) (minY - frustumY),
                                (float) (minZ - frustumZ),
                                (float) (maxX - frustumX),
                                (float) (maxY - frustumY),
                                (float) (maxZ - frustumZ));
                        if (classification == FrustumIntersection.INSIDE) {
                            return HierarchicalFrustumCuller.INSIDE;
                        }
                        if (classification == FrustumIntersection.INTERSECT) {
                            return HierarchicalFrustumCuller.INTERSECT;
                        }
                        return HierarchicalFrustumCuller.OUTSIDE;
                    },
                    chunk -> frustum.isVisible(chunk.getBoundingBox()),
                    builtChunks::add);
            return true;
        } finally {
            rtxremix$releaseIndex(index);
        }
    }
}

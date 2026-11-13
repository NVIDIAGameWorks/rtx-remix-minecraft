/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.phys.AABB;

/**
 * A published coarse frustum index over the sections vanilla's visibility graph has reached.
 *
 * <p>{@code state} is the vanilla visibility state the index was built from, so an apply can
 * refuse an index that outlived a publish, and {@code chunks} is that state's reached-section
 * set, so incremental additions can be matched to the right index. Cached cell bounds go stale
 * when the torus re-origins slots; {@link #reconcile} replays the moves logged since the last
 * reconcile and re-buckets exactly those sections. Mutable state belongs to the render thread.
 *
 * <p>The culler behind an index is reused by later builds. The render thread brackets every
 * use with {@link #tryAcquire} and {@link #release}; the publisher takes the culler back with
 * {@link #tryRetire}, which fails while a use is in progress, and the reader retires a
 * superseded index itself when its use ends. Whichever side wins the compare-and-set owns the
 * culler, so no build can clear arrays a query is still walking.
 */
public final class BuiltChunkFrustumIndex {
    private static final int FRESH = 0;
    private static final int IN_USE = 1;
    private static final int RETIRED = 2;

    private static final HierarchicalFrustumCuller.EntryBounds<SectionRenderDispatcher.RenderSection> CHUNK_BOUNDS =
            (chunk, out) -> {
                AABB bounds = chunk.getBoundingBox();
                out[0] = bounds.minX;
                out[1] = bounds.minY;
                out[2] = bounds.minZ;
                out[3] = bounds.maxX;
                out[4] = bounds.maxY;
                out[5] = bounds.maxZ;
            };

    private final Object state;
    private final LinkedHashSet<?> chunks;
    private final HierarchicalFrustumCuller<SectionRenderDispatcher.RenderSection> culler;
    private final IntConsumer rebucketMovedSlot;
    private final AtomicInteger use = new AtomicInteger(FRESH);
    private long reconciledSequence;

    BuiltChunkFrustumIndex(
            Object state,
            LinkedHashSet<?> chunks,
            HierarchicalFrustumCuller<SectionRenderDispatcher.RenderSection> culler,
            long reconciledSequence) {
        this.state = state;
        this.chunks = chunks;
        this.culler = culler;
        this.reconciledSequence = reconciledSequence;
        this.rebucketMovedSlot = slot -> culler.rebucket(slot, CHUNK_BOUNDS);
    }

    public Object state() {
        return state;
    }

    public LinkedHashSet<?> chunks() {
        return chunks;
    }

    /** Claims the index for one render-thread use; false once the publisher has retired it. */
    public boolean tryAcquire() {
        return use.compareAndSet(FRESH, IN_USE);
    }

    /** Ends the use begun by a successful {@link #tryAcquire}. */
    public void release() {
        use.set(FRESH);
    }

    /**
     * Takes the culler back for reuse, or null while a use is in progress or once another
     * caller already took it. A retired index refuses every later acquire.
     */
    public HierarchicalFrustumCuller<SectionRenderDispatcher.RenderSection> tryRetire() {
        return use.compareAndSet(FRESH, RETIRED) ? culler : null;
    }

    /** Number of indexed sections, the size a reconcile or query walks. */
    public int size() {
        return culler.size();
    }

    /** Indexes a section the graph reached after publication, using its current bounds. */
    public void add(SectionRenderDispatcher.RenderSection chunk) {
        add(culler, chunk);
    }

    static void add(HierarchicalFrustumCuller<SectionRenderDispatcher.RenderSection> culler, SectionRenderDispatcher.RenderSection chunk) {
        AABB bounds = chunk.getBoundingBox();
        culler.add(chunk, chunk.index,
                bounds.minX, bounds.minY, bounds.minZ,
                bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    /**
     * Re-buckets the sections whose slots moved since the last reconcile. When the log no
     * longer holds that window, every indexed section is re-read instead.
     */
    public void reconcile(ChunkSlotMoveLog moveLog) {
        long moveSequence = moveLog.sequence();
        if (reconciledSequence == moveSequence) {
            return;
        }
        if (!moveLog.replay(reconciledSequence, moveSequence, rebucketMovedSlot)) {
            culler.rebucketAll(CHUNK_BOUNDS);
        }
        reconciledSequence = moveSequence;
    }

    /** Emits the sections a frustum can see, in graph order; see {@link HierarchicalFrustumCuller#forEachVisible}. */
    public void forEachVisible(HierarchicalFrustumCuller.BoxClassifier cellClassifier,
                               Predicate<? super SectionRenderDispatcher.RenderSection> chunkTest,
                               Consumer<? super SectionRenderDispatcher.RenderSection> consumer) {
        culler.forEachVisible(cellClassifier, chunkTest, consumer);
    }
}

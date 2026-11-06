/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.nvidia.rtxremix.HardcodedVertex;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.core.SectionPos;

/**
 * Thread-safe queue that transfers block-gen section data from chunk-build
 * worker threads to the render thread.
 */
public final class BlockDataQueue {

    private static final int DEFAULT_MAX_PENDING_ITEMS = 512;
    private static final long DEFAULT_MAX_PENDING_BYTES = 128L * 1024L * 1024L;
    private static final int DEFAULT_MAX_DRAIN_ITEMS = 32;
    private static final long DEFAULT_MAX_DRAIN_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_PENDING_ITEMS = Math.max(16,
            Integer.getInteger("rtxremix.blockDataQueue.maxPendingItems", DEFAULT_MAX_PENDING_ITEMS));
    private static final long MAX_PENDING_BYTES = Math.max(16L * 1024L * 1024L,
            Long.getLong("rtxremix.blockDataQueue.maxPendingBytes", DEFAULT_MAX_PENDING_BYTES));
    private static final int MAX_DRAIN_ITEMS = Math.max(1,
            Integer.getInteger("rtxremix.blockDataQueue.maxDrainItems", DEFAULT_MAX_DRAIN_ITEMS));
    private static final long MAX_DRAIN_BYTES = Math.max(4L * 1024L * 1024L,
            Long.getLong("rtxremix.blockDataQueue.maxDrainBytes", DEFAULT_MAX_DRAIN_BYTES));
    // Total time a producer may block on a full queue before trading the
    // oldest queued capture for liveness. Producers are vanilla's shared
    // chunk workers holding ChunkBuilder buffer slots; parking them
    // indefinitely halts world loading everywhere, so a wedged or outpaced
    // drain must cost a recapture, never a frozen worker pool.
    private static final long DEFAULT_PUBLISH_WAIT_BUDGET_NS = Math.max(50_000_000L,
            Long.getLong("rtxremix.blockDataQueue.publishWaitBudgetNs", 500_000_000L));
    private static volatile long publishWaitBudgetNs = DEFAULT_PUBLISH_WAIT_BUDGET_NS;
    private static final long EVICTION_RETRY_WAIT_NS = 50_000_000L;

    private static final AtomicLong BACKPRESSURE_WAITS = new AtomicLong();
    private static final AtomicLong BACKPRESSURE_EVICTIONS = new AtomicLong();
    private static final AtomicLong NEXT_CHUNK_TOKEN = new AtomicLong();
    private static final AtomicLong NEXT_QUEUE_GENERATION = new AtomicLong();
    private static final AtomicReference<QueueState> STATE =
            new AtomicReference<>(new QueueState(allocateQueueGeneration()));

    /** World generation and chunk incarnation latched together at capture start. */
    public record CaptureToken(long queueGeneration, long chunkToken) {}

    /** Captured data retains its identity until the render thread applies it. */
    public record QueuedCapture(SectionBlockData data, CaptureToken capture) {}

    /**
     * Section whose capture was sacrificed under backpressure. The render
     * thread must re-flag it for vanilla rebuild or its terrain stays stale
     * until an unrelated update touches it.
     */
    public record EvictedSection(int x, int y, int z) {}

    private record QueuedSectionData(QueuedCapture capture, long bytes) {}

    private static final class QueueState {
        final long generation;
        final ConcurrentLinkedQueue<QueuedSectionData> queue = new ConcurrentLinkedQueue<>();
        final ConcurrentHashMap<Long, Long> chunkTokens = new ConcurrentHashMap<>();
        final AtomicInteger reservedItems = new AtomicInteger();
        final AtomicLong reservedBytes = new AtomicLong();
        final AtomicInteger pendingItems = new AtomicInteger();
        final AtomicLong pendingBytes = new AtomicLong();
        final AtomicInteger activePublishers = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean();
        final ReentrantLock capacityLock = new ReentrantLock();
        final Condition capacityChanged = capacityLock.newCondition();
        final AtomicInteger capacityWaiters = new AtomicInteger();
        final ConcurrentLinkedQueue<EvictedSection> evictedSections =
                new ConcurrentLinkedQueue<>();

        QueueState(long generation) {
            this.generation = generation;
        }

        void close() {
            if (!closed.compareAndSet(false, true)) return;
            signalCapacityWaiters();
            discardRetiredDataIfQuiescent();
        }

        void signalCapacityWaiters() {
            if (capacityWaiters.get() == 0) return;
            capacityLock.lock();
            try {
                capacityChanged.signalAll();
            } finally {
                capacityLock.unlock();
            }
        }

        void discardRetiredDataIfQuiescent() {
            if (closed.get() && activePublishers.get() == 0) {
                QueuedSectionData retired;
                while ((retired = queue.poll()) != null) {
                    releaseMeshData(retired.capture.data());
                }
            }
        }
    }

    private BlockDataQueue() {}

    /** Returns the current capture identity, creating a live chunk incarnation if needed. */
    public static CaptureToken beginCapture(int cx, int cz) {
        QueueState state = STATE.get();
        long key = packChunkKey(cx, cz);
        long chunkToken = state.chunkTokens.computeIfAbsent(
                key, ignored -> allocateChunkToken());
        return new CaptureToken(state.generation, chunkToken);
    }

    /** Invalidates captures that began before this chunk-unload event. */
    public static void markChunkUnloaded(int cx, int cz) {
        QueueState state = STATE.get();
        state.chunkTokens.remove(packChunkKey(cx, cz));
        state.signalCapacityWaiters();
    }

    /**
     * Called with the identity captured at SectionBuilder.build HEAD. Takes over
     * the capture's mesh data: it is released here when the capture is dropped
     * and otherwise travels to the render thread with the queue entry.
     */
    public static void enqueue(SectionBlockData data, CaptureToken capture) {
        QueueState state = STATE.get();
        if (state.generation != capture.queueGeneration()
                || !isCurrentChunkToken(state, data, capture.chunkToken())) {
            releaseMeshData(data);
            return;
        }

        long bytes = estimateBytes(data);
        if (!acquireCapacity(state, data, capture, bytes)) {
            releaseMeshData(data);
            return;
        }

        if (!beginPublish(state)) {
            releaseCapacity(state, bytes);
            state.signalCapacityWaiters();
            releaseMeshData(data);
            return;
        }
        try {
            if (!isCaptureCurrent(state, data, capture)) {
                releaseCapacity(state, bytes);
                state.signalCapacityWaiters();
                releaseMeshData(data);
                return;
            }

            // Publish accounting before the queue node so the render thread can
            // never consume an entry whose counters have not yet been charged.
            state.pendingItems.incrementAndGet();
            state.pendingBytes.addAndGet(bytes);
            state.queue.add(new QueuedSectionData(new QueuedCapture(data, capture), bytes));
        } finally {
            endPublish(state);
        }
    }

    /**
     * Called from the render thread once per frame.
     * Drains a bounded amount of pending block-gen data into the provided list and returns the count.
     */
    public static int drain(List<QueuedCapture> out) {
        QueueState state = STATE.get();
        int removedCount = 0;
        int outputCount = 0;
        long bytes = 0;
        while (true) {
            QueuedSectionData next = state.queue.peek();
            if (next == null) {
                break;
            }
            if (removedCount >= MAX_DRAIN_ITEMS
                    || (removedCount > 0 && (bytes >= MAX_DRAIN_BYTES
                    || next.bytes > MAX_DRAIN_BYTES - bytes))) {
                break;
            }

            next = state.queue.poll();
            if (next == null) continue;

            state.pendingItems.decrementAndGet();
            state.pendingBytes.addAndGet(-next.bytes);
            releaseCapacity(state, next.bytes);

            if (isCurrent(next.capture)) {
                out.add(next.capture);
                outputCount++;
            } else {
                releaseMeshData(next.capture.data());
            }
            removedCount++;
            bytes += next.bytes;
        }
        if (removedCount > 0) state.signalCapacityWaiters();
        return outputCount;
    }

    /** Discards all pending data (e.g. on world unload). */
    public static void clear() {
        QueueState retired = STATE.getAndSet(new QueueState(allocateQueueGeneration()));
        retired.close();
    }

    public static int pendingCount() {
        return STATE.get().pendingItems.get();
    }

    public static long pendingBytes() {
        return STATE.get().pendingBytes.get();
    }

    public static long drainBackpressureWaits() {
        return BACKPRESSURE_WAITS.getAndSet(0);
    }

    /** Current captures sacrificed under backpressure, each costing one recapture. */
    public static long drainBackpressureEvictions() {
        return BACKPRESSURE_EVICTIONS.getAndSet(0);
    }

    /**
     * Drains sections whose captures were sacrificed under backpressure so the
     * render thread can re-flag them for vanilla rebuild. World changes drop
     * stale entries with the rest of the queue state.
     */
    public static int drainEvictedSections(List<EvictedSection> out, int maxItems) {
        QueueState state = STATE.get();
        int drained = 0;
        EvictedSection section;
        while (drained < maxItems && (section = state.evictedSections.poll()) != null) {
            out.add(section);
            drained++;
        }
        return drained;
    }

    /** Revalidates data retained by the render thread across a frame boundary. */
    public static boolean isCurrent(QueuedCapture queued) {
        QueueState state = STATE.get();
        CaptureToken capture = queued.capture();
        return state.generation == capture.queueGeneration()
                && !state.closed.get()
                && isCurrentChunkToken(state, queued.data(), capture.chunkToken());
    }

    static int trackedChunkCount() {
        return STATE.get().chunkTokens.size();
    }

    static int maxPendingItemsForTest() {
        return MAX_PENDING_ITEMS;
    }

    static long maxPendingBytesForTest() {
        return MAX_PENDING_BYTES;
    }

    /** Applies to producers that start waiting afterwards; returns the previous budget. */
    static long setPublishWaitBudgetNsForTest(long nanos) {
        long previous = publishWaitBudgetNs;
        publishWaitBudgetNs = nanos;
        return previous;
    }

    private static boolean acquireCapacity(QueueState state, SectionBlockData data,
                                           CaptureToken capture, long bytes) {
        if (!isCaptureCurrent(state, data, capture)) return false;
        if (tryReserveCapacity(state, bytes)) return true;

        // WorldRenderer can rebuild nearby chunks synchronously. It is also
        // the sole consumer, so waiting here would prevent the queue from ever
        // freeing capacity. Worker captures remain subject to both bounds.
        if (isRenderThread()) {
            state.reservedItems.incrementAndGet();
            state.reservedBytes.addAndGet(bytes);
            return true;
        }

        // Latched once per wait: a budget change applies to producers that
        // begin waiting afterwards, never to one already counted as waiting.
        long waitBudgetNs = publishWaitBudgetNs;
        BACKPRESSURE_WAITS.incrementAndGet();
        try {
            state.capacityLock.lockInterruptibly();
            state.capacityWaiters.incrementAndGet();
            try {
                while (isCaptureCurrent(state, data, capture)) {
                    if (tryReserveCapacity(state, bytes)) return true;
                    if (waitBudgetNs > 0L) {
                        waitBudgetNs = state.capacityChanged.awaitNanos(waitBudgetNs);
                        continue;
                    }
                    // The drain freed no room for the whole wait budget, so it
                    // is wedged or hopelessly outpaced. Trade the oldest queued
                    // captures for liveness; FIFO order makes the head the most
                    // stale entry and exactly what the drain would consume next.
                    if (evictIntoReservation(state, data, bytes)) return true;
                    // Capacity is held by reservations still in flight
                    // between reserve and enqueue; re-arm a short wait.
                    waitBudgetNs = EVICTION_RETRY_WAIT_NS;
                }
                return false;
            } finally {
                state.capacityWaiters.decrementAndGet();
                state.capacityLock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Consumes queue heads without publishing them until their reservations
     * cover {@code bytes}, then hands that reservation to the caller. Handing
     * capacity over directly, rather than releasing it and reserving again,
     * keeps a lockless fast-path producer from taking it first and forcing a
     * needless second eviction. Returns false once the queue runs empty, with
     * everything consumed released again.
     */
    private static boolean evictIntoReservation(QueueState state, SectionBlockData incoming,
                                                long bytes) {
        long held = evictQueueHead(state, incoming);
        if (held < 0L) return false;

        int foldedItems = 0;
        while (!tryReserveBytes(state, held, bytes)) {
            long next = evictQueueHead(state, incoming);
            if (next < 0L) {
                releaseCapacity(state, held);
                state.signalCapacityWaiters();
                return false;
            }
            // Every further head brings an item slot the caller does not need.
            state.reservedItems.decrementAndGet();
            foldedItems++;
            held += next;
        }
        if (foldedItems > 0 || held > bytes) state.signalCapacityWaiters();
        return true;
    }

    /**
     * Consumes the queue head without publishing it and returns its byte
     * reservation, which the caller now owns together with its item slot; -1
     * when the queue is empty. A current head for another section is recorded
     * so the render thread can re-flag it for vanilla rebuild. A head whose
     * chunk already unloaded needs no recapture, and one for the incoming
     * section is superseded by the capture about to be published.
     */
    private static long evictQueueHead(QueueState state, SectionBlockData incoming) {
        QueuedSectionData head = state.queue.poll();
        if (head == null) return -1L;

        state.pendingItems.decrementAndGet();
        state.pendingBytes.addAndGet(-head.bytes);
        releaseMeshData(head.capture.data());

        SectionPos sacrificed = head.capture.data().sectionPos();
        if (isCurrent(head.capture) && !sacrificed.equals(incoming.sectionPos())) {
            BACKPRESSURE_EVICTIONS.incrementAndGet();
            state.evictedSections.add(new EvictedSection(
                    sacrificed.getX(), sacrificed.getY(), sacrificed.getZ()));
        }
        return head.bytes;
    }

    private static boolean tryReserveCapacity(QueueState state, long bytes) {
        if (state.closed.get() || !tryReserveItem(state)) return false;
        if (tryReserveBytes(state, 0L, bytes)) return true;

        state.reservedItems.decrementAndGet();
        state.signalCapacityWaiters();
        return false;
    }

    private static boolean tryReserveItem(QueueState state) {
        while (true) {
            int items = state.reservedItems.get();
            if (items >= MAX_PENDING_ITEMS) return false;
            if (state.reservedItems.compareAndSet(items, items + 1)) return true;
        }
    }

    /**
     * Moves the caller's byte reservation from {@code held} (zero for a fresh
     * one) to {@code wanted}. Growth must fit beside everyone else's
     * reservations, except that a lone capture is always admitted so an
     * oversized section can never starve on an otherwise empty queue.
     */
    private static boolean tryReserveBytes(QueueState state, long held, long wanted) {
        while (true) {
            long reserved = state.reservedBytes.get();
            long others = reserved - held;
            if (wanted > held && others > 0 && wanted > MAX_PENDING_BYTES - others) return false;
            if (state.reservedBytes.compareAndSet(reserved, reserved + wanted - held)) return true;
        }
    }

    private static void releaseCapacity(QueueState state, long bytes) {
        state.reservedBytes.addAndGet(-bytes);
        state.reservedItems.decrementAndGet();
    }

    private static boolean beginPublish(QueueState state) {
        state.activePublishers.incrementAndGet();
        if (!state.closed.get() && STATE.get() == state) return true;
        endPublish(state);
        return false;
    }

    private static void endPublish(QueueState state) {
        state.activePublishers.decrementAndGet();
        state.discardRetiredDataIfQuiescent();
    }

    private static boolean isCaptureCurrent(QueueState state, SectionBlockData data,
                                            CaptureToken capture) {
        return STATE.get() == state
                && !state.closed.get()
                && state.generation == capture.queueGeneration()
                && isCurrentChunkToken(state, data, capture.chunkToken());
    }

    private static boolean isRenderThread() {
        return Thread.currentThread().getName().equals("Render thread");
    }

    private static long estimateBytes(SectionBlockData data) {
        long bytes = 128;
        if (data.opacityBitmap() != null) {
            bytes += (long) data.opacityBitmap().length * Long.BYTES;
        }
        if (data.lights() != null) {
            bytes += (long) data.lights().size() * 128L;
        }
        if (data.meshData() != null) {
            for (SectionMeshData.Surface surface : data.meshData().surfaces()) {
                bytes += 64;
                // Capacity, not payload: the pool rounds a buffer up to its size class,
                // and the bound exists to track the memory a pending section pins.
                bytes += surface.vertexBuffer().capacity();
            }
        }
        return Math.max(bytes, 1L);
    }

    private static void releaseMeshData(SectionBlockData data) {
        SectionMeshData mesh = data.meshData();
        if (mesh != null) {
            mesh.release();
        }
    }

    private static boolean isCurrentChunkToken(QueueState state, SectionBlockData data,
                                               long captureChunkToken) {
        Long current = state.chunkTokens.get(packChunkKey(
                data.sectionPos().getX(), data.sectionPos().getZ()));
        return current != null && current == captureChunkToken;
    }

    private static long allocateChunkToken() {
        long token = NEXT_CHUNK_TOKEN.incrementAndGet();
        if (token == 0) {
            throw new IllegalStateException("Chunk capture token space exhausted");
        }
        return token;
    }

    private static long allocateQueueGeneration() {
        long generation = NEXT_QUEUE_GENERATION.incrementAndGet();
        if (generation == 0) {
            throw new IllegalStateException("Block-data queue generation space exhausted");
        }
        return generation;
    }

    private static long packChunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}

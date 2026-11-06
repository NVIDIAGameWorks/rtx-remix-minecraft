/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.nvidia.rtxremix.minecraft.terrain.ChunkManager;
import com.nvidia.rtxremix.minecraft.light.LightEntry;
import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.minecraft.texture.RedstoneEmission;
import net.minecraft.util.math.ChunkSectionPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockDataQueueTest {

    /** Long enough that no wake-path test can be pre-empted by an eviction. */
    private static final long NO_EVICTION_BUDGET_NS = TimeUnit.HOURS.toNanos(1);
    private static final long PROMPT_EVICTION_BUDGET_NS = TimeUnit.MILLISECONDS.toNanos(1);

    private long productionBudgetNs;

    @BeforeEach
    void clearQueueBeforeTest() {
        productionBudgetNs = BlockDataQueue.setPublishWaitBudgetNsForTest(NO_EVICTION_BUDGET_NS);
        BlockDataQueue.clear();
        BlockDataQueue.drainBackpressureWaits();
        BlockDataQueue.drainBackpressureEvictions();
    }

    @AfterEach
    void clearQueueAfterTest() {
        BlockDataQueue.clear();
        BlockDataQueue.drainBackpressureWaits();
        BlockDataQueue.drainBackpressureEvictions();
        BlockDataQueue.setPublishWaitBudgetNsForTest(productionBudgetNs);
    }

    @Test
    void chunkUnloadRejectsQueuedAndLateFinishingOldCaptures() {
        int cx = 7;
        int cz = -3;
        SectionBlockData data = emptyCapture(cx, 2, cz);
        BlockDataQueue.CaptureToken oldCapture = BlockDataQueue.beginCapture(cx, cz);

        BlockDataQueue.enqueue(data, oldCapture);
        BlockDataQueue.markChunkUnloaded(cx, cz);
        assertEquals(0, BlockDataQueue.trackedChunkCount());
        BlockDataQueue.enqueue(data, oldCapture);

        List<BlockDataQueue.QueuedCapture> drained = new ArrayList<>();
        assertEquals(0, BlockDataQueue.drain(drained));
        assertEquals(0, drained.size());
        assertEquals(0, BlockDataQueue.pendingCount());
        assertEquals(0, BlockDataQueue.pendingBytes());

        BlockDataQueue.CaptureToken reloadedCapture = BlockDataQueue.beginCapture(cx, cz);
        assertNotEquals(oldCapture.chunkToken(), reloadedCapture.chunkToken());
        BlockDataQueue.enqueue(data, reloadedCapture);
        assertEquals(1, BlockDataQueue.drain(drained));
        assertEquals(List.of(data), drained.stream().map(BlockDataQueue.QueuedCapture::data).toList());
    }

    @Test
    void chunkUnloadInvalidationIsIsolatedAndReleasesQueueAccounting() {
        SectionBlockData unloaded = emptyCapture(4, 1, 6);
        SectionBlockData retained = emptyCapture(-8, 2, 3);
        BlockDataQueue.CaptureToken unloadedCapture = BlockDataQueue.beginCapture(4, 6);
        BlockDataQueue.CaptureToken retainedCapture = BlockDataQueue.beginCapture(-8, 3);

        BlockDataQueue.enqueue(unloaded, unloadedCapture);
        BlockDataQueue.enqueue(retained, retainedCapture);
        BlockDataQueue.markChunkUnloaded(4, 6);

        List<BlockDataQueue.QueuedCapture> drained = new ArrayList<>();
        assertEquals(1, BlockDataQueue.drain(drained));
        assertEquals(List.of(retained),
                drained.stream().map(BlockDataQueue.QueuedCapture::data).toList());
        assertEquals(0, BlockDataQueue.pendingCount());
        assertEquals(0, BlockDataQueue.pendingBytes());
    }

    @Test
    void unloadedCoordinatesAreEvictedAndFreshCapturesDoNotReuseTokens() {
        BlockDataQueue.CaptureToken liveCapture = BlockDataQueue.beginCapture(-40, 25);
        Set<Long> issuedTokens = new HashSet<>();
        assertTrue(issuedTokens.add(liveCapture.chunkToken()));

        for (int i = 0; i < 10_000; i++) {
            int cx = 10_000 + i;
            int cz = -20_000 - i;
            BlockDataQueue.CaptureToken capture = BlockDataQueue.beginCapture(cx, cz);
            assertTrue(issuedTokens.add(capture.chunkToken()));
            BlockDataQueue.markChunkUnloaded(cx, cz);
        }

        assertEquals(1, BlockDataQueue.trackedChunkCount(),
                "Unloaded coordinates must not accumulate token state");
        assertEquals(liveCapture, BlockDataQueue.beginCapture(-40, 25));

        BlockDataQueue.CaptureToken oldCapture = BlockDataQueue.beginCapture(77, -88);
        assertTrue(issuedTokens.add(oldCapture.chunkToken()));
        BlockDataQueue.markChunkUnloaded(77, -88);
        BlockDataQueue.CaptureToken freshCapture = BlockDataQueue.beginCapture(77, -88);

        assertTrue(issuedTokens.add(freshCapture.chunkToken()));
        assertNotEquals(oldCapture.chunkToken(), freshCapture.chunkToken(),
                "A reloaded coordinate must not reuse an earlier incarnation token");
        assertEquals(2, BlockDataQueue.trackedChunkCount());

        BlockDataQueue.clear();
        BlockDataQueue.CaptureToken nextWorldCapture = BlockDataQueue.beginCapture(77, -88);
        assertTrue(issuedTokens.add(nextWorldCapture.chunkToken()));
        assertNotEquals(freshCapture.chunkToken(), nextWorldCapture.chunkToken(),
                "Incarnation tokens must remain unique across world clears");
        assertEquals(1, BlockDataQueue.trackedChunkCount());
    }

    @Test
    void drainedCaptureRemainsInvalidatedAcrossDeferredProcessing() {
        int cx = 12;
        int cz = -19;
        SectionBlockData oldData = emptyCapture(cx, 4, cz);
        BlockDataQueue.CaptureToken oldCapture = BlockDataQueue.beginCapture(cx, cz);
        BlockDataQueue.enqueue(oldData, oldCapture);

        List<BlockDataQueue.QueuedCapture> pending = new ArrayList<>();
        assertEquals(1, BlockDataQueue.drain(pending));
        assertTrue(BlockDataQueue.isCurrent(pending.getFirst()));

        BlockDataQueue.markChunkUnloaded(cx, cz);
        BlockDataQueue.CaptureToken replacement = BlockDataQueue.beginCapture(cx, cz);

        assertNotEquals(oldCapture.chunkToken(), replacement.chunkToken());
        assertFalse(BlockDataQueue.isCurrent(pending.getFirst()),
                "A drained capture must not survive unload/reload while pending");
    }

    @Test
    void concurrentEnqueuesKeepPendingAccountingExact() throws Exception {
        int producerCount = 8;
        int capturesPerProducer = 50;
        SectionBlockData data = emptyCapture(3, 1, -5);
        BlockDataQueue.CaptureToken capture = BlockDataQueue.beginCapture(3, -5);
        ExecutorService executor = Executors.newFixedThreadPool(producerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int producer = 0; producer < producerCount; producer++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < capturesPerProducer; i++) {
                        BlockDataQueue.enqueue(data, capture);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(5, TimeUnit.SECONDS);

            int expected = producerCount * capturesPerProducer;
            assertEquals(expected, BlockDataQueue.pendingCount());
            assertEquals(expected * 128L, BlockDataQueue.pendingBytes());

            List<BlockDataQueue.QueuedCapture> drained = new ArrayList<>();
            while (BlockDataQueue.pendingCount() > 0) {
                BlockDataQueue.drain(drained);
            }
            assertEquals(expected, drained.size());
            assertEquals(0, BlockDataQueue.pendingCount());
            assertEquals(0, BlockDataQueue.pendingBytes());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void clearWakesBackpressuredProducerWithoutPublishingOldWorldData() throws Exception {
        SectionBlockData data = emptyCapture(9, 2, 11);
        BlockDataQueue.CaptureToken capture = BlockDataQueue.beginCapture(9, 11);
        for (int i = 0; i < BlockDataQueue.maxPendingItemsForTest(); i++) {
            BlockDataQueue.enqueue(data, capture);
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        try {
            Future<?> blocked = executor.submit(() -> {
                started.countDown();
                BlockDataQueue.enqueue(data, capture);
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertTrue(awaitBackpressure(5, TimeUnit.SECONDS));
            assertFalse(blocked.isDone());

            BlockDataQueue.clear();
            blocked.get(5, TimeUnit.SECONDS);

            assertEquals(0, BlockDataQueue.drainBackpressureEvictions());
            assertEquals(0, BlockDataQueue.pendingCount());
            assertEquals(0, BlockDataQueue.pendingBytes());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void chunkUnloadWakesBackpressuredStaleProducer() throws Exception {
        int cx = -14;
        int cz = 27;
        SectionBlockData data = emptyCapture(cx, 3, cz);
        BlockDataQueue.CaptureToken capture = BlockDataQueue.beginCapture(cx, cz);
        for (int i = 0; i < BlockDataQueue.maxPendingItemsForTest(); i++) {
            BlockDataQueue.enqueue(data, capture);
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        try {
            Future<?> blocked = executor.submit(() -> {
                started.countDown();
                BlockDataQueue.enqueue(data, capture);
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertTrue(awaitBackpressure(5, TimeUnit.SECONDS));

            BlockDataQueue.markChunkUnloaded(cx, cz);
            blocked.get(5, TimeUnit.SECONDS);

            assertEquals(0, BlockDataQueue.drainBackpressureEvictions());
            assertEquals(BlockDataQueue.maxPendingItemsForTest(),
                    BlockDataQueue.pendingCount());
            List<BlockDataQueue.QueuedCapture> drained = new ArrayList<>();
            while (BlockDataQueue.pendingCount() > 0) {
                assertEquals(0, BlockDataQueue.drain(drained));
            }
            assertTrue(drained.isEmpty());
            assertEquals(0, BlockDataQueue.pendingBytes());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void interruptDoesNotLetBackpressuredProducerExceedCapacity() throws Exception {
        SectionBlockData data = emptyCapture(6, 5, -12);
        BlockDataQueue.CaptureToken capture = BlockDataQueue.beginCapture(6, -12);
        for (int i = 0; i < BlockDataQueue.maxPendingItemsForTest(); i++) {
            BlockDataQueue.enqueue(data, capture);
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        try {
            Future<?> blocked = executor.submit(() -> {
                started.countDown();
                try {
                    BlockDataQueue.enqueue(data, capture);
                } finally {
                    finished.countDown();
                }
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertTrue(awaitBackpressure(5, TimeUnit.SECONDS));

            blocked.cancel(true);
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals(0, BlockDataQueue.drainBackpressureEvictions());
            assertEquals(BlockDataQueue.maxPendingItemsForTest(),
                    BlockDataQueue.pendingCount());
            assertEquals(BlockDataQueue.maxPendingItemsForTest() * 128L,
                    BlockDataQueue.pendingBytes());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void fullQueueDoesNotDeadlockSynchronousRenderThreadCapture() throws Exception {
        SectionBlockData data = emptyCapture(-4, 6, 15);
        BlockDataQueue.CaptureToken capture = BlockDataQueue.beginCapture(-4, 15);
        for (int i = 0; i < BlockDataQueue.maxPendingItemsForTest(); i++) {
            BlockDataQueue.enqueue(data, capture);
        }

        ExecutorService renderExecutor = Executors.newSingleThreadExecutor(
                task -> new Thread(task, "Render thread"));
        try {
            Future<?> synchronousCapture = renderExecutor.submit(
                    () -> BlockDataQueue.enqueue(data, capture));
            synchronousCapture.get(5, TimeUnit.SECONDS);

            assertEquals(BlockDataQueue.maxPendingItemsForTest() + 1,
                    BlockDataQueue.pendingCount());
            assertEquals((BlockDataQueue.maxPendingItemsForTest() + 1L) * 128L,
                    BlockDataQueue.pendingBytes());

            List<BlockDataQueue.QueuedCapture> drained = new ArrayList<>();
            while (BlockDataQueue.pendingCount() > 0) {
                BlockDataQueue.drain(drained);
            }
            assertEquals(BlockDataQueue.maxPendingItemsForTest() + 1, drained.size());
            assertEquals(0, BlockDataQueue.pendingCount());
            assertEquals(0, BlockDataQueue.pendingBytes());
        } finally {
            renderExecutor.shutdownNow();
        }
    }

    @Test
    void concurrentUnloadChurnDoesNotRetainChunkTokens() throws Exception {
        int workerCount = 8;
        int chunksPerWorker = 1_000;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int worker = 0; worker < workerCount; worker++) {
                int workerIndex = worker;
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < chunksPerWorker; i++) {
                        int cx = workerIndex * chunksPerWorker + i;
                        int cz = -cx - 1;
                        BlockDataQueue.beginCapture(cx, cz);
                        BlockDataQueue.markChunkUnloaded(cx, cz);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
            assertEquals(0, BlockDataQueue.trackedChunkCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void exhaustedWaitBudgetEvictsTheQueueHead() {
        int cx = 3;
        int cz = -5;
        BlockDataQueue.CaptureToken capture = BlockDataQueue.beginCapture(cx, cz);
        for (int i = 0; i < BlockDataQueue.maxPendingItemsForTest(); i++) {
            BlockDataQueue.enqueue(emptyCapture(cx, i, cz), capture);
        }
        SectionBlockData incoming = emptyCapture(
                cx, BlockDataQueue.maxPendingItemsForTest(), cz);

        BlockDataQueue.setPublishWaitBudgetNsForTest(PROMPT_EVICTION_BUDGET_NS);
        BlockDataQueue.enqueue(incoming, capture);

        assertEquals(BlockDataQueue.maxPendingItemsForTest(), BlockDataQueue.pendingCount());
        assertEquals(1, BlockDataQueue.drainBackpressureEvictions());
        List<BlockDataQueue.EvictedSection> evicted = new ArrayList<>();
        assertEquals(1, BlockDataQueue.drainEvictedSections(evicted, 16));
        assertEquals(new BlockDataQueue.EvictedSection(cx, 0, cz), evicted.get(0));

        List<BlockDataQueue.QueuedCapture> drained = new ArrayList<>();
        while (BlockDataQueue.pendingCount() > 0) BlockDataQueue.drain(drained);
        assertTrue(drained.stream().anyMatch(queued -> queued.data() == incoming));
        assertFalse(drained.stream().anyMatch(
                queued -> queued.data().sectionPos().getY() == 0));
    }

    @Test
    void worldChangeDropsTheEvictedSectionBacklog() {
        int cx = 8;
        int cz = 13;
        BlockDataQueue.CaptureToken capture = BlockDataQueue.beginCapture(cx, cz);
        for (int i = 0; i < BlockDataQueue.maxPendingItemsForTest(); i++) {
            BlockDataQueue.enqueue(emptyCapture(cx, i, cz), capture);
        }
        BlockDataQueue.setPublishWaitBudgetNsForTest(PROMPT_EVICTION_BUDGET_NS);
        BlockDataQueue.enqueue(emptyCapture(
                cx, BlockDataQueue.maxPendingItemsForTest(), cz), capture);
        assertEquals(1, BlockDataQueue.drainBackpressureEvictions());

        BlockDataQueue.clear();
        List<BlockDataQueue.EvictedSection> evicted = new ArrayList<>();
        assertEquals(0, BlockDataQueue.drainEvictedSections(evicted, 16));
    }

    @Test
    void evictingAStaleHeadRecordsNoRecapture() {
        BlockDataQueue.CaptureToken unloaded = BlockDataQueue.beginCapture(4, 4);
        for (int i = 0; i < BlockDataQueue.maxPendingItemsForTest(); i++) {
            BlockDataQueue.enqueue(emptyCapture(4, i, 4), unloaded);
        }
        BlockDataQueue.markChunkUnloaded(4, 4);
        SectionBlockData incoming = emptyCapture(5, 0, 5);

        BlockDataQueue.setPublishWaitBudgetNsForTest(PROMPT_EVICTION_BUDGET_NS);
        BlockDataQueue.enqueue(incoming, BlockDataQueue.beginCapture(5, 5));

        assertEquals(0, BlockDataQueue.drainBackpressureEvictions());
        assertEquals(0, BlockDataQueue.drainEvictedSections(new ArrayList<>(), 16));
        assertEquals(BlockDataQueue.maxPendingItemsForTest(), BlockDataQueue.pendingCount());
        List<BlockDataQueue.QueuedCapture> drained = new ArrayList<>();
        while (BlockDataQueue.pendingCount() > 0) BlockDataQueue.drain(drained);
        assertEquals(List.of(incoming),
                drained.stream().map(BlockDataQueue.QueuedCapture::data).toList());
    }

    @Test
    void evictingAnOlderCaptureOfTheIncomingSectionNeedsNoReflag() {
        int cx = 6;
        int cz = -2;
        BlockDataQueue.CaptureToken capture = BlockDataQueue.beginCapture(cx, cz);
        for (int i = 0; i < BlockDataQueue.maxPendingItemsForTest(); i++) {
            BlockDataQueue.enqueue(emptyCapture(cx, i, cz), capture);
        }
        SectionBlockData incoming = emptyCapture(cx, 0, cz);

        BlockDataQueue.setPublishWaitBudgetNsForTest(PROMPT_EVICTION_BUDGET_NS);
        BlockDataQueue.enqueue(incoming, capture);

        assertEquals(0, BlockDataQueue.drainBackpressureEvictions());
        assertEquals(0, BlockDataQueue.drainEvictedSections(new ArrayList<>(), 16));
        assertEquals(BlockDataQueue.maxPendingItemsForTest(), BlockDataQueue.pendingCount());
        List<BlockDataQueue.QueuedCapture> drained = new ArrayList<>();
        while (BlockDataQueue.pendingCount() > 0) BlockDataQueue.drain(drained);
        assertEquals(List.of(incoming), drained.stream()
                .map(BlockDataQueue.QueuedCapture::data)
                .filter(data -> data.sectionPos().getY() == 0)
                .toList());
    }

    @Test
    void evictionHandsTheHeadReservationOverAndWakesWaitersWithTheSurplus() throws Exception {
        long maxBytes = BlockDataQueue.maxPendingBytesForTest();
        BlockDataQueue.CaptureToken capture = BlockDataQueue.beginCapture(1, 1);
        BlockDataQueue.enqueue(captureOfBytes(1, 0, 1, maxBytes / 4 * 3), capture);
        SectionBlockData waiting = captureOfBytes(1, 1, 1, maxBytes / 2);
        SectionBlockData evicting = captureOfBytes(1, 2, 1, maxBytes / 2);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> waiter = executor.submit(() -> BlockDataQueue.enqueue(waiting, capture));
            assertTrue(awaitBackpressure(5, TimeUnit.SECONDS));
            assertFalse(waiter.isDone());

            // The waiter is on the hour-long budget, so only the surplus
            // released by the eviction below can wake it.
            BlockDataQueue.setPublishWaitBudgetNsForTest(PROMPT_EVICTION_BUDGET_NS);
            BlockDataQueue.enqueue(evicting, capture);
            waiter.get(5, TimeUnit.SECONDS);

            assertEquals(1, BlockDataQueue.drainBackpressureEvictions());
            List<BlockDataQueue.EvictedSection> evicted = new ArrayList<>();
            assertEquals(1, BlockDataQueue.drainEvictedSections(evicted, 16));
            assertEquals(new BlockDataQueue.EvictedSection(1, 0, 1), evicted.get(0));
            assertEquals(2, BlockDataQueue.pendingCount());
            assertEquals(maxBytes, BlockDataQueue.pendingBytes());

            List<BlockDataQueue.QueuedCapture> drained = new ArrayList<>();
            while (BlockDataQueue.pendingCount() > 0) BlockDataQueue.drain(drained);
            List<SectionBlockData> published =
                    drained.stream().map(BlockDataQueue.QueuedCapture::data).toList();
            assertEquals(2, published.size());
            assertTrue(published.containsAll(List.of(waiting, evicting)));
            assertEquals(0, BlockDataQueue.pendingBytes());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void evictionConsumesFurtherHeadsUntilTheReservationCoversTheCapture() {
        long maxBytes = BlockDataQueue.maxPendingBytesForTest();
        BlockDataQueue.CaptureToken capture = BlockDataQueue.beginCapture(2, 2);
        BlockDataQueue.enqueue(captureOfBytes(2, 0, 2, maxBytes / 4), capture);
        BlockDataQueue.enqueue(captureOfBytes(2, 1, 2, maxBytes / 2), capture);
        SectionBlockData incoming = captureOfBytes(2, 2, 2, maxBytes / 4 * 3);

        BlockDataQueue.setPublishWaitBudgetNsForTest(PROMPT_EVICTION_BUDGET_NS);
        BlockDataQueue.enqueue(incoming, capture);

        assertEquals(2, BlockDataQueue.drainBackpressureEvictions());
        List<BlockDataQueue.EvictedSection> evicted = new ArrayList<>();
        assertEquals(2, BlockDataQueue.drainEvictedSections(evicted, 16));
        assertEquals(List.of(new BlockDataQueue.EvictedSection(2, 0, 2),
                new BlockDataQueue.EvictedSection(2, 1, 2)), evicted);
        assertEquals(1, BlockDataQueue.pendingCount());
        assertEquals(maxBytes / 4 * 3, BlockDataQueue.pendingBytes());

        List<BlockDataQueue.QueuedCapture> drained = new ArrayList<>();
        while (BlockDataQueue.pendingCount() > 0) BlockDataQueue.drain(drained);
        assertEquals(List.of(incoming),
                drained.stream().map(BlockDataQueue.QueuedCapture::data).toList());

        // The extra heads' item slots went back to the pool, so a full
        // complement of captures must fit again without another eviction.
        for (int i = 0; i < BlockDataQueue.maxPendingItemsForTest(); i++) {
            BlockDataQueue.enqueue(emptyCapture(2, i, 2), capture);
        }
        assertEquals(0, BlockDataQueue.drainBackpressureEvictions());
        assertEquals(BlockDataQueue.maxPendingItemsForTest(), BlockDataQueue.pendingCount());
    }

    @Test
    void droppedCapturesReleaseTheirMeshData() {
        BlockDataQueue.CaptureToken capture = BlockDataQueue.beginCapture(3, 3);

        SectionBlockData unloaded = meshCapture(3, 0, 3);
        BlockDataQueue.markChunkUnloaded(3, 3);
        BlockDataQueue.enqueue(unloaded, capture);
        assertThrows(IllegalStateException.class, unloaded.meshData()::surfaces,
                "a capture rejected at enqueue is released");

        long maxBytes = BlockDataQueue.maxPendingBytesForTest();
        BlockDataQueue.CaptureToken live = BlockDataQueue.beginCapture(4, 4);
        SectionBlockData evicted = meshCapture(4, 0, 4);
        BlockDataQueue.enqueue(evicted, live);
        BlockDataQueue.enqueue(captureOfBytes(4, 1, 4, maxBytes / 4 * 3), live);
        BlockDataQueue.setPublishWaitBudgetNsForTest(PROMPT_EVICTION_BUDGET_NS);
        BlockDataQueue.enqueue(captureOfBytes(4, 2, 4, maxBytes / 2), live);
        assertThrows(IllegalStateException.class, evicted.meshData()::surfaces,
                "an evicted head is released by the evicting producer");

        SectionBlockData queued = meshCapture(4, 3, 4);
        BlockDataQueue.enqueue(queued, live);
        BlockDataQueue.clear();
        assertThrows(IllegalStateException.class, queued.meshData()::surfaces,
                "world unload releases everything still queued");
    }

    private static SectionBlockData meshCapture(int cx, int sy, int cz) {
        ByteBuffer vertices = ByteBuffer.allocate(4 * HardcodedVertex.STRIDE)
                .order(ByteOrder.LITTLE_ENDIAN);
        SectionMeshData meshData = new SectionMeshData(List.of(new SectionMeshData.Surface(
                ChunkManager.CAT_SOLID, "solid", RedstoneEmission.NONE, vertices, 4, 6, true)));
        return new SectionBlockData(
                ChunkSectionPos.from(cx, sy, cz), meshData, List.of(),
                0, 0, 0, 0, 0, 0, null);
    }

    private static boolean awaitBackpressure(long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (BlockDataQueue.drainBackpressureWaits() > 0) return true;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        return false;
    }

    private static SectionBlockData emptyCapture(int cx, int sy, int cz) {
        return new SectionBlockData(
                ChunkSectionPos.from(cx, sy, cz), null, List.of(),
                0, 0, 0, 0, 0, 0, null);
    }

    /**
     * A capture whose byte estimate is exactly {@code bytes} (a multiple of
     * 128): the queue charges 128 bytes per light entry, and nCopies is a
     * constant-size view, so a byte-bound queue costs no real memory.
     */
    private static SectionBlockData captureOfBytes(int cx, int sy, int cz, long bytes) {
        return new SectionBlockData(
                ChunkSectionPos.from(cx, sy, cz), null,
                Collections.<LightEntry>nCopies((int) (bytes / 128) - 1, null),
                0, 0, 0, 0, 0, 0, null);
    }
}

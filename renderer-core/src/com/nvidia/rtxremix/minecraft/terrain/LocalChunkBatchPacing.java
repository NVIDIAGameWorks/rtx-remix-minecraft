/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.google.common.collect.Comparators;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * Paces the integrated server's chunk delivery to the local client.
 *
 * <p>Vanilla's {@code ChunkDataSender} treats a local connection as free: every
 * chunk pending for the player goes out in one batch each tick, and the rate the
 * client requests is ignored. The client applies a batch in a single
 * {@code MinecraftClient.runTasks} drain on the render thread, so a chunk-boundary
 * crossing at render distance 32 lands a 65-column row (and world join the whole
 * disc) in one frame: the deserialization alone is several milliseconds, and the
 * section rebuilds and Remix captures it triggers arrive as one wave over the
 * following frames. A remote server would have sent at most 64 nearest chunks
 * per tick.
 *
 * <p>The batch is sized from what a chunk actually costs the render thread to
 * apply, measured around {@code ClientChunkManager.loadChunkFromPacket} and kept
 * as a moving average. Vanilla's own request ({@code ChunkBatchSizeCalculator})
 * cannot serve here: on a local connection it times the netty thread's dispatch
 * of the burst, not the apply, so it saturates at the 64 clamp on any machine.
 * A frontier batch is the smaller of the chunks that fit a small apply budget
 * per tick and a chunk cap: the budget protects a slow machine (about 8 per tick
 * on an N1X at 130 us per chunk), the cap keeps a row spread over several ticks
 * where the apply is cheap, because the rebuilds and captures a row triggers cost
 * the frame far more than its deserialization. A large backlog is sent as a
 * share of what remains, bounded the same two ways by a larger backlog budget
 * and cap, so a join disc still streams in over a couple of seconds, nearest
 * chunks first. Between the bounds the share is a tenth of the remainder, so
 * that band decays geometrically. As a model, not a measurement: with every
 * chunk ready, no new arrivals and one batch per tick, a render-distance-32
 * join area (at most 4225 columns) drains in about 40 ticks (two seconds) at
 * the caps, and in about 75 ticks at 130 us per chunk, where the budgets bind
 * instead.
 *
 * <p>Delivery is one tick later per batch, which is invisible at walking speed;
 * in sustained flight the pending set grows until its share exceeds the floor,
 * so the rate rises with the backlog rather than falling behind at a fixed cap.
 *
 * <p>Unloads are paced the same way. Vanilla writes an {@code UnloadChunkS2CPacket}
 * the moment a column leaves the player's chunk filter, so a crossing lands the
 * trailing row's 65 unloads in the same client drain as the chunk data. The sender
 * queues them ({@link PacedUnloadQueue}) and drains the queue once per tick under a
 * budget of its own, sized from the measured cost of an unload the client actually
 * performs; a column re-added while its unload is queued cancels the unload, and a
 * column that has reached the last band of the client's chunk ring is sent that tick
 * regardless of budget, because the client ignores an unload beyond the ring.
 * Unloads have no chunk cap: nothing costly follows one on the client. The two
 * budgets are independent and therefore add up: a crossing tick brings a leading
 * row of chunk data and a trailing row of unloads together, so it may carry up to
 * both allowances of estimated render-thread work. They are estimates from moving
 * averages, rounded up to whole chunks and overridden by the ring's last band, not
 * a bound on one client drain.
 *
 * <p>Kill switch: {@code -Drtxremix.chunk.localBatchPacing=false} restores
 * vanilla's single batch and immediate unloads. {@code -Drtxremix.chunk.localBatchBudgetMs}
 * (render-thread apply time per tick for a frontier batch),
 * {@code -Drtxremix.chunk.localBacklogBudgetMs} (the same for a large backlog),
 * {@code -Drtxremix.chunk.localBatchMaxChunks} and
 * {@code -Drtxremix.chunk.localBacklogMaxChunks} (the two chunk caps) and
 * {@code -Drtxremix.chunk.localBatchBacklogDivisor} (the share of the remaining
 * backlog per tick) tune delivery; {@code -Drtxremix.chunk.localUnloadPacing=false}
 * restores immediate unloads alone and {@code -Drtxremix.chunk.localUnloadBudgetMs}
 * (render-thread unload time per tick) tunes them, with the backlog budget and
 * divisor shared. A value below its minimum is reported and replaced by the
 * default rather than honoured.
 */
public final class LocalChunkBatchPacing {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalChunkBatchPacing.class);
    private static final String ENABLED_PROPERTY = "rtxremix.chunk.localBatchPacing";
    private static final String BATCH_BUDGET_PROPERTY = "rtxremix.chunk.localBatchBudgetMs";
    private static final String BACKLOG_BUDGET_PROPERTY = "rtxremix.chunk.localBacklogBudgetMs";
    private static final String BACKLOG_DIVISOR_PROPERTY = "rtxremix.chunk.localBatchBacklogDivisor";
    private static final String BATCH_MAX_CHUNKS_PROPERTY = "rtxremix.chunk.localBatchMaxChunks";
    private static final String BACKLOG_MAX_CHUNKS_PROPERTY = "rtxremix.chunk.localBacklogMaxChunks";
    private static final String UNLOAD_ENABLED_PROPERTY = "rtxremix.chunk.localUnloadPacing";
    private static final String UNLOAD_BUDGET_PROPERTY = "rtxremix.chunk.localUnloadBudgetMs";
    private static final double DEFAULT_BATCH_BUDGET_MS = 1.0;
    private static final double DEFAULT_BACKLOG_BUDGET_MS = 10.0;
    private static final int DEFAULT_BACKLOG_DIVISOR = 10;
    /**
     * About the frontier batch the time budget alone produces on a desktop when each
     * apply is expensive, kept as the spread even where the apply is cheap.
     */
    private static final int DEFAULT_BATCH_MAX_CHUNKS = 24;
    private static final int DEFAULT_BACKLOG_MAX_CHUNKS = 240;
    private static final double DEFAULT_UNLOAD_BUDGET_MS = 1.0;
    /** Assumed apply cost until the first chunk has been measured: between a desktop and an N1X. */
    static final long PRIOR_APPLY_NANOS = 100_000L;
    /** Assumed cost of a real unload until the first has been measured, on the same footing. */
    static final long PRIOR_UNLOAD_NANOS = 30_000L;

    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"));
    private static final double BATCH_BUDGET_MS =
            positiveOrDefault(BATCH_BUDGET_PROPERTY, System.getProperty(BATCH_BUDGET_PROPERTY), DEFAULT_BATCH_BUDGET_MS);
    private static final double BACKLOG_BUDGET_MS =
            positiveOrDefault(BACKLOG_BUDGET_PROPERTY, System.getProperty(BACKLOG_BUDGET_PROPERTY), DEFAULT_BACKLOG_BUDGET_MS);
    private static final int BACKLOG_DIVISOR =
            countOrDefault(BACKLOG_DIVISOR_PROPERTY, System.getProperty(BACKLOG_DIVISOR_PROPERTY), DEFAULT_BACKLOG_DIVISOR);
    private static final int BATCH_MAX_CHUNKS =
            countOrDefault(BATCH_MAX_CHUNKS_PROPERTY, System.getProperty(BATCH_MAX_CHUNKS_PROPERTY), DEFAULT_BATCH_MAX_CHUNKS);
    private static final int BACKLOG_MAX_CHUNKS =
            countOrDefault(BACKLOG_MAX_CHUNKS_PROPERTY, System.getProperty(BACKLOG_MAX_CHUNKS_PROPERTY), DEFAULT_BACKLOG_MAX_CHUNKS);

    public static final boolean UNLOAD_ENABLED = unloadEnabledFrom(ENABLED, System.getProperty(UNLOAD_ENABLED_PROPERTY));
    private static final double UNLOAD_BUDGET_MS =
            positiveOrDefault(UNLOAD_BUDGET_PROPERTY, System.getProperty(UNLOAD_BUDGET_PROPERTY), DEFAULT_UNLOAD_BUDGET_MS);

    private static final CostEstimate APPLY_COST = new CostEstimate(PRIOR_APPLY_NANOS);
    private static final CostEstimate UNLOAD_COST = new CostEstimate(PRIOR_UNLOAD_NANOS);

    // Produced on the integrated-server thread, read into the per-frame terrain
    // counters on the render thread: chunks and unloads sent (per-frame sums), the
    // backlog the last paced batch left and the unload queue left after the last
    // tick's drain (gauges).
    private static final AtomicInteger PACED_CHUNKS = new AtomicInteger();
    private static final AtomicInteger BACKLOG = new AtomicInteger();
    private static final AtomicInteger PACED_UNLOADS = new AtomicInteger();
    private static final AtomicInteger UNLOAD_BACKLOG = new AtomicInteger();

    private LocalChunkBatchPacing() {
    }

    /** Records one render-thread chunk apply; called from the client chunk manager. */
    public static void recordApplyNanos(long nanos) {
        APPLY_COST.record(nanos);
    }

    /**
     * Records one render-thread unload of a column the client actually held; called
     * from the client chunk manager. An unload of an absent column costs nothing and
     * must not be recorded, or a view-distance decrease would drive the estimate to
     * zero and the next trailing row would go out whole.
     */
    public static void recordUnloadNanos(long nanos) {
        UNLOAD_COST.record(nanos);
    }

    /** Chunks to send this tick out of {@code pending}, at the measured apply cost and the configured bounds. */
    public static int batchSize(int pending) {
        return batchSize(pending, APPLY_COST.averageNanos(), BATCH_BUDGET_MS, BACKLOG_BUDGET_MS, BACKLOG_DIVISOR,
                BATCH_MAX_CHUNKS, BACKLOG_MAX_CHUNKS);
    }

    /**
     * The share {@code pending / backlogDivisor} of the backlog, clamped between a
     * floor and a ceiling. The floor is the smaller of the chunks whose apply fits
     * {@code batchBudgetMs} at {@code applyNanosPerChunk} each and {@code batchMaxChunks};
     * the ceiling likewise from {@code backlogBudgetMs} and {@code backlogMaxChunks},
     * never below the floor. The result never exceeds what is pending and never drops
     * below one while anything is pending.
     */
    static int batchSize(int pending, double applyNanosPerChunk, double batchBudgetMs, double backlogBudgetMs,
                         int backlogDivisor, int batchMaxChunks, int backlogMaxChunks) {
        if (pending <= 0) {
            return 0;
        }
        int floor = Math.max(1, Math.min(chunksFor(applyNanosPerChunk, batchBudgetMs), batchMaxChunks));
        int ceiling = Math.max(floor, Math.min(chunksFor(applyNanosPerChunk, backlogBudgetMs), backlogMaxChunks));
        int share = 1 + (pending - 1) / backlogDivisor;
        return Math.min(pending, Math.max(floor, Math.min(ceiling, share)));
    }

    /** {@link #batchSize(int, double, double, double, int, int, int)} without chunk caps. */
    static int batchSize(int pending, double nanosPerChunk, double batchBudgetMs, double backlogBudgetMs,
                         int backlogDivisor) {
        return batchSize(pending, nanosPerChunk, batchBudgetMs, backlogBudgetMs, backlogDivisor,
                Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    private static int chunksFor(double applyNanosPerChunk, double budgetMs) {
        return (int) Math.ceil(budgetMs * 1_000_000.0 / applyNanosPerChunk);
    }

    /** Unloads to send this tick out of {@code queued}, at the measured cost of a real unload. */
    public static int unloadBatchSize(int queued) {
        return batchSize(queued, UNLOAD_COST.averageNanos(), UNLOAD_BUDGET_MS, BACKLOG_BUDGET_MS, BACKLOG_DIVISOR);
    }

    /**
     * The radius of the client's chunk ring at a server view distance: the client keeps
     * columns up to this chebyshev distance from its centre and ignores an unload for
     * any column beyond it. Mirrors {@code ClientChunkManager.getChunkMapRadius}.
     */
    public static int clientRingRadius(int serverViewDistance) {
        return Math.max(2, serverViewDistance) + 3;
    }

    /**
     * Removes the nearest {@code batch} ready chunks from {@code pending} and returns
     * them nearest first. Only ready chunks compete for the batch, so an unready near
     * column cannot starve the ready ones behind it; unready and unselected keys stay
     * pending for the next tick. Equal distances have no defined order.
     *
     * @param pending         the sender's pending chunk keys, mutated in place
     * @param batch           how many chunks to take
     * @param readyChunk      the ready chunk for a key, or null while it is still generating
     * @param key             a chunk's pending key
     * @param squaredDistance a chunk's squared chunk distance from the player
     */
    public static <T> List<T> takeNearest(LongSet pending, int batch, LongFunction<T> readyChunk,
                                          ToLongFunction<T> key, ToIntFunction<T> squaredDistance) {
        List<T> selected = pending.longStream()
                .mapToObj(readyChunk)
                .filter(Objects::nonNull)
                .collect(Comparators.least(batch, Comparator.comparingInt(squaredDistance)));
        for (T chunk : selected) {
            pending.remove(key.applyAsLong(chunk));
        }
        return selected;
    }

    /** Records one paced batch: the chunks it carried and the backlog it leaves behind. */
    public static void recordBatch(int sent, int backlogAfter) {
        PACED_CHUNKS.addAndGet(sent);
        BACKLOG.set(backlogAfter);
    }

    /** Returns and resets the chunks delivered by paced batches, for the per-frame terrain counters. */
    public static int drainPacedChunks() {
        return PACED_CHUNKS.getAndSet(0);
    }

    /** The backlog the last paced batch left pending, for the per-frame terrain counters. */
    public static int pacedBacklog() {
        return BACKLOG.get();
    }

    /** The measured render-thread apply cost per chunk in microseconds, for the per-frame terrain counters. */
    public static int applyMicrosPerChunk() {
        return (int) Math.round(APPLY_COST.averageNanos() / 1_000.0);
    }

    /** Records one tick's unload drain: the unloads it sent and the queue it leaves behind, zero while the queue is empty. */
    public static void recordUnloadBatch(int sent, int backlogAfter) {
        PACED_UNLOADS.addAndGet(sent);
        UNLOAD_BACKLOG.set(backlogAfter);
    }

    /** Returns and resets the unloads delivered by paced batches, for the per-frame terrain counters. */
    public static int drainPacedUnloads() {
        return PACED_UNLOADS.getAndSet(0);
    }

    /** The unloads left queued after the last tick's drain, for the per-frame terrain counters. */
    public static int pacedUnloadBacklog() {
        return UNLOAD_BACKLOG.get();
    }

    /** The measured render-thread cost of a real unload in microseconds, for the per-frame terrain counters. */
    public static int unloadMicrosPerChunk() {
        return (int) Math.round(UNLOAD_COST.averageNanos() / 1_000.0);
    }

    /** One startup log line that makes every run self-describing. */
    public static String effectiveStateLine() {
        if (!ENABLED) {
            return "Local chunk batch pacing: off, vanilla single batch and immediate unloads ("
                    + ENABLED_PROPERTY + "=false)";
        }
        String unloads = UNLOAD_ENABLED
                ? ", unloads " + UNLOAD_BUDGET_MS + " ms of measured unload per tick (" + UNLOAD_ENABLED_PROPERTY + "=true)"
                : ", unloads immediate (" + UNLOAD_ENABLED_PROPERTY + "=false)";
        return "Local chunk batch pacing: permitted while RTX capture is on, " + BATCH_BUDGET_MS
                + " ms of measured chunk apply or " + BATCH_MAX_CHUNKS + " chunks per tick, backlog share 1/"
                + BACKLOG_DIVISOR + " up to " + BACKLOG_BUDGET_MS + " ms or " + BACKLOG_MAX_CHUNKS
                + " chunks (" + ENABLED_PROPERTY + "=true)" + unloads;
    }

    /** Unload pacing follows its own switch but never outlives the master switch. */
    static boolean unloadEnabledFrom(boolean masterEnabled, String raw) {
        return masterEnabled && (raw == null || Boolean.parseBoolean(raw));
    }

    /** A positive, finite duration knob; anything else is reported and replaced by the default. */
    static double positiveOrDefault(String property, String raw, double fallback) {
        if (raw == null) {
            return fallback;
        }
        double value = parseDoubleOrNaN(raw);
        if (value > 0.0 && Double.isFinite(value)) {
            return value;
        }
        LOGGER.warn("{}={} is not a positive number; using {}", property, raw, fallback);
        return fallback;
    }

    /** A count knob of at least one; anything else is reported and replaced by the default. */
    static int countOrDefault(String property, String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        int value = parseIntOrZero(raw);
        if (value >= 1) {
            return value;
        }
        LOGGER.warn("{}={} is not a count of at least one; using {}", property, raw, fallback);
        return fallback;
    }

    private static double parseDoubleOrNaN(String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static int parseIntOrZero(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Exponential moving average of a render-thread cost per chunk, an apply or an
     * unload. One writer (the render thread) and one reader (the integrated-server
     * thread), so a volatile field is the whole synchronisation. A sample is capped
     * so that a stall inside one operation (a GC pause, a page fault) does not read
     * as a cost.
     */
    static final class CostEstimate {
        static final double WEIGHT = 0.1;
        static final long MAX_SAMPLE_NANOS = 5_000_000L;

        private volatile double averageNanos;

        CostEstimate(long priorNanos) {
            averageNanos = priorNanos;
        }

        void record(long nanos) {
            double sample = Math.min(Math.max(nanos, 0L), MAX_SAMPLE_NANOS);
            averageNanos += WEIGHT * (sample - averageNanos);
        }

        double averageNanos() {
            return averageNanos;
        }
    }
}

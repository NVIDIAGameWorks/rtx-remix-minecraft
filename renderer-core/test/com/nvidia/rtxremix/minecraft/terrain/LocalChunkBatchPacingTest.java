/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalChunkBatchPacingTest {

    /** Render-thread apply cost of one chunk on the 7950X with vanilla's full surface scan. */
    private static final double DESKTOP_APPLY_NANOS = 43_000;
    /** The same on an N1X laptop. */
    private static final double N1X_APPLY_NANOS = 130_000;
    /** Roughly what the seeded surface scan leaves of the desktop apply. */
    private static final double SEEDED_DESKTOP_APPLY_NANOS = 10_000;

    private static int batch(int pending, double applyNanos) {
        return LocalChunkBatchPacing.batchSize(pending, applyNanos, 1.0, 10.0, 10, 24, 240);
    }

    @Test
    void frontierRowIsPacedToTheApplyBudget() {
        // A 65-column row at the 1 ms budget with the caps out of the way: 1 ms / 43 us
        // -> 24 per tick on the desktop, 1 ms / 130 us -> 8 per tick on an N1X.
        assertEquals(24, LocalChunkBatchPacing.batchSize(65, DESKTOP_APPLY_NANOS, 1.0, 10.0, 10, 1000, 1000));
        assertEquals(8, LocalChunkBatchPacing.batchSize(65, N1X_APPLY_NANOS, 1.0, 10.0, 10, 1000, 1000));
    }

    @Test
    void frontierBatchIsCappedInChunksWhenTheApplyIsCheap() {
        // At 10 us the budget alone would admit 100 chunks, the whole row in one tick;
        // the cap keeps the spread the row-crossing A/B validated.
        assertEquals(24, batch(65, SEEDED_DESKTOP_APPLY_NANOS));
    }

    @Test
    void largeBacklogIsCappedByTheBacklogBudget() {
        // The join disc (4225 columns): a tenth per tick would be 423, but the 10 ms
        // backlog budget caps it at 233 on the desktop and 77 on an N1X.
        assertEquals(233, batch(4225, DESKTOP_APPLY_NANOS));
        assertEquals(77, batch(4225, N1X_APPLY_NANOS));
    }

    @Test
    void largeBacklogIsCappedInChunksWhenTheApplyIsCheap() {
        // At 10 us the backlog budget alone would admit 1000; the chunk cap holds it at 240.
        assertEquals(240, batch(4225, SEEDED_DESKTOP_APPLY_NANOS));
    }

    @Test
    void backlogShareRulesBetweenTheTwoBudgets() {
        assertEquals(30, batch(300, DESKTOP_APPLY_NANOS));
        assertEquals(30, batch(300, N1X_APPLY_NANOS));
    }

    @Test
    void neverExceedsWhatIsPendingAndAlwaysSendsOne() {
        assertEquals(5, batch(5, DESKTOP_APPLY_NANOS));
        assertEquals(0, batch(0, DESKTOP_APPLY_NANOS));
        // A machine whose apply costs 5 ms per chunk still gets one (two under the backlog budget).
        assertEquals(2, batch(65, 5_000_000));
        assertEquals(1, batch(65, 50_000_000));
    }

    @Test
    void chunkCapsNeverLowerTheCeilingBelowTheFloor() {
        // A backlog cap below the frontier cap cannot invert the clamp.
        assertEquals(24, LocalChunkBatchPacing.batchSize(4225, SEEDED_DESKTOP_APPLY_NANOS, 1.0, 10.0, 10, 24, 10));
    }

    @Test
    void shippedDefaultsAndPriorGiveTenPerTickUntilMeasured() {
        // Nothing has been measured in the test JVM, so the 100 us prior is in force:
        // 1 ms / 100 us -> 10 per tick, 10 ms -> 100 for a backlog.
        assertEquals(10, LocalChunkBatchPacing.batchSize(65));
        assertEquals(100, LocalChunkBatchPacing.batchSize(4225));
    }

    @Test
    void costEstimateStartsAtThePriorAndFollowsSamples() {
        LocalChunkBatchPacing.CostEstimate estimate = new LocalChunkBatchPacing.CostEstimate(100_000);
        assertEquals(100_000, estimate.averageNanos());
        for (int i = 0; i < 50; i++) {
            estimate.record(40_000);
        }
        assertTrue(Math.abs(estimate.averageNanos() - 40_000) < 1_000);
    }

    @Test
    void costEstimateCapsAStallSample() {
        LocalChunkBatchPacing.CostEstimate estimate = new LocalChunkBatchPacing.CostEstimate(100_000);
        estimate.record(500_000_000L);
        LocalChunkBatchPacing.CostEstimate capped = new LocalChunkBatchPacing.CostEstimate(100_000);
        capped.record(LocalChunkBatchPacing.CostEstimate.MAX_SAMPLE_NANOS);
        assertEquals(capped.averageNanos(), estimate.averageNanos());
    }

    @Test
    void knobsBelowTheirMinimumFallBackToTheDefault() {
        assertEquals(1.0, LocalChunkBatchPacing.positiveOrDefault("k", null, 1.0));
        assertEquals(2.5, LocalChunkBatchPacing.positiveOrDefault("k", "2.5", 1.0));
        assertEquals(1.0, LocalChunkBatchPacing.positiveOrDefault("k", "0", 1.0));
        assertEquals(1.0, LocalChunkBatchPacing.positiveOrDefault("k", "-3", 1.0));
        assertEquals(1.0, LocalChunkBatchPacing.positiveOrDefault("k", "fast", 1.0));
        assertEquals(1.0, LocalChunkBatchPacing.positiveOrDefault("k", "Infinity", 1.0));
        assertEquals(1.0, LocalChunkBatchPacing.positiveOrDefault("k", "NaN", 1.0));
        assertEquals(10, LocalChunkBatchPacing.countOrDefault("k", null, 10));
        assertEquals(4, LocalChunkBatchPacing.countOrDefault("k", "4", 10));
        assertEquals(10, LocalChunkBatchPacing.countOrDefault("k", "0", 10));
        assertEquals(10, LocalChunkBatchPacing.countOrDefault("k", "-1", 10));
        assertEquals(10, LocalChunkBatchPacing.countOrDefault("k", "ten", 10));
    }

    /** Client cost of one real unload: about 10 us on the 7950X (scaled), about 40 us on an N1X. */
    private static final double DESKTOP_UNLOAD_NANOS = 10_000;
    private static final double N1X_UNLOAD_NANOS = 40_000;

    private static int unloadBatch(int queued, double unloadNanos) {
        return LocalChunkBatchPacing.batchSize(queued, unloadNanos, 1.0, 10.0, 10);
    }

    @Test
    void unloadRowGoesWholeWhereTheUnloadIsCheap() {
        // A 65-column trailing row fits the 1 ms budget at 10 us with room to spare, and
        // unloads have no chunk cap: nothing costly follows an unload on the client.
        assertEquals(65, unloadBatch(65, DESKTOP_UNLOAD_NANOS));
    }

    @Test
    void unloadRowIsSpreadWhereTheUnloadIsDear() {
        assertEquals(25, unloadBatch(65, N1X_UNLOAD_NANOS));
        assertEquals(34, unloadBatch(65, LocalChunkBatchPacing.PRIOR_UNLOAD_NANOS));
    }

    @Test
    void bulkUnloadsFollowTheBacklogRules() {
        assertEquals(250, unloadBatch(4225, N1X_UNLOAD_NANOS));
        assertEquals(423, unloadBatch(4225, DESKTOP_UNLOAD_NANOS));
    }

    @Test
    void shippedUnloadDefaultsAndPriorGiveThirtyFourPerTickUntilMeasured() {
        // Nothing has been measured in the test JVM, so the 30 us prior is in force:
        // 1 ms / 30 us -> 34 per tick, 10 ms -> 334 for a backlog.
        assertEquals(34, LocalChunkBatchPacing.unloadBatchSize(65));
        assertEquals(334, LocalChunkBatchPacing.unloadBatchSize(4225));
    }

    @Test
    void clientRingRadiusMirrorsTheClientChunkMap() {
        assertEquals(35, LocalChunkBatchPacing.clientRingRadius(32));
        assertEquals(11, LocalChunkBatchPacing.clientRingRadius(8));
        assertEquals(5, LocalChunkBatchPacing.clientRingRadius(2));
        assertEquals(5, LocalChunkBatchPacing.clientRingRadius(1));
    }

    @Test
    void unloadPacingFollowsTheMasterSwitch() {
        assertTrue(LocalChunkBatchPacing.unloadEnabledFrom(true, null));
        assertTrue(LocalChunkBatchPacing.unloadEnabledFrom(true, "true"));
        assertFalse(LocalChunkBatchPacing.unloadEnabledFrom(true, "false"));
        assertFalse(LocalChunkBatchPacing.unloadEnabledFrom(false, null));
        assertFalse(LocalChunkBatchPacing.unloadEnabledFrom(false, "true"));
    }

    /** A ready chunk: its pending key and squared distance from the player. A key absent from the ready map is still generating. */
    private record Column(long key, int squaredDistance) {
    }

    private static Map<Long, Column> ready(long... keys) {
        Map<Long, Column> map = new HashMap<>();
        for (long key : keys) {
            map.put(key, new Column(key, (int) key));
        }
        return map;
    }

    private static List<Column> take(LongSet pending, int batch, Map<Long, Column> readyColumns) {
        return LocalChunkBatchPacing.takeNearest(pending, batch, readyColumns::get, Column::key, Column::squaredDistance);
    }

    @Test
    void takesTheNearestReadyChunksAndRemovesExactlyThose() {
        // Keys double as squared distances; 1 is pending but not ready yet.
        LongSet pending = new LongOpenHashSet(new long[]{7, 1, 3, 9, 5});
        List<Column> taken = take(pending, 2, ready(3, 5, 7, 9));
        assertEquals(List.of(3L, 5L), taken.stream().map(Column::key).toList());
        assertEquals(new LongOpenHashSet(new long[]{1, 7, 9}), pending);
    }

    @Test
    void returnsFewerThanTheBudgetWhenFewAreReady() {
        LongSet pending = new LongOpenHashSet(new long[]{2, 4, 6, 8});
        List<Column> taken = take(pending, 3, ready(4, 8));
        assertEquals(List.of(4L, 8L), taken.stream().map(Column::key).toList());
        assertEquals(new LongOpenHashSet(new long[]{2, 6}), pending);
    }

    @Test
    void leavesThePendingSetUntouchedWhenNothingIsReady() {
        LongSet pending = new LongOpenHashSet(new long[]{2, 4});
        assertTrue(take(pending, 3, ready()).isEmpty());
        assertEquals(new LongOpenHashSet(new long[]{2, 4}), pending);
    }

    @Test
    void aChunkThatBecomesReadyIsTakenOnALaterCall() {
        LongSet pending = new LongOpenHashSet(new long[]{2, 4});
        assertEquals(List.of(4L), take(pending, 1, ready(4)).stream().map(Column::key).toList());
        assertEquals(List.of(2L), take(pending, 1, ready(2)).stream().map(Column::key).toList());
        assertTrue(pending.isEmpty());
    }
}

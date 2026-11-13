/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSlotMoveLogTest {

    @Test
    void replayVisitsEachRecordedMoveOnceInOrder() {
        ChunkSlotMoveLog log = new ChunkSlotMoveLog(8);
        long before = log.sequence();
        log.record(7);
        log.record(3);
        log.record(7);

        List<Integer> replayed = new ArrayList<>();
        assertTrue(log.replay(before, log.sequence(), replayed::add));
        assertEquals(List.of(7, 3, 7), replayed);
    }

    @Test
    void replayOfAnEmptyRangeVisitsNothing() {
        ChunkSlotMoveLog log = new ChunkSlotMoveLog(8);
        log.record(1);
        List<Integer> replayed = new ArrayList<>();
        assertTrue(log.replay(log.sequence(), log.sequence(), replayed::add));
        assertEquals(List.of(), replayed);
    }

    @Test
    void wrappedRingStillReplaysTheMostRecentCapacityMoves() {
        ChunkSlotMoveLog log = new ChunkSlotMoveLog(4);
        for (int slot = 0; slot < 10; slot++) {
            log.record(slot);
        }

        List<Integer> replayed = new ArrayList<>();
        assertTrue(log.replay(log.sequence() - 4, log.sequence(), replayed::add));
        assertEquals(List.of(6, 7, 8, 9), replayed);
    }

    @Test
    void replayRefusesARangeWiderThanTheRing() {
        ChunkSlotMoveLog log = new ChunkSlotMoveLog(4);
        long before = log.sequence();
        for (int slot = 0; slot < 5; slot++) {
            log.record(slot);
        }

        List<Integer> replayed = new ArrayList<>();
        assertFalse(log.replay(before, log.sequence(), replayed::add),
                "an overwritten move must force the caller onto the full reconcile");
        assertEquals(List.of(), replayed, "a refused replay must not report a partial window");
    }

    @Test
    void capacityMustBeAPowerOfTwo() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkSlotMoveLog(6));
    }
}

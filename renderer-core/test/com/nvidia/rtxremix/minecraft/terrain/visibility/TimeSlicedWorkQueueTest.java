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

class TimeSlicedWorkQueueTest {

    /** A clock that advances by a fixed amount on every read, standing in for per-poll work. */
    private static final class SteppingClock {
        private long now;
        private final long step;

        private SteppingClock(long step) {
            this.step = step;
        }

        private long read() {
            now += step;
            return now;
        }
    }

    private static List<Integer> drain(TimeSlicedWorkQueue<Integer> queue) {
        List<Integer> polled = new ArrayList<>();
        while (!queue.isEmpty()) {
            polled.add(queue.poll());
        }
        return polled;
    }

    @Test
    void aSliceThatFitsTheBudgetDrainsEverythingInOrder() {
        SteppingClock clock = new SteppingClock(1);
        TimeSlicedWorkQueue<Integer> queue = new TimeSlicedWorkQueue<>(1_000, 1, clock::read);
        for (int i = 0; i < 10; i++) {
            queue.add(i);
        }

        queue.beginSlice();
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), drain(queue));
        assertFalse(queue.budgetHit());
        assertFalse(queue.hasPendingWork());
    }

    @Test
    void anExhaustedBudgetStopsTheSliceAndTheNextSliceResumesWhereItStopped() {
        // Each clock read costs 10 units; the budget of 45 allows about four polls per slice.
        SteppingClock clock = new SteppingClock(10);
        TimeSlicedWorkQueue<Integer> queue = new TimeSlicedWorkQueue<>(45, 1, clock::read);
        for (int i = 0; i < 10; i++) {
            queue.add(i);
        }

        queue.beginSlice();
        List<Integer> first = drain(queue);
        assertTrue(queue.budgetHit(), "the slice must report that it stopped on its budget");
        assertTrue(queue.hasPendingWork());
        assertTrue(first.size() < 10 && !first.isEmpty(), "polled " + first);

        List<Integer> all = new ArrayList<>(first);
        while (queue.hasPendingWork()) {
            queue.beginSlice();
            all.addAll(drain(queue));
        }
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), all, "no entry may be lost or reordered");
        assertFalse(queue.budgetHit(), "a slice that drained the queue did not hit its budget");
    }

    @Test
    void thePollFloorGuaranteesProgressEvenWhenTheBudgetIsAlreadyGone() {
        SteppingClock clock = new SteppingClock(1_000_000);
        TimeSlicedWorkQueue<Integer> queue = new TimeSlicedWorkQueue<>(1, 3, clock::read);
        for (int i = 0; i < 10; i++) {
            queue.add(i);
        }

        queue.beginSlice();
        assertEquals(List.of(0, 1, 2), drain(queue));
        assertTrue(queue.budgetHit());
        assertEquals(7, queue.size());
    }

    @Test
    void entriesAddedDuringASliceAreProcessedInSubsequentSlices() {
        SteppingClock clock = new SteppingClock(10);
        TimeSlicedWorkQueue<Integer> queue = new TimeSlicedWorkQueue<>(25, 1, clock::read);
        queue.add(1);
        queue.add(2);

        queue.beginSlice();
        List<Integer> polled = new ArrayList<>();
        while (!queue.isEmpty()) {
            int entry = queue.poll();
            polled.add(entry);
            if (entry == 1) {
                queue.add(3);
            }
        }
        while (queue.hasPendingWork()) {
            queue.beginSlice();
            polled.addAll(drain(queue));
        }
        assertEquals(List.of(1, 2, 3), polled);
    }

    @Test
    void anEmptyQueueIsEmptyWithoutClaimingABudgetHit() {
        SteppingClock clock = new SteppingClock(1_000_000);
        TimeSlicedWorkQueue<Integer> queue = new TimeSlicedWorkQueue<>(1, 1, clock::read);
        queue.beginSlice();
        assertTrue(queue.isEmpty());
        assertFalse(queue.budgetHit());
    }

    @Test
    void clearingTheQueueWithdrawsTheBudgetHitReport() {
        SteppingClock clock = new SteppingClock(1_000_000);
        TimeSlicedWorkQueue<Integer> queue = new TimeSlicedWorkQueue<>(1, 1, clock::read);
        queue.add(1);
        queue.add(2);

        queue.beginSlice();
        assertEquals(List.of(1), drain(queue));
        assertTrue(queue.budgetHit());

        queue.clear();
        assertFalse(queue.budgetHit(), "a cleared queue holds no work the slice could have stopped on");
        assertFalse(queue.hasPendingWork());
    }

    @Test
    void budgetAndFloorMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new TimeSlicedWorkQueue<>(0, 1, () -> 0));
        assertThrows(IllegalArgumentException.class, () -> new TimeSlicedWorkQueue<>(1, 0, () -> 0));
    }
}

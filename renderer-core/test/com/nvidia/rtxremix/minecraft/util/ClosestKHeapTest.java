/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClosestKHeapTest {

    private static Set<String> keptItems(ClosestKHeap<String> heap) {
        Set<String> kept = new HashSet<>();
        for (int i = 0; i < heap.size(); i++) {
            kept.add(heap.get(i));
        }
        return kept;
    }

    @Test
    void resetLowersTheBoundSoTheHeapItselfSelectsTheClosest() {
        // The bug this guards: taking the first k slots of a larger heap is not the closest k.
        // Heap order is not distance order, slot 0 is the farthest kept item, and the nearest
        // live in the leaves that a tail cut removes.
        ClosestKHeap<String> heap = new ClosestKHeap<>(256);
        heap.reset(3);

        heap.offer("far-a", 100.0);
        heap.offer("far-b", 90.0);
        heap.offer("far-c", 80.0);
        heap.offer("near-a", 1.0);
        heap.offer("near-b", 2.0);
        heap.offer("near-c", 3.0);

        assertEquals(3, heap.size(), "bound must be the reset value, not the constructed one");
        assertEquals(Set.of("near-a", "near-b", "near-c"), keptItems(heap));
        assertEquals(3, heap.overflowCount());
    }

    @Test
    void resetClampsToBackingCapacityAndClearsPreviousRound() {
        ClosestKHeap<String> heap = new ClosestKHeap<>(4);
        heap.offer("stale", 1.0);

        heap.reset(99);
        assertEquals(0, heap.size(), "reset must clear the previous round");
        for (int i = 0; i < 6; i++) {
            heap.offer("x" + i, i);
        }
        assertEquals(4, heap.size(), "must not exceed the backing arrays");

        heap.reset(0);
        heap.offer("only", 1.0);
        assertEquals(1, heap.size(), "a non-positive bound must still admit one item");
    }

    @Test
    void clearRestoresTheFullConstructedBound() {
        ClosestKHeap<String> heap = new ClosestKHeap<>(5);
        heap.reset(1);
        heap.offer("a", 1.0);
        assertEquals(1, heap.size());

        heap.clear();
        for (int i = 0; i < 5; i++) {
            heap.offer("y" + i, i);
        }
        assertEquals(5, heap.size(), "clear must not leave the lowered bound in place");
    }

    @Test
    void underCapacityTheHeapKeepsEverything() {
        ClosestKHeap<String> heap = new ClosestKHeap<>(8);
        heap.offer("a", 5.0);
        heap.offer("b", 1.0);
        heap.offer("c", 3.0);

        assertEquals(3, heap.size());
        assertEquals(0, heap.overflowCount());
        assertEquals(Set.of("a", "b", "c"), keptItems(heap));
    }

    @Test
    void overCapacityTheHeapKeepsTheClosestRegardlessOfOfferOrder() {
        double[] dists = {10, 3, 7, 1, 9, 2, 8, 5, 4, 6};
        ClosestKHeap<String> heap = new ClosestKHeap<>(4);
        for (double d : dists) {
            heap.offer("d" + (int) d, d);
        }

        assertEquals(4, heap.size());
        assertEquals(6, heap.overflowCount());
        assertEquals(Set.of("d1", "d2", "d3", "d4"), keptItems(heap));
    }

    @Test
    void ascendingThenDescendingOffersKeepTheClosest() {
        ClosestKHeap<String> ascending = new ClosestKHeap<>(3);
        for (int i = 1; i <= 6; i++) ascending.offer("a" + i, i);
        assertEquals(Set.of("a1", "a2", "a3"), keptItems(ascending));
        assertEquals(3, ascending.overflowCount());

        ClosestKHeap<String> descending = new ClosestKHeap<>(3);
        for (int i = 6; i >= 1; i--) descending.offer("d" + i, i);
        assertEquals(Set.of("d1", "d2", "d3"), keptItems(descending));
        assertEquals(3, descending.overflowCount());
    }

    @Test
    void aTieAtTheBoundaryDropsExactlyOne() {
        ClosestKHeap<String> heap = new ClosestKHeap<>(2);
        heap.offer("near", 1.0);
        heap.offer("tieA", 2.0);
        heap.offer("tieB", 2.0);

        assertEquals(2, heap.size());
        assertEquals(1, heap.overflowCount());
        Set<String> kept = keptItems(heap);
        assertTrue(kept.contains("near"));
        assertTrue(kept.contains("tieA") || kept.contains("tieB"));
    }

    @Test
    void clearResetsSizeAndOverflow() {
        ClosestKHeap<String> heap = new ClosestKHeap<>(2);
        heap.offer("a", 1.0);
        heap.offer("b", 2.0);
        heap.offer("c", 3.0);
        assertEquals(1, heap.overflowCount());

        heap.clear();
        assertEquals(0, heap.size());
        assertEquals(0, heap.overflowCount());

        heap.offer("x", 9.0);
        assertEquals(1, heap.size());
        assertEquals(Set.of("x"), keptItems(heap));
    }

    @Test
    void randomizedAgainstSortReference() {
        Random rng = new Random(42);
        for (int round = 0; round < 50; round++) {
            int capacity = 1 + rng.nextInt(20);
            int n = rng.nextInt(60);
            ClosestKHeap<Integer> heap = new ClosestKHeap<>(capacity);
            List<Double> dists = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                double d = rng.nextInt(1000); // ints so ties occur
                dists.add(d);
                heap.offer(i, d);
            }

            List<Double> sorted = new ArrayList<>(dists);
            sorted.sort(null);
            int expectedKept = Math.min(capacity, n);
            assertEquals(expectedKept, heap.size());
            assertEquals(n - expectedKept, heap.overflowCount());

            // Every kept distance must be <= the k-th smallest distance.
            double cutoff = expectedKept == 0 ? 0 : sorted.get(expectedKept - 1);
            for (int i = 0; i < heap.size(); i++) {
                assertTrue(dists.get(heap.get(i)) <= cutoff,
                        "kept item with dist " + dists.get(heap.get(i)) + " > cutoff " + cutoff);
            }
        }
    }

    @Test
    void anInvalidCapacityThrows() {
        assertThrows(IllegalArgumentException.class, () -> new ClosestKHeap<String>(0));
    }
}

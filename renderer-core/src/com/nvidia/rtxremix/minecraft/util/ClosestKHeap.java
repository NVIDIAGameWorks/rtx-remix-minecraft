/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.util;

/**
 * Bounded selection of the K items with the smallest distance, backed by a
 * fixed-size binary max-heap. Allocation-free after construction, intended
 * for per-frame reuse via {@link #clear()}.
 *
 * <p>Used by {@code EntitySweepPlanner} to pick the closest bounded set of
 * entities when more are in capture range than the per-frame cap allows.
 * Without this, capture order follows {@code world.getEntities()} insertion
 * order, which starves newly spawned entities (they iterate last) whenever
 * the cap is saturated.
 *
 * <p>Items are returned in arbitrary heap order via {@link #get(int)}; callers
 * that need distance ordering must sort themselves.
 *
 * @param <T> item type
 */
public final class ClosestKHeap<T> {

    private final double[] dist;
    private final Object[] items;
    private int capacity;
    private int size;
    private int overflow;

    public ClosestKHeap(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.dist = new double[capacity];
        this.items = new Object[capacity];
        this.capacity = capacity;
    }

    /** Resets for reuse. Drops item references so entities are not retained across frames. */
    public void clear() {
        for (int i = 0; i < size; i++) {
            items[i] = null;
        }
        size = 0;
        overflow = 0;
        capacity = dist.length;
    }

    /**
     * Resets and lowers the bound to {@code k} for this round, clamped to the backing arrays.
     * Callers whose budget is smaller than the constructed capacity must use this rather than
     * keeping the first {@code k} of a larger result: heap order is not distance order, so
     * slot 0 holds the farthest kept item and the nearest ones live in the leaves that a tail
     * cut removes.
     */
    public void reset(int k) {
        clear();
        capacity = Math.max(1, Math.min(k, dist.length));
    }

    /**
     * Offers an item. Kept if the heap has room or {@code distSq} beats the
     * current farthest kept item (which is then evicted). Each offer beyond
     * capacity (whether rejected or evicting) increments {@link #overflowCount()}
     * by exactly one: one in-range item that will not be kept.
     */
    public void offer(T item, double distSq) {
        if (size < capacity) {
            // Sift up from the new leaf.
            int i = size++;
            while (i > 0) {
                int parent = (i - 1) >> 1;
                if (dist[parent] >= distSq) break;
                dist[i] = dist[parent];
                items[i] = items[parent];
                i = parent;
            }
            dist[i] = distSq;
            items[i] = item;
            return;
        }

        overflow++;
        if (distSq >= dist[0]) {
            return; // farther than (or tied with) the current worst, so reject
        }

        // Replace the root (current farthest) and sift down.
        int i = 0;
        int half = size >> 1;
        while (i < half) {
            int child = (i << 1) + 1;
            int right = child + 1;
            if (right < size && dist[right] > dist[child]) child = right;
            if (dist[child] <= distSq) break;
            dist[i] = dist[child];
            items[i] = items[child];
            i = child;
        }
        dist[i] = distSq;
        items[i] = item;
    }

    /** Number of items currently kept. */
    public int size() {
        return size;
    }

    /** Number of offers since {@link #clear()} that exceeded capacity (= items not kept). */
    public int overflowCount() {
        return overflow;
    }

    /** Returns the kept item at heap slot {@code index} (arbitrary order). */
    @SuppressWarnings("unchecked")
    public T get(int index) {
        return (T) items[index];
    }
}

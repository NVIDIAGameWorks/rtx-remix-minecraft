/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.util.ClosestKHeap;

/**
 * Allocation-free closest-first selection for entities omitted by the host's
 * visible traversal.
 *
 * <p>Entities that own a scene light bypass the distance cull and rank ahead
 * of every ordinary entity, so a saturated capture budget cannot submit an
 * emitter without its housing.</p>
 */
public final class EntitySweepPlanner<T> {
    public static final int DEFAULT_MAX_ENTITIES = 256;

    private final int maxEntities;
    private final ClosestKHeap<T> heap;
    private boolean accepting;

    public EntitySweepPlanner(int maxEntities) {
        if (maxEntities < 1) {
            throw new IllegalArgumentException("maxEntities must be positive");
        }
        this.maxEntities = maxEntities;
        this.heap = new ClosestKHeap<T>(maxEntities);
    }

    /**
     * Resets the planner to the frame budget left after visible captures.
     *
     * @return whether any off-frustum candidate can be admitted
     */
    public boolean begin(int visibleCaptureCount) {
        int budget = maxEntities - visibleCaptureCount;
        if (budget <= 0) {
            heap.clear();
            accepting = false;
            return false;
        }
        heap.reset(budget);
        accepting = true;
        return true;
    }

    public void offer(T candidate, double distanceSquared, double maxDistanceSquared,
                      boolean ownsLight) {
        if (accepting && admits(distanceSquared, maxDistanceSquared, ownsLight)) {
            heap.offer(candidate, priority(distanceSquared, ownsLight));
        }
    }

    public int size() {
        return heap.size();
    }

    public T get(int index) {
        return heap.get(index);
    }

    public int overflowCount() {
        return heap.overflowCount();
    }

    public void clear() {
        heap.clear();
        accepting = false;
    }

    public static boolean admits(
            double distanceSquared,
            double maxDistanceSquared,
            boolean ownsLight) {
        return ownsLight || distanceSquared <= maxDistanceSquared;
    }

    /**
     * Lower values win. A light owner ranks ahead of every non-owner, while
     * owners and non-owners each retain nearest-first ordering.
     */
    public static double priority(double distanceSquared, boolean ownsLight) {
        return ownsLight ? -1.0 / (1.0 + distanceSquared) : distanceSquared;
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Recycles the {@code long[]} storage behind the block-state copies a render region snapshot
 * takes. Vanilla snapshots every section of up to nine chunks on the render thread for each
 * rebuild it schedules, and the region lives only until the rebuild task has run, so the
 * arrays are the render thread's largest source of garbage while chunks load. Copies take
 * their array from here while a snapshot is being made ({@link #copying}) and the rebuild
 * task returns them once the region it used is dead.
 *
 * <p>Arrays are keyed by exact length. The pool is bounded so a burst of rebuilds cannot pin
 * memory for the rest of the session; anything past the bound is left to the collector.
 */
public final class RenderRegionStoragePool {
    /** About 12 MiB of the common 256 to 512 long arrays. */
    private static final int MAX_POOLED = 4096;

    private static final ConcurrentHashMap<Integer, Stack> BY_LENGTH = new ConcurrentHashMap<>();
    private static final AtomicInteger pooled = new AtomicInteger();
    private static final ThreadLocal<Boolean> COPYING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private RenderRegionStoragePool() {
    }

    /** True while the current thread is taking a region snapshot; copies then draw from the pool. */
    public static boolean copying() {
        return COPYING.get();
    }

    public static void beginCopying() {
        COPYING.set(Boolean.TRUE);
    }

    public static void endCopying() {
        COPYING.set(Boolean.FALSE);
    }

    /** A recycled array of exactly {@code length} longs, or a new one. Contents are undefined. */
    public static long[] acquire(int length) {
        Stack stack = BY_LENGTH.get(length);
        if (stack != null) {
            long[] array = stack.pop();
            if (array != null) {
                pooled.decrementAndGet();
                return array;
            }
        }
        return new long[length];
    }

    /** Returns an array no reader can still reach. */
    public static void release(long[] array) {
        if (pooled.get() >= MAX_POOLED) {
            return;
        }
        pooled.incrementAndGet();
        BY_LENGTH.computeIfAbsent(array.length, length -> new Stack()).push(array);
    }

    /**
     * A locked array stack. A concurrent queue would allocate a node per release, and the
     * release path runs for every section of every region the render thread retires; the
     * lock is held for a few instructions and the two sides rarely meet.
     */
    private static final class Stack {
        private long[][] items = new long[64][];
        private int size;

        synchronized void push(long[] array) {
            if (size == items.length) {
                items = Arrays.copyOf(items, size * 2);
            }
            items[size++] = array;
        }

        synchronized long[] pop() {
            if (size == 0) {
                return null;
            }
            long[] array = items[--size];
            items[size] = null;
            return array;
        }
    }

    /** Arrays currently held, for diagnostics. */
    public static int pooledCount() {
        return pooled.get();
    }
}

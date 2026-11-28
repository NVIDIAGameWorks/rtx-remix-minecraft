/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/**
 * A work queue that reports itself empty once the current slice's time budget is spent.
 *
 * <p>A consumer written as {@code while (!queue.isEmpty()) process(queue.poll())} therefore
 * stops at the budget and, because the entries stay queued, resumes from exactly where it
 * stopped on the next slice. Vanilla's incremental chunk-visibility propagation is such a
 * consumer: a chunk-load burst can hand it thousands of seed sections, which it otherwise
 * walks to completion inside one frame on the render thread.
 *
 * <p>A minimum number of polls per slice guarantees progress under any budget, so a queue
 * that is fed faster than the budget drains it still shrinks by that much every slice.
 * Entries are never dropped; the queue's owner decides when its contents become invalid.
 *
 * <p>{@link #isEmpty()} answers for the slice while the rest of the
 * {@code ArrayDeque} surface answers for the queue, which breaks the
 * {@code Collection} contract: {@code size()}, iteration, {@code toString} and
 * bulk operations still see every queued entry, so {@code size()} can be
 * non-zero while {@code isEmpty()} is true. Use {@link #hasPendingWork()} to ask
 * about the contents and {@code isEmpty()} only to drive a slice loop. Not
 * thread safe: one slice at a time, on the thread that owns the queue.
 */
public final class TimeSlicedWorkQueue<E> extends ArrayDeque<E> {
    private final long budgetNanos;
    private final int minimumPollsPerSlice;
    private final LongSupplier clock;
    private long sliceDeadline;
    private int pollsThisSlice;
    private boolean budgetHit;

    public TimeSlicedWorkQueue(long budgetNanos, int minimumPollsPerSlice, LongSupplier clock) {
        if (budgetNanos <= 0 || minimumPollsPerSlice <= 0) {
            throw new IllegalArgumentException("budget and minimum polls must be positive");
        }
        this.budgetNanos = budgetNanos;
        this.minimumPollsPerSlice = minimumPollsPerSlice;
        this.clock = clock;
    }

    /** Starts a slice: the budget runs from now and the poll floor is reset. */
    public void beginSlice() {
        sliceDeadline = clock.getAsLong() + budgetNanos;
        pollsThisSlice = 0;
        budgetHit = false;
    }

    /**
     * True when there is nothing left to poll, or when this slice has both met its poll floor
     * and exhausted its budget while entries remain.
     */
    @Override
    public boolean isEmpty() {
        if (super.isEmpty()) {
            return true;
        }
        if (pollsThisSlice < minimumPollsPerSlice || clock.getAsLong() < sliceDeadline) {
            return false;
        }
        budgetHit = true;
        return true;
    }

    @Override
    public E poll() {
        E entry = super.poll();
        if (entry != null) {
            pollsThisSlice++;
        }
        return entry;
    }

    /** Empties the queue; nothing remains for a slice to have stopped on. */
    @Override
    public void clear() {
        super.clear();
        budgetHit = false;
    }

    /** Whether entries are still queued, regardless of the slice budget. */
    public boolean hasPendingWork() {
        return !super.isEmpty();
    }

    /** Whether the current slice stopped on its budget with work still queued. */
    public boolean budgetHit() {
        return budgetHit;
    }
}

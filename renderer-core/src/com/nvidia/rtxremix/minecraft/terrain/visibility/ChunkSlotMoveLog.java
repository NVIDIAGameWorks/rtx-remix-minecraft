/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import java.util.function.IntConsumer;

/**
 * Log of vanilla built-chunk slots whose origin moved, in the order they moved.
 *
 * <p>Every slot move appends the slot and advances a monotonic sequence. A frustum index
 * remembers the sequence it was last reconciled to and replays only the moves after it, so
 * keeping cached cell bounds exact costs one bounds read per moved slot rather than one per
 * indexed section. Torus repositioning moves whole planes of a few thousand slots, against
 * indexes of ten to twenty thousand sections.
 *
 * <p>Ownership: the log is written and replayed on the render thread only. Other threads may
 * read {@link #sequence()}; the volatile write that advances it also publishes the slot's new
 * origin, so a reader that observes a sequence sees every move counted in it.
 */
public final class ChunkSlotMoveLog {
    /** Large enough to hold a full-storage re-origin at render distance 32 without wrapping. */
    public static final ChunkSlotMoveLog RENDER_THREAD = new ChunkSlotMoveLog(1 << 17);

    private final int[] slots;
    private final int mask;
    private volatile long sequence;

    ChunkSlotMoveLog(int capacity) {
        if (Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two");
        }
        slots = new int[capacity];
        mask = capacity - 1;
    }

    /** Number of moves recorded so far. */
    public long sequence() {
        return sequence;
    }

    /** Records that {@code slot} moved. Render thread only. */
    public void record(int slot) {
        long current = sequence;
        slots[(int) (current & mask)] = slot;
        sequence = current + 1;
    }

    /**
     * Replays the moves with sequence numbers in {@code [from, to)}.
     *
     * @return false when the range is wider than the log, so some of those moves have been
     *         overwritten and the caller must reconcile everything it indexes instead
     */
    public boolean replay(long from, long to, IntConsumer movedSlot) {
        if (to - from > slots.length) {
            return false;
        }
        for (long position = from; position < to; position++) {
            movedSlot.accept(slots[(int) (position & mask)]);
        }
        return true;
    }
}

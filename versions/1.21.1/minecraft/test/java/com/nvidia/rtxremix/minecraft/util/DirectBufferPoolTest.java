/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.util;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectBufferPoolTest {

    /** A size class the pre-warm does not seed, so acquire and release are observable in isolation. */
    private static final int CLASS_BYTES = 1 << 13;

    @Test
    void acquiredBufferIsDirectLittleEndianAndRoundedUp() {
        ByteBuffer buf = DirectBufferPool.acquire(CLASS_BYTES - 100);
        try {
            assertTrue(buf.isDirect());
            assertEquals(ByteOrder.LITTLE_ENDIAN, buf.order());
            assertEquals(CLASS_BYTES, buf.capacity());
            assertEquals(0, buf.position());
            assertEquals(CLASS_BYTES, buf.limit());
        } finally {
            DirectBufferPool.release(buf);
        }
    }

    @Test
    void releasedBufferIsReusedForTheSameClass() {
        // Drain whatever earlier tests left idle in this class so the bin is observable.
        List<ByteBuffer> drained = new ArrayList<>();
        long idleBefore;
        while (true) {
            idleBefore = DirectBufferPool.pooledBytes();
            ByteBuffer b = DirectBufferPool.acquire(CLASS_BYTES);
            if (DirectBufferPool.pooledBytes() == idleBefore) {
                // Freshly allocated: the bin was empty before this acquire.
                drained.add(b);
                break;
            }
            drained.add(b);
        }
        ByteBuffer first = drained.remove(drained.size() - 1);
        try {
            long idle = DirectBufferPool.pooledBytes();
            DirectBufferPool.release(first);
            assertEquals(idle + CLASS_BYTES, DirectBufferPool.pooledBytes());

            ByteBuffer again = DirectBufferPool.acquire(CLASS_BYTES);
            try {
                assertEquals(MemoryUtil.memAddress0(first), MemoryUtil.memAddress0(again),
                        "the idle buffer of the matching class is handed out again");
                assertEquals(idle, DirectBufferPool.pooledBytes());
            } finally {
                DirectBufferPool.release(again);
            }
        } finally {
            for (ByteBuffer b : drained) {
                DirectBufferPool.release(b);
            }
        }
    }

    @Test
    void secondReleaseOfTheSameBufferIsIgnored() {
        ByteBuffer buf = DirectBufferPool.acquire(CLASS_BYTES);
        long idleBefore = DirectBufferPool.pooledBytes();
        DirectBufferPool.release(buf);
        DirectBufferPool.release(buf);
        assertEquals(idleBefore + CLASS_BYTES, DirectBufferPool.pooledBytes(),
                "a stale second release must not queue the buffer a second time");

        // However many buffers are idle in the class, no two acquires may return the same
        // allocation: the double release would have made that possible.
        List<ByteBuffer> taken = new ArrayList<>();
        Set<Long> addresses = new HashSet<>();
        try {
            long idle;
            do {
                idle = DirectBufferPool.pooledBytes();
                ByteBuffer b = DirectBufferPool.acquire(CLASS_BYTES);
                taken.add(b);
                assertTrue(addresses.add(MemoryUtil.memAddress0(b)), "two owners must never hold the same allocation");
            } while (DirectBufferPool.pooledBytes() != idle);
        } finally {
            for (ByteBuffer b : taken) {
                DirectBufferPool.release(b);
            }
        }
    }

    @Test
    void foreignDirectBuffersAreLeftToTheCollector() {
        ByteBuffer foreign = ByteBuffer.allocateDirect(CLASS_BYTES);
        long idleBefore = DirectBufferPool.pooledBytes();
        long allocatedBefore = DirectBufferPool.allocatedBytes();
        DirectBufferPool.release(foreign);
        DirectBufferPool.release(ByteBuffer.allocate(CLASS_BYTES));
        assertEquals(idleBefore, DirectBufferPool.pooledBytes());
        assertEquals(allocatedBefore, DirectBufferPool.allocatedBytes());
    }

    @Test
    void oversizedRequestsAreExactAndFreedOnRelease() {
        int oversized = (1 << 26) + 16;
        long allocatedBefore = DirectBufferPool.allocatedBytes();
        ByteBuffer buf = DirectBufferPool.acquire(oversized);
        assertEquals(oversized, buf.capacity());
        assertEquals(allocatedBefore + oversized, DirectBufferPool.allocatedBytes());
        DirectBufferPool.release(buf);
        assertEquals(allocatedBefore, DirectBufferPool.allocatedBytes(), "above the largest class nothing is pooled");
    }
}

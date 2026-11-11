/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.Buffer;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe pool of direct {@link ByteBuffer}s sized in power-of-2 classes.
 *
 * <p>Production adapters install a C allocator; the Java-8-compatible default uses
 * {@code ByteBuffer.allocateDirect}. The JDK counts the default allocator against a limit
 * that defaults to {@code -Xmx}, and at render distance 32 the retained section meshes alone
 * are over 3 GiB, so on the Minecraft launcher's default 2 GB heap the mod died of direct
 * memory within a minute; {@code allocateDirect} also triggers {@code System.gc()} when that
 * limit is near. Memory allocated here is invisible to both, which is what lets the mod run
 * on the launcher's own JVM arguments. The cost is that every buffer must be released:
 * nothing frees it otherwise. Every caller pairs {@link #acquire} with {@link #release},
 * and a released buffer is either kept for reuse or freed at once.
 *
 * <p>Each live buffer carries an ownership state, in flight or idle, keyed by its address.
 * {@link #release} accepts only in-flight buffers: releasing a buffer twice, which before
 * explicit freeing would have queued it in a bin twice and later handed one allocation to
 * two owners, is now a logged no-op instead of an alias or a double free. Buffers this pool
 * did not allocate are ignored entirely; the collector owns those.
 *
 * <p>Size classes: 4 KB ... 64 MB. Hot classes (256 KB to 8 MB) are pre-warmed with a small
 * seed so the first burst of chunk builds does not all funnel through the allocator at once.
 * Idle buffers are capped globally; bursts may allocate above the cap while in flight, and
 * excess buffers are freed on release instead of being retained forever. Requests above the
 * largest class are allocated exactly and freed on release.
 */
public final class DirectBufferPool {
    private static final Logger LOGGER = LoggerFactory.getLogger(DirectBufferPool.class);

    private static final int MIN_ORDER = 12;  // 4 KB
    private static final int MAX_ORDER = 26;  // 64 MB
    private static final int BIN_COUNT = MAX_ORDER - MIN_ORDER + 1;

    private static final int PREWARM_PER_BIN = 4;
    private static final long DEFAULT_MAX_POOLED_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_POOLED_BYTES = Math.max(0L,
            Long.getLong("rtxremix.directBufferPool.maxBytes", DEFAULT_MAX_POOLED_BYTES));

    private static final Boolean IN_FLIGHT = Boolean.TRUE;
    private static final Boolean IDLE = Boolean.FALSE;

    @SuppressWarnings("unchecked")
    private static final ConcurrentLinkedQueue<ByteBuffer>[] BINS = new ConcurrentLinkedQueue[BIN_COUNT];
    private static final AtomicLong pooledBytes = new AtomicLong();
    /** Bytes handed out and not yet released, plus bytes idle in the bins. */
    private static final AtomicLong allocatedBytes = new AtomicLong();
    /** Ownership state of every live buffer this pool allocated, by object identity. */
    private static final ConcurrentHashMap<IdentityKey, Boolean> owned = new ConcurrentHashMap<>();
    private static final AtomicBoolean prewarmed = new AtomicBoolean();
    private static final Allocator JVM_ALLOCATOR = new Allocator() {
        @Override
        public ByteBuffer allocate(int bytes) {
            return ByteBuffer.allocateDirect(bytes);
        }

        @Override
        public void free(ByteBuffer buffer) {
            // The JVM owns buffers allocated by the compatibility fallback.
        }
    };
    private static volatile Allocator allocator = detectAllocator();

    static {
        for (int i = 0; i < BIN_COUNT; i++) {
            BINS[i] = new ConcurrentLinkedQueue<>();
        }
    }

    public interface Allocator {
        ByteBuffer allocate(int bytes);

        void free(ByteBuffer buffer);
    }

    /**
     * Installs the process allocator before first use. Fabric supplies LWJGL's
     * C allocator; Forge may supply its own without coupling core code to it.
     */
    public static void setAllocator(Allocator value) {
        if (value == null) throw new NullPointerException("value");
        if (allocatedBytes.get() != 0) {
            throw new IllegalStateException("Direct buffer allocator changed after first use");
        }
        allocator = value;
    }

    private static Allocator detectAllocator() {
        try {
            Class<?> memoryUtil = Class.forName("org.lwjgl.system.MemoryUtil");
            final Method allocate = memoryUtil.getMethod("memAlloc", int.class);
            final Method free = memoryUtil.getMethod("memFree", Buffer.class);
            return new Allocator() {
                @Override
                public ByteBuffer allocate(int bytes) {
                    try {
                        return (ByteBuffer) allocate.invoke(null, bytes);
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException("LWJGL native buffer allocation failed", failure);
                    }
                }

                @Override
                public void free(ByteBuffer buffer) {
                    try {
                        free.invoke(null, buffer);
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException("LWJGL native buffer release failed", failure);
                    }
                }
            };
        } catch (ReflectiveOperationException unavailable) {
            return JVM_ALLOCATOR;
        }
    }

    private static void ensurePrewarmed() {
        if (!prewarmed.compareAndSet(false, true)) return;
        for (int order = 18; order <= 23; order++) {
            ConcurrentLinkedQueue<ByteBuffer> bin = BINS[order - MIN_ORDER];
            for (int j = 0; j < PREWARM_PER_BIN; j++) {
                long after = pooledBytes.addAndGet(1L << order);
                if (after <= MAX_POOLED_BYTES) {
                    ByteBuffer buf = allocate(1 << order);
                    owned.put(new IdentityKey(buf), IDLE);
                    bin.offer(buf);
                } else {
                    pooledBytes.addAndGet(-(1L << order));
                }
            }
        }
    }

    private DirectBufferPool() {}

    /**
     * Returns a direct ByteBuffer with at least {@code minBytes} capacity,
     * little-endian, position at 0 and limit at capacity. The actual capacity
     * is rounded up to the next power-of-2 size class.
     */
    public static ByteBuffer acquire(int minBytes) {
        ensurePrewarmed();
        int order = orderFor(minBytes);
        if (order > MAX_ORDER) {
            return allocate(minBytes);
        }
        ConcurrentLinkedQueue<ByteBuffer> bin = BINS[order - MIN_ORDER];
        ByteBuffer buf = bin.poll();
        if (buf != null) {
            pooledBytes.addAndGet(-buf.capacity());
            owned.put(new IdentityKey(buf), IN_FLIGHT);
            buf.clear();
            return buf;
        }
        return allocate(1 << order);
    }

    /**
     * Returns a buffer to the pool for reuse, or frees it when the pool is full or the
     * buffer is above the largest class. Only a buffer this pool handed out and that has
     * not been released since is accepted; anything else is ignored, and a second release
     * of the same buffer is logged, because it means two owners believed they held it.
     */
    public static void release(ByteBuffer buf) {
        if (buf == null || !buf.isDirect()) {
            return;
        }
        IdentityKey key = new IdentityKey(buf);
        if (!owned.replace(key, IN_FLIGHT, IDLE)) {
            if (owned.containsKey(key)) {
                LOGGER.warn("Direct buffer released twice ({} bytes); the second owner's reference is stale",
                        buf.capacity(), new IllegalStateException("second release"));
            }
            return;
        }
        long bytes = buf.capacity();
        int order = orderFor(buf.capacity());
        if (order <= MAX_ORDER && bytes == (1L << order)) {
            long after = pooledBytes.addAndGet(bytes);
            if (after <= MAX_POOLED_BYTES) {
                buf.clear();
                BINS[order - MIN_ORDER].offer(buf);
                return;
            }
            pooledBytes.addAndGet(-bytes);
        }
        free(buf);
    }

    /** Bytes currently allocated by the pool, in flight or idle, for diagnostics. */
    public static long allocatedBytes() {
        return allocatedBytes.get();
    }

    /** Bytes idle in the bins, for diagnostics. */
    public static long pooledBytes() {
        return pooledBytes.get();
    }

    private static ByteBuffer allocate(int bytes) {
        ByteBuffer buf = allocator.allocate(bytes);
        owned.put(new IdentityKey(buf), IN_FLIGHT);
        allocatedBytes.addAndGet(bytes);
        return buf.order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void free(ByteBuffer buf) {
        if (owned.remove(new IdentityKey(buf)) != null) {
            allocatedBytes.addAndGet(-buf.capacity());
            allocator.free(buf);
        }
    }

    private static final class IdentityKey {
        private final ByteBuffer buffer;
        private final int hash;

        IdentityKey(ByteBuffer buffer) {
            this.buffer = buffer;
            this.hash = System.identityHashCode(buffer);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityKey
                    && ((IdentityKey) other).buffer == buffer;
        }
    }

    private static int orderFor(int bytes) {
        if (bytes <= (1 << MIN_ORDER)) return MIN_ORDER;
        return 32 - Integer.numberOfLeadingZeros(bytes - 1);
    }
}

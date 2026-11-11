/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.profiling;

/**
 * Lightweight aggregate counters for vanilla particle ticking. Per-particle
 * Tracy zones would dominate the benchmark, so the particle mixins record only
 * counts and aggregate collision time for the enclosing ParticleManager phase.
 */
public final class ParticleTickProfiler {
    private static final ThreadLocal<Stats> STATS = ThreadLocal.withInitial(Stats::new);

    private ParticleTickProfiler() {
    }

    public static void beginTick() {
        STATS.get().reset();
    }

    public static void beginCollisionCheck() {
        Stats stats = STATS.get();
        stats.collisionChecks++;
        stats.collisionStartNanos = System.nanoTime();
    }

    public static void recordSkippedCollisionCheck() {
        Stats stats = STATS.get();
        stats.collisionChecks++;
        stats.skippedCollisionChecks++;
    }

    public static void endCollisionCheck() {
        Stats stats = STATS.get();
        long startedAt = stats.collisionStartNanos;
        if (startedAt == 0L) {
            return;
        }
        stats.collisionStartNanos = 0L;
        stats.collisionNanos += System.nanoTime() - startedAt;
    }

    public static Stats current() {
        return STATS.get();
    }

    public static final class Stats {
        private int collisionChecks;
        private int skippedCollisionChecks;
        private long collisionNanos;
        private long collisionStartNanos;

        private void reset() {
            collisionChecks = 0;
            skippedCollisionChecks = 0;
            collisionNanos = 0L;
            collisionStartNanos = 0L;
        }

        public int collisionChecks() {
            return collisionChecks;
        }

        public int skippedCollisionChecks() {
            return skippedCollisionChecks;
        }

        public long collisionNanos() {
            return collisionNanos;
        }
    }
}

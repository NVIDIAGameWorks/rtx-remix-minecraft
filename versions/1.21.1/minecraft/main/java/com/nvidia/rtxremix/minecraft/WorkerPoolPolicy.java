/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

/**
 * Bounds Minecraft's shared background worker pool before the game creates it.
 *
 * <p>{@code net.minecraft.util.Util} sizes its {@code Worker-Main} pool to the
 * logical CPU count minus one, capped by the {@code max.bg.threads} system
 * property. Every section build, chunk load, lighting update and resource reload
 * runs there, so on a 32-thread machine a chunk-boundary crossing puts 31
 * threads to work at once, and with Remix capture inline in every section build
 * that wave contends with the render thread and the dxvk CS thread for every
 * core: the frame it lands in inflates by tens of milliseconds. The same work
 * needs about two cores on average, so a small static pool keeps throughput and
 * removes the contention.
 *
 * <p>The cap is applied only when the property is unset, so a user who passes
 * {@code -Dmax.bg.threads} keeps their value. It runs at pre-launch because the
 * pool is created in a static initialiser at class load.
 */
public final class WorkerPoolPolicy {
    public static final String PROPERTY = "max.bg.threads";
    public static final int MAX_THREADS = 8;

    private WorkerPoolPolicy() {}

    /**
     * Mirrors vanilla's own choice ({@code availableProcessors - 1}, at least 1)
     * up to {@link #MAX_THREADS}.
     */
    public static int choose(int availableProcessors) {
        return Math.max(1, Math.min(MAX_THREADS, availableProcessors - 1));
    }

    /**
     * Installs the default before vanilla creates its worker pool.
     *
     * @return the installed value, or {@code -1} when the user already set one
     */
    public static int applyDefault() {
        if (System.getProperty(PROPERTY) != null) {
            return -1;
        }
        int threads = choose(Runtime.getRuntime().availableProcessors());
        System.setProperty(PROPERTY, Integer.toString(threads));
        return threads;
    }
}

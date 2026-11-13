/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounds how many vanilla chunk sections build at the same time.
 *
 * <p>Vanilla sizes its section-builder buffer pool to the logical CPU count, and
 * the shared {@code Worker-Main} pool has one thread per CPU minus one, so on a
 * 32-thread machine a chunk-boundary crossing can put 31 section builds in
 * flight at once. With Remix capture inline in every build, that wave competes
 * with the render thread, the dxvk CS thread and the mesh merge pool for every
 * core, and the frame it lands in inflates by tens of milliseconds. Builds
 * finish faster and the frame stays flat when a few builders run at a time:
 * the same section throughput needs about two cores on average.
 *
 * <p>The cap only limits concurrent builds; queued sections still build in
 * order, and the worker pool stays available for chunk loading and lighting.
 */
public final class SectionBuildConcurrency {

    private static final Logger LOGGER = LoggerFactory.getLogger(SectionBuildConcurrency.class);
    private static final int DEFAULT_MAX_BUILDERS = 8;
    private static final String PROPERTY = "rtxremix.chunk.sectionBuilders";

    private SectionBuildConcurrency() {
    }

    /**
     * Returns the number of section builders to allocate given vanilla's own
     * choice, which is the logical CPU count.
     */
    public static int cap(int vanillaBuilders) {
        int requested = Integer.getInteger(PROPERTY, DEFAULT_MAX_BUILDERS);
        int builders = Math.max(1, Math.min(requested, vanillaBuilders));
        LOGGER.info("Section builders: {} (vanilla would allocate {}, {}={})",
                builders, vanillaBuilders, PROPERTY, requested);
        return builders;
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decides whether the client's chunk apply seeds the sky-light surface scan from the
 * packet's {@code WORLD_SURFACE} heightmap.
 *
 * <p>Vanilla recomputes {@code ChunkSkyLight} for every chunk the client receives by
 * walking each of the 256 columns downward from the top of the chunk's highest non-empty
 * section until a block pair blocks sky light. Almost all of that walk is air over air,
 * which never blocks, and it is most of the render thread's cost of applying a chunk.
 * The packet already says where the air ends: {@code WORLD_SURFACE.get(x, z)} is the Y of
 * the first block above the column's highest non-air block. Starting vanilla's own walk
 * at that pair skips only air-over-air pairs, so the palette it produces is the one the
 * full walk produces; the shortcut has no behaviour to return to when RTX capture is off
 * and applies to remote servers as well.
 *
 * <p>The seed is a contract on the sender, not a definition. The walk assumes that every
 * block at or above {@code WORLD_SURFACE.get(x, z)} in the sections of the same packet is
 * air, which is how vanilla computes that heightmap (its {@code NOT_AIR} predicate) for the
 * chunks it generates or edits in memory, and {@code ChunkData} serializes heightmaps and
 * sections from one chunk. Vanilla checks only the array's length on receipt, so a save
 * whose stored heightmaps were edited out of step with its blocks, or a non-vanilla sender,
 * can break the contract: a height below a real block lowers that column's sky-light surface
 * silently, while a height above the world is clamped to vanilla's start and harmless.
 * Vanilla's client shows {@code WORLD_SURFACE} only in the F3 overlay and reads
 * {@code MOTION_BLOCKING} for weather; this is the first client-side reader whose result
 * reaches lighting, so a bad heightmap now costs lighting rather than a debug readout. The
 * kill switch is the remedy for such a connection.
 *
 * <p>Kill switch: {@code -Drtxremix.light.vanillaSurfaceScan=true} restores the full walk.
 */
public final class SkyLightSurfaceScanPolicy {
    public static final String PROPERTY = "rtxremix.light.vanillaSurfaceScan";

    /** Read once: a session either seeds or never does. */
    public static final boolean SEED_ALLOWED = seedAllowedFrom(System.getProperty(PROPERTY));

    private static final Logger LOGGER = LoggerFactory.getLogger(SkyLightSurfaceScanPolicy.class);
    private static final AtomicInteger SEEDED = new AtomicInteger();
    private static final AtomicBoolean MISSING_HEIGHTMAP_REPORTED = new AtomicBoolean();

    private SkyLightSurfaceScanPolicy() {
    }

    static boolean seedAllowedFrom(String property) {
        return !Boolean.parseBoolean(property);
    }

    /**
     * Whether this apply seeds the scan. A packet without the surface heightmap (a
     * non-vanilla server) has nothing to seed from and takes the full walk.
     */
    public static boolean seedFromHeightmap(boolean seedAllowed, boolean worldSurfaceInPacket) {
        return seedAllowed && worldSurfaceInPacket;
    }

    /** Records one seeded scan, for the per-frame terrain counters. */
    public static void recordSeeded() {
        SEEDED.incrementAndGet();
    }

    /**
     * Notes that a packet arrived without the surface heightmap while seeding is permitted,
     * so the full walk those chunks take shows once in the log rather than only as a zero
     * counter. Returns whether this was the first such packet.
     */
    public static boolean noteMissingHeightmap() {
        if (!MISSING_HEIGHTMAP_REPORTED.compareAndSet(false, true)) {
            return false;
        }
        LOGGER.info("Chunk packets carry no WORLD_SURFACE heightmap; the client sky-light surface scan takes vanilla's full walk for them");
        return true;
    }

    /** Returns and resets the seeded-scan count. */
    public static int drainSeeded() {
        return SEEDED.getAndSet(0);
    }

    /** One startup log line that makes every run self-describing. */
    public static String effectiveStateLine() {
        return SEED_ALLOWED
                ? "Client sky-light surface scan: seeded from the WORLD_SURFACE heightmap (" + PROPERTY + "=false)"
                : "Client sky-light surface scan: vanilla top-down walk (" + PROPERTY + "=true)";
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.world.level.Level;

/**
 * Reconstructs a smooth presentation time from synchronized integer world ticks.
 *
 * <p>The block-entity renderer's tick delta can remain fixed while a handled
 * screen is open. World ticks still arrive at 20 Hz, so anchoring each observed
 * tick to monotonic render time preserves synchronization without showing those
 * ticks as visible rotation steps.
 */
public final class CopperBeaconAnimationClock {

    private static final long NANOS_PER_TICK = 50_000_000L;

    private static Level world;
    private static long worldTick = Long.MIN_VALUE;
    private static long tickStartNanos;

    private CopperBeaconAnimationClock() {}

    public static double sample(Level currentWorld, float tickDelta) {
        return sample(currentWorld, currentWorld.getGameTime(), tickDelta, System.nanoTime());
    }

    static double sample(
            Level currentWorld, long currentTick, float tickDelta, long nowNanos) {
        double vanillaFraction = Math.clamp(
                Float.isFinite(tickDelta) ? tickDelta : 0.0f,
                0.0f,
                1.0f);
        if (world != currentWorld || worldTick != currentTick) {
            world = currentWorld;
            worldTick = currentTick;
            tickStartNanos = nowNanos - (long) (vanillaFraction * NANOS_PER_TICK);
        }

        double elapsedFraction = Math.clamp(
                (nowNanos - tickStartNanos) / (double) NANOS_PER_TICK,
                0.0,
                1.0);
        return currentTick + Math.max(vanillaFraction, elapsedFraction);
    }

    static void reset() {
        world = null;
        worldTick = Long.MIN_VALUE;
        tickStartNanos = 0L;
    }
}

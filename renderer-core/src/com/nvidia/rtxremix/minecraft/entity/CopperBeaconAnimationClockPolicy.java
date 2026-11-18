/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

/**
 * Reconstructs a smooth presentation time from synchronized integer world ticks.
 *
 * <p>The block-entity renderer's tick delta can remain fixed while a handled
 * screen is open. World ticks still arrive at 20 Hz, so anchoring each observed
 * tick to monotonic render time preserves synchronization without showing those
 * ticks as visible rotation steps.
 */
public final class CopperBeaconAnimationClockPolicy {

    private static final long NANOS_PER_TICK = 50_000_000L;

    private static Object session;
    private static long worldTick = Long.MIN_VALUE;
    private static long tickStartNanos;

    private CopperBeaconAnimationClockPolicy() {}

    public static double sample(
            Object currentSession, long currentTick, float tickDelta, long nowNanos) {
        double vanillaFraction = clamp(
                Float.isFinite(tickDelta) ? tickDelta : 0.0f, 0.0, 1.0);
        if (session != currentSession || worldTick != currentTick) {
            session = currentSession;
            worldTick = currentTick;
            tickStartNanos = nowNanos - (long) (vanillaFraction * NANOS_PER_TICK);
        }

        double elapsedFraction = clamp(
                (nowNanos - tickStartNanos) / (double) NANOS_PER_TICK, 0.0, 1.0);
        return currentTick + Math.max(vanillaFraction, elapsedFraction);
    }

    public static void reset() {
        session = null;
        worldTick = Long.MIN_VALUE;
        tickStartNanos = 0L;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}

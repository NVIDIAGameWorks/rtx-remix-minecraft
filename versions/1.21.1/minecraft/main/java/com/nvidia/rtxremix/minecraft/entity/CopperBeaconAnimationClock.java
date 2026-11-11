/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.world.level.Level;

/** Adapts Minecraft world time to the canonical beacon animation clock. */
public final class CopperBeaconAnimationClock {
    private CopperBeaconAnimationClock() {}

    public static double sample(Level currentWorld, float tickDelta) {
        return sample(currentWorld, currentWorld.getGameTime(), tickDelta, System.nanoTime());
    }

    static double sample(
            Level currentWorld, long currentTick, float tickDelta, long nowNanos) {
        return CopperBeaconAnimationClockPolicy.sample(
                currentWorld, currentTick, tickDelta, nowNanos);
    }

    static void reset() {
        CopperBeaconAnimationClockPolicy.reset();
    }
}

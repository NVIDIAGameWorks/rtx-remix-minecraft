/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import java.util.Objects;
import net.minecraft.client.multiplayer.ClientLevel;

/** Loader extension points for particle behavior exposed by loader fluid APIs. */
public final class MinecraftParticleHooks {
    public interface Hooks {
        void spawnWaterRapids(ClientLevel level, int centerX, int centerY, int centerZ);
    }

    private static volatile Hooks hooks;

    private MinecraftParticleHooks() {
    }

    public static void install(Hooks value) {
        hooks = Objects.requireNonNull(value, "value");
    }

    public static void spawnWaterRapids(
            ClientLevel level, int centerX, int centerY, int centerZ) {
        Hooks value = hooks;
        if (value != null) {
            value.spawnWaterRapids(level, centerX, centerY, centerZ);
        }
    }
}

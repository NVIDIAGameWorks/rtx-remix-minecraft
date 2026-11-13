/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;

/** Loader bridge for opening menus that carry loader-specific extra data. */
public final class MinecraftMenus {
    public interface Opener {
        void openCopperBeacon(
                ServerPlayer player, CopperBeaconBlockEntity beacon);
    }

    private static volatile Opener opener;

    private MinecraftMenus() {
    }

    public static void install(Opener value) {
        opener = Objects.requireNonNull(value, "value");
    }

    public static void openCopperBeacon(
            ServerPlayer player, CopperBeaconBlockEntity beacon) {
        Opener value = opener;
        if (value == null) {
            throw new IllegalStateException("Minecraft menu bridge is not installed");
        }
        value.openCopperBeacon(player, beacon);
    }
}

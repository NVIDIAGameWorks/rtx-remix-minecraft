/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.nvidia.rtxremix.minecraft.block.CopperBeaconUpdatePayload;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconScreenHandler;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;

/** Loader bridge for the mod's small client-to-server control channel. */
public final class MinecraftNetworking {
    public interface ClientSender {
        void send(CopperBeaconUpdatePayload payload);
    }

    private static volatile ClientSender sender;

    private MinecraftNetworking() {
    }

    public static void installClientSender(ClientSender value) {
        sender = Objects.requireNonNull(value, "value");
    }

    public static void send(CopperBeaconUpdatePayload payload) {
        ClientSender value = sender;
        if (value == null) {
            throw new IllegalStateException("Minecraft networking is not installed");
        }
        value.send(payload);
    }

    /**
     * Applies a client control payload only to the menu instance that authorized
     * it. Loaders own transport and thread dispatch; gameplay validation remains
     * canonical.
     */
    public static void handleServerPayload(
            ServerPlayer player, CopperBeaconUpdatePayload payload) {
        if (player != null
                && player.containerMenu instanceof CopperBeaconScreenHandler handler
                && handler.containerId == payload.syncId()
                && handler.stillValid(player)) {
            handler.applyUpdate(payload.field(), payload.value());
        }
    }
}

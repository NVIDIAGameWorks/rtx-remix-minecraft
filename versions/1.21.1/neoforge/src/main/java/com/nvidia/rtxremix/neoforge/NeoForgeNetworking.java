/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.neoforge;

import com.nvidia.rtxremix.minecraft.MinecraftNetworking;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconUpdatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** NeoForge transport for shared gameplay payloads. */
public final class NeoForgeNetworking {
    private static final String PROTOCOL = "1";

    private NeoForgeNetworking() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(NeoForgeNetworking::onRegisterPayloadHandlers);
    }

    private static void onRegisterPayloadHandlers(
            RegisterPayloadHandlersEvent event) {
        event.registrar(PROTOCOL)
                .optional()
                .playToServer(
                        CopperBeaconUpdatePayload.ID,
                        CopperBeaconUpdatePayload.PACKET_CODEC,
                        (payload, context) ->
                                MinecraftNetworking.handleServerPayload(
                                        (ServerPlayer) context.player(), payload));
    }

    public static void installClientSender() {
        MinecraftNetworking.installClientSender(payload -> {
            var listener = Minecraft.getInstance().getConnection();
            if (listener != null
                    && NetworkRegistry.hasChannel(listener, payload.type().id())) {
                PacketDistributor.sendToServer(payload);
            }
        });
    }
}

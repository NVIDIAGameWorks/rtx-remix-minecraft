/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge;

import com.nvidia.rtxremix.minecraft.MinecraftNetworking;
import com.nvidia.rtxremix.minecraft.MinecraftPlatform;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconUpdatePayload;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;

/** Forge transport for shared gameplay payloads. */
public final class ForgeNetworking {
    private static final int PROTOCOL = 1;
    private static final Channel.VersionTest COMPATIBLE_REMOTE =
            Channel.VersionTest.exact(PROTOCOL)
                    .or(Channel.VersionTest.ACCEPT_MISSING)
                    .or(Channel.VersionTest.ACCEPT_VANILLA);
    private static final SimpleChannel CHANNEL = ChannelBuilder.named(
                    ResourceLocation.fromNamespaceAndPath(
                            MinecraftPlatform.RESOURCE_NAMESPACE, "main"))
            .networkProtocolVersion(PROTOCOL)
            .clientAcceptedVersions(COMPATIBLE_REMOTE)
            .serverAcceptedVersions(COMPATIBLE_REMOTE)
            .simpleChannel();

    private ForgeNetworking() {
    }

    public static void register() {
        CHANNEL.messageBuilder(
                        CopperBeaconUpdatePayload.class,
                        NetworkDirection.PLAY_TO_SERVER)
                .codec(CopperBeaconUpdatePayload.PACKET_CODEC)
                .consumerMainThread((payload, context) ->
                        MinecraftNetworking.handleServerPayload(
                                context.getSender(), payload))
                .add();
    }

    public static void installClientSender(
            java.util.function.Supplier<Connection> connection) {
        MinecraftNetworking.installClientSender(payload -> {
            Connection remote = connection.get();
            if (remote != null && CHANNEL.isRemotePresent(remote)) {
                CHANNEL.send(payload, PacketDistributor.SERVER.noArg());
            }
        });
    }
}

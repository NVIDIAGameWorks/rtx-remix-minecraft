/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Additional data needed to bind the client-side handler to its block entity. */
public record CopperBeaconScreenData(BlockPos pos) {
    public static final StreamCodec<RegistryFriendlyByteBuf, CopperBeaconScreenData> PACKET_CODEC =
            new StreamCodec<>() {
                @Override
                public CopperBeaconScreenData decode(RegistryFriendlyByteBuf buffer) {
                    return new CopperBeaconScreenData(buffer.readBlockPos());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, CopperBeaconScreenData data) {
                    buffer.writeBlockPos(data.pos);
                }
            };
}

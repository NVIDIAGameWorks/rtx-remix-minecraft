/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.neoforge;

import com.nvidia.rtxremix.minecraft.MinecraftMenus;
import com.nvidia.rtxremix.minecraft.MinecraftPlatform;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

/** NeoForge's loader entrypoint. Rendering behavior lives in shared code. */
@Mod(RtxRemixNeoForge.MOD_ID)
public final class RtxRemixNeoForge {
    public static final String MOD_ID = "rtx_remix_renderer";

    public RtxRemixNeoForge(IEventBus modBus) {
        MinecraftPlatform.install(new NeoForgePlatformServices());
        MinecraftMenus.install((player, beacon) -> player.openMenu(
                beacon, buffer -> buffer.writeBlockPos(beacon.getBlockPos())));
        NeoForgeContent.register(modBus);
        NeoForgeNetworking.register(modBus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            NeoForgeClientBootstrap.install(modBus);
        }
    }
}

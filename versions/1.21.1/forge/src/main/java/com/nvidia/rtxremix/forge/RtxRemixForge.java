/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge;

import com.nvidia.rtxremix.minecraft.MinecraftMenus;
import com.nvidia.rtxremix.minecraft.MinecraftPlatform;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.common.Mod;

/** Forge's loader entrypoint. Rendering behavior lives in the shared renderer. */
@Mod(RtxRemixForge.MOD_ID)
public final class RtxRemixForge {
    public static final String MOD_ID = "rtx_remix_renderer";

    public RtxRemixForge(FMLJavaModLoadingContext context) {
        MinecraftPlatform.install(new ForgePlatformServices());
        MinecraftMenus.install((player, beacon) -> player.openMenu(
                beacon, buffer -> buffer.writeBlockPos(beacon.getBlockPos())));
        ForgeContent.register(context.getModEventBus());
        ForgeNetworking.register();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ForgeClientBootstrap.install(context.getModEventBus());
        }
    }
}

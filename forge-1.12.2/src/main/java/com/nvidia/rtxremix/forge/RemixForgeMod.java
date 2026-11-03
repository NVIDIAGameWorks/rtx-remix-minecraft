/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge;

import com.nvidia.rtxremix.forge.client.ClientEvents;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = RemixForgeMod.ID, name = "RTX Remix for Forge", version = "0.1.0",
        clientSideOnly = true, acceptedMinecraftVersions = "[1.12.2]", acceptableRemoteVersions = "*")
public final class RemixForgeMod {
    public static final String ID = "rtxremixforge";
    public static final Logger LOG = LogManager.getLogger(ID);

    @Mod.EventHandler
    public void initialize(FMLInitializationEvent event) {
        ClientEvents client = new ClientEvents();
        MinecraftForge.EVENT_BUS.register(client);
        client.register();
        LOG.info("Experimental Forge renderer loaded. Use /remix on in a test world.");
    }
}
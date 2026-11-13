/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.neoforge;

import com.nvidia.rtxremix.core.HostGraphicsIntegration;
import com.nvidia.rtxremix.minecraft.MinecraftPlatform;
import com.nvidia.rtxremix.minecraft.gl.MinecraftOpenGlGraphicsIntegration;
import java.nio.file.Path;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/** NeoForge's implementation of the shared loader metadata boundary. */
public final class NeoForgePlatformServices
        implements MinecraftPlatform.Services {
    @Override
    public Path gameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public Path configDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Path modRoot() {
        return ModList.get()
                .getModContainerById(RtxRemixNeoForge.MOD_ID)
                .orElseThrow()
                .getModInfo()
                .getOwningFile()
                .getFile()
                .getSecureJar()
                .getRootPath();
    }

    @Override
    public String modVersion() {
        return ModList.get()
                .getModContainerById(RtxRemixNeoForge.MOD_ID)
                .orElseThrow()
                .getModInfo()
                .getVersion()
                .toString();
    }

    @Override
    public String loaderId() {
        return "neoforge";
    }

    @Override
    public String metadataResource() {
        return "META-INF/neoforge.mods.toml";
    }

    @Override
    public HostGraphicsIntegration createGraphicsIntegration() {
        return new MinecraftOpenGlGraphicsIntegration();
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.fabric;

import com.nvidia.rtxremix.core.HostGraphicsIntegration;
import com.nvidia.rtxremix.minecraft.MinecraftPlatform;
import com.nvidia.rtxremix.minecraft.gl.MinecraftOpenGlGraphicsIntegration;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/** Fabric's implementation of the shared loader metadata boundary. */
public final class FabricPlatformServices implements MinecraftPlatform.Services {
    private static FabricLoader loader() {
        return FabricLoader.getInstance();
    }

    private static ModContainer container() {
        return loader().getModContainer(RtxRemixCommonMod.MOD_ID)
            .orElseThrow(() -> new IllegalStateException("Cannot find the RTX Remix mod container"));
    }

    @Override
    public Path gameDirectory() {
        return loader().getGameDir();
    }

    @Override
    public Path configDirectory() {
        return loader().getConfigDir();
    }

    @Override
    public Path modRoot() {
        return container().getRootPaths().getFirst();
    }

    @Override
    public String modVersion() {
        return container().getMetadata().getVersion().getFriendlyString();
    }

    @Override
    public String loaderId() {
        return "fabric";
    }

    @Override
    public String metadataResource() {
        return "fabric.mod.json";
    }

    @Override
    public HostGraphicsIntegration createGraphicsIntegration() {
        return new MinecraftOpenGlGraphicsIntegration();
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.fabric;

import com.nvidia.rtxremix.minecraft.MinecraftMenus;
import com.nvidia.rtxremix.minecraft.MinecraftPlatform;
import net.fabricmc.api.ModInitializer;

/**
 * Common (both-sides) entrypoint.
 *
 * <p>The renderer itself is client-only and lives in {@link RtxRemixMod}. This
 * class exists solely for content that must exist on the logical server too:
 * currently the beacon blocks.
 *
 * <p><b>Why this is separate:</b> blocks live in a <i>synced</i> registry. A
 * block registered only on the client desynchronises from a server that has
 * never heard of it, so it cannot be placed or persisted in multiplayer.
 * Particles ({@code RapidFoamParticles}) do not have this problem: that
 * registry is client-side, which is why they stay in the client entrypoint.
 *
 * <p>Everything reachable from here must avoid client-only classes: this runs
 * on dedicated servers, where {@code net.minecraft.client.*} does not exist.
 * Fabric invokes {@code main} entrypoints before {@code client} ones, so blocks
 * are registered before {@link RtxRemixMod#onInitializeClient()} runs.
 */
public class RtxRemixCommonMod implements ModInitializer {

    /**
     * The mod id, and the namespace of every identifier this mod registers.
     *
     * <p>Owned by this class rather than by {@link RtxRemixMod}: block
     * registration runs on dedicated servers, where the client entrypoint
     * cannot be loaded, so a constant that path depends on must not live on a
     * client-only class.
     */
    public static final String MOD_ID = "rtx-remix-renderer";

    @Override
    public void onInitialize() {
        MinecraftPlatform.install(new FabricPlatformServices());
        MinecraftMenus.install((player, beacon) -> player.openMenu(beacon));
        FabricContent.register();
    }
}

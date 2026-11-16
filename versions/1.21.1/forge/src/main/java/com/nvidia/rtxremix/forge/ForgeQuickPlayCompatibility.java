/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge;

import com.nvidia.rtxremix.forge.mixin.ForgeInternalHandlerAccessor;
import com.nvidia.rtxremix.minecraft.MinecraftRuntime;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.Util;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraftforge.common.loot.LootModifierManager;

/**
 * Repairs Forge 52's missing initial server reload on vanilla quick play.
 *
 * <p>When {@code --quickPlaySingleplayer} opens a world during client startup,
 * Forge can finish {@link MinecraftServer#initServer()} without ever creating
 * its global loot-modifier manager. The first mob death then takes down the
 * integrated server. A normal title-screen world already has the manager and
 * does no extra work here.
 */
public final class ForgeQuickPlayCompatibility {
    private ForgeQuickPlayCompatibility() {
    }

    public static void ensureLootModifiersReady(MinecraftServer server) {
        if (initializeIfMissing(
                ForgeInternalHandlerAccessor.rtxremix$getLootModifierManager(),
                () -> loadLootModifiers(server),
                ForgeInternalHandlerAccessor::rtxremix$setLootModifierManager)) {
            MinecraftRuntime.LOGGER.info(
                    "Forge loot modifiers initialized by compatibility loader");
        }
    }

    private static LootModifierManager loadLootModifiers(MinecraftServer server) {
        MinecraftRuntime.LOGGER.warn(
                "Forge loot modifiers were not initialized before server startup; "
                        + "loading the missing loot-modifier resources");
        LootModifierManager manager =
                new LootModifierManager(
                        (HolderLookup.Provider) server.registryAccess());
        PreparableReloadListener.PreparationBarrier barrier =
                new PreparableReloadListener.PreparationBarrier() {
                    @Override
                    public <T> CompletableFuture<T> wait(T prepared) {
                        return CompletableFuture.completedFuture(prepared);
                    }
                };
        CompletableFuture<Void> reload = manager.reload(
                barrier,
                server.getResourceManager(),
                InactiveProfiler.INSTANCE,
                InactiveProfiler.INSTANCE,
                Util.backgroundExecutor(),
                server);
        server.managedBlock(reload::isDone);
        reload.join();
        return manager;
    }

    static <T> boolean initializeIfMissing(
            T existing, Supplier<T> load, Consumer<T> publish) {
        if (existing != null) {
            return false;
        }
        publish.accept(load.get());
        return true;
    }
}

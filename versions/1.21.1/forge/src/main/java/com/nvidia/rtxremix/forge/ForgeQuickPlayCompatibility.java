/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge;

import com.nvidia.rtxremix.minecraft.MinecraftRuntime;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
    private static final String INTERNAL_HANDLER =
            "net.minecraftforge.common.ForgeInternalHandler";
    private static final Field LOOT_MODIFIER_MANAGER_FIELD =
            findStaticField(INTERNAL_HANDLER, LootModifierManager.class);

    private ForgeQuickPlayCompatibility() {
    }

    public static void ensureLootModifiersReady(MinecraftServer server) {
        Field managerField = LOOT_MODIFIER_MANAGER_FIELD;
        if (managerField == null) {
            MinecraftRuntime.LOGGER.warn(
                    "Forge Quick Play compatibility is unavailable because "
                            + "Forge's loot-modifier storage did not match the "
                            + "supported Forge 52.x structure");
            return;
        }
        LootModifierManager existing = readManager(managerField);
        if (initializeIfMissing(
                existing,
                () -> loadLootModifiers(server),
                manager -> publishManager(managerField, manager))) {
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

    private static LootModifierManager readManager(Field field) {
        try {
            return (LootModifierManager) field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Could not read Forge's loot-modifier manager", e);
        }
    }

    private static void publishManager(Field field, LootModifierManager manager) {
        try {
            field.set(null, manager);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Could not publish Forge's loot-modifier manager", e);
        }
    }

    static Field findStaticField(String ownerName, Class<?> fieldType) {
        final Class<?> owner;
        try {
            owner = Class.forName(ownerName, false,
                    ForgeQuickPlayCompatibility.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }

        Field match = null;
        for (Field field : owner.getDeclaredFields()) {
            if (field.getType() != fieldType
                    || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (match != null || !field.trySetAccessible()) {
                return null;
            }
            match = field;
        }
        return match;
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

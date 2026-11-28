/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.neoforge;

import com.nvidia.rtxremix.minecraft.MinecraftPlatform;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlock;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconScreenData;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconScreenHandler;
import com.nvidia.rtxremix.minecraft.particle.rapids.RapidFoamParticles;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

/** NeoForge registrations for gameplay behavior owned by the shared layer. */
public final class NeoForgeContent {
    private static final ResourceLocation BEACON_ID =
            ResourceLocation.fromNamespaceAndPath(
                    MinecraftPlatform.RESOURCE_NAMESPACE, CopperBeaconBlock.ID);
    private static BlockItem beaconItem;
    private static final MenuType<CopperBeaconScreenHandler> BEACON_MENU =
            IMenuTypeExtension.create((syncId, inventory, buffer) ->
                    new CopperBeaconScreenHandler(
                            syncId,
                            inventory,
                            new CopperBeaconScreenData(buffer.readBlockPos())));

    private NeoForgeContent() {
    }

    public static void register(IEventBus modBus) {
        CopperBeaconScreenHandler.installType(BEACON_MENU);
        modBus.addListener(NeoForgeContent::onRegister);
        modBus.addListener(NeoForgeContent::onBuildCreativeTab);
    }

    private static void onRegister(RegisterEvent event) {
        event.register(
                Registries.BLOCK, BEACON_ID, () -> CopperBeaconBlock.INSTANCE);
        event.register(Registries.ITEM, BEACON_ID, () -> {
            beaconItem = new BlockItem(
                    CopperBeaconBlock.INSTANCE, new Item.Properties());
            return beaconItem;
        });
        event.register(
                Registries.BLOCK_ENTITY_TYPE,
                BEACON_ID,
                () -> CopperBeaconBlockEntity.TYPE);
        event.register(Registries.MENU, BEACON_ID, () -> BEACON_MENU);
        event.register(
                Registries.PARTICLE_TYPE,
                id("rapid_foam"),
                () -> RapidFoamParticles.RAPID_FOAM);
        event.register(
                Registries.PARTICLE_TYPE,
                id("rapid_foam_dot"),
                () -> RapidFoamParticles.RAPID_FOAM_DOT);
        event.register(
                Registries.PARTICLE_TYPE,
                id("rapid_mist"),
                () -> RapidFoamParticles.RAPID_MIST);
    }

    private static void onBuildCreativeTab(
            BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(beaconItem);
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(
                MinecraftPlatform.RESOURCE_NAMESPACE, path);
    }
}

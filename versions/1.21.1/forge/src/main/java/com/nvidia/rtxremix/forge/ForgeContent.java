/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge;

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
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.RegisterEvent;

/** Forge registrations for gameplay behavior owned by the shared layer. */
public final class ForgeContent {
    private static final ResourceLocation BEACON_ID = ResourceLocation.fromNamespaceAndPath(
            MinecraftPlatform.RESOURCE_NAMESPACE, CopperBeaconBlock.ID);
    private static BlockItem beaconItem;
    private static final MenuType<CopperBeaconScreenHandler> BEACON_MENU =
            IForgeMenuType.create((syncId, inventory, buffer) ->
                    new CopperBeaconScreenHandler(
                            syncId,
                            inventory,
                            new CopperBeaconScreenData(buffer.readBlockPos())));

    private ForgeContent() {
    }

    public static void register(IEventBus modBus) {
        CopperBeaconScreenHandler.installType(BEACON_MENU);
        modBus.addListener(ForgeContent::onRegister);
        modBus.addListener(ForgeContent::onBuildCreativeTab);
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

    private static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(beaconItem);
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(
                MinecraftPlatform.RESOURCE_NAMESPACE, path);
    }
}

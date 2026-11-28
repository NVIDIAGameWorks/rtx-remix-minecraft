/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.fabric;

import com.nvidia.rtxremix.minecraft.MinecraftNetworking;
import com.nvidia.rtxremix.minecraft.MinecraftPlatform;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlock;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconScreenData;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconScreenHandler;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconUpdatePayload;
import com.nvidia.rtxremix.minecraft.particle.rapids.RapidFoamParticles;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.inventory.MenuType;

/** Fabric registrations for shared Minecraft content and its control packet. */
public final class FabricContent {
    /**
     * Vanilla's "Functional Blocks" creative tab, where torches and lanterns live,
     * built from its registry id because {@code CreativeModeTabs.FUNCTIONAL_BLOCKS}
     * is private.
     */
    private static final ResourceKey<CreativeModeTab> FUNCTIONAL_BLOCKS = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("functional_blocks"));

    private FabricContent() {
    }

    public static void register() {
        Identifier beaconId =
                Identifier.fromNamespaceAndPath(
                        MinecraftPlatform.RESOURCE_NAMESPACE, CopperBeaconBlock.ID);
        Registry.register(BuiltInRegistries.BLOCK, beaconId, CopperBeaconBlock.INSTANCE);
        BlockItem beaconItem = Registry.register(
                BuiltInRegistries.ITEM,
                beaconId,
                new BlockItem(CopperBeaconBlock.INSTANCE, new Item.Properties()
                        .setId(ResourceKey.create(Registries.ITEM, beaconId))
                        .useBlockDescriptionPrefix()));
        CreativeModeTabEvents.modifyOutputEvent(FUNCTIONAL_BLOCKS)
                .register(entries -> entries.accept(beaconItem));
        Registry.register(
                BuiltInRegistries.BLOCK_ENTITY_TYPE, beaconId, CopperBeaconBlockEntity.TYPE);

        MenuType<CopperBeaconScreenHandler> menuType = new ExtendedMenuType<>(
                CopperBeaconScreenHandler::new, CopperBeaconScreenData.PACKET_CODEC);
        CopperBeaconScreenHandler.installType(menuType);
        Registry.register(BuiltInRegistries.MENU, beaconId, menuType);
        Registry.register(
                BuiltInRegistries.PARTICLE_TYPE,
                id("rapid_foam"),
                RapidFoamParticles.RAPID_FOAM);
        Registry.register(
                BuiltInRegistries.PARTICLE_TYPE,
                id("rapid_foam_dot"),
                RapidFoamParticles.RAPID_FOAM_DOT);
        Registry.register(
                BuiltInRegistries.PARTICLE_TYPE,
                id("rapid_mist"),
                RapidFoamParticles.RAPID_MIST);

        PayloadTypeRegistry.serverboundPlay().register(
                CopperBeaconUpdatePayload.ID, CopperBeaconUpdatePayload.PACKET_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(
                CopperBeaconUpdatePayload.ID,
                (payload, context) -> context.server().execute(() ->
                        MinecraftNetworking.handleServerPayload(
                                context.player(), payload)));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(
                MinecraftPlatform.RESOURCE_NAMESPACE, path);
    }
}

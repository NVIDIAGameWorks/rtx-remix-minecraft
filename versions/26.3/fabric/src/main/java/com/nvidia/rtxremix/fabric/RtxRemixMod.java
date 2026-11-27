/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.fabric;

import com.nvidia.rtxremix.minecraft.MinecraftClientRuntime;
import com.nvidia.rtxremix.minecraft.MinecraftNetworking;
import com.nvidia.rtxremix.minecraft.MinecraftParticleHooks;
import com.nvidia.rtxremix.minecraft.MinecraftRuntime;
import com.nvidia.rtxremix.minecraft.MinecraftTerrainHooks;
import com.nvidia.rtxremix.minecraft.RtxClientCommands;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconScreenHandler;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconUpdatePayload;
import com.nvidia.rtxremix.minecraft.entity.CopperBeaconBlockEntityRenderer;
import com.nvidia.rtxremix.minecraft.particle.rapids.WaterRapidParticleSpawner;
import com.nvidia.rtxremix.minecraft.screen.CopperBeaconScreen;
import com.nvidia.rtxremix.minecraft.settings.MinecraftSettingsButton;
import com.nvidia.rtxremix.minecraft.weather.RemixWeatherRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

/** Fabric registration and event adapter for the canonical Minecraft client runtime. */
public final class RtxRemixMod implements ClientModInitializer {
    private static final MinecraftClientRuntime CLIENT_RUNTIME =
            new MinecraftClientRuntime();

    private KeyMapping toggleHeldLightsKey;
    private KeyMapping toggleRtxKey;

    @Override
    public void onInitializeClient() {
        CLIENT_RUNTIME.bootstrap();
        MinecraftTerrainHooks.install(new FabricTerrainHooks());
        MinecraftParticleHooks.install(
                WaterRapidParticleSpawner::spawnRandomDisplayParticles);
        MinecraftNetworking.installClientSender(payload -> {
            if (ClientPlayNetworking.canSend(CopperBeaconUpdatePayload.ID)) {
                ClientPlayNetworking.send(payload);
            }
        });

        registerRenderers();
        registerCommands();
        FabricParticles.registerProviders();
        registerKeyBindings();
        registerScreenHooks();
        registerLifecycleHooks();
        registerChunkHooks();
        registerResourceHooks();
        registerWorldRenderHooks();

        CLIENT_RUNTIME.finishSetup();
    }

    private static void registerRenderers() {
        EntityModelLayerRegistry.registerModelLayer(
                CopperBeaconBlockEntityRenderer.BODY_MODEL_LAYER,
                CopperBeaconBlockEntityRenderer::getBodyTexturedModelData);
        EntityModelLayerRegistry.registerModelLayer(
                CopperBeaconBlockEntityRenderer.LENS_MODEL_LAYER,
                CopperBeaconBlockEntityRenderer::getLensTexturedModelData);
        BlockEntityRenderers.register(
                CopperBeaconBlockEntity.TYPE,
                CopperBeaconBlockEntityRenderer::new);
        MenuScreens.register(
                CopperBeaconScreenHandler.TYPE,
                CopperBeaconScreen::new);
        CoreShaderRegistrationCallback.EVENT.register(context ->
                context.register(
                        RemixWeatherRenderer.rainShaderId(),
                        RemixWeatherRenderer.rainShaderFormat(),
                        RemixWeatherRenderer::setRainShader));
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            new RtxClientCommands<>(
                    CLIENT_RUNTIME,
                    new RtxClientCommands.SourceAdapter<FabricClientCommandSource>() {
                        @Override
                        public void feedback(
                                FabricClientCommandSource source,
                                Component message) {
                            source.sendFeedback(message);
                        }

                        @Override
                        public LocalPlayer player(FabricClientCommandSource source) {
                            return source.getPlayer();
                        }
                    }).register(dispatcher);
            MinecraftRuntime.LOGGER.info("Registered /rtx command");
        });
    }

    private void registerKeyBindings() {
        toggleHeldLightsKey = KeyBindingHelper.registerKeyBinding(
                MinecraftClientRuntime.createToggleHeldLightsKey());
        toggleRtxKey = KeyBindingHelper.registerKeyBinding(
                MinecraftClientRuntime.createToggleRtxKey());

        ClientTickEvents.END_CLIENT_TICK.register(client ->
                CLIENT_RUNTIME.handleKeyMappings(
                        client, toggleHeldLightsKey, toggleRtxKey));
    }

    private static void registerScreenHooks() {
        ScreenEvents.AFTER_INIT.register(
                (client, screen, scaledWidth, scaledHeight) ->
                        MinecraftSettingsButton.add(
                                client,
                                screen,
                                scaledWidth,
                                scaledHeight,
                                Screens.getButtons(screen),
                                CLIENT_RUNTIME.settings(),
                                CLIENT_RUNTIME::saveSettings));
    }

    private void registerLifecycleHooks() {
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            CLIENT_RUNTIME.onClientStopping();
        });
        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> CLIENT_RUNTIME.onJoin(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            CLIENT_RUNTIME.onDisconnect();
        });
        ClientTickEvents.END_CLIENT_TICK.register(CLIENT_RUNTIME::onClientTick);
    }

    private static void registerChunkHooks() {
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            var pos = chunk.getPos();
            CLIENT_RUNTIME.onClientChunkUnload(pos.x, pos.z);
        });
        ServerChunkEvents.CHUNK_LOAD.register(
                (world, chunk) ->
                        CLIENT_RUNTIME.onServerChunkLoad(world.getServer(), chunk));
    }

    private static void registerResourceHooks() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public ResourceLocation getFabricId() {
                        return ResourceLocation.fromNamespaceAndPath(
                                RtxRemixCommonMod.MOD_ID, "atlas_reload");
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager manager) {
                        CLIENT_RUNTIME.onClientResourcesReload(manager);
                    }
                });
    }

    private void registerWorldRenderHooks() {
        WorldRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, hitResult) -> {
            return true;
        });
        WorldRenderEvents.BLOCK_OUTLINE.register((context, outlineContext) -> {
            CLIENT_RUNTIME.captureBlockOutline(
                    context.world(),
                    outlineContext.blockPos(),
                    outlineContext.blockState(),
                    outlineContext.entity(),
                    context.camera(),
                    context.tickCounter().getGameTimeDeltaPartialTick(true));
            return true;
        });
    }

}

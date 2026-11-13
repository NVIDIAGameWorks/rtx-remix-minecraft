/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.neoforge;

import com.nvidia.rtxremix.minecraft.MinecraftClientRuntime;
import com.nvidia.rtxremix.minecraft.MinecraftParticleHooks;
import com.nvidia.rtxremix.minecraft.MinecraftTerrainHooks;
import com.nvidia.rtxremix.minecraft.RtxClientCommands;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconScreenHandler;
import com.nvidia.rtxremix.minecraft.entity.CopperBeaconBlockEntityRenderer;
import com.nvidia.rtxremix.minecraft.particle.rapids.RapidFoamDotParticle;
import com.nvidia.rtxremix.minecraft.particle.rapids.RapidFoamParticle;
import com.nvidia.rtxremix.minecraft.particle.rapids.RapidFoamParticles;
import com.nvidia.rtxremix.minecraft.particle.rapids.RapidMistParticle;
import com.nvidia.rtxremix.minecraft.particle.rapids.WaterRapidParticleSpawner;
import com.nvidia.rtxremix.minecraft.screen.CopperBeaconScreen;
import com.nvidia.rtxremix.minecraft.settings.MinecraftSettingsButton;
import com.nvidia.rtxremix.minecraft.weather.RemixWeatherRenderer;
import java.io.IOException;
import java.util.ArrayList;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/** Client-only NeoForge adapter installation. */
final class NeoForgeClientBootstrap {
    static final MinecraftClientRuntime RUNTIME = new MinecraftClientRuntime();

    private NeoForgeClientBootstrap() {
    }

    private static KeyMapping toggleHeldLights;
    private static KeyMapping toggleRtx;

    static void install(IEventBus modBus) {
        MinecraftTerrainHooks.install(new NeoForgeTerrainHooks());
        MinecraftParticleHooks.install(
                WaterRapidParticleSpawner::spawnRandomDisplayParticles);
        NeoForgeNetworking.installClientSender();
        RUNTIME.bootstrap();
        modBus.addListener(NeoForgeClientBootstrap::onRegisterMenuScreens);
        modBus.addListener(NeoForgeClientBootstrap::onRegisterLayers);
        modBus.addListener(NeoForgeClientBootstrap::onRegisterRenderers);
        modBus.addListener(NeoForgeClientBootstrap::onRegisterParticles);
        modBus.addListener(NeoForgeClientBootstrap::onRegisterShaders);
        modBus.addListener(NeoForgeClientBootstrap::onRegisterKeyMappings);
        modBus.addListener(NeoForgeClientBootstrap::onRegisterReloadListeners);
        IEventBus gameBus = NeoForge.EVENT_BUS;
        gameBus.addListener(NeoForgeClientBootstrap::onClientTick);
        gameBus.addListener(NeoForgeClientBootstrap::onLogout);
        gameBus.addListener(NeoForgeClientBootstrap::onLogin);
        gameBus.addListener(NeoForgeClientBootstrap::onBlockHighlight);
        gameBus.addListener(NeoForgeClientBootstrap::onScreenInit);
        gameBus.addListener(NeoForgeClientBootstrap::onRegisterCommands);
        gameBus.addListener(NeoForgeClientBootstrap::onShutdown);
        gameBus.addListener(NeoForgeClientBootstrap::onChunkUnload);
        gameBus.addListener(NeoForgeClientBootstrap::onChunkLoad);
        RUNTIME.finishSetup();
    }

    private static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(CopperBeaconScreenHandler.TYPE, CopperBeaconScreen::new);
    }

    private static void onRegisterLayers(
            EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(
                CopperBeaconBlockEntityRenderer.BODY_MODEL_LAYER,
                CopperBeaconBlockEntityRenderer::getBodyTexturedModelData);
        event.registerLayerDefinition(
                CopperBeaconBlockEntityRenderer.LENS_MODEL_LAYER,
                CopperBeaconBlockEntityRenderer::getLensTexturedModelData);
    }

    private static void onRegisterRenderers(
            EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                CopperBeaconBlockEntity.TYPE,
                CopperBeaconBlockEntityRenderer::new);
    }

    private static void onRegisterParticles(
            RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(
                RapidFoamParticles.RAPID_FOAM, RapidFoamParticle.Factory::new);
        event.registerSpriteSet(
                RapidFoamParticles.RAPID_FOAM_DOT,
                RapidFoamDotParticle.Factory::new);
        event.registerSpriteSet(
                RapidFoamParticles.RAPID_MIST, RapidMistParticle.Factory::new);
    }

    private static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(
                            event.getResourceProvider(),
                            RemixWeatherRenderer.rainShaderId(),
                            RemixWeatherRenderer.rainShaderFormat()),
                    RemixWeatherRenderer::setRainShader);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to register Remix weather shader", e);
        }
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        toggleHeldLights = MinecraftClientRuntime.createToggleHeldLightsKey();
        toggleRtx = MinecraftClientRuntime.createToggleRtxKey();
        event.register(toggleHeldLights);
        event.register(toggleRtx);
    }

    private static void onRegisterReloadListeners(
            RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager ->
                RUNTIME.onClientResourcesReload(manager));
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        RUNTIME.handleKeyMappings(client, toggleHeldLights, toggleRtx);
        RUNTIME.onClientTick(client);
    }

    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        RUNTIME.onDisconnect();
    }

    private static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft client = Minecraft.getInstance();
        RUNTIME.onJoin(client);
    }

    private static void onBlockHighlight(RenderHighlightEvent.Block event) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        var pos = event.getTarget().getBlockPos();
        RUNTIME.captureBlockOutline(
                client.level,
                pos,
                client.level.getBlockState(pos),
                event.getCamera().getEntity(),
                event.getCamera(),
                event.getDeltaTracker().getGameTimeDeltaPartialTick(false));
    }

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        var widgets =
                new ArrayList<net.minecraft.client.gui.components.AbstractWidget>();
        for (var listener : event.getListenersList()) {
            if (listener
                    instanceof net.minecraft.client.gui.components.AbstractWidget widget) {
                widgets.add(widget);
            }
        }
        Minecraft client = Minecraft.getInstance();
        MinecraftSettingsButton.add(
                client,
                event.getScreen(),
                event.getScreen().width,
                event.getScreen().height,
                widgets,
                RUNTIME.settings(),
                RUNTIME::saveSettings);
        for (var widget : widgets) {
            if (!event.getListenersList().contains(widget)) {
                event.addListener(widget);
            }
        }
    }

    private static void onRegisterCommands(RegisterClientCommandsEvent event) {
        new RtxClientCommands<>(
                RUNTIME,
                new RtxClientCommands.SourceAdapter<
                        net.minecraft.commands.CommandSourceStack>() {
                    @Override
                    public void feedback(
                            net.minecraft.commands.CommandSourceStack source,
                            net.minecraft.network.chat.Component message) {
                        source.sendSuccess(() -> message, false);
                    }

                    @Override
                    public net.minecraft.client.player.LocalPlayer player(
                            net.minecraft.commands.CommandSourceStack source) {
                        return Minecraft.getInstance().player;
                    }
                }).register(event.getDispatcher());
    }

    private static void onShutdown(GameShuttingDownEvent event) {
        RUNTIME.onClientStopping();
    }

    private static void onChunkUnload(ChunkEvent.Unload event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        var pos = event.getChunk().getPos();
        RUNTIME.onClientChunkUnload(pos.x, pos.z);
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide()
                || !(event.getChunk()
                        instanceof net.minecraft.world.level.chunk.LevelChunk chunk)) {
            return;
        }
        RUNTIME.onServerChunkLoad(event.getLevel().getServer(), chunk);
    }
}

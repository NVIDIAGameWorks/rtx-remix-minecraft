/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge;

import com.nvidia.rtxremix.minecraft.MinecraftTerrainHooks;
import com.nvidia.rtxremix.minecraft.MinecraftParticleHooks;
import com.nvidia.rtxremix.minecraft.MinecraftClientRuntime;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconScreenHandler;
import com.nvidia.rtxremix.minecraft.entity.CopperBeaconBlockEntityRenderer;
import com.nvidia.rtxremix.minecraft.RtxClientCommands;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Client-only Forge adapter installation. */
final class ForgeClientBootstrap {
    static final MinecraftClientRuntime RUNTIME = new MinecraftClientRuntime();

    private ForgeClientBootstrap() {
    }

    private static KeyMapping toggleHeldLights;
    private static KeyMapping toggleRtx;

    static void install(IEventBus modBus) {
        MinecraftTerrainHooks.install(new ForgeTerrainHooks());
        MinecraftParticleHooks.install(
                WaterRapidParticleSpawner::spawnRandomDisplayParticles);
        ForgeNetworking.installClientSender(() -> {
            var listener = Minecraft.getInstance().getConnection();
            return listener != null ? listener.getConnection() : null;
        });
        RUNTIME.bootstrap();
        modBus.addListener(ForgeClientBootstrap::onClientSetup);
        modBus.addListener(ForgeClientBootstrap::onRegisterLayers);
        modBus.addListener(ForgeClientBootstrap::onRegisterRenderers);
        modBus.addListener(ForgeClientBootstrap::onRegisterParticles);
        modBus.addListener(ForgeClientBootstrap::onRegisterShaders);
        modBus.addListener(ForgeClientBootstrap::onRegisterKeyMappings);
        modBus.addListener(ForgeClientBootstrap::onRegisterReloadListeners);
        IEventBus forgeBus = MinecraftForge.EVENT_BUS;
        forgeBus.addListener(ForgeClientBootstrap::onClientTick);
        forgeBus.addListener(ForgeClientBootstrap::onLogout);
        forgeBus.addListener(ForgeClientBootstrap::onLogin);
        forgeBus.addListener(ForgeClientBootstrap::onBlockHighlight);
        forgeBus.addListener(ForgeClientBootstrap::onScreenInit);
        forgeBus.addListener(ForgeClientBootstrap::onRegisterCommands);
        forgeBus.addListener(ForgeClientBootstrap::onShutdown);
        forgeBus.addListener(ForgeClientBootstrap::onChunkUnload);
        forgeBus.addListener(ForgeClientBootstrap::onChunkLoad);
        RUNTIME.finishSetup();
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(
                CopperBeaconScreenHandler.TYPE, CopperBeaconScreen::new));
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

    private static void onRegisterParticles(RegisterParticleProvidersEvent event) {
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
            throw new IllegalStateException("Failed to register Remix weather shader", e);
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
        event.registerReloadListener((ResourceManagerReloadListener) manager -> {
            RUNTIME.onClientResourcesReload(manager);
        });
    }

    private static void onClientTick(TickEvent.ClientTickEvent.Post event) {
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
                event.getPartialTick());
    }

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        var widgets = new ArrayList<net.minecraft.client.gui.components.AbstractWidget>();
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

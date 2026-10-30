/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.nvidia.rtxremix.minecraft.weather.RemixWeatherRenderer;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.texture.AtlasAnimationForwarder;
import com.nvidia.rtxremix.minecraft.terrain.WorldRendererTerrainAccess;
import com.nvidia.rtxremix.minecraft.terrain.SkyLightSurfaceScanPolicy;
import com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter;
import com.nvidia.rtxremix.minecraft.terrain.LocalChunkBatchPacing;
import com.nvidia.rtxremix.minecraft.terrain.ClientLightSnapshots;
import com.nvidia.rtxremix.minecraft.terrain.ClientLightSnapshotPolicy;
import com.nvidia.rtxremix.minecraft.terrain.ChunkManager;
import com.nvidia.rtxremix.minecraft.sky.SkyRenderer;
import com.nvidia.rtxremix.minecraft.settings.RtxSettingsStore;
import com.nvidia.rtxremix.minecraft.settings.RtxSettingsScreen;
import com.nvidia.rtxremix.minecraft.settings.RtxSettingsFactory;
import com.nvidia.rtxremix.minecraft.settings.RtxSettings;
import com.nvidia.rtxremix.minecraft.screen.CopperBeaconScreen;
import com.nvidia.rtxremix.minecraft.profiling.WorldRendererProfilingAccess;
import com.nvidia.rtxremix.minecraft.profiling.TracyGCListener;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.particle.rapids.RapidFoamParticles;
import com.nvidia.rtxremix.minecraft.logic.ZoneRegistry;
import com.nvidia.rtxremix.minecraft.logic.ZonePersistentState;
import com.nvidia.rtxremix.minecraft.logic.ZoneDefinition;
import com.nvidia.rtxremix.minecraft.logic.MarkerBlockScanner;
import com.nvidia.rtxremix.minecraft.logic.LogicChannels;
import com.nvidia.rtxremix.minecraft.logic.ConfLayerDriver;
import com.nvidia.rtxremix.minecraft.light.LightManager;
import com.nvidia.rtxremix.minecraft.light.HeldLightState;
import com.nvidia.rtxremix.minecraft.light.BlockLightRuleManager;
import com.nvidia.rtxremix.minecraft.input.RemixMouseAccess;
import com.nvidia.rtxremix.minecraft.input.RemixKeyboardAccess;
import com.nvidia.rtxremix.minecraft.input.RemixInputGate;
import com.nvidia.rtxremix.minecraft.gl.TextureOverlay;
import com.nvidia.rtxremix.minecraft.entity.CopperBeaconBlockEntityRenderer;
import com.nvidia.rtxremix.minecraft.camera.CameraController;
import com.nvidia.rtxremix.minecraft.camera.StillShot;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconScreenHandler;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.nvidia.rtxremix.ErrorCode;
import com.nvidia.rtxremix.RemixException;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntBiFunction;

/**
 * Main mod entrypoint for RTX Remix Renderer.
 * 
 * This mod submits Minecraft scene data to NVIDIA RTX Remix and displays the
 * path-traced result in-game.
 */
public class RtxRemixMod implements ClientModInitializer {
    
    public static final Logger LOGGER = LoggerFactory.getLogger(RtxRemixMod.class);

    /** Names a quality preset to apply for one launch; the launchers' --preset sets it. */
    private static final String LAUNCH_PRESET_PROPERTY = "rtxremix.preset";

    /** The one shot set the in-game commands edit; the harness captures it as the documentation images. */
    private static final String DOCS_SHOT_SET = "docs";

    private static RtxRemixMod instance;
    static public RemixRenderer renderer;
    private static RtxSettings rtxSettings;
    private static RtxSettingsStore rtxSettingsStore;
    private boolean remixAvailable = false;

    public static RtxSettings getRtxSettings() {
        return rtxSettings;
    }

    public static void saveRtxSettings() {
        if (rtxSettings != null && rtxSettingsStore != null) {
            rtxSettingsStore.save(rtxSettings);
        }
    }

    /** Applies the preset named by {@value #LAUNCH_PRESET_PROPERTY}, if any. */
    private static void applyLaunchPreset() {
        String name = System.getProperty(LAUNCH_PRESET_PROPERTY);
        if (name == null || name.isBlank()) {
            return;
        }
        // There is no chat at startup, so the outcome lands in the log.
        LOGGER.info("{}", applyQualityPreset(name.strip()).getString());
    }

    /** Sets Remix's graphics tier for this session and returns what to tell the player. */
    private static Component applyQualityPreset(String name) {
        Optional<QualityPreset> preset = QualityPreset.byId(name);
        if (preset.isEmpty()) {
            return Component.literal("No preset '" + name + "'. Available: "
                    + String.join(", ", QualityPreset.ids()));
        }
        if (renderer == null || renderer.getRemixApi() == null) {
            return Component.literal("Preset '" + name + "' did NOT apply: the Remix runtime is unavailable");
        }
        preset.get().writeTo(renderer.getRemixApi()::setConfigVariable);
        return Component.literal("Remix graphics preset '" + name + "' applied for this session");
    }
    
    // Volatile: read off the render thread by SectionBuilderMixin's chunk-builder
    // workers and by ChunkDataSenderMixin on the integrated-server thread.
    private static volatile boolean rtxEnabled = true;
    private static boolean entitiesEnabled = true;
    private static boolean worldEnabled = true;
    private static final boolean DEFAULT_SKY_ENABLED = true;
    private static boolean skyEnabled = DEFAULT_SKY_ENABLED;
    private static boolean blockEntitiesEnabled = true;
    private static boolean particlesEnabled = true;
    /**
     * Whether a lit item held in first person trails smoke. Held smoke on
     * third-person and other entities' models is unconditional and is not
     * governed by this value.
     */
    private static boolean firstPersonSmokeEnabled = true;

    private TextureOverlay textureOverlay;
    private ProfileScope worldRenderPhaseScope;
    private boolean remixFrameReadyToSubmit;
    /** Latches the frame-failure log so a persistent failure reports once, not every frame. */
    private boolean frameFailureLogged;

    private static final RemixInputGate REMIX_INPUT_GATE = new RemixInputGate();
    /** Reads into the gate's own buffer. Held in a field so the per-frame read allocates nothing. */
    private static final ToIntBiFunction<String, byte[]> REMIX_CONFIG_READER =
            (key, value) -> renderer.getRemixApi().getConfigVariableResolved(key, value);
    /** Whether the last menu visibility read failed, so a run of failures logs once. */
    private static boolean remixMenuVisibilityReadFailed;
    private KeyMapping toggleHeldLightsKey;
    private KeyMapping toggleRtxKey;
    private static final long CAMERA_TIME_STEP_TICKS = 10L;
    private static final long CAMERA_TIME_FAST_STEP_TICKS = 250L;

    /** Returns the shared Minecraft-side input gate used by the GLFW callback mixins. */
    public static RemixInputGate getRemixInputGate() {
        return REMIX_INPUT_GATE;
    }

    /** Returns the version stamped into this mod's Fabric metadata. */
    public static String getModVersion() {
        return FabricLoader.getInstance()
                .getModContainer(RtxRemixCommonMod.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    /**
     * Opens the developer menu, or closes whichever Remix menu is open.
     * Called from {@link KeyboardMixin} on ALT+X.
     */
    public static void toggleRemixMenu() {
        if (REMIX_INPUT_GATE.isCaptureSessionOpen()) {
            closeRemixMenu();
        } else {
            openRemixMenu(RemixInputGate.Menu.DEVELOPER);
        }
    }

    /**
     * Opens a Remix menu, which then owns all input until Alt+X or the menu's own
     * controls close it. Does nothing when a menu is already open.
     */
    public static void openRemixMenu(RemixInputGate.Menu menu) {
        if (!isRemixMenuAvailable() || REMIX_INPUT_GATE.isCaptureSessionOpen()) {
            return;
        }
        try {
            REMIX_INPUT_GATE.open(menu, renderer.getRemixApi()::setConfigVariable);
        } catch (Exception e) {
            LOGGER.warn("Failed to open Remix menu", e);
            return;
        }
        takeInputFromMinecraft();
        LOGGER.info("Remix menu open: {}", menu);
    }

    /** Closes the open Remix menu on Alt+X and returns input to Minecraft. */
    private static void closeRemixMenu() {
        if (!isRemixMenuAvailable()) {
            return;
        }
        releaseRemixMenuCapture();
        relockCursorUnlessScreenOpen();
        LOGGER.info("Remix menu closed");
    }

    /**
     * Moves input capture with a Remix menu the runtime showed or hid itself: through a menu's
     * own controls, the runtime's menu hotkey, or its First Use Guide. Called at the start of
     * every frame from {@link com.nvidia.rtxremix.minecraft.mixin.GameRendererMixin}.
     */
    public static void followRemixMenuVisibility() {
        if (renderer == null || !renderer.isInitialized()) {
            return;
        }
        RemixInputGate.VisibilityChange change;
        try {
            change = REMIX_INPUT_GATE.followVisibility(REMIX_CONFIG_READER);
        } catch (RemixException e) {
            if (!remixMenuVisibilityReadFailed) {
                LOGGER.warn("Failed to read Remix menu visibility, so input capture stays unchanged until a read succeeds", e);
                remixMenuVisibilityReadFailed = true;
            }
            return;
        }
        remixMenuVisibilityReadFailed = false;
        switch (change) {
            case OPENED -> {
                takeInputFromMinecraft();
                LOGGER.info("Remix menu opened by the runtime");
            }
            case CLOSED -> {
                KeyMapping.releaseAll();
                relockCursorUnlessScreenOpen();
                LOGGER.info("Remix menu closed by the runtime");
            }
            case OWN_WRITE_OVERDUE -> LOGGER.warn(
                    "Remix has not applied the mod's rtx.showUI write after {} frames, so input capture"
                            + " follows the menu Remix shows instead. A config layer above Derived Settings,"
                            + " such as user.conf or an acquired conf layer, may set rtx.showUI.",
                    RemixInputGate.OWN_WRITE_OVERDUE_FRAMES);
            case NONE -> { }
        }
    }

    /**
     * Finalizes Minecraft's held key bindings, F3 combination and mouse button, whose releases
     * will not reach Minecraft while a menu holds input, and frees the cursor.
     */
    private static void takeInputFromMinecraft() {
        Minecraft client = Minecraft.getInstance();
        KeyMapping.releaseAll();
        ((RemixKeyboardAccess) client.keyboardHandler).rtxremix$resetDebugKeyState();
        try {
            ((RemixMouseAccess) client.mouseHandler).rtxremix$releaseActiveButton();
        } finally {
            client.mouseHandler.releaseMouse();
        }
    }

    /** Relocks the cursor for gameplay unless a Minecraft screen is current. */
    private static void relockCursorUnlessScreenOpen() {
        Minecraft client = Minecraft.getInstance();
        if (client.screen == null) {
            client.mouseHandler.grabMouse();
        }
    }

    private static boolean isRemixMenuAvailable() {
        if (instance != null && renderer != null && renderer.isInitialized()) {
            return true;
        }
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.player != null) {
            client.player.displayClientMessage(
                    Component.literal("[RTX Remix] Menu unavailable: renderer is not initialized. Run /rtx status."),
                    false);
        }
        LOGGER.warn("Remix menu request ignored because the renderer is not initialized");
        return false;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        EntityModelLayerRegistry.registerModelLayer(
                CopperBeaconBlockEntityRenderer.BODY_MODEL_LAYER,
                CopperBeaconBlockEntityRenderer::getBodyTexturedModelData);
        EntityModelLayerRegistry.registerModelLayer(
                CopperBeaconBlockEntityRenderer.LENS_MODEL_LAYER,
                CopperBeaconBlockEntityRenderer::getLensTexturedModelData);
        BlockEntityRenderers.register(
                CopperBeaconBlockEntity.TYPE, CopperBeaconBlockEntityRenderer::new);
        MenuScreens.register(CopperBeaconScreenHandler.TYPE, CopperBeaconScreen::new);
        RemixWeatherRenderer.registerShaders();
        LOGGER.info("========================================");
        LOGGER.info("RTX Remix Renderer v{} initializing...", getModVersion());
        LOGGER.info("========================================");

        NativeExtractor.extractIfNeeded();
        TracyGCListener.install();
        LOGGER.info(NeighbourReflagFilter.effectiveStateLine());
        LOGGER.info(ClientLightSnapshotPolicy.effectiveStateLine());
        LOGGER.info(LocalChunkBatchPacing.effectiveStateLine());
        LOGGER.info(SkyLightSurfaceScanPolicy.effectiveStateLine());

        remixAvailable = checkRemixAvailability();
        
        registerCommands();
        // Particle types are a client-side registry, so they stay here. Blocks
        // are synced and register in RtxRemixCommonMod instead.
        RapidFoamParticles.register();

        // G is unbound in vanilla 1.21.1 and unclaimed elsewhere in this mod, so
        // this is a real registered binding rather than a raw GLFW intercept: it
        // shows up in Options -> Controls and players can rebind it.
        toggleHeldLightsKey = KeyBindingHelper.registerKeyBinding(
            new KeyMapping(
                "key.rtxremix.toggleHeldLights",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.rtxremix"
            )
        );
        // F8 is unbound in vanilla 1.21.1 and sits away from the movement keys: a
        // toggle that reloads the world renderer should not be a stray press away
        // while playing. Same registered-binding treatment as the held-lights key.
        toggleRtxKey = KeyBindingHelper.registerKeyBinding(
            new KeyMapping(
                "key.rtxremix.toggleRtx",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                "category.rtxremix"
            )
        );

        ScreenEvents.AFTER_INIT.register(
                (client, screen, scaledWidth, scaledHeight) -> {
                    if (!(screen instanceof OptionsScreen)) {
                        return;
                    }
                    if (rtxSettings == null) {
                        return;
                    }
                    List<AbstractWidget> menuButtons = Screens.getButtons(screen);
                    Component settingsButtonText = Component.translatable("rtxremix.settings.button");
                    menuButtons.removeIf(
                            widget -> widget.getMessage().equals(settingsButtonText));
                    AbstractWidget done = null;
                    for (AbstractWidget w : menuButtons) {
                        if (w.getMessage().equals(CommonComponents.GUI_DONE)) {
                            done = w;
                            break;
                        }
                    }
                    Button button = Button.builder(
                                    settingsButtonText,
                                    b -> client.setScreen(
                                            new RtxSettingsScreen(
                                                    screen, rtxSettings, RtxRemixMod::saveRtxSettings)))
                            .build();
                    if (done != null) {
                        int lastBodyY = Integer.MIN_VALUE;
                        for (AbstractWidget widget : menuButtons) {
                            if (widget != done && widget.getY() < done.getY()) {
                                lastBodyY = Math.max(lastBodyY, widget.getY());
                            }
                        }
                        if (lastBodyY == Integer.MIN_VALUE) {
                            lastBodyY = done.getY() - 24;
                        }
                        int buttonY = lastBodyY + 24;
                        int overflow = buttonY + 24 - done.getY();
                        if (overflow > 0) {
                            int firstBodyY = lastBodyY - 96;
                            for (AbstractWidget widget : menuButtons) {
                                if (widget != done
                                        && widget.getY() >= firstBodyY
                                        && widget.getY() <= lastBodyY) {
                                    widget.setY(widget.getY() - overflow);
                                }
                            }
                            buttonY -= overflow;
                        }
                        button.setRectangle(308, 20, scaledWidth / 2 - 154, buttonY);
                    } else {
                        button.setRectangle(
                                200, 20, scaledWidth / 2 - 100, scaledHeight - 27);
                    }
                    menuButtons.add(button);
                });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try (ProfileScope tickScope = ProfileScope.begin("ClientTick.END.heldLightsKeybind")) {
                if (toggleHeldLightsKey == null) {
                    return;
                }
                // Drained rather than tested, so two presses in one tick toggle
                // twice and land back where they started. Not gated on renderer
                // init: the toggle is plain state, and swallowing presses while
                // Remix is still coming up would leave it out of step with what
                // the player pressed.
                while (toggleHeldLightsKey.consumeClick()) {
                    boolean off = HeldLightState.toggleLocalHeldLights();
                    LOGGER.info("Held lights {}", off ? "off" : "on");
                }
            }
            if (toggleRtxKey != null) {
                // Drained like the held-lights key, but applied once by parity: every
                // RTX toggle reloads the world renderer and recaptures terrain, so two
                // presses in one tick should cost nothing rather than two reloads.
                // Vanilla only feeds key bindings while no screen is open, and the
                // Remix overlay takes all input while its capture session is open, so
                // neither needs a check here.
                int presses = 0;
                while (toggleRtxKey.consumeClick()) {
                    presses++;
                }
                if ((presses & 1) == 1) {
                    toggleRtx();
                    if (client.player != null) {
                        client.player.displayClientMessage(
                                Component.literal("RTX Rendering: " + (rtxEnabled ? "ON" : "OFF")), true);
                    }
                }
            }
        });

        if (!remixAvailable) {
            LOGGER.warn("========================================");
            LOGGER.warn("RTX Remix SDK not found!");
            LOGGER.warn("Expected d3d9.dll at: {}",
                    RemixRenderer.runtimeDll(FabricLoader.getInstance().getGameDir()));
            LOGGER.warn("Mod will continue but rendering disabled.");
            LOGGER.warn("========================================");
        }
        
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            LOGGER.info("Minecraft client started");
            
            textureOverlay = new TextureOverlay();
            
            if (remixAvailable) {
                LOGGER.info("Initializing RTX Remix renderer...");
                initializeRemix(client);
                if (renderer != null && renderer.isInitialized()) {
                    rtxSettings = RtxSettingsFactory.build(renderer);
                    Path cfg = FabricLoader.getInstance()
                            .getConfigDir().resolve("rtxremix.json");
                    rtxSettingsStore = new RtxSettingsStore(cfg);
                    rtxSettingsStore.load(rtxSettings);
                    LOGGER.info("RTX settings loaded from {}", cfg);
                    applyLaunchPreset();
                }
            } else {
                LOGGER.warn("RTX Remix disabled - SDK not found");
            }
        });
        
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            shutdownForClientStop();
        });
        
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            releaseRemixMenuCapture();
            if (renderer != null) {
                ZoneRegistry zoneRegistry = renderer.getZoneRegistry();
                // Clear first so definitions from another save or remote server
                // cannot leak into this session, then hydrate the integrated
                // server's world-saved definitions asynchronously.
                zoneRegistry.clear();
                restoreWorldSavedZones(client, zoneRegistry);
                acquireWorldConfLayers(client);
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOGGER.info("Disconnected from world, clearing Remix resources...");
            releaseRemixMenuCapture();
            closeOpenProfileScopes();
            if (renderer != null) {
                renderer.getBenchmarkController().onDisconnect(renderer.getPerfTimer());
                renderer.getCameraController().resetSession();
                renderer.getZoneRegistry().clear();
                // The map's own profiles leave with the map; the mod catalog
                // keeps driving so the main menu and the next world are clean.
                ConfLayerDriver leavingDriver = renderer.getConfLayerDriver();
                if (leavingDriver != null) {
                    leavingDriver.onWorldLeave();
                }
            }
            if (renderer != null && renderer.isInitialized()) {
                renderer.clearWorld();
            }
        });

        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            ChunkPos pos = chunk.getPos();
            if (renderer != null && renderer.getChunkManager() != null) {
                renderer.getChunkManager().onChunkUnloaded(pos);
            }
            if (Minecraft.getInstance().levelRenderer instanceof WorldRendererTerrainAccess access) {
                access.rtxremix$onNeighbourColumnInvalidated(pos.x, pos.z);
            }
        });

        // Zone discovery: server-thread scan of command blocks as chunks load.
        // The event only fires in-process (integrated server = single player).
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            if (renderer != null && Minecraft.getInstance().getSingleplayerServer() == world.getServer()) {
                MarkerBlockScanner.scanChunk(renderer.getZoneRegistry(), chunk);
            }
        });

        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public ResourceLocation getFabricId() {
                        return ResourceLocation.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "atlas_reload");
                    }
                    @Override
                    public void onResourceManagerReload(ResourceManager manager) {
                        BlockLightRuleManager.reload(manager);
                        if (renderer != null) renderer.requestTextureReload();
                    }
                });

        // Applies pending world and resource changes and preloads the large
        // block-entity atlases before vanilla's world render, where block entities
        // are captured in place by BlockEntityRenderDispatcherMixin.
        WorldRenderEvents.START.register(context -> {
            if (renderer != null && renderer.isInitialized()) {
                renderer.beginBlockOutlineFrame();
            }
            if (isRendererProfilingActive()) {
                try (ProfileScope startScope = ProfileScope.begin("WorldRender.START")) {
                    renderer.beginWorldRenderFrame();
                }
                transitionWorldRenderPhase("WorldRender.vanilla.setup");
            } else {
                closeWorldRenderPhaseScope();
            }
        });

        WorldRenderEvents.AFTER_SETUP.register(context ->
            transitionWorldRenderPhase("WorldRender.vanilla.terrainOpaque"));
        WorldRenderEvents.BEFORE_ENTITIES.register(context ->
            transitionWorldRenderPhase("WorldRender.vanilla.entities"));
        WorldRenderEvents.AFTER_ENTITIES.register(context ->
            transitionWorldRenderPhase("WorldRender.vanilla.blockEntities"));
        WorldRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, hitResult) -> {
            transitionWorldRenderPhase("WorldRender.vanilla.blockOutline");
            return true;
        });
        WorldRenderEvents.BLOCK_OUTLINE.register((context, outlineContext) -> {
            if (renderer != null
                    && renderer.isInitialized()
                    && isRtxEnabled()) {
                renderer.captureBlockOutline(context, outlineContext);
            }
            return true;
        });
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context ->
            transitionWorldRenderPhase("WorldRender.vanilla.debugAndTranslucent"));
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context ->
            transitionWorldRenderPhase("WorldRender.vanilla.weatherClouds"));
        WorldRenderEvents.LAST.register(context -> {
            transitionWorldRenderPhase("WorldRender.vanilla.last");
            closeWorldRendererProfilerPhase(context);
        });

        // WorldRenderer returns to GameRenderer before Minecraft draws the
        // first-person arms/items. Mark the scene ready here, then submit it
        // from GameRendererMixin after that model has been captured and before
        // Minecraft draws its screen overlays.
        WorldRenderEvents.END.register(context -> {
            closeWorldRendererProfilerPhase(context);
            closeWorldRenderPhaseScope();
            ProfileScope.markWorldRenderEnd();
            remixFrameReadyToSubmit = renderer != null && renderer.isInitialized();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try (ProfileScope tickScope = ProfileScope.begin("ClientTick.END.benchmark")) {
                if (renderer != null) {
                    renderer.getCameraController().onClientTick(client);
                    renderer.getBenchmarkController().onClientTick(client);
                }
            }
        });

        LOGGER.info("RTX Remix Renderer initialized successfully!");
    }

    /**
     * Whether GameRenderer should replace the current vanilla held-item draw
     * with a Remix-only capture.
     */
    public static boolean shouldCaptureFirstPersonModel() {
        return instance != null
                && instance.remixFrameReadyToSubmit
                && renderer != null
                && renderer.isInitialized()
                && isRtxEnabled();
    }

    /**
     * Submits and composites the ready Remix scene exactly once. Called before
     * Minecraft's screen overlays, or from a render-tail fallback when that
     * overlay path is skipped.
     */
    public static void submitRemixFrameIfReady() {
        RtxRemixMod mod = instance;
        if (mod == null || !mod.remixFrameReadyToSubmit) {
            return;
        }
        mod.remixFrameReadyToSubmit = false;

        // The submit and blit below run whenever the renderer is initialized,
        // including while RTX is switched off: they are what signals the VK->GL
        // and GL->VK semaphores, and skipping them desynchronizes the pair
        // across an off-to-on transition.
        if (renderer == null || !renderer.isInitialized()) {
            return;
        }

        try (ProfileScope callbackScope = ProfileScope.begin("WorldRender.END")) {
            int remixTexture;
            try {
                renderer.renderAndSubmitFrame();
                remixTexture = renderer.blitVkImageToGL();
            } catch (RuntimeException e) {
                // Compositing is skipped, so this frame presents whatever GL drew, which
                // with RTX on holds neither terrain nor entities: both are captured for
                // Remix instead of drawn. The renderer stays enabled because a failure here
                // is per-frame, and an initialization failure is thrown at startup.
                if (!mod.frameFailureLogged) {
                    LOGGER.error("Remix frame failed; skipping compositing this frame", e);
                    mod.frameFailureLogged = true;
                }
                return;
            }
            mod.frameFailureLogged = false;
            if (remixTexture > 0 && mod.textureOverlay != null && isRtxEnabled()) {
                mod.textureOverlay.render(remixTexture);
                RemixWeatherRenderer weather = renderer.getWeatherRenderer();
                if (weather != null) {
                    try (ProfileScope weatherScope = ProfileScope.begin("Weather")) {
                        weather.render(
                                remixTexture,
                                renderer.getInteropDepthTexture());
                        weatherScope
                                .drawCalls(weather.getLastDrawCount())
                                .vertexCount(weather.getLastQuadCount() * 4);
                    }
                }
            }
        }
    }

    private void transitionWorldRenderPhase(String phaseName) {
        if (!isRendererProfilingActive()) {
            closeWorldRenderPhaseScope();
            return;
        }

        closeWorldRenderPhaseScope();
        if (ProfileScope.isDeepProfilingEnabled()) {
            worldRenderPhaseScope = ProfileScope.beginJfrOnly(phaseName)
                    .plotOnClose("Java." + phaseName + ".ms");
        }
    }

    private boolean isRendererProfilingActive() {
        return renderer != null && renderer.isInitialized() && rtxEnabled;
    }

    private void closeWorldRenderPhaseScope() {
        ProfileScope scope = worldRenderPhaseScope;
        if (scope != null) {
            worldRenderPhaseScope = null;
            scope.close();
        }
    }

    private static void closeWorldRendererProfilerPhase(WorldRenderContext context) {
        if (context != null && context.worldRenderer() instanceof WorldRendererProfilingAccess access) {
            access.rtxremix$closeProfilerPhaseForFabricEvent();
        }
    }

    private void closeOpenProfileScopes() {
        closeWorldRenderPhaseScope();
    }

    private boolean checkRemixAvailability() {
        Path dll = RemixRenderer.runtimeDll(FabricLoader.getInstance().getGameDir());
        if (!Files.isRegularFile(dll)) {
            return false;
        }
        LOGGER.info("Found RTX Remix SDK at: {}", dll);
        return true;
    }
    
    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("rtx")
                .executes(context -> {
                    toggleRtx();
                    context.getSource().sendFeedback(Component.literal("RTX Rendering: " + (rtxEnabled ? "ON" : "OFF")));
                    return 1;
                })
                .then(ClientCommandManager.literal("on")
                    .executes(context -> {
                        setRtxEnabled(true);
                        context.getSource().sendFeedback(Component.literal("RTX Rendering: ON"));
                        return 1;
                    }))
                .then(ClientCommandManager.literal("off")
                    .executes(context -> {
                        setRtxEnabled(false);
                        context.getSource().sendFeedback(Component.literal("RTX Rendering: OFF"));
                        return 1;
                    }))
                .then(ClientCommandManager.literal("status")
                    .executes(context -> {
                        context.getSource().sendFeedback(Component.literal("RTX Status:"));
                        context.getSource().sendFeedback(Component.literal("  Enabled (master): " + rtxEnabled));
                        context.getSource().sendFeedback(Component.literal(
                                "  World: " + worldEnabled + " (active " + isWorldActive() + ")"
                                + ", Entities: " + entitiesEnabled + " (active " + isEntitiesActive() + ")"
                                + ", BlockEntities: " + blockEntitiesEnabled + " (active " + isBlockEntitiesActive() + ")"
                                + ", Particles: " + particlesEnabled + " (active " + isParticlesActive() + ")"
                                + ", Sky: " + skyEnabled + " (active " + isSkyActive() + ")"));
                        context.getSource().sendFeedback(Component.literal("  Sky Dome: " + SkyRenderer.isEnabled() + ", Sky Ambient Rect: " + SkyRenderer.isSkyAmbientRectEnabled()));
                        context.getSource().sendFeedback(Component.literal(
                                "  Held Lights: " + (HeldLightState.isLocalHeldLightsOff() ? "OFF" : "ON")
                                + ", Held Flames Under Water: "
                                + (HeldLightState.areFlamesAllowedUnderwater() ? "ALLOWED" : "EXTINGUISHED")));
                        context.getSource().sendFeedback(Component.literal(
                                "  Renderer Initialized: " + (renderer != null && renderer.isInitialized())));
                        context.getSource().sendFeedback(Component.literal("  SDK Available: " + remixAvailable));
                        if (renderer != null && renderer.getChunkManager() != null) {
                            ChunkManager cm = renderer.getChunkManager();
                            context.getSource().sendFeedback(Component.literal("  Loaded Chunks: " + cm.getLoadedChunkCount()));
                            context.getSource().sendFeedback(Component.literal("  Retained Pages: " + cm.getTerrainDiagnostics().retainedPages()));
                        }
                        if (renderer != null && renderer.getEntityPass() != null) {
                            context.getSource().sendFeedback(Component.literal("  Entity Cull Distance: "
                                    + renderer.getEntityPass().getEntityCullDistance() + " chunks"));
                        }
                        if (renderer != null && renderer.getChunkManager() != null) {
                            context.getSource().sendFeedback(Component.literal("  Light Cull Distance: "
                                    + renderer.getChunkManager().getLightCullDistance() + " chunks"
                                    + (renderer.getChunkManager().getLightCullDistance() == 0 ? " (view distance)" : "")));
                            context.getSource().sendFeedback(Component.literal("  Light Reach: "
                                    + renderer.getChunkManager().getLightReach() + " blocks"));
                        }
                        if (renderer != null && renderer.getLightManager() != null) {
                            context.getSource().sendFeedback(Component.literal("  Active Lights: " + renderer.getLightManager().getLightCount()));
                        }
                        if (renderer != null) {
                            context.getSource().sendFeedback(Component.literal("  View Distance Fade: " + renderer.getViewDistanceFade().describe()));
                        }
                        return 1;
                    }))
                .then(ClientCommandManager.literal("entities")
                    .executes(context -> {
                        entitiesEnabled = !entitiesEnabled;
                        context.getSource().sendFeedback(Component.literal("Entity Rendering: " + (entitiesEnabled ? "ON" : "OFF")));
                        return 1;
                    }))
                // /rtx blockentities - toggle block entity rendering (chests, furnaces, signs, etc.)
                .then(ClientCommandManager.literal("blockentities")
                    .executes(context -> {
                        blockEntitiesEnabled = !blockEntitiesEnabled;
                        context.getSource().sendFeedback(Component.literal("Block Entity Rendering: " + (blockEntitiesEnabled ? "ON" : "OFF")));
                        return 1;
                    }))
                .then(ClientCommandManager.literal("particles")
                    .executes(context -> {
                        particlesEnabled = !particlesEnabled;
                        context.getSource().sendFeedback(Component.literal("Particle Rendering: " + (particlesEnabled ? "ON" : "OFF")));
                        return 1;
                    }))
                // /rtx heldlights - put the local player's held lights out or relight
                // them, as the Toggle Held Lights key does; /rtx heldlights underwater
                // lets open flames keep burning under water.
                .then(ClientCommandManager.literal("heldlights")
                    .executes(context -> {
                        boolean off = HeldLightState.toggleLocalHeldLights();
                        context.getSource().sendFeedback(Component.literal("Held lights: " + (off ? "OFF" : "ON")));
                        return 1;
                    })
                    .then(ClientCommandManager.literal("underwater")
                        .executes(context -> {
                            boolean allowed = !HeldLightState.areFlamesAllowedUnderwater();
                            HeldLightState.setFlamesAllowedUnderwater(allowed);
                            context.getSource().sendFeedback(Component.literal(
                                    "Held flames under water: " + (allowed ? "ALLOWED" : "EXTINGUISHED")));
                            return 1;
                        })))
                // /rtx world - toggle world rendering
                .then(ClientCommandManager.literal("world")
                    .executes(context -> {
                        worldEnabled = !worldEnabled;
                        context.getSource().sendFeedback(Component.literal("World Rendering: " + (worldEnabled ? "ON" : "OFF")));
                        return 1;
                    }))
                .then(ClientCommandManager.literal("skyDomeMult")
                    .executes(context -> {
                        context.getSource().sendFeedback(Component.literal(
                                "Sky dome multiplier: " + SkyRenderer.getSkyDomeMultiplier()));
                        return 1;
                    })
                    .then(ClientCommandManager.argument("value",
                            FloatArgumentType.floatArg(0.0f))
                        .executes(context -> {
                            float v = FloatArgumentType.getFloat(context, "value");
                            SkyRenderer.setSkyDomeMultiplier(v);
                            context.getSource().sendFeedback(Component.literal("Sky dome multiplier: " + v));
                            return 1;
                        })))
                .then(ClientCommandManager.literal("sky")
                    .executes(context -> {
                        setSkyEnabled(!isSkyEnabled());
                        context.getSource().sendFeedback(Component.literal("Sky Rendering: " + (skyEnabled ? "ON" : "OFF")));
                        return 1;
                    })
                    .then(ClientCommandManager.literal("ambient")
                        .executes(context -> {
                            SkyRenderer.setSkyAmbientRectEnabled(!SkyRenderer.isSkyAmbientRectEnabled());
                            boolean on = SkyRenderer.isSkyAmbientRectEnabled();
                            context.getSource().sendFeedback(Component.literal("Sky Ambient Rect: " + (on ? "ON" : "OFF")));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("night")
                            .executes(context -> {
                                context.getSource().sendFeedback(Component.literal("Night sky ambient boost: " + SkyRenderer.getNightAmbientBoost()));
                                return 1;
                            })
                            .then(ClientCommandManager.argument("value",
                                    FloatArgumentType.floatArg(0.0f, 10.0f))
                                .executes(context -> {
                                    float v = FloatArgumentType.getFloat(context, "value");
                                    SkyRenderer.setNightAmbientBoost(v);
                                    if (renderer != null && renderer.getSkyRenderer() != null)
                                        renderer.getSkyRenderer().forceRefresh();
                                    context.getSource().sendFeedback(Component.literal("Night sky ambient boost: " + v));
                                    return 1;
                                })))
                        .then(ClientCommandManager.literal("day")
                            .executes(context -> {
                                context.getSource().sendFeedback(Component.literal("Day sky ambient boost: " + SkyRenderer.getDayAmbientBoost()));
                                return 1;
                            })
                            .then(ClientCommandManager.argument("value",
                                    FloatArgumentType.floatArg(0.0f, 5.0f))
                                .executes(context -> {
                                    float v = FloatArgumentType.getFloat(context, "value");
                                    SkyRenderer.setDayAmbientBoost(v);
                                    if (renderer != null && renderer.getSkyRenderer() != null)
                                        renderer.getSkyRenderer().forceRefresh();
                                    context.getSource().sendFeedback(Component.literal("Day sky ambient boost: " + v));
                                    return 1;
                                }))))
                    .then(ClientCommandManager.literal("minimumopacity")
                        .executes(context -> {
                            context.getSource().sendFeedback(Component.literal("Minimum celestial weather visibility: " + SkyRenderer.getMinimumCelestialWeatherVisibility()));
                            return 1;
                        })
                        .then(ClientCommandManager.argument("value",
                                FloatArgumentType.floatArg(0.0f, 1.0f))
                            .executes(context -> {
                                float v = FloatArgumentType.getFloat(context, "value");
                                SkyRenderer.setMinimumCelestialWeatherVisibility(v);
                                if (renderer != null && renderer.getSkyRenderer() != null)
                                    renderer.getSkyRenderer().forceRefresh();
                                saveRtxSettings();
                                context.getSource().sendFeedback(
                                        Component.literal("Minimum celestial weather visibility: " + v));
                                return 1;
                            })))
                    .then(ClientCommandManager.literal("sun")
                        .executes(context -> {
                            context.getSource().sendFeedback(Component.literal("Sun intensity: " + LightManager.getSunIntensity()));
                            return 1;
                        })
                        .then(ClientCommandManager.argument("value",
                                FloatArgumentType.floatArg(0.0f, 100.0f))
                            .executes(context -> {
                                float v = FloatArgumentType.getFloat(context, "value");
                                LightManager.setSunIntensity(v);
                                if (renderer != null && renderer.getSkyRenderer() != null)
                                    renderer.getSkyRenderer().forceRefresh();
                                context.getSource().sendFeedback(Component.literal("Sun intensity: " + v));
                                return 1;
                            })))
                    .then(ClientCommandManager.literal("moon")
                        .executes(context -> {
                            context.getSource().sendFeedback(Component.literal("Moon intensity: " + LightManager.getMoonIntensity()));
                            return 1;
                        })
                        .then(ClientCommandManager.argument("value",
                                FloatArgumentType.floatArg(0.0f, 10.0f))
                            .executes(context -> {
                                float v = FloatArgumentType.getFloat(context, "value");
                                LightManager.setMoonIntensity(v);
                                if (renderer != null && renderer.getSkyRenderer() != null)
                                    renderer.getSkyRenderer().forceRefresh();
                                context.getSource().sendFeedback(Component.literal("Moon intensity: " + v));
                                return 1;
                            }))))
                .then(ClientCommandManager.literal("culldistance")
                    .executes(context -> {
                        if (renderer != null && renderer.getChunkManager() != null) {
                            int light = renderer.getChunkManager().getLightCullDistance();
                            context.getSource().sendFeedback(Component.literal("Light cull distance: " + light + " chunks" + (light == 0 ? " (view distance)" : "")));
                            if (renderer.getEntityPass() != null) {
                                int entity = renderer.getEntityPass().getEntityCullDistance();
                                context.getSource().sendFeedback(Component.literal("Entity cull distance: " + entity + " chunks" + (entity == 0 ? " (view distance)" : "")));
                            }
                            context.getSource().sendFeedback(Component.literal("Light reach: "
                                    + renderer.getChunkManager().getLightReach() + " blocks (torch cutoff)"));
                        } else {
                            context.getSource().sendFeedback(Component.literal("Chunk manager not available"));
                        }
                        return 1;
                    })
                    .then(ClientCommandManager.literal("light")
                        .then(ClientCommandManager.argument("chunks",
                                IntegerArgumentType.integer(0, 32))
                            .executes(context -> {
                                int n = IntegerArgumentType.getInteger(context, "chunks");
                                if (renderer != null && renderer.getChunkManager() != null) {
                                    renderer.getChunkManager().setLightCullDistance(n);
                                    context.getSource().sendFeedback(Component.literal("Light cull distance set to " + n + " chunks" + (n == 0 ? " (view distance)" : "")));
                                } else {
                                    context.getSource().sendFeedback(Component.literal("Chunk manager not available"));
                                }
                                return 1;
                            })))
                    .then(ClientCommandManager.literal("entity")
                        .then(ClientCommandManager.argument("chunks",
                                IntegerArgumentType.integer(0, 32))
                            .executes(context -> {
                                int n = IntegerArgumentType.getInteger(context, "chunks");
                                if (renderer != null && renderer.getEntityPass() != null) {
                                    renderer.getEntityPass().setEntityCullDistance(n);
                                    context.getSource().sendFeedback(Component.literal("Entity cull distance set to " + n + " chunks" + (n == 0 ? " (view distance)" : "")));
                                } else {
                                    context.getSource().sendFeedback(Component.literal("Entity renderer not available"));
                                }
                                return 1;
                            })))
                    .then(ClientCommandManager.literal("reach")
                        .then(ClientCommandManager.argument("blocks",
                                IntegerArgumentType.integer(
                                        ChunkManager.MIN_LIGHT_REACH_BLOCKS, ChunkManager.MAX_LIGHT_REACH_BLOCKS))
                            .executes(context -> {
                                int n = IntegerArgumentType.getInteger(context, "blocks");
                                if (renderer != null && renderer.getChunkManager() != null) {
                                    renderer.getChunkManager().setLightReach(n);
                                    context.getSource().sendFeedback(Component.literal("Light reach set to " + n + " blocks"));
                                } else {
                                    context.getSource().sendFeedback(Component.literal("Chunk manager not available"));
                                }
                                return 1;
                            }))))
                .then(ClientCommandManager.literal("lights")
                    .executes(context -> {
                        if (renderer == null || renderer.getChunkManager() == null) {
                            context.getSource().sendFeedback(Component.literal("Chunk manager not available"));
                            return 1;
                        }
                        List<ChunkManager.LightRuleStats> stats =
                                renderer.getChunkManager().lightStatsByRule();
                        int total = 0, drawn = 0;
                        for (ChunkManager.LightRuleStats s : stats) {
                            total += s.total();
                            drawn += s.drawn();
                        }
                        context.getSource().sendFeedback(Component.literal(
                                "Terrain lights: " + drawn + " drawn of " + total));
                        for (ChunkManager.LightRuleStats s : stats) {
                            context.getSource().sendFeedback(Component.literal(
                                    "  " + s.ruleId().getPath() + ": " + s.drawn() + " / " + s.total()));
                        }
                        return 1;
                    }))
                .then(ClientCommandManager.literal("preset")
                    .executes(context -> {
                        context.getSource().sendFeedback(Component.literal(
                                "Presets: " + String.join(", ", QualityPreset.ids())
                                        + ". A preset sets Remix's graphics tier for this session."));
                        return 1;
                    })
                    .then(ClientCommandManager.argument("name",
                            StringArgumentType.word())
                        .suggests((context, builder) -> {
                            for (String name : QualityPreset.ids()) {
                                builder.suggest(name);
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            String name = StringArgumentType
                                    .getString(context, "name");
                            context.getSource().sendFeedback(applyQualityPreset(name));
                            return 1;
                        })))
                .then(ClientCommandManager.literal("viewdistance")
                    .executes(context -> {
                        if (renderer != null) {
                            context.getSource().sendFeedback(Component.literal("View distance fade: " + renderer.getViewDistanceFade().describe()));
                        } else {
                            context.getSource().sendFeedback(Component.literal("Renderer not available"));
                        }
                        return 1;
                    })
                    .then(ClientCommandManager.literal("auto")
                        .executes(context -> {
                            if (renderer != null) {
                                boolean on = !renderer.getViewDistanceFade().isAuto();
                                renderer.setViewDistanceFadeAuto(on);
                                context.getSource().sendFeedback(Component.literal("View distance fade: " + renderer.getViewDistanceFade().describe()));
                            }
                            return 1;
                        })
                        .then(ClientCommandManager.literal("on")
                            .executes(context -> {
                                if (renderer != null) {
                                    renderer.setViewDistanceFadeAuto(true);
                                    context.getSource().sendFeedback(Component.literal("View distance fade: " + renderer.getViewDistanceFade().describe()));
                                }
                                return 1;
                            }))
                        .then(ClientCommandManager.literal("off")
                            .executes(context -> {
                                if (renderer != null) {
                                    renderer.setViewDistanceFadeAuto(false);
                                    context.getSource().sendFeedback(Component.literal("View distance fade: " + renderer.getViewDistanceFade().describe()));
                                }
                                return 1;
                            }))))
                .then(ClientCommandManager.literal("reload")
                    .executes(context -> {
                        if (rebuildLoadedTerrain()) {
                            context.getSource().sendFeedback(Component.literal("Terrain cleared - recapturing the sections that were built"));
                        } else {
                            context.getSource().sendFeedback(Component.literal("Remix world rendering inactive - nothing to reload"));
                        }
                        return 1;
                    }))
                .then(sssCommand())
                .then(zoneCommand())
                .then(ClientCommandManager.literal("conf")
                    .then(ClientCommandManager.literal("reload")
                        .executes(context -> {
                            // Layers parse their conf files at acquire time, so a
                            // release + re-acquire re-reads every conf from disk and
                            // re-scans confs/zones/ for new or removed zone confs:
                            // a no-restart iteration loop for conf authors.
                            ConfLayerDriver driver = renderer != null ? renderer.getConfLayerDriver() : null;
                            if (driver == null) {
                                context.getSource().sendFeedback(Component.literal(
                                        "Conf layers unavailable: the Remix renderer is not initialized"));
                                return 1;
                            }
                            if (driver.reload()) {
                                context.getSource().sendFeedback(Component.literal(
                                        "Conf layers re-acquired (zones rescanned) - edited conf values are live"));
                            } else if (driver.isActive()) {
                                // reload() also reports failure when only the
                                // selected quality preset could not be
                                // re-acquired; the catalog is driving.
                                context.getSource().sendFeedback(Component.literal(
                                        "Conf layers re-acquired, but the selected quality preset conf could not be "
                                                + "re-acquired (see log)"));
                            } else {
                                context.getSource().sendFeedback(Component.literal(
                                        "Conf layer reload failed - conf-layer driving disabled (see log)"));
                            }
                            return 1;
                        }))
                    .then(ClientCommandManager.literal("status")
                        // Default: only interesting rows (nonzero strengths and
                        // unacquired layers) + a summary; "all" dumps every layer.
                        .executes(context -> sendConfStatus(context.getSource(), false))
                        .then(ClientCommandManager.literal("all")
                            .executes(context -> sendConfStatus(context.getSource(), true)))))
                .then(ClientCommandManager.literal("water")
                    .executes(context -> {
                        BlockTextureManager textureManager = getTextureManager();
                        if (textureManager == null) {
                            context.getSource().sendFeedback(Component.literal("Water material not available"));
                            return 0;
                        }
                        sendWaterStatus(context.getSource(), textureManager);
                        return 1;
                    })
                    .then(ClientCommandManager.literal("ior")
                        .executes(context -> {
                            BlockTextureManager textureManager = getTextureManager();
                            if (textureManager == null) {
                                context.getSource().sendFeedback(Component.literal("Water material not available"));
                                return 0;
                            }
                            context.getSource().sendFeedback(Component.literal("Water IOR: " + textureManager.getWaterIor()));
                            return 1;
                        })
                        .then(ClientCommandManager.argument("value",
                            FloatArgumentType.floatArg(1.0f, 5.0f))
                            .executes(context -> {
                                float value = FloatArgumentType.getFloat(context, "value");
                                return updateWaterSetting(context.getSource(),
                                        manager -> manager.updateWaterIor(value),
                                        "Water IOR set to " + value);
                            })))
                    .then(ClientCommandManager.literal("distance")
                        .executes(context -> {
                            BlockTextureManager textureManager = getTextureManager();
                            if (textureManager == null) {
                                context.getSource().sendFeedback(Component.literal("Water material not available"));
                                return 0;
                            }
                            context.getSource().sendFeedback(Component.literal(
                                "Water transmittance distance: " + textureManager.getWaterTransmittanceDistance()));
                            return 1;
                        })
                        .then(ClientCommandManager.argument("value",
                            FloatArgumentType.floatArg(0.001f, 1000.0f))
                            .executes(context -> {
                                float value = FloatArgumentType.getFloat(context, "value");
                                return updateWaterSetting(context.getSource(),
                                        manager -> manager.updateWaterTransmittanceDistance(value),
                                        "Water transmittance distance set to " + value);
                            })))
                    .then(ClientCommandManager.literal("color")
                        .executes(context -> {
                            BlockTextureManager textureManager = getTextureManager();
                            if (textureManager == null) {
                                context.getSource().sendFeedback(Component.literal("Water material not available"));
                                return 0;
                            }
                            context.getSource().sendFeedback(Component.literal("Water transmittance color: "
                                + textureManager.getWaterTransmittanceR() + ", "
                                + textureManager.getWaterTransmittanceG() + ", "
                                + textureManager.getWaterTransmittanceB()));
                            return 1;
                        })
                        .then(ClientCommandManager.argument("r",
                            FloatArgumentType.floatArg(0.0f, 10.0f))
                            .then(ClientCommandManager.argument("g",
                                FloatArgumentType.floatArg(0.0f, 10.0f))
                                .then(ClientCommandManager.argument("b",
                                    FloatArgumentType.floatArg(0.0f, 10.0f))
                                    .executes(context -> {
                                        float r = FloatArgumentType.getFloat(context, "r");
                                        float g = FloatArgumentType.getFloat(context, "g");
                                        float b = FloatArgumentType.getFloat(context, "b");
                                        return updateWaterSetting(context.getSource(),
                                                manager -> manager.updateWaterTransmittanceColor(r, g, b),
                                                "Water transmittance color set to " + r + ", " + g + ", " + b);
                                    }))))))
            );

            dispatcher.register(ClientCommandManager.literal("rtx")
                .then(benchmarkCommand())
                .then(cameraCommand())
                .then(shotCommand())
            );
            
            LOGGER.info("Registered /rtx command");
        });
    }

    /**
     * /rtx conf status: with a large authored catalog a flat dump can flood
     * chat, so the default view is the bounded summary from
     * ConfLayerDriver.formatStatusSummary; "all" prints every layer.
     */
    private int sendConfStatus(FabricClientCommandSource source,
                               boolean all) {
        ConfLayerDriver driver = renderer != null ? renderer.getConfLayerDriver() : null;
        if (driver == null) {
            source.sendFeedback(Component.literal(
                    "Conf layers unavailable: the Remix renderer is not initialized"));
            return 1;
        }
        if (!driver.isActive()) {
            source.sendFeedback(Component.literal(
                    "Conf layers inactive (reload failed); run /rtx conf reload to retry"));
            return 1;
        }
        List<ConfLayerDriver.LayerStatus> layers = driver.snapshot();
        if (all) {
            int active = 0;
            int idle = 0;
            int unacquired = 0;
            for (ConfLayerDriver.LayerStatus layer : layers) {
                if (!layer.acquired()) {
                    unacquired++;
                } else if (layer.strength() > 0.0f) {
                    active++;
                } else {
                    idle++;
                }
                source.sendFeedback(Component.literal("  " + layer.name() + " = "
                        + (layer.acquired() ? String.valueOf(layer.strength()) : "unacquired (see log)")));
            }
            source.sendFeedback(Component.literal(String.format("Conf layers: %d active, %d idle, %d unacquired",
                    active, idle, unacquired)));
        } else {
            // Bounded default view (worst case 12 lines; healthy quiet = 1):
            // summary header, capped active rows, one line for unacquired.
            for (String line : ConfLayerDriver.formatStatusSummary(layers)) {
                source.sendFeedback(Component.literal(line));
            }
        }
        return 1;
    }

    /**
     * Acquires the joined world's own conf profiles from
     * {@code <world>/rtx-remix/confs/}, so a published map carries its looks
     * inside the save rather than needing a separate download.
     *
     * <p>Reads the save directory on the server thread, mirroring
     * {@link #restoreWorldSavedZones}, then acquires on the client/render
     * thread because that is where the driver owns its handles. A remote
     * server gives this client no save directory, so map profiles are
     * single-player only, exactly like world-saved zone definitions.</p>
     */
    private static void acquireWorldConfLayers(Minecraft client) {
        ConfLayerDriver driver = renderer != null ? renderer.getConfLayerDriver() : null;
        if (driver == null) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            driver.onWorldLeave(); // nothing to read; drop any previous world's layers
            return;
        }
        try {
            server.execute(() -> {
                Path worldDir;
                try {
                    worldDir = server.getWorldPath(LevelResource.ROOT).normalize();
                } catch (RuntimeException e) {
                    LOGGER.error("Failed to resolve the world directory for conf layers", e);
                    return;
                }
                client.execute(() -> {
                    // The session can end while this hop is in flight.
                    if (client.getSingleplayerServer() != server || renderer == null
                            || renderer.getConfLayerDriver() != driver) {
                        return;
                    }
                    driver.onWorldJoin(worldDir);
                });
            });
        } catch (RuntimeException e) {
            LOGGER.error("Failed to queue world conf-layer acquisition", e);
        }
    }

    /**
     * Loads the integrated server's saved zones without blocking the client
     * thread. Identity checks prevent a late task from repopulating the
     * registry after disconnect or a fast world switch.
     */
    private static void restoreWorldSavedZones(Minecraft client, ZoneRegistry targetRegistry) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return; // Remote multiplayer retains local/session-only zones.
        }
        server.execute(() -> {
            Collection<ZoneDefinition> saved;
            try {
                // Absent is the normal case for a map whose zones are command
                // blocks; the scanner supplies those independently.
                ZonePersistentState state = ZonePersistentState.getIfPresent(server);
                saved = state != null ? state.snapshot() : List.of();
            } catch (RuntimeException e) {
                LOGGER.error("Failed to load world-saved RTX Remix zones", e);
                return;
            }
            client.execute(() -> {
                if (client.getSingleplayerServer() != server || renderer == null
                        || renderer.getZoneRegistry() != targetRegistry) {
                    return;
                }
                targetRegistry.putAll(saved);
                LOGGER.info("Restored {} world-saved RTX Remix zone(s)", saved.size());

                // Spawn chunks can load before JOIN clears the registry. Scan
                // them again after hydration so command-block markers remain a
                // reliable alternative to world-saved chat definitions.
                MarkerBlockScanner.rescan(server, targetRegistry,
                        () -> client.getSingleplayerServer() == server && renderer != null
                                && renderer.getZoneRegistry() == targetRegistry);
            });
        });
    }

    /** Queues an integrated-server state update; false means remote multiplayer. */
    private static boolean saveZoneToCurrentWorld(ZoneDefinition zone,
                                                   Consumer<Boolean> completion) {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return false;
        }
        try {
            server.execute(() -> {
                boolean succeeded = true;
                try {
                    // Storing a definition is the one path that should create
                    // the save data if this world has none yet.
                    ZonePersistentState.getOrCreate(server).put(zone);
                } catch (RuntimeException e) {
                    succeeded = false;
                    LOGGER.error("Failed to save RTX Remix zone '{}'", zone.name(), e);
                }
                boolean result = succeeded;
                client.execute(() -> {
                    if (client.getSingleplayerServer() == server) {
                        completion.accept(result);
                    }
                });
            });
        } catch (RuntimeException e) {
            LOGGER.error("Failed to queue save for RTX Remix zone '{}'", zone.name(), e);
            completion.accept(false);
        }
        return true;
    }

    /** Queues deletion from the integrated-server state; false means remote multiplayer. */
    private static boolean removeZoneFromCurrentWorld(String name,
                                                       Consumer<Boolean> completion) {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return false;
        }
        try {
            server.execute(() -> {
                boolean succeeded = true;
                try {
                    // Nothing saved means nothing to remove; do not create the
                    // save data just to delete from it.
                    ZonePersistentState state = ZonePersistentState.getIfPresent(server);
                    if (state != null) {
                        state.remove(name);
                    }
                } catch (RuntimeException e) {
                    succeeded = false;
                    LOGGER.error("Failed to remove saved RTX Remix zone '{}'", name, e);
                }
                boolean result = succeeded;
                client.execute(() -> {
                    if (client.getSingleplayerServer() == server) {
                        completion.accept(result);
                    }
                });
            });
        } catch (RuntimeException e) {
            LOGGER.error("Failed to queue removal of saved RTX Remix zone '{}'", name, e);
            completion.accept(false);
        }
        return true;
    }

    private LiteralArgumentBuilder<
            FabricClientCommandSource> zoneCommand() {
        return ClientCommandManager.literal("zone")
            // /rtx zone define <name> [--inner r] [--outer r] [--center x y z] ... - define or replace a
            // logic zone; radii are optional (bare define = inner 10 / outer 30, box --fade defaults to 20)
            .then(ClientCommandManager.literal("define")
                .then(ClientCommandManager.argument("spec",
                        StringArgumentType.greedyString())
                    .executes(context -> {
                        if (renderer == null) {
                            context.getSource().sendFeedback(Component.literal("Remix renderer not available"));
                            return 1;
                        }
                        ConfLayerDriver defineDriver = renderer.getConfLayerDriver();
                        if (defineDriver == null || !defineDriver.isActive()) {
                            context.getSource().sendFeedback(Component.literal(
                                "Conf-layer blending is inactive (layer acquire failure); "
                                + "check /rtx conf status and the log, or retry with /rtx conf reload"));
                            return 1;
                        }
                        LocalPlayer player = context.getSource().getPlayer();
                        if (player == null) {
                            context.getSource().sendFeedback(Component.literal("Player not available"));
                            return 1;
                        }
                        String spec = "rtx zone define "
                                + StringArgumentType.getString(context, "spec");
                        try {
                            ZoneDefinition zone = ZoneDefinition.parse(spec, player.getX(), player.getY(), player.getZ());
                            ZoneRegistry activeRegistry = renderer.getZoneRegistry();
                            activeRegistry.put(zone);
                            boolean savedToWorld = saveZoneToCurrentWorld(zone, succeeded -> {
                                // A join-time snapshot may have been queued just
                                // before this command. Reapply the newer mutation
                                // after its server-state task so stale hydration
                                // cannot restore the previous geometry.
                                if (renderer != null && renderer.getZoneRegistry() == activeRegistry) {
                                    activeRegistry.put(zone);
                                }
                                context.getSource().sendFeedback(Component.literal(succeeded
                                    ? "Saved zone '" + zone.name() + "' -> " + zone.channel()
                                      + " to this single-player world"
                                    : "Zone '" + zone.name() + "' is active, but could not be saved; see the log"));
                            });
                            if (!savedToWorld) {
                                context.getSource().sendFeedback(Component.literal(
                                    "Zone '" + zone.name() + "' -> " + zone.channel()
                                    + " is active locally for this multiplayer session only; "
                                    + "it is not saved or synchronized"));
                            }
                            if (spec.contains("--center") || spec.contains("--box")) {
                                context.getSource().sendFeedback(Component.literal(
                                    "The same definition text also works as a command-block marker"));
                            } else {
                                context.getSource().sendFeedback(Component.literal(String.format(
                                    "Centered at your position (%.1f %.1f %.1f). "
                                    + "Add --center to the text before pasting into a command block, "
                                    + "or the block's position becomes the center.",
                                    player.getX(), player.getY(), player.getZ())));
                            }
                            if (zone.usedDefaultRadii()) {
                                context.getSource().sendFeedback(Component.literal(String.format(
                                    "Radii: inner %.1f, outer %.1f (defaults; pass --inner/--outer to override)",
                                    zone.innerRadius(), zone.outerRadius())));
                            } else if (zone.usedDefaultFade()) {
                                context.getSource().sendFeedback(Component.literal(String.format(
                                    "Fade: %.1f (default; pass --fade to override)", zone.fade())));
                            }
                            if (!defineDriver.hasAcquiredLayer(zone.channel())) {
                                context.getSource().sendFeedback(Component.literal(noConfHint(zone.channel())));
                            }
                        } catch (IllegalArgumentException e) {
                            context.getSource().sendFeedback(Component.literal("Invalid zone: " + e.getMessage()));
                        }
                        return 1;
                    })))
            .then(ClientCommandManager.literal("list")
                .executes(context -> {
                    if (renderer == null) {
                        context.getSource().sendFeedback(Component.literal("Remix renderer not available"));
                        return 1;
                    }
                    Collection<ZoneDefinition> zones = renderer.getZoneRegistry().zones();
                    context.getSource().sendFeedback(Component.literal("Zones: " + zones.size()));
                    for (ZoneDefinition zone : zones) {
                        context.getSource().sendFeedback(Component.literal("  " + zone.name() + " -> " + zone.channel()));
                    }
                    return 1;
                }))
            .then(ClientCommandManager.literal("remove")
                .then(ClientCommandManager.argument("name",
                        StringArgumentType.word())
                    .executes(context -> {
                        if (renderer == null) {
                            context.getSource().sendFeedback(Component.literal("Remix renderer not available"));
                            return 1;
                        }
                        String name = StringArgumentType.getString(context, "name");
                        ZoneRegistry activeRegistry = renderer.getZoneRegistry();
                        boolean removed = activeRegistry.remove(name);
                        boolean removedFromWorld = removeZoneFromCurrentWorld(name, succeeded -> {
                            // See define above: a stale join snapshot must not
                            // resurrect a definition after this newer removal.
                            if (renderer != null && renderer.getZoneRegistry() == activeRegistry) {
                                activeRegistry.remove(name);
                            }
                            String message;
                            if (!succeeded) {
                                message = "Zone '" + name
                                        + "' was removed locally, but the world save could not be updated; see the log";
                            } else if (removed) {
                                message = "Removed zone '" + name + "' from this single-player world";
                            } else {
                                message = "No active zone named '" + name
                                        + "'; any saved definition has been removed";
                            }
                            context.getSource().sendFeedback(Component.literal(message));
                        });
                        if (!removedFromWorld) {
                            context.getSource().sendFeedback(Component.literal(removed
                                    ? "Removed local session zone '" + name + "'"
                                    : "No local session zone named '" + name + "'"));
                        }
                        return 1;
                    })))
            .then(ClientCommandManager.literal("rescan")
                .executes(context -> {
                    if (renderer == null) {
                        context.getSource().sendFeedback(Component.literal("Remix renderer not available"));
                        return 1;
                    }
                    MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
                    if (server == null) {
                        context.getSource().sendFeedback(Component.literal("Rescan needs the integrated server (single-player)"));
                        return 1;
                    }
                    context.getSource().sendFeedback(Component.literal("Rescanning loaded chunks for zone command blocks... (results in log)"));
                    MarkerBlockScanner.rescan(server, renderer.getZoneRegistry());
                    return 1;
                }))
            .then(ClientCommandManager.literal("status")
                .executes(context -> {
                    if (renderer == null) {
                        context.getSource().sendFeedback(Component.literal("Remix renderer not available"));
                        return 1;
                    }
                    Collection<ZoneDefinition> zones = renderer.getZoneRegistry().zones();
                    context.getSource().sendFeedback(Component.literal("Zones: " + zones.size()));
                    LocalPlayer player = context.getSource().getPlayer();
                    ConfLayerDriver statusDriver = renderer.getConfLayerDriver();
                    for (ZoneDefinition zone : zones) {
                        List<String> notes = new ArrayList<>();
                        if (player != null) {
                            notes.add(String.format("weight %.3f here",
                                zone.weight(player.getX(), player.getY(), player.getZ())));
                        }
                        // Zone layers are discovered from conf file names; flag
                        // zones whose channel has no conf backing it yet.
                        if (statusDriver != null && statusDriver.isActive()
                                && !statusDriver.hasAcquiredLayer(zone.channel())) {
                            notes.add(noConfHint(zone.channel()));
                        }
                        String line = "  " + zone.name() + " -> " + zone.channel()
                            + (notes.isEmpty() ? "" : " (" + String.join("; ", notes) + ")");
                        context.getSource().sendFeedback(Component.literal(line));
                    }
                    context.getSource().sendFeedback(Component.literal("Conf layer strengths: /rtx conf status"));
                    return 1;
                }));
    }

    /** One-line pointer from a zone channel with no acquired layer to the conf that would create it. */
    private static String noConfHint(String channel) {
        if (channel.startsWith(LogicChannels.ZONE_PREFIX)) {
            String stem = channel.substring(LogicChannels.ZONE_PREFIX.length());
            return "no conf - create zones/" + stem + ".conf under the mod's confs/ (shared archetype) "
                    + "or <world>/rtx-remix/confs/ (ships with this map), then /rtx conf reload";
        }
        return "no conf layer for channel " + channel;
    }

    private LiteralArgumentBuilder<
            FabricClientCommandSource> sssCommand() {
        return ClientCommandManager.literal("sss")
            .executes(context -> {
                BlockTextureManager textureManager = getTextureManager();
                if (!isCutoutSssAvailable(context.getSource(), textureManager)) return 0;
                sendCutoutSssStatus(context.getSource(), textureManager);
                return 1;
            })
            .then(ClientCommandManager.literal("cutout")
                .executes(context -> {
                    BlockTextureManager textureManager = getTextureManager();
                    if (!isCutoutSssAvailable(context.getSource(), textureManager)) return 0;
                    sendCutoutSssStatus(context.getSource(), textureManager);
                    return 1;
                })
                .then(ClientCommandManager.literal("transmissionDistance")
                    .executes(context -> {
                        BlockTextureManager textureManager = getTextureManager();
                        if (!isCutoutSssAvailable(context.getSource(), textureManager)) return 0;
                        context.getSource().sendFeedback(Component.literal("Cutout SSS transmission distance: "
                                + textureManager.getCutoutSssTransmissionDistance()));
                        return 1;
                    })
                    .then(ClientCommandManager.argument("value",
                            FloatArgumentType.floatArg(0.0001f, 1000.0f))
                        .executes(context -> {
                            float value = FloatArgumentType.getFloat(context, "value");
                            return updateCutoutSssSetting(context.getSource(),
                                    textureManager -> textureManager.updateCutoutSssTransmissionDistance(value),
                                    "Cutout SSS transmission distance set to " + value);
                        })))
                .then(ClientCommandManager.literal("anisotropy")
                    .executes(context -> {
                        BlockTextureManager textureManager = getTextureManager();
                        if (!isCutoutSssAvailable(context.getSource(), textureManager)) return 0;
                        context.getSource().sendFeedback(Component.literal("Cutout SSS anisotropy: "
                                + textureManager.getCutoutSssAnisotropy()));
                        return 1;
                    })
                    .then(ClientCommandManager.argument("value",
                            FloatArgumentType.floatArg(-1.0f, 1.0f))
                        .executes(context -> {
                            float value = FloatArgumentType.getFloat(context, "value");
                            return updateCutoutSssSetting(context.getSource(),
                                    textureManager -> textureManager.updateCutoutSssAnisotropy(value),
                                    "Cutout SSS anisotropy set to " + value);
                        })))
                .then(ClientCommandManager.literal("albedo")
                    .executes(context -> {
                        BlockTextureManager textureManager = getTextureManager();
                        if (!isCutoutSssAvailable(context.getSource(), textureManager)) return 0;
                        context.getSource().sendFeedback(Component.literal("Cutout SSS fallback albedo: "
                                + textureManager.getCutoutSssAlbedoR() + ", "
                                + textureManager.getCutoutSssAlbedoG() + ", "
                                + textureManager.getCutoutSssAlbedoB()));
                        return 1;
                    })
                    .then(ClientCommandManager.argument("r",
                            FloatArgumentType.floatArg(0.0f, 1.0f))
                        .then(ClientCommandManager.argument("g",
                                FloatArgumentType.floatArg(0.0f, 1.0f))
                            .then(ClientCommandManager.argument("b",
                                    FloatArgumentType.floatArg(0.0f, 1.0f))
                                .executes(context -> {
                                    float r = FloatArgumentType.getFloat(context, "r");
                                    float g = FloatArgumentType.getFloat(context, "g");
                                    float b = FloatArgumentType.getFloat(context, "b");
                                    return updateCutoutSssSetting(context.getSource(),
                                            textureManager -> textureManager.updateCutoutSssAlbedo(r, g, b),
                                            "Cutout SSS fallback albedo set to " + r + ", " + g + ", " + b);
                                })))))
                .then(ClientCommandManager.literal("albedoTexture")
                    .executes(context -> {
                        BlockTextureManager textureManager = getTextureManager();
                        if (!isCutoutSssAvailable(context.getSource(), textureManager)) return 0;
                        context.getSource().sendFeedback(Component.literal("Cutout SSS albedo texture: "
                                + textureManager.isCutoutSssAlbedoTextureEnabled()));
                        return 1;
                    })
                    .then(ClientCommandManager.literal("on")
                        .executes(context -> updateCutoutSssSetting(context.getSource(),
                                textureManager -> textureManager.updateCutoutSssAlbedoTextureEnabled(true),
                                "Cutout SSS albedo texture enabled")))
                    .then(ClientCommandManager.literal("off")
                        .executes(context -> updateCutoutSssSetting(context.getSource(),
                                textureManager -> textureManager.updateCutoutSssAlbedoTextureEnabled(false),
                                "Cutout SSS albedo texture disabled")))
                    .then(ClientCommandManager.argument("enabled",
                            BoolArgumentType.bool())
                        .executes(context -> {
                            boolean enabled = BoolArgumentType.getBool(
                                    context, "enabled");
                            return updateCutoutSssSetting(context.getSource(),
                                    textureManager -> textureManager.updateCutoutSssAlbedoTextureEnabled(enabled),
                                    "Cutout SSS albedo texture " + (enabled ? "enabled" : "disabled"));
                        })))
                .then(ClientCommandManager.literal("reset")
                    .executes(context -> updateCutoutSssSetting(context.getSource(),
                            BlockTextureManager::resetCutoutSss,
                            "Cutout SSS settings reset"))));
    }

    private boolean isCutoutSssAvailable(
            FabricClientCommandSource source,
            BlockTextureManager textureManager) {
        if (textureManager == null || !textureManager.isInitialized()) {
            source.sendFeedback(Component.literal("Cutout SSS materials not available"));
            return false;
        }
        return true;
    }

    private int updateCutoutSssSetting(
            FabricClientCommandSource source,
            Function<BlockTextureManager, Boolean> updater,
            String successMessage) {
        BlockTextureManager textureManager = getTextureManager();
        if (!isCutoutSssAvailable(source, textureManager)) return 0;

        boolean hadActiveAtlasMaterial = textureManager.hasCutoutSssAtlasMaterial();
        if (hadActiveAtlasMaterial) {
            clearTerrainForMaterialRebuild();
        }

        boolean rebuilt = Boolean.TRUE.equals(updater.apply(textureManager));
        if (hadActiveAtlasMaterial && !rebuilt) {
            source.sendFeedback(Component.literal("Failed to rebuild active cutout SSS material"));
            return 0;
        }
        if (rebuilt) {
            requestTerrainRebuildForMaterialChange();
        }

        source.sendFeedback(Component.literal(successMessage + (rebuilt
                ? " (terrain rebuild requested)"
                : " (no active cutout SSS atlas material)")));
        return 1;
    }

    private int updateWaterSetting(
            FabricClientCommandSource source,
            Function<BlockTextureManager, Boolean> updater,
            String successMessage) {
        BlockTextureManager textureManager = getTextureManager();
        if (textureManager == null) {
            source.sendFeedback(Component.literal("Water material not available"));
            return 0;
        }

        clearTerrainForMaterialRebuild();

        boolean rebuilt = Boolean.TRUE.equals(updater.apply(textureManager));
        if (!rebuilt) {
            source.sendFeedback(Component.literal("Failed to rebuild water material"));
            return 0;
        }

        requestTerrainRebuildForMaterialChange();
        source.sendFeedback(Component.literal(successMessage + " (terrain rebuild requested)"));
        return 1;
    }

    private void clearTerrainForMaterialRebuild() {
        if (renderer != null && renderer.getChunkManager() != null) {
            renderer.getChunkManager().clear();
        }
    }

    /**
     * Drops every Remix terrain page and recaptures the sections the player had
     * seen. A clear on its own leaves the scene empty until vanilla happens to
     * rebuild a section, so it is always paired with the vanilla reload and the
     * explicit queue. A full rebuild is expensive: settings screens call this
     * once per committed transaction, never per slider step.
     *
     * @return whether an active Remix world was there to rebuild
     */
    public static boolean rebuildLoadedTerrain() {
        Minecraft client = Minecraft.getInstance();
        if (!isWorldActive() || client == null || client.levelRenderer == null) {
            return false;
        }
        if (renderer != null && renderer.getChunkManager() != null) {
            renderer.getChunkManager().clear();
        }
        LongSet built = builtSections(client);
        client.levelRenderer.allChanged();
        recaptureTerrainForRemix(client, built);
        return true;
    }

    /**
     * Recreates shared terrain materials and recaptures loaded terrain after a
     * committed material setting change.
     */
    public static void rebuildTerrainForMaterialSettings() {
        if (renderer != null) {
            renderer.requestMaterialSettingsReload();
        }
    }

    private void requestTerrainRebuildForMaterialChange() {
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.levelRenderer != null) {
            LongSet built = builtSections(client);
            client.levelRenderer.allChanged();
            recaptureTerrainForRemix(client, built);
        }
    }

    /**
     * Sections the last recapture actually queued, kept until the next snapshot so a
     * reload that lands while they are still building does not lose them: their new
     * slots hold {@code ChunkData.EMPTY} until the rebuild completes and would otherwise
     * fall out of the built set. Only what the queue accepted is kept, not its input:
     * the input unions the previous pending set, so storing it would carry every
     * position the residency, non-empty and lighting filters rejected from one
     * recapture to the next and grow without bound across locations. Scoped to the
     * world it was taken in.
     */
    private static LongSet pendingRecapture =
            LongSets.emptySet();
    private static Object pendingRecaptureWorld;

    /**
     * The sections vanilla has built, taken before a {@code WorldRenderer.reload()}
     * forgets them, plus whatever the previous recapture queued in this world that may
     * still be building. Vanilla builds a section when its occlusion walk reaches it, so
     * the built set is the frustum-driven subset of the loaded area: the terrain the
     * Remix scene held, and the same subset a world join produces.
     */
    static LongSet builtSections(Minecraft client) {
        if (!(client.levelRenderer instanceof WorldRendererTerrainAccess access)) {
            return LongSets.emptySet();
        }
        LongSet built = access.rtxremix$builtSectionPositions();
        if (pendingRecaptureWorld == client.level && !pendingRecapture.isEmpty()) {
            LongOpenHashSet union =
                    new LongOpenHashSet(built);
            union.addAll(pendingRecapture);
            return union;
        }
        return built;
    }

    /**
     * Recaptures {@code built} after a vanilla reload. The reload only marks fresh
     * slots for rebuilding and vanilla then schedules the subset its occlusion graph
     * reaches, so the explicit queue is what restores the scene the player had.
     *
     * <p>Only the sections vanilla built are queued, not every loaded section: the
     * loaded area is about three times the frustum-driven built set, and capturing
     * it spends that much more video memory on terrain the Remix scene never held.
     */
    static void recaptureTerrainForRemix(Minecraft client, LongSet built) {
        if (!isWorldActive() || client.levelRenderer == null) return;
        if (client.getCameraEntity() == null) {
            // Without a camera entity, WorldRenderer.reload() skips
            // BuiltChunkStorage.updateCameraPosition, so section origins are
            // still index-based and queueing would capture nothing useful.
            LOGGER.warn("Skipping Remix terrain recapture: no camera entity (world still loading?)");
            return;
        }
        if (client.levelRenderer instanceof WorldRendererTerrainAccess access) {
            LongSet queued = access.rtxremix$queueSectionsForRemix(built);
            pendingRecapture = queued;
            pendingRecaptureWorld = client.level;
            LOGGER.info("Queued {} of {} previously built sections for Remix recapture", queued.size(), built.size());
        }
    }

    private static LiteralArgumentBuilder<
            FabricClientCommandSource> benchmarkCommand() {
        return ClientCommandManager.literal("benchmark")
            .executes(context -> {
                if (renderer != null && renderer.getBenchmarkController() != null) {
                    context.getSource().sendFeedback(
                            Component.literal(renderer.getBenchmarkController().start()));
                }
                return 1;
            })
            .then(ClientCommandManager.literal("stop")
                .executes(context -> {
                    if (renderer != null && renderer.getBenchmarkController() != null) {
                        renderer.getBenchmarkController().stop(renderer.getPerfTimer());
                        context.getSource().sendFeedback(Component.literal("Benchmark stopped"));
                    }
                    return 1;
                }))
            .then(ClientCommandManager.literal("status")
                .executes(context -> {
                    if (renderer != null && renderer.getBenchmarkController() != null) {
                        context.getSource().sendFeedback(
                            Component.literal("Benchmark: " + renderer.getBenchmarkController().getStatus()
                                + " | target " + renderer.getBenchmarkController().getTargetStatus()));
                    }
                    return 1;
                }));
    }

    static LiteralArgumentBuilder<
            FabricClientCommandSource> cameraCommand() {
        return ClientCommandManager.literal("camera")
            .executes(context -> cameraStatus(context.getSource()))
            .then(ClientCommandManager.literal("record")
                .then(ClientCommandManager.argument("name",
                        StringArgumentType.word())
                    .executes(context -> {
                        String name = StringArgumentType.getString(context, "name");
                        CameraController controller = getCameraControllerForCommand(context.getSource());
                        if (controller == null) return 0;
                        context.getSource().sendFeedback(Component.literal(controller.arm(name)));
                        return 1;
                    })))
            .then(ClientCommandManager.literal("play")
                .then(ClientCommandManager.argument("name",
                        StringArgumentType.word())
                    .executes(context -> playCamera(
                            context.getSource(),
                            StringArgumentType.getString(context, "name"),
                            CameraController.PlaybackMode.TIME
                    ))
                    .then(ClientCommandManager.literal("time")
                        .executes(context -> playCamera(
                                context.getSource(),
                                StringArgumentType.getString(context, "name"),
                                CameraController.PlaybackMode.TIME
                        )))
                    .then(ClientCommandManager.literal("fixed-60")
                        .executes(context -> playCamera(
                                context.getSource(),
                                StringArgumentType.getString(context, "name"),
                                CameraController.PlaybackMode.FIXED_60
                        )))))
            .then(ClientCommandManager.literal("seek")
                .then(ClientCommandManager.argument("seconds",
                        DoubleArgumentType.doubleArg(0.0))
                    .executes(context -> {
                        double seconds = DoubleArgumentType.getDouble(
                                context, "seconds");
                        CameraController controller = getCameraControllerForCommand(context.getSource());
                        if (controller == null) return 0;
                        context.getSource().sendFeedback(Component.literal(
                                controller.seek(Math.round(seconds * 1_000.0))));
                        return 1;
                    }))
                .then(ClientCommandManager.argument("name",
                        StringArgumentType.word())
                    .then(ClientCommandManager.argument("seconds",
                            DoubleArgumentType.doubleArg(0.0))
                        .executes(context -> {
                            String name = StringArgumentType.getString(
                                    context, "name");
                            double seconds = DoubleArgumentType.getDouble(
                                    context, "seconds");
                            CameraController controller = getCameraControllerForCommand(context.getSource());
                            if (controller == null) return 0;
                            context.getSource().sendFeedback(Component.literal(
                                    controller.seek(name, Math.round(seconds * 1_000.0))));
                            return 1;
                        }))))
            .then(ClientCommandManager.literal("pause")
                .executes(context -> {
                    CameraController controller = getCameraControllerForCommand(context.getSource());
                    if (controller == null) return 0;
                    context.getSource().sendFeedback(Component.literal(controller.pause()));
                    return 1;
                }))
            .then(ClientCommandManager.literal("resume")
                .executes(context -> {
                    CameraController controller = getCameraControllerForCommand(context.getSource());
                    if (controller == null) return 0;
                    context.getSource().sendFeedback(Component.literal(controller.resume()));
                    return 1;
                }))
            .then(ClientCommandManager.literal("stop")
                .executes(context -> {
                    CameraController controller = getCameraControllerForCommand(context.getSource());
                    if (controller == null) return 0;
                    context.getSource().sendFeedback(Component.literal(controller.stop()));
                    return 1;
                }))
            .then(ClientCommandManager.literal("status")
                .executes(context -> cameraStatus(context.getSource())))
            .then(ClientCommandManager.literal("time")
                .then(ClientCommandManager.literal("day")
                    .executes(context -> setCameraTime(context.getSource(), 1000)))
                .then(ClientCommandManager.literal("noon")
                    .executes(context -> setCameraTime(context.getSource(), 6000)))
                .then(ClientCommandManager.literal("sunset")
                    .executes(context -> setCameraTime(context.getSource(), 12000)))
                .then(ClientCommandManager.literal("night")
                    .executes(context -> setCameraTime(context.getSource(), 13000)))
                .then(ClientCommandManager.literal("midnight")
                    .executes(context -> setCameraTime(context.getSource(), 18000)))
                .then(ClientCommandManager.argument("ticks",
                        LongArgumentType.longArg(0, 23999))
                    .executes(context -> {
                        long ticks = LongArgumentType.getLong(context, "ticks");
                        return setCameraTime(context.getSource(), ticks);
                    })));
    }

    /**
     * Still shots for documentation screenshots. A mark is a pose plus the
     * scene state the pose alone cannot carry, taken at the instant the command
     * runs; the harness captures the set headlessly later.
     */
    static LiteralArgumentBuilder<
            FabricClientCommandSource> shotCommand() {
        return ClientCommandManager.literal("shot")
            .executes(context -> listShots(context.getSource()))
            .then(ClientCommandManager.literal("mark")
                .then(ClientCommandManager.argument("name",
                        StringArgumentType.word())
                    .executes(context -> markShot(
                            context.getSource(),
                            StringArgumentType.getString(context, "name")))))
            .then(ClientCommandManager.literal("list")
                .executes(context -> listShots(context.getSource())))
            .then(ClientCommandManager.literal("remove")
                .then(ClientCommandManager.argument("name",
                        StringArgumentType.word())
                    .executes(context -> {
                        CameraController controller = getCameraControllerForCommand(context.getSource());
                        if (controller == null) return 0;
                        context.getSource().sendFeedback(Component.literal(controller.removeStillShot(
                                DOCS_SHOT_SET, StringArgumentType.getString(context, "name"))));
                        return 1;
                    })));
    }

    private static int markShot(FabricClientCommandSource source, String name) {
        CameraController controller = getCameraControllerForCommand(source);
        if (controller == null) return 0;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            source.sendFeedback(Component.literal("No world loaded."));
            return 0;
        }
        CameraController.StillShotScene scene = new CameraController.StillShotScene(
                StillShot.Weather.of(client.level.isRaining(), client.level.isThundering()),
                isRtxEnabled(),
                renderer.getCloudRenderer() != null && renderer.getCloudRenderer().isProceduralClouds());
        source.sendFeedback(Component.literal(controller.markStillShot(DOCS_SHOT_SET, name, scene)));
        return 1;
    }

    private static int listShots(FabricClientCommandSource source) {
        CameraController controller = getCameraControllerForCommand(source);
        if (controller == null) return 0;
        for (String line : controller.describeStillShots(DOCS_SHOT_SET).split("\n")) {
            source.sendFeedback(Component.literal(line));
        }
        return 1;
    }

    private static int cameraStatus(
            FabricClientCommandSource source) {
        CameraController controller = getCameraControllerForCommand(source);
        if (controller == null) return 0;
        source.sendFeedback(Component.literal(controller.status()));
        return 1;
    }

    private static int playCamera(
            FabricClientCommandSource source,
            String name,
            CameraController.PlaybackMode mode) {
        CameraController controller = getCameraControllerForCommand(source);
        if (controller == null) return 0;
        source.sendFeedback(Component.literal(controller.play(name, mode)));
        return 1;
    }

    private static int setCameraTime(
            FabricClientCommandSource source,
            long timeOfDay) {
        CameraController controller = getCameraControllerForCommand(source);
        if (controller == null) return 0;
        source.sendFeedback(Component.literal(controller.setTimeOfDay(timeOfDay)));
        return 1;
    }

    private static CameraController getCameraControllerForCommand(
            FabricClientCommandSource source) {
        if (renderer == null || !renderer.isInitialized() || renderer.getCameraController() == null) {
            source.sendFeedback(Component.literal("Camera controller not available: RTX Remix renderer is not initialized."));
            return null;
        }
        if (renderer.getBenchmarkController() != null && renderer.getBenchmarkController().isRunning()) {
            source.sendFeedback(Component.literal("Camera controls are unavailable while a benchmark is running."));
            return null;
        }
        return renderer.getCameraController();
    }

    private BlockTextureManager getTextureManager() {
        if (renderer == null || !renderer.isInitialized()) {
            return null;
        }
        return renderer.getTextureManager();
    }

    private void sendWaterStatus(FabricClientCommandSource source,
                                 BlockTextureManager textureManager) {
        source.sendFeedback(Component.literal("Water material:"));
        source.sendFeedback(Component.literal("  IOR: " + textureManager.getWaterIor()));
        source.sendFeedback(Component.literal("  Transmittance color: "
                + textureManager.getWaterTransmittanceR() + ", "
                + textureManager.getWaterTransmittanceG() + ", "
                + textureManager.getWaterTransmittanceB()));
        source.sendFeedback(Component.literal(
                "  Transmittance distance: " + textureManager.getWaterTransmittanceDistance()));
    }

    private void sendCutoutSssStatus(
            FabricClientCommandSource source,
            BlockTextureManager textureManager) {
        source.sendFeedback(Component.literal("Cutout SSS material:"));
        source.sendFeedback(Component.literal(
                "  Active atlas material: " + textureManager.hasCutoutSssAtlasMaterial()));
        source.sendFeedback(Component.literal(
                "  Transmission distance: " + textureManager.getCutoutSssTransmissionDistance()));
        source.sendFeedback(Component.literal("  Anisotropy: " + textureManager.getCutoutSssAnisotropy()));
        source.sendFeedback(Component.literal("  Fallback albedo: "
                + textureManager.getCutoutSssAlbedoR() + ", "
                + textureManager.getCutoutSssAlbedoG() + ", "
                + textureManager.getCutoutSssAlbedoB()));
        source.sendFeedback(Component.literal(
                "  Albedo texture: " + textureManager.isCutoutSssAlbedoTextureEnabled()));
    }
    
    private void initializeRemix(Minecraft client) {
        // rtxSettings binds to this renderer's managers, which CLIENT_STARTED
        // builds once after this runs, so a renderer and its settings are
        // created as a pair: replacing one without the other leaves the
        // settings bound to dead managers.
        try {
            renderer = new RemixRenderer();
            renderer.initialize();
            LOGGER.info("RTX Remix renderer created successfully");
        } catch (RemixException e) {
            LOGGER.error("Failed to initialize RTX Remix renderer", e);
            remixAvailable = false;
            if (e.getErrorCode() == ErrorCode.HRESULT_DRIVER_VERSION_BELOW_MINIMUM) {
                LOGGER.error("NVIDIA driver is below the minimum supported by RTX Remix; closing Minecraft");
                client.stop();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to initialize RTX Remix renderer", e);
            remixAvailable = false;
        }
    }
    
    private void shutdownRemix() {
        if (renderer != null) {
            renderer.shutdown();
        }
    }

    public static void shutdownForClientStop() {
        if (instance != null) {
            instance.shutdownForClientStopInternal();
        }
    }

    public static void shutdownAfterMinecraftClose() {
        if (instance != null) {
            instance.shutdownAfterMinecraftCloseInternal();
        }
    }

    private void shutdownForClientStopInternal() {
        releaseRemixMenuCapture();
        if (renderer == null && textureOverlay == null) {
            TracyGCListener.uninstall();
            closeOpenProfileScopes();
            return;
        }

        LOGGER.info("Minecraft client stopping, shutting down RTX Remix...");
        TracyGCListener.uninstall();
        closeOpenProfileScopes();
        remixFrameReadyToSubmit = false;
        if (textureOverlay != null) {
            textureOverlay.shutdown();
            textureOverlay = null;
        }
        shutdownRemix();
    }

    private static void releaseRemixMenuCapture() {
        try {
            REMIX_INPUT_GATE.close((option, value) ->
                    renderer.getRemixApi().setConfigVariable(option, value));
        } catch (Exception e) {
            LOGGER.warn("Failed to hide Remix menu while releasing input capture", e);
        } finally {
            KeyMapping.releaseAll();
        }
    }

    private void shutdownAfterMinecraftCloseInternal() {
        if (renderer != null) {
            renderer.shutdownNativeRuntime();
            renderer = null;
        }
    }
    
    public static RtxRemixMod getInstance() {
        return instance;
    }
    
    public RemixRenderer getRenderer() {
        return renderer;
    }
    
    /**
     * Toggles path recording on/off via the armed plain R hotkey.
     * When stopping, auto-saves to the active world-scoped camera target.
     */
    public static void togglePathRecording() {
        RtxRemixMod mod = instance;
        Minecraft client = Minecraft.getInstance();
        if (mod == null || renderer == null || !renderer.isInitialized()) {
            sendCameraMessage(client, "Camera recording unavailable: RTX Remix renderer is not initialized.");
            LOGGER.warn("Path recording shortcut ignored because the RTX Remix renderer is not initialized");
            return;
        }

        CameraController controller = renderer.getCameraController();
        if (controller == null) {
            sendCameraMessage(client, "Camera recording unavailable: camera controller is missing.");
            LOGGER.warn("Path recording shortcut ignored because the camera controller is missing");
            return;
        }

        String message = controller.toggleRecording();
        LOGGER.info(message);
        sendCameraMessage(client, message);
    }

    public static boolean shouldHandlePlainPathRecordingShortcut() {
        RtxRemixMod mod = instance;
        if (mod == null || renderer == null || !renderer.isInitialized()) {
            return false;
        }

        CameraController controller = renderer.getCameraController();
        return controller != null && controller.shouldHandleRecordingShortcut();
    }

    public static boolean handleCameraTimeShortcut(int key, int action, int mods) {
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_REPEAT) {
            return false;
        }

        int direction = cameraTimeAdjustmentDirection(key, mods);
        if (direction == 0) {
            return false;
        }

        RtxRemixMod mod = instance;
        if (mod == null || renderer == null || !renderer.isInitialized()) {
            return false;
        }

        CameraController controller = renderer.getCameraController();
        if (!isCameraTimeShortcutActive(controller)) {
            return false;
        }

        Minecraft client = Minecraft.getInstance();
        long deltaTicks = direction * cameraTimeAdjustmentStep(mods);
        Long timeOfDay = controller.adjustTimeOfDay(deltaTicks);
        if (timeOfDay == null) {
            sendCameraActionBar(client, "No world loaded.");
            return true;
        }

        sendCameraActionBar(client, "Time of day: " + timeOfDay);
        return true;
    }

    static boolean isCameraTimeShortcutActive(CameraController controller) {
        return controller != null && controller.isRecording();
    }

    static int cameraTimeAdjustmentDirection(int key, int mods) {
        return switch (key) {
            case GLFW.GLFW_KEY_EQUAL -> 1;
            case GLFW.GLFW_KEY_MINUS -> -1;
            default -> 0;
        };
    }

    static long cameraTimeAdjustmentStep(int mods) {
        return (mods & GLFW.GLFW_MOD_CONTROL) != 0
                ? CAMERA_TIME_FAST_STEP_TICKS
                : CAMERA_TIME_STEP_TICKS;
    }

    private static void sendCameraMessage(Minecraft client, String message) {
        if (client != null && client.player != null) {
            client.player.displayClientMessage(Component.literal("[Camera] " + message), false);
        }
    }

    private static void sendCameraActionBar(Minecraft client, String message) {
        if (client != null && client.player != null) {
            client.player.displayClientMessage(Component.literal("[Camera] " + message), true);
        }
    }

    public static boolean isRtxEnabled() {
        return rtxEnabled;
    }
    
    /**
     * Switches RTX capture on or off. Render thread only: the light snapshot republish
     * below has to land before the flag flips.
     */
    public static void setRtxEnabled(boolean enabled) {
        Minecraft client = Minecraft.getInstance();
        if (client != null && !client.isSameThread()) {
            throw new IllegalStateException("setRtxEnabled must run on the render thread");
        }
        if (rtxEnabled == enabled) return;
        if (!enabled && client != null && client.level != null) {
            // Rebuild tasks that start between this flip and the reload below latch capture
            // off and read light on their worker from the published snapshot, which while
            // capture was on was the engine's live map. The reload cancels queued tasks, not
            // running ones, so the real copy has to exist before any of them can start.
            ClientLightSnapshots.publishRealSnapshots(client.level);
        }
        rtxEnabled = enabled;
        LOGGER.info("RTX Rendering {}", enabled ? "ENABLED" : "DISABLED");

        // Pause/resume atlas animation forwarding alongside the toggle. Without
        // this, SpriteContents.upload continues to fire while scene submission
        // (and thus flush) is skipped, growing pendingPatches unbounded.
        if (enabled) {
            AtlasAnimationForwarder.resume();
        } else {
            AtlasAnimationForwarder.pause();
        }

        // Sections built under the opposite mode have geometry in the wrong
        // place (Remix-only when on, GL-only when off). Rebuild all loaded
        // chunks so they pick up the new mode. Must run on the render thread.
        if (client == null) return;
        client.execute(() -> {
            if (instance != null && renderer != null && renderer.isInitialized()
                    && renderer.getChunkManager() != null) {
                renderer.getChunkManager().clear();
            }
            if (client.levelRenderer != null) {
                // What vanilla has built is what the player has looked at, in either
                // mode; recapturing that restores the scene they had. Every loaded
                // section would be three times as much terrain.
                LongSet built = enabled
                        ? builtSections(client)
                        : LongSets.emptySet();
                client.levelRenderer.allChanged();
                if (enabled) {
                    recaptureTerrainForRemix(client, built);
                }
            }
        });
    }
    
    public static void toggleRtx() {
        setRtxEnabled(!rtxEnabled);
    }
    
    public static boolean isEntitiesEnabled() {
        return entitiesEnabled;
    }
    
    public static boolean isWorldEnabled() {
        return worldEnabled;
    }

    public static boolean isSkyEnabled() {
        return skyEnabled;
    }

    /** Keeps the renderer gate and the sky renderer's own gate in sync. */
    public static void setSkyEnabled(boolean enabled) {
        skyEnabled = enabled;
        SkyRenderer.setEnabled(enabled);
    }

    public static boolean isBlockEntitiesEnabled() {
        return blockEntitiesEnabled;
    }

    public static boolean isParticlesEnabled() {
        return particlesEnabled;
    }

    public static boolean isFirstPersonSmokeEnabled() {
        return firstPersonSmokeEnabled;
    }

    /**
     * "Active" predicates: a subsystem is only active when the master RTX flag
     * is on AND its own sub-flag is on. The master flag (set by /rtx on|off)
     * has authority over every sub-toggle. Use these, not the bare sub-flag
     * accessors, anywhere a decision means "is Remix actually rendering this".
     */
    public static boolean isWorldActive() {
        return rtxEnabled && worldEnabled;
    }

    /** Whether Remix, rather than vanilla, is drawing the world right now. */
    public static boolean isRemixWorldRendering() {
        RtxRemixMod mod = instance;
        return isWorldActive()
                && mod != null
                && mod.getRenderer() != null
                && mod.getRenderer().isInitialized();
    }

    public static boolean isEntitiesActive() {
        return rtxEnabled && entitiesEnabled;
    }

    public static boolean isBlockEntitiesActive() {
        return rtxEnabled && blockEntitiesEnabled;
    }

    public static boolean isSkyActive() {
        return rtxEnabled && skyEnabled;
    }

    public static boolean isParticlesActive() {
        return rtxEnabled && particlesEnabled;
    }
}

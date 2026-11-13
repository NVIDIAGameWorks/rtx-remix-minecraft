/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.mojang.blaze3d.platform.InputConstants;
import com.nvidia.rtxremix.ErrorCode;
import com.nvidia.rtxremix.RemixException;
import com.nvidia.rtxremix.minecraft.camera.CameraController;
import com.nvidia.rtxremix.minecraft.input.RemixInputGate;
import com.nvidia.rtxremix.minecraft.input.RemixKeyboardAccess;
import com.nvidia.rtxremix.minecraft.input.RemixMouseAccess;
import com.nvidia.rtxremix.minecraft.terrain.ClientLightSnapshots;
import com.nvidia.rtxremix.minecraft.logic.ConfLayerDriver;
import com.nvidia.rtxremix.minecraft.logic.MarkerBlockScanner;
import com.nvidia.rtxremix.minecraft.logic.ZoneDefinition;
import com.nvidia.rtxremix.minecraft.logic.ZonePersistentState;
import com.nvidia.rtxremix.minecraft.logic.ZoneRegistry;
import com.nvidia.rtxremix.minecraft.profiling.TracyGCListener;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.sky.SkyRenderer;
import com.nvidia.rtxremix.minecraft.settings.RtxSettings;
import com.nvidia.rtxremix.minecraft.settings.RtxSettingsFactory;
import com.nvidia.rtxremix.minecraft.settings.RtxSettingsStore;
import com.nvidia.rtxremix.minecraft.terrain.ClientLightSnapshotPolicy;
import com.nvidia.rtxremix.minecraft.terrain.LocalChunkBatchPacing;
import com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter;
import com.nvidia.rtxremix.minecraft.terrain.SkyLightSurfaceScanPolicy;
import com.nvidia.rtxremix.minecraft.texture.AtlasAnimationForwarder;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.ToIntBiFunction;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import org.lwjgl.glfw.GLFW;

/**
 * Canonical client runtime. Loaders translate their events into these methods;
 * renderer state and policy do not belong to a loader.
 */
public final class MinecraftClientRuntime implements MinecraftRuntime.Host {
    private static final long CAMERA_TIME_STEP_TICKS = 10L;
    private static final long CAMERA_TIME_FAST_STEP_TICKS = 250L;

    private final RemixInputGate inputGate = MinecraftRuntime.inputGate();
    private final ToIntBiFunction<String, byte[]> configReader =
            (key, value) -> renderer().getRemixApi()
                    .getConfigVariableResolved(key, value);

    private boolean remixAvailable;
    private boolean bootstrapped;
    private boolean setupFinished;
    private boolean nativeExtractionSucceeded;
    private boolean frameReady;
    private boolean frameFailureLogged;
    private boolean menuReadFailureLogged;
    private boolean clientStarted;
    private boolean pendingJoin;
    private RtxSettings settings;
    private RtxSettingsStore settingsStore;
    private RemixRenderer rendererAwaitingNativeShutdown;

    public void bootstrap() {
        if (bootstrapped) {
            return;
        }
        MinecraftPlatform.Services platform = MinecraftPlatform.services();
        LoaderArchitectureContract.validateInstalled(platform);
        bootstrapped = true;
        MinecraftRuntime.install(this);
        MinecraftRuntime.LOGGER.info(
                "RTX Remix Renderer v{} initializing...",
                platform.modVersion());
        nativeExtractionSucceeded = NativeExtractor.extractIfNeeded();
        TracyGCListener.install();
        MinecraftRuntime.LOGGER.info(NeighbourReflagFilter.effectiveStateLine());
        MinecraftRuntime.LOGGER.info(ClientLightSnapshotPolicy.effectiveStateLine());
        MinecraftRuntime.LOGGER.info(LocalChunkBatchPacing.effectiveStateLine());
        MinecraftRuntime.LOGGER.info(SkyLightSurfaceScanPolicy.effectiveStateLine());
        Path dll = RemixRenderer.runtimeDll(
                MinecraftPlatform.services().gameDirectory());
        remixAvailable = Files.isRegularFile(dll);
        if (remixAvailable) {
            MinecraftRuntime.LOGGER.info("Found RTX Remix SDK at: {}", dll);
        } else {
            MinecraftRuntime.LOGGER.warn("RTX Remix SDK not found at {}", dll);
            if (!nativeExtractionSucceeded) {
                MinecraftRuntime.LOGGER.warn(
                        "Extracting the runtime from the mod JAR failed this launch; "
                                + "see the error above. Relaunch to retry.");
            }
        }
    }

    /** Logs completion after the active loader has registered its thin adapter hooks. */
    public void finishSetup() {
        if (setupFinished) {
            return;
        }
        setupFinished = true;
        if (remixAvailable) {
            MinecraftRuntime.LOGGER.info("RTX Remix Renderer mod setup complete");
        } else {
            MinecraftRuntime.LOGGER.warn(
                    "RTX Remix Renderer mod setup complete, with path tracing disabled: "
                            + "the runtime is missing");
        }
    }

    @Override
    public void onClientStarted(Minecraft client) {
        if (clientStarted) {
            return;
        }
        clientStarted = true;
        if (!remixAvailable) {
            return;
        }
        RemixRenderer renderer = null;
        try {
            renderer = new RemixRenderer();
            renderer.initialize();
            settings = RtxSettingsFactory.build(renderer);
            Path configPath = MinecraftPlatform.services()
                    .configDirectory().resolve("rtxremix.json");
            settingsStore = new RtxSettingsStore(configPath);
            settingsStore.load(settings);
            applyLaunchPreset(renderer);
            MinecraftRuntime.publishRenderer(renderer);
            MinecraftRuntime.LOGGER.info("RTX Remix renderer created successfully");
            if (pendingJoin) {
                onJoin(client);
            }
        } catch (RemixException e) {
            retainFailedRenderer(renderer);
            MinecraftRuntime.LOGGER.error(
                    "Failed to initialize RTX Remix renderer", e);
            remixAvailable = false;
            if (e.getErrorCode()
                    == ErrorCode.HRESULT_DRIVER_VERSION_BELOW_MINIMUM) {
                client.stop();
            }
        } catch (Exception e) {
            retainFailedRenderer(renderer);
            MinecraftRuntime.LOGGER.error(
                    "Failed to initialize RTX Remix renderer", e);
            remixAvailable = false;
        }
    }

    private void retainFailedRenderer(RemixRenderer renderer) {
        MinecraftRuntime.publishRenderer(null);
        settings = null;
        settingsStore = null;
        if (renderer == null) {
            return;
        }
        try {
            renderer.shutdown();
        } catch (Exception cleanupFailure) {
            MinecraftRuntime.LOGGER.error(
                    "Failed to clean up RTX Remix after initialization failure",
                    cleanupFailure);
        }
        rendererAwaitingNativeShutdown = renderer;
    }

    public RtxSettings settings() {
        return settings;
    }

    public boolean isRemixAvailable() {
        return remixAvailable;
    }

    public void saveSettings() {
        if (settings != null && settingsStore != null) {
            settingsStore.save(settings);
        }
    }

    public void onClientTick(Minecraft client) {
        try (ProfileScope ignored =
                ProfileScope.begin("ClientTick.END.benchmark")) {
            RemixRenderer renderer = renderer();
            if (renderer != null) {
                renderer.getCameraController().onClientTick(client);
                renderer.getBenchmarkController().onClientTick(client);
            }
        }
    }

    public static KeyMapping createToggleHeldLightsKey() {
        return new KeyMapping(
                "key.rtxremix.toggleHeldLights",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.rtxremix");
    }

    public static KeyMapping createToggleRtxKey() {
        return new KeyMapping(
                "key.rtxremix.toggleRtx",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                "category.rtxremix");
    }

    public void handleKeyMappings(
            Minecraft client,
            KeyMapping toggleHeldLights,
            KeyMapping toggleRtx) {
        if (toggleHeldLights != null) {
            try (ProfileScope ignored =
                    ProfileScope.begin("ClientTick.END.heldLightsKeybind")) {
                while (toggleHeldLights.consumeClick()) {
                    toggleHeldLights();
                }
            }
        }

        if (toggleRtx == null) {
            return;
        }
        int presses = 0;
        while (toggleRtx.consumeClick()) {
            presses++;
        }
        // Several queued presses have the same net effect as one exactly when
        // their count is odd; consume the whole queue so every loader
        // integration observes the same toggle semantics.
        if ((presses & 1) != 0) {
            toggleRtxFromKey(client);
        }
    }

    public void toggleHeldLights() {
        boolean off = com.nvidia.rtxremix.minecraft.light.HeldLightState
                .toggleLocalHeldLights();
        MinecraftRuntime.LOGGER.info("Held lights {}", off ? "off" : "on");
    }

    public void toggleRtxFromKey(Minecraft client) {
        boolean enabled = !MinecraftRuntime.isRtxEnabled();
        MinecraftRuntime.setRtxEnabled(enabled);
        if (client.player != null) {
            client.player.displayClientMessage(
                    Component.literal(
                            "RTX Rendering: " + (enabled ? "ON" : "OFF")),
                    true);
        }
    }

    public void onClientResourcesReload(ResourceManager manager) {
        com.nvidia.rtxremix.minecraft.light.BlockLightRuleManager.reload(manager);
        RemixRenderer renderer = renderer();
        if (renderer != null) {
            renderer.requestTextureReload();
        }
    }

    public void onClientChunkUnload(int chunkX, int chunkZ) {
        RemixRenderer renderer = renderer();
        if (renderer != null && renderer.getChunkManager() != null) {
            renderer.getChunkManager().onChunkUnloaded(chunkX, chunkZ);
        }
        Minecraft client = Minecraft.getInstance();
        if (client.levelRenderer
                instanceof com.nvidia.rtxremix.minecraft.terrain
                        .WorldRendererTerrainAccess access) {
            access.rtxremix$onNeighbourColumnInvalidated(chunkX, chunkZ);
        }
    }

    public void onServerChunkLoad(MinecraftServer server, LevelChunk chunk) {
        RemixRenderer renderer = renderer();
        if (renderer != null
                && Minecraft.getInstance().getSingleplayerServer() == server) {
            MarkerBlockScanner.scanChunk(renderer.getZoneRegistry(), chunk);
        }
    }

    public void captureBlockOutline(
            ClientLevel world,
            BlockPos pos,
            BlockState state,
            Entity entity,
            Camera camera,
            float tickDelta) {
        RemixRenderer renderer = renderer();
        if (world == null
                || renderer == null
                || !renderer.isInitialized()
                || !MinecraftRuntime.isRtxEnabled()) {
            return;
        }
        com.nvidia.rtxremix.minecraft.entity.MinecraftBlockOutlineCapture.capture(
                renderer.getBlockOutlinePass(),
                world,
                pos,
                state,
                entity,
                camera,
                tickDelta);
    }

    @Override
    public void beginWorldRenderFrame() {
        RemixRenderer renderer = renderer();
        if (renderer != null && renderer.isInitialized()) {
            renderer.beginBlockOutlineFrame();
            renderer.beginWorldRenderFrame();
        }
    }

    @Override
    public void markFrameReady() {
        RemixRenderer renderer = renderer();
        frameReady = renderer != null && renderer.isInitialized();
    }

    public void onDisconnect() {
        pendingJoin = false;
        releaseMenuCapture();
        RemixRenderer renderer = renderer();
        if (renderer == null) {
            return;
        }
        releaseWorldSession(renderer);
    }

    private static void releaseWorldSession(RemixRenderer renderer) {
        renderer.getBenchmarkController().onDisconnect(renderer.getPerfTimer());
        renderer.getCameraController().resetSession();
        releaseWorldState(renderer);
    }

    private static void releaseWorldState(RemixRenderer renderer) {
        renderer.getZoneRegistry().clear();
        if (renderer.getConfLayerDriver() != null) {
            renderer.getConfLayerDriver().onWorldLeave();
        }
        if (renderer.isInitialized()) {
            renderer.clearWorld();
        }
    }

    public void onJoin(Minecraft client) {
        pendingJoin = true;
        releaseMenuCapture();
        RemixRenderer renderer = renderer();
        if (renderer == null) {
            return;
        }
        pendingJoin = false;
        // JOIN and DISCONNECT are not paired during server reconfiguration.
        // Release state owned by the previous world before acquiring this one,
        // but preserve an idle pending benchmark: QuickPlay's initial JOIN is
        // the event that pending trigger is waiting for.
        renderer.getBenchmarkController().onWorldJoin(renderer.getPerfTimer());
        if (renderer.getCameraController().state()
                != CameraController.State.IDLE) {
            renderer.getCameraController().resetSession();
        }
        releaseWorldState(renderer);
        ZoneRegistry zones = renderer.getZoneRegistry();
        restoreWorldSavedZones(client, renderer, zones);
        acquireWorldConfLayers(client, renderer);
    }

    public void onClientStopping() {
        releaseMenuCapture();
        TracyGCListener.uninstall();
        frameReady = false;
        RemixRenderer renderer = renderer();
        if (renderer != null) {
            renderer.shutdown();
        }
    }

    @Override
    public void shutdownAfterMinecraftClose() {
        RemixRenderer renderer = renderer();
        if (renderer != null) {
            renderer.shutdownNativeRuntime();
            MinecraftRuntime.publishRenderer(null);
        }
        RemixRenderer failedRenderer = rendererAwaitingNativeShutdown;
        rendererAwaitingNativeShutdown = null;
        if (failedRenderer != null && failedRenderer != renderer) {
            failedRenderer.shutdownNativeRuntime();
        }
    }

    public RemixRenderer renderer() {
        return MinecraftRuntime.renderer();
    }

    @Override
    public void setRtxEnabled(boolean enabled) {
        Minecraft client = Minecraft.getInstance();
        if (client != null && !client.isSameThread()) {
            throw new IllegalStateException(
                    "setRtxEnabled must run on the render thread");
        }
        if (MinecraftRuntime.isRtxEnabled() == enabled) {
            return;
        }
        if (!enabled && client != null && client.level != null) {
            ClientLightSnapshots.publishRealSnapshots(client.level);
        }
        MinecraftRuntime.publishFeatureState(
                enabled,
                MinecraftRuntime.isEntitiesEnabled(),
                MinecraftRuntime.isWorldEnabled(),
                MinecraftRuntime.isSkyEnabled(),
                MinecraftRuntime.isBlockEntitiesEnabled(),
                MinecraftRuntime.isParticlesEnabled(),
                MinecraftRuntime.isFirstPersonSmokeEnabled());
        if (enabled) {
            AtlasAnimationForwarder.resume();
        } else {
            AtlasAnimationForwarder.pause();
        }
        if (client == null) {
            return;
        }
        client.execute(() -> {
            RemixRenderer renderer = renderer();
            if (renderer != null
                    && renderer.isInitialized()
                    && renderer.getChunkManager() != null) {
                renderer.getChunkManager().clear();
            }
            if (client.levelRenderer != null) {
                LongSet built = enabled
                        ? MinecraftRuntime.builtSections(client)
                        : LongSets.emptySet();
                client.levelRenderer.allChanged();
                if (enabled) {
                    MinecraftRuntime.recaptureTerrainForRemix(client, built);
                }
            }
        });
    }

    @Override
    public void setSkyEnabled(boolean enabled) {
        MinecraftRuntime.publishFeatureState(
                MinecraftRuntime.isRtxEnabled(),
                MinecraftRuntime.isEntitiesEnabled(),
                MinecraftRuntime.isWorldEnabled(),
                enabled,
                MinecraftRuntime.isBlockEntitiesEnabled(),
                MinecraftRuntime.isParticlesEnabled(),
                MinecraftRuntime.isFirstPersonSmokeEnabled());
        SkyRenderer.setEnabled(enabled);
    }

    @Override
    public void openRemixMenu(RemixInputGate.Menu menu) {
        RemixRenderer renderer = renderer();
        if (renderer == null
                || !renderer.isInitialized()
                || inputGate.isCaptureSessionOpen()) {
            return;
        }
        try {
            inputGate.open(menu, renderer.getRemixApi()::setConfigVariable);
            takeInputFromMinecraft();
        } catch (Exception e) {
            MinecraftRuntime.LOGGER.warn("Failed to open Remix menu", e);
        }
    }

    @Override
    public void toggleRemixMenu() {
        if (inputGate.isCaptureSessionOpen()) {
            releaseMenuCapture();
            relockCursorUnlessScreenOpen();
        } else {
            openRemixMenu(RemixInputGate.Menu.DEVELOPER);
        }
    }

    @Override
    public void followRemixMenuVisibility() {
        RemixRenderer renderer = renderer();
        if (renderer == null || !renderer.isInitialized()) {
            return;
        }
        RemixInputGate.VisibilityChange change;
        try {
            change = inputGate.followVisibility(configReader);
        } catch (RemixException e) {
            if (!menuReadFailureLogged) {
                MinecraftRuntime.LOGGER.warn(
                        "Failed to read Remix menu visibility", e);
                menuReadFailureLogged = true;
            }
            return;
        }
        menuReadFailureLogged = false;
        switch (change) {
            case OPENED -> takeInputFromMinecraft();
            case CLOSED -> {
                KeyMapping.releaseAll();
                relockCursorUnlessScreenOpen();
            }
            case OWN_WRITE_OVERDUE -> MinecraftRuntime.LOGGER.warn(
                    "Remix did not apply the mod's rtx.showUI write after {} frames",
                    RemixInputGate.OWN_WRITE_OVERDUE_FRAMES);
            case NONE -> {
            }
        }
    }

    @Override
    public boolean shouldCaptureFirstPersonModel() {
        RemixRenderer renderer = renderer();
        return frameReady
                && renderer != null
                && renderer.isInitialized()
                && MinecraftRuntime.isRtxEnabled();
    }

    @Override
    public void submitRemixFrameIfReady() {
        if (!frameReady) {
            return;
        }
        frameReady = false;
        RemixRenderer renderer = renderer();
        if (renderer == null || !renderer.isInitialized()) {
            return;
        }
        try (ProfileScope callbackScope = ProfileScope.begin("WorldRender.END")) {
            int remixTexture;
            try {
                renderer.renderAndSubmitFrame();
                remixTexture = renderer.acquireHostOutputTexture();
            } catch (RuntimeException e) {
                if (!frameFailureLogged) {
                    MinecraftRuntime.LOGGER.error(
                            "Remix frame failed; skipping compositing this frame", e);
                    frameFailureLogged = true;
                }
                return;
            }
            frameFailureLogged = false;
            if (remixTexture > 0
                    && MinecraftRuntime.isRtxEnabled()) {
                renderer.compositeHostOutput(remixTexture);
            }
        }
    }

    @Override
    public void togglePathRecording() {
        RemixRenderer renderer = renderer();
        Minecraft client = Minecraft.getInstance();
        if (renderer == null || !renderer.isInitialized()) {
            sendCameraMessage(client, "Camera recording unavailable.");
            return;
        }
        String message = renderer.getCameraController().toggleRecording();
        sendCameraMessage(client, message);
    }

    @Override
    public boolean shouldHandlePlainPathRecordingShortcut() {
        RemixRenderer renderer = renderer();
        return renderer != null
                && renderer.isInitialized()
                && renderer.getCameraController().shouldHandleRecordingShortcut();
    }

    @Override
    public boolean handleCameraTimeShortcut(int key, int action, int mods) {
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_REPEAT) {
            return false;
        }
        int direction = switch (key) {
            case GLFW.GLFW_KEY_EQUAL -> 1;
            case GLFW.GLFW_KEY_MINUS -> -1;
            default -> 0;
        };
        RemixRenderer renderer = renderer();
        if (direction == 0
                || renderer == null
                || !renderer.isInitialized()) {
            return false;
        }
        CameraController controller = renderer.getCameraController();
        if (controller == null || !controller.isRecording()) {
            return false;
        }
        long step = (mods & GLFW.GLFW_MOD_CONTROL) != 0
                ? CAMERA_TIME_FAST_STEP_TICKS
                : CAMERA_TIME_STEP_TICKS;
        Long time = controller.adjustTimeOfDay(direction * step);
        sendCameraActionBar(
                Minecraft.getInstance(),
                time == null ? "No world loaded." : "Time of day: " + time);
        return true;
    }

    private void releaseMenuCapture() {
        RemixRenderer renderer = renderer();
        try {
            if (renderer != null && renderer.isInitialized()) {
                inputGate.close((option, value) ->
                        renderer.getRemixApi().setConfigVariable(option, value));
            }
        } catch (Exception e) {
            MinecraftRuntime.LOGGER.warn(
                    "Failed to hide Remix menu while releasing input capture", e);
        } finally {
            KeyMapping.releaseAll();
        }
    }

    private static void takeInputFromMinecraft() {
        Minecraft client = Minecraft.getInstance();
        KeyMapping.releaseAll();
        ((RemixKeyboardAccess) client.keyboardHandler)
                .rtxremix$resetDebugKeyState();
        try {
            ((RemixMouseAccess) client.mouseHandler)
                    .rtxremix$releaseActiveButton();
        } finally {
            client.mouseHandler.releaseMouse();
        }
    }

    private static void relockCursorUnlessScreenOpen() {
        Minecraft client = Minecraft.getInstance();
        if (client.screen == null) {
            client.mouseHandler.grabMouse();
        }
    }

    private static void sendCameraMessage(Minecraft client, String message) {
        if (client != null && client.player != null) {
            client.player.displayClientMessage(
                    Component.literal("[Camera] " + message), false);
        }
    }

    private static void sendCameraActionBar(Minecraft client, String message) {
        if (client != null && client.player != null) {
            client.player.displayClientMessage(
                    Component.literal("[Camera] " + message), true);
        }
    }

    private static void applyLaunchPreset(RemixRenderer renderer) {
        String name = System.getProperty("rtxremix.preset");
        if (name == null || name.isBlank()) {
            return;
        }
        Optional<QualityPreset> preset = QualityPreset.byId(name.strip());
        if (preset.isEmpty()) {
            MinecraftRuntime.LOGGER.warn(
                    "No preset '{}'. Available: {}",
                    name,
                    String.join(", ", QualityPreset.ids()));
            return;
        }
        preset.get().writeTo(renderer.getRemixApi()::setConfigVariable);
        MinecraftRuntime.LOGGER.info(
                "Remix graphics preset '{}' applied for this session", name);
    }

    private static void acquireWorldConfLayers(
            Minecraft client, RemixRenderer renderer) {
        ConfLayerDriver driver = renderer.getConfLayerDriver();
        if (driver == null) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            driver.onWorldLeave();
            return;
        }
        server.execute(() -> {
            Path worldDir;
            try {
                worldDir = server.getWorldPath(LevelResource.ROOT).normalize();
            } catch (RuntimeException e) {
                MinecraftRuntime.LOGGER.error(
                        "Failed to resolve the world directory for conf layers", e);
                return;
            }
            client.execute(() -> {
                if (client.getSingleplayerServer() == server
                        && MinecraftRuntime.renderer() == renderer
                        && renderer.getConfLayerDriver() == driver) {
                    driver.onWorldJoin(worldDir);
                }
            });
        });
    }

    private static void restoreWorldSavedZones(
            Minecraft client,
            RemixRenderer renderer,
            ZoneRegistry targetRegistry) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            Collection<ZoneDefinition> saved;
            try {
                ZonePersistentState state =
                        ZonePersistentState.getIfPresent(server);
                saved = state != null ? state.snapshot() : List.of();
            } catch (RuntimeException e) {
                MinecraftRuntime.LOGGER.error(
                        "Failed to load world-saved RTX Remix zones", e);
                return;
            }
            client.execute(() -> {
                if (client.getSingleplayerServer() != server
                        || MinecraftRuntime.renderer() != renderer
                        || renderer.getZoneRegistry() != targetRegistry) {
                    return;
                }
                targetRegistry.putAll(saved);
                MarkerBlockScanner.rescan(
                        server,
                        targetRegistry,
                        () -> client.getSingleplayerServer() == server
                                && MinecraftRuntime.renderer() == renderer
                                && renderer.getZoneRegistry() == targetRegistry);
            });
        });
    }
}

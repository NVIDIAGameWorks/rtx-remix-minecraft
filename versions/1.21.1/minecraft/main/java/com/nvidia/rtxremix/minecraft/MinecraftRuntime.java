/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.nvidia.rtxremix.minecraft.entity.RemixBlockDamagePass;
import com.nvidia.rtxremix.minecraft.input.RemixInputGate;
import com.nvidia.rtxremix.minecraft.terrain.WorldRendererTerrainAccess;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loader-neutral access to the active Minecraft client integration. */
public final class MinecraftRuntime {
    public static final Logger LOGGER = LoggerFactory.getLogger("RTX Remix");

    public interface Host {
        void setRtxEnabled(boolean enabled);
        void setSkyEnabled(boolean enabled);
        void openRemixMenu(RemixInputGate.Menu menu);
        void toggleRemixMenu();
        void followRemixMenuVisibility();
        void beginWorldRenderFrame();
        void markFrameReady();
        boolean shouldCaptureFirstPersonModel();
        void submitRemixFrameIfReady();
        void togglePathRecording();
        boolean shouldHandlePlainPathRecordingShortcut();
        boolean handleCameraTimeShortcut(int key, int action, int mods);
        void shutdownAfterMinecraftClose();
    }

    private static volatile Host host;
    private static volatile RemixRenderer renderer;
    private static final RemixInputGate INPUT_GATE = new RemixInputGate();
    private static volatile boolean rtxEnabled = true;
    private static volatile boolean entitiesEnabled = true;
    private static volatile boolean worldEnabled = true;
    private static volatile boolean skyEnabled = true;
    private static volatile boolean blockEntitiesEnabled = true;
    private static volatile boolean particlesEnabled = true;
    private static volatile boolean firstPersonSmokeEnabled = true;
    private static LongSet pendingRecapture = LongSets.emptySet();
    private static Object pendingRecaptureWorld;

    private MinecraftRuntime() {
    }

    public static void install(Host value) {
        Objects.requireNonNull(value, "value");
        synchronized (MinecraftRuntime.class) {
            if (host != null && host != value) {
                throw new IllegalStateException("Minecraft runtime host already installed");
            }
            host = value;
        }
    }

    private static Host host() {
        Host value = host;
        if (value == null) {
            throw new IllegalStateException("Minecraft runtime host is not installed");
        }
        return value;
    }

    public static RemixRenderer renderer() { return renderer; }
    public static void publishRenderer(RemixRenderer value) { renderer = value; }
    public static RemixInputGate inputGate() { return INPUT_GATE; }
    public static String modVersion() { return MinecraftPlatform.services().modVersion(); }
    public static boolean isRtxEnabled() { return rtxEnabled; }
    public static boolean isEntitiesEnabled() { return entitiesEnabled; }
    public static boolean isWorldEnabled() { return worldEnabled; }
    public static boolean isSkyEnabled() { return skyEnabled; }
    public static boolean isBlockEntitiesEnabled() { return blockEntitiesEnabled; }
    public static boolean isParticlesEnabled() { return particlesEnabled; }
    public static boolean isFirstPersonSmokeEnabled() { return firstPersonSmokeEnabled; }
    public static boolean isWorldActive() { return rtxEnabled && worldEnabled; }
    public static boolean isRemixWorldRendering() {
        RemixRenderer value = renderer;
        return isWorldActive() && value != null && value.isInitialized();
    }
    public static boolean captureBlockDamageInPlace(
            BlockPos pos,
            int stage,
            RemixBlockDamagePass.BlockDamageRenderCall draw) {
        RemixRenderer value = renderer;
        return value != null && value.renderBlockDamageInPlace(pos, stage, draw);
    }
    public static boolean shouldDrawVanillaParticles() {
        return !isRemixWorldRendering();
    }
    public static void publishFeatureState(
            boolean rtx,
            boolean entities,
            boolean world,
            boolean sky,
            boolean blockEntities,
            boolean particles,
            boolean firstPersonSmoke) {
        rtxEnabled = rtx;
        entitiesEnabled = entities;
        worldEnabled = world;
        skyEnabled = sky;
        blockEntitiesEnabled = blockEntities;
        particlesEnabled = particles;
        firstPersonSmokeEnabled = firstPersonSmoke;
    }
    public static void setRtxEnabled(boolean enabled) {
        Host value = host;
        if (value != null) {
            value.setRtxEnabled(enabled);
        } else {
            rtxEnabled = enabled;
        }
    }
    public static void setSkyEnabled(boolean enabled) {
        skyEnabled = enabled;
        Host value = host;
        if (value != null) {
            value.setSkyEnabled(enabled);
        }
    }
    public static void setEntitiesEnabled(boolean enabled) { entitiesEnabled = enabled; }
    public static void setWorldEnabled(boolean enabled) { worldEnabled = enabled; }
    public static void setBlockEntitiesEnabled(boolean enabled) {
        blockEntitiesEnabled = enabled;
    }
    public static void setParticlesEnabled(boolean enabled) { particlesEnabled = enabled; }
    public static void setFirstPersonSmokeEnabled(boolean enabled) {
        firstPersonSmokeEnabled = enabled;
    }
    public static void openRemixMenu(RemixInputGate.Menu menu) { host().openRemixMenu(menu); }
    public static void toggleRemixMenu() { host().toggleRemixMenu(); }
    public static void followRemixMenuVisibility() { host().followRemixMenuVisibility(); }
    public static void beginWorldRenderFrame() { host().beginWorldRenderFrame(); }
    public static void markFrameReady() { host().markFrameReady(); }
    public static boolean shouldCaptureFirstPersonModel() { return host().shouldCaptureFirstPersonModel(); }
    public static void submitRemixFrameIfReady() { host().submitRemixFrameIfReady(); }
    public static void togglePathRecording() { host().togglePathRecording(); }
    public static boolean shouldHandlePlainPathRecordingShortcut() {
        return host().shouldHandlePlainPathRecordingShortcut();
    }
    public static boolean handleCameraTimeShortcut(int key, int action, int mods) {
        return host().handleCameraTimeShortcut(key, action, mods);
    }
    public static boolean rebuildLoadedTerrain() {
        Minecraft client = Minecraft.getInstance();
        if (!isWorldActive() || client == null || client.levelRenderer == null) {
            return false;
        }
        RemixRenderer value = renderer;
        if (value != null && value.getChunkManager() != null) {
            value.getChunkManager().clear();
        }
        LongSet built = builtSections(client);
        client.levelRenderer.allChanged();
        recaptureTerrainForRemix(client, built);
        return true;
    }
    public static void rebuildTerrainForMaterialSettings() {
        RemixRenderer value = renderer;
        if (value != null) {
            value.requestMaterialSettingsReload();
        }
    }
    public static LongSet builtSections(Minecraft client) {
        if (!(client.levelRenderer instanceof WorldRendererTerrainAccess access)) {
            return LongSets.emptySet();
        }
        LongSet built = access.rtxremix$builtSectionPositions();
        if (pendingRecaptureWorld == client.level && !pendingRecapture.isEmpty()) {
            LongOpenHashSet union = new LongOpenHashSet(built);
            union.addAll(pendingRecapture);
            return union;
        }
        return built;
    }
    public static void recaptureTerrainForRemix(Minecraft client, LongSet builtSections) {
        if (!isWorldActive() || client.levelRenderer == null) {
            return;
        }
        if (client.getCameraEntity() == null) {
            LOGGER.warn(
                    "Skipping Remix terrain recapture: no camera entity (world still loading?)");
            return;
        }
        if (client.levelRenderer instanceof WorldRendererTerrainAccess access) {
            LongSet queued = access.rtxremix$queueSectionsForRemix(builtSections);
            pendingRecapture = queued;
            pendingRecaptureWorld = client.level;
            LOGGER.info(
                    "Queued {} of {} previously built sections for Remix recapture",
                    queued.size(),
                    builtSections.size());
        }
    }
    public static void shutdownAfterMinecraftClose() { host().shutdownAfterMinecraftClose(); }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.CameraInfo;
import com.nvidia.rtxremix.InteropInfo;
import com.nvidia.rtxremix.PresentInfo;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.StartupInfo;
import com.nvidia.rtxremix.forge.RemixForgeMod;
import com.nvidia.rtxremix.forge.interop.LegacyGlInterop;
import com.nvidia.rtxremix.forge.presentation.FullscreenPresentation;
import com.nvidia.rtxremix.forge.presentation.PresentationHooks;
import com.nvidia.rtxremix.forge.runtime.ForgeRuntime;
import java.io.File;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.world.World;

/** Owns native resources and frame submission on Minecraft's render thread. */
final class ForgeRenderer implements PresentationHooks.Backend {
    private final Minecraft mc;
    private final CameraCapture camera = new CameraCapture();
    private final PresentInfo present = new PresentInfo();
    private RemixAPI api;
    private LegacyGlInterop interop;
    private WorldClient world;
    private boolean attempted, pending, closing;
    private long frames;

    ForgeRenderer(Minecraft mc) { this.mc = mc; }

    void prepareWorld() {
        requireRenderThread();
        if (interop == null) initialize();
        if (world != mc.world) {
            releaseWorld();
            world = mc.world;
        }
    }

    void render(float partialTicks) {
        requireRenderThread();
        CameraInfo view = camera.capture(mc, partialTicks);
        api.setupCamera(view);
        InteropInfo frame = submit();
        interop.acquireToFramebuffer(frame, mc.getFramebuffer().framebufferObject,
                mc.displayWidth, mc.displayHeight, true);
        if (++frames == 1) RemixForgeMod.LOG.info("Forge Remix: first native frame copied to Minecraft.");
    }

    private void initialize() {
        if (attempted) throw new IllegalStateException("Restart Minecraft after native initialization failed");
        attempted = true;
        File runtime = ForgeRuntime.prepare(mc.gameDir);
        RemixAPI.redactCommandLineOptions(runtime.getAbsolutePath(), Arrays.asList(
                "--username", "--accessToken", "--uuid", "--xuid", "--clientId"));
        api = RemixAPI.load(runtime.getAbsolutePath());
        FullscreenPresentation.prepare(mc);
        long hwnd = LegacyGlInterop.currentWindowHandle();
        if (hwnd == 0) throw new IllegalStateException("Minecraft has no current OpenGL window");
        api.startup(StartupInfo.builder().hwnd(hwnd).forceNoVkSwapchain(false).interopImage(true)
                .combineGuiInFinalColor(true).build());
        interop = new LegacyGlInterop(api.dxvkGetInteropInfo());
        RemixForgeMod.LOG.info("Forge Remix initialized against API {}.{}.{}.",
                RemixAPI.VERSION_MAJOR, RemixAPI.VERSION_MINOR, RemixAPI.VERSION_PATCH);
    }

    private InteropInfo submit() {
        if (pending) throw new IllegalStateException("Previous native frame was not presented");
        api.submit(null);
        pending = true;
        return api.dxvkGetInteropInfo();
    }

    @Override public boolean ownsSwapchain() { return interop != null && !closing; }

    /** Runs after Minecraft finishes its HUD, including menu frames and disabled world capture. */
    @Override public void presentFrame() {
        requireRenderThread();
        interop.validateContext();
        try {
            if (!pending) interop.acquire(submit());
            if (interop.hasAcquiredFrame()) {
                interop.finishFromFramebuffer(mc.getFramebuffer().framebufferObject,
                        mc.displayWidth, mc.displayHeight, true);
            }
        } finally {
            if (pending) {
                // Native copies can signal before throwing, so query the last completion value.
                // A failed release is fatal; presenting an unsignaled timeline value would deadlock.
                present.clientDoneSemaphore_ValueToWait = interop.releaseAcquired();
                present.skipVulkanSwapchain = 0;
                present.hwndOverride = LegacyGlInterop.currentWindowHandle();
                try { api.present(present); }
                finally { pending = false; }
            }
        }
    }

    RemixAPI api() { return interop == null ? null : api; }
    WorldClient world() { return world; }
    long frames() { return frames; }
    String status() { return "native=" + (interop != null) + "; frames=" + frames; }

    void releaseWorld(World expected) {
        if (world == expected) releaseWorld();
    }

    void releaseWorld() {
        requireRenderThread();
        world = null;
    }

    void beforeDisplayDestroy() {
        closing = true;
        try { releaseWorld(); }
        finally {
            if (interop != null) {
                try { interop.close(); }
                finally { interop = null; }
            }
        }
    }

    void afterDisplayDestroy() {
        try {
            if (api != null) {
                try { api.shutdown(); }
                finally { api = null; }
                RemixForgeMod.LOG.info("Forge Remix native renderer shut down.");
            }
        } finally { FullscreenPresentation.afterShutdown(); }
    }

    private void requireRenderThread() {
        if (!mc.isCallingFromMinecraftThread()) throw new IllegalStateException("Remix requires the render thread");
    }
}
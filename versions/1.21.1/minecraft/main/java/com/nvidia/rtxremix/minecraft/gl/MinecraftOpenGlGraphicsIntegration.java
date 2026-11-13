/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import com.nvidia.rtxremix.InteropInfo;
import com.nvidia.rtxremix.PresentInfo;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.StartupInfo;
import com.nvidia.rtxremix.core.HostGraphicsIntegration;
import com.nvidia.rtxremix.core.RemixRendererSession;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft's Windows/OpenGL implementation of the host graphics boundary.
 *
 * <p>All assumptions about GLFW, HWNDs, OpenGL extension support, Minecraft's
 * main framebuffer, and GL/Vulkan semaphore transfer live here. Canonical
 * renderer and scene policy do not depend on them.</p>
 */
public final class MinecraftOpenGlGraphicsIntegration
        implements HostGraphicsIntegration {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(MinecraftOpenGlGraphicsIntegration.class);

    private RemixAPI api;
    private GlInterop interop;
    private TextureOverlay textureOverlay;
    private boolean presentFailureLogged;

    @Override
    public StartupInfo prepareStartup() {
        Minecraft client = Minecraft.getInstance();
        long glfwHandle = client.getWindow().getWindow();
        long windowHandle = GLFWNativeWin32.glfwGetWin32Window(glfwHandle);
        if (windowHandle == 0) {
            throw new IllegalStateException("Failed to get native window handle");
        }
        LOGGER.info("Got window handle: 0x{}", Long.toHexString(windowHandle));
        applyRequestedWindowPosition(glfwHandle);
        requireExternalMemorySupport();
        return StartupInfo.builder()
                .hwnd(windowHandle)
                .forceNoVkSwapchain(false)
                .interopImage(true)
                .useRemixOwnedChildWindow(true)
                .build();
    }

    @Override
    public void attach(RemixAPI api) {
        if (this.api != null || interop != null) {
            throw new IllegalStateException("Host graphics integration is already attached");
        }
        this.api = api;
        InteropInfo info = api.dxvkGetInteropInfo();
        LOGGER.info(
                "GL interop: got Remix info: dxvkDoneSemaphore_Handle=0x{}, "
                        + "clientDoneSemaphore_Handle=0x{}, imageMemory_Handle=0x{}, w={}, h={}",
                Long.toHexString(info.dxvkDoneSemaphore_Handle),
                Long.toHexString(info.clientDoneSemaphore_Handle),
                Long.toHexString(info.imageMemory_Handle),
                info.width,
                info.height);
        interop = new GlInterop(info);
        textureOverlay = new TextureOverlay();
        LOGGER.info(
                "GL interop created: vkDoneSemaphore=0x{}, clientDoneSemaphore=0x{}",
                Integer.toHexString(interop.getVkDoneSemaphore()),
                Integer.toHexString(interop.getClientDoneSemaphore()));
    }

    @Override
    public int acquireOutputTexture() {
        GlInterop current = requireInterop();
        try (ProfileScope ignored = ProfileScope.begin("Interop.VKToGL")) {
            InteropInfo info = requireApi().dxvkGetInteropInfo();
            current.recreateImagesIf(info);
            current.waitForVk(info);
            return current.getTexture();
        }
    }

    @Override
    public void compositeOutput(
            int colorTexture, Runnable additionalHostPasses) {
        if (colorTexture <= 0) {
            return;
        }
        if (textureOverlay == null) {
            throw new IllegalStateException(
                    "Host graphics compositor is not attached");
        }
        textureOverlay.render(colorTexture);
        additionalHostPasses.run();
    }

    @Override
    public boolean present(RemixRendererSession session) {
        boolean failed = false;
        try {
            GlInterop current = requireInterop();
            try (ProfileScope ignored = ProfileScope.begin("Interop.GLToVK")) {
                copyHostFramebuffer(session, current);
            } catch (RuntimeException exception) {
                failed = true;
                logPresentFailureOnce(
                        "Error copying GL frame to Remix", exception);
            }

            PresentInfo presentInfo = new PresentInfo();
            presentInfo.hwndOverride = 0;
            presentInfo.skipVulkanSwapchain = 0;
            presentInfo.interopFlipY = 1;
            presentInfo.clientDoneSemaphore_ValueToWait =
                    current.getClientDoneSemaphoreValue();
            presentInfo.disableCpuWaitForExternalSemaphores = 0;
            try (ProfileScope ignored = ProfileScope.begin("Remix.Present")) {
                session.present(presentInfo);
            }
            if (!failed) {
                presentFailureLogged = false;
            }
            return true;
        } catch (Exception exception) {
            logPresentFailureOnce("Error presenting frame", exception);
            return false;
        }
    }

    @Override
    public int depthTexture() {
        return interop != null ? interop.getDepthTexture() : 0;
    }

    @Override
    public void close() {
        if (textureOverlay != null) {
            textureOverlay.shutdown();
            textureOverlay = null;
        }
        if (interop != null) {
            interop.destroy();
            interop = null;
        }
        api = null;
    }

    private void copyHostFramebuffer(
            RemixRendererSession session, GlInterop current) {
        if (!session.isSubmitted()) {
            session.ensureSubmitted();
        }
        InteropInfo info = requireApi().dxvkGetInteropInfo();
        current.recreateImagesIf(info);
        current.waitForVk(info);
        current.copyFromFramebuffer(
                Minecraft.getInstance().getMainRenderTarget().frameBufferId);
        current.signalDone();
    }

    private static void applyRequestedWindowPosition(long glfwHandle) {
        String value = System.getenv("RTXREMIX_WINDOW_POS");
        if (value == null || value.isBlank()) {
            return;
        }
        String[] parts = value.split(",");
        try {
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            GLFW.glfwSetWindowPos(glfwHandle, x, y);
            LOGGER.info("RTXREMIX_WINDOW_POS: moved window to ({}, {})", x, y);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "RTXREMIX_WINDOW_POS: could not apply '{}': {}",
                    value,
                    exception.getMessage());
        }
    }

    private static void requireExternalMemorySupport() {
        GLCapabilities capabilities;
        try {
            capabilities = GL.getCapabilities();
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Could not check GL capabilities", exception);
        }
        if (!capabilities.GL_EXT_memory_object
                || !capabilities.GL_EXT_semaphore
                || !capabilities.GL_EXT_memory_object_win32
                || !capabilities.GL_EXT_semaphore_win32) {
            throw new IllegalStateException(
                    "GL interop: required external-memory extensions are not supported");
        }
        if (!capabilities.GL_NV_timeline_semaphore) {
            LOGGER.warn(
                    "GL_NV_timeline_semaphore reported as unsupported. "
                            + "Remix present via GL-VK interop might not work.");
        }
        LOGGER.info("GL interop: required GL extensions are supported");
    }

    private RemixAPI requireApi() {
        if (api == null) {
            throw new IllegalStateException("Host graphics integration is not attached");
        }
        return api;
    }

    private GlInterop requireInterop() {
        if (interop == null) {
            throw new IllegalStateException(
                    "Initialized renderer has no GL/Vulkan interop");
        }
        return interop;
    }

    private void logPresentFailureOnce(String message, Exception exception) {
        if (presentFailureLogged) {
            return;
        }
        presentFailureLogged = true;
        LOGGER.error(
                "{}; further present failures stay unlogged until one frame presents",
                message,
                exception);
    }
}

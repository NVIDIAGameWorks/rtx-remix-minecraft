/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import com.nvidia.rtxremix.InteropInfo;
import com.nvidia.rtxremix.RemixBridge;
import com.nvidia.rtxremix.TextureFormat;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT;
import static org.lwjgl.opengl.EXTSemaphore.*;
import static org.lwjgl.opengl.EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL21.GL_SRGB8_ALPHA8;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_R32F;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL45.*;

/**
 * Imports Remix color and primary-depth images into OpenGL under one semaphore pair.
 */
public final class GlInterop {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlInterop.class);
    private static final int[] NO_BUFFERS = new int[0];

    private final InteropImage colorImage = new InteropImage(true);
    private final InteropImage depthImage = new InteropImage(false);
    private final long dxvkDoneSemaphoreHandle;
    private final long clientDoneSemaphoreHandle;
    private int vkDoneSemaphore;
    private int clientDoneSemaphore;
    private long clientDoneSemaphoreValue;
    private long lastWaitedVkDoneSemaphoreValue = -1;
    /** Wraps the texture {@link #copyFromTexture} reads, which has no framebuffer of its own. */
    private int sourceFbo;
    private int[] barrierTextures = new int[0];
    private int[] barrierLayouts = new int[0];

    public GlInterop(InteropInfo interopInfo) {
        if (interopInfo.dxvkDoneSemaphore_Handle == 0
                || interopInfo.clientDoneSemaphore_Handle == 0) {
            throw new IllegalArgumentException("Remix interop semaphore handles must be nonzero");
        }
        dxvkDoneSemaphoreHandle = interopInfo.dxvkDoneSemaphore_Handle;
        clientDoneSemaphoreHandle = interopInfo.clientDoneSemaphore_Handle;
        vkDoneSemaphore = importVulkanSemaphore(
                dxvkDoneSemaphoreHandle, "dxvkDoneSemaphore_Handle");
        clientDoneSemaphore = importVulkanSemaphore(
                clientDoneSemaphoreHandle, "clientDoneSemaphore_Handle");
    }

    private int importVulkanSemaphore(long win32Handle, String debugName) {
        int semaphore = RemixBridge.openglCreateSemaphoresNV();
        checkGlError("glCreateSemaphoresNV " + debugName);
        if (semaphore == 0) {
            throw new IllegalStateException("Failed to create GL semaphore for " + debugName);
        }
        int result = RemixBridge.openglSemaphoreParameterivNV(
                semaphore,
                RemixBridge.GL_SEMAPHORE_TYPE_NV,
                RemixBridge.GL_SEMAPHORE_TYPE_TIMELINE_NV);
        checkGlError("glSemaphoreParameterivNV " + debugName);
        if (result != 0) {
            throw new IllegalStateException("Failed to configure timeline semaphore " + debugName);
        }
        glImportSemaphoreWin32HandleEXT(
                semaphore, GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, win32Handle);
        checkGlError("glImportSemaphoreWin32HandleEXT " + debugName);
        return semaphore;
    }

    public boolean recreateImagesIf(InteropInfo info) {
        validateSemaphoreHandles(info);
        boolean recreated = colorImage.recreate(
                info.imageMemory_Handle,
                info.allocationSize,
                info.width,
                info.height,
                glInternalFormat(info.interopImageFormat),
                GL_LINEAR,
                "color");
        recreated |= depthImage.recreate(
                info.depthImageMemory_Handle,
                info.depthAllocationSize,
                info.depthWidth,
                info.depthHeight,
                glInternalFormat(info.interopDepthImageFormat),
                GL_NEAREST,
                "depth");
        if (recreated) {
            rebuildBarrierArrays();
            lastWaitedVkDoneSemaphoreValue = -1;
        }
        return recreated;
    }

    public void waitForVk(InteropInfo info) {
        validateSemaphoreHandles(info);
        if (info.dxvkDoneSemaphore_ValueToWait == lastWaitedVkDoneSemaphoreValue) {
            return;
        }
        if (info.dxvkDoneSemaphore_ValueToWait < lastWaitedVkDoneSemaphoreValue) {
            throw new IllegalStateException(
                    "Vulkan-done semaphore value moved backwards on the same semaphore: "
                            + info.dxvkDoneSemaphore_ValueToWait + " after "
                            + lastWaitedVkDoneSemaphoreValue);
        }
        drainStaleGlErrors();
        int result = RemixBridge.openglSemaphoreParameterui64vEXT(
                vkDoneSemaphore,
                RemixBridge.GL_TIMELINE_SEMAPHORE_VALUE_NV,
                info.dxvkDoneSemaphore_ValueToWait);
        checkGlError("glSemaphoreParameterui64vNV(wait)");
        if (result != 0) {
            throw new IllegalStateException("Failed to set Vulkan-done semaphore value");
        }
        glWaitSemaphoreEXT(
                vkDoneSemaphore, NO_BUFFERS, barrierTextures, barrierLayouts);
        checkGlError("glWaitSemaphoreEXT");
        lastWaitedVkDoneSemaphoreValue = info.dxvkDoneSemaphore_ValueToWait;
    }

    private void validateSemaphoreHandles(InteropInfo info) {
        if (info.dxvkDoneSemaphore_Handle != dxvkDoneSemaphoreHandle
                || info.clientDoneSemaphore_Handle != clientDoneSemaphoreHandle) {
            throw new IllegalStateException(
                    "Remix replaced an interop semaphore during an active session");
        }
    }

    public void signalDone() {
        clientDoneSemaphoreValue++;
        int result = RemixBridge.openglSemaphoreParameterui64vEXT(
                clientDoneSemaphore,
                RemixBridge.GL_TIMELINE_SEMAPHORE_VALUE_NV,
                clientDoneSemaphoreValue);
        checkGlError("glSemaphoreParameterui64vNV(signal)");
        if (result != 0) {
            throw new IllegalStateException("Failed to set client-done semaphore value");
        }
        glSignalSemaphoreEXT(
                clientDoneSemaphore, NO_BUFFERS, barrierTextures, barrierLayouts);
        checkGlError("glSignalSemaphoreEXT");
        glFlush();
    }

    /**
     * Copies a colour texture into the shared image. Since 26.x vanilla's main
     * render target is a texture with no framebuffer of its own, so it is
     * attached to one this interop owns, by direct state access, which changes
     * no binding vanilla's GL backend has cached.
     */
    public void copyFromTexture(int texture, int width, int height) {
        drainStaleGlErrors();
        if (!colorImage.available()) {
            return;
        }
        if (sourceFbo == 0) {
            sourceFbo = glCreateFramebuffers();
        }
        glNamedFramebufferTexture(sourceFbo, GL_COLOR_ATTACHMENT0, texture, 0);
        int destinationFbo = colorImage.framebuffer();
        glBlitNamedFramebuffer(
                sourceFbo,
                destinationFbo,
                0,
                0,
                width,
                height,
                0,
                0,
                colorImage.width,
                colorImage.height,
                GL_COLOR_BUFFER_BIT,
                GL_NEAREST);
        checkGlError("glBlitNamedFramebuffer");
    }

    public int getTexture() {
        return colorImage.texture;
    }

    public int getDepthTexture() {
        return depthImage.texture;
    }

    public int getVkDoneSemaphore() {
        return vkDoneSemaphore;
    }

    public int getClientDoneSemaphore() {
        return clientDoneSemaphore;
    }

    public long getClientDoneSemaphoreValue() {
        return clientDoneSemaphoreValue;
    }

    public void destroy() {
        colorImage.destroy();
        if (sourceFbo != 0) {
            glDeleteFramebuffers(sourceFbo);
            sourceFbo = 0;
        }
        depthImage.destroy();
        if (vkDoneSemaphore != 0) {
            glDeleteSemaphoresEXT(vkDoneSemaphore);
            vkDoneSemaphore = 0;
        }
        if (clientDoneSemaphore != 0) {
            glDeleteSemaphoresEXT(clientDoneSemaphore);
            clientDoneSemaphore = 0;
        }
        barrierTextures = new int[0];
        barrierLayouts = new int[0];
        lastWaitedVkDoneSemaphoreValue = -1;
        LOGGER.info("GlInterop: destroyed");
    }

    private void rebuildBarrierArrays() {
        int count = (colorImage.available() ? 1 : 0) + (depthImage.available() ? 1 : 0);
        barrierTextures = new int[count];
        barrierLayouts = new int[count];
        int index = 0;
        if (colorImage.available()) {
            barrierTextures[index] = colorImage.texture;
            barrierLayouts[index++] = GL_LAYOUT_GENERAL_EXT;
        }
        if (depthImage.available()) {
            barrierTextures[index] = depthImage.texture;
            barrierLayouts[index] = GL_LAYOUT_GENERAL_EXT;
        }
    }

    private static int glInternalFormat(int remixFormat) {
        return switch (remixFormat) {
            case TextureFormat.RGBA8_UNORM, TextureFormat.BGRA8_UNORM -> GL_RGBA8;
            case TextureFormat.RGBA8_SRGB, TextureFormat.BGRA8_SRGB -> GL_SRGB8_ALPHA8;
            case TextureFormat.R32_SFLOAT -> GL_R32F;
            default -> throw new IllegalArgumentException(
                    "Unsupported Remix interop texture format: " + remixFormat);
        };
    }

    /**
     * GL error flags are sticky, so an error left by the rest of the frame would be
     * reported as an interop failure by the next {@link #checkGlError}. Called once at
     * the head of each interop sequence rather than in every method: glGetError is a
     * synchronous driver query on the render thread, and the calls that follow within
     * one sequence report their own errors.
     */
    private void drainStaleGlErrors() {
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {
            // Discard: only errors raised by the interop's own calls are its to report.
        }
    }

    private void checkGlError(String operation) {
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            throw new IllegalStateException(
                    "OpenGL error after " + operation + ": 0x" + Integer.toHexString(error));
        }
    }

    private final class InteropImage {
        private final boolean color;
        private long memoryHandle;
        private int memoryObject;
        private int texture;
        private int width;
        private int height;
        private int internalFormat;
        private int destinationFbo;

        private InteropImage(boolean color) {
            this.color = color;
        }

        private boolean recreate(
                long newMemoryHandle,
                long allocationSize,
                int newWidth,
                int newHeight,
                int internalFormat,
                int filter,
                String debugName) {
            if (newMemoryHandle == 0 || newMemoryHandle == -1L) {
                if (available()) {
                    destroy();
                    return true;
                }
                return false;
            }
            if (available()
                    && memoryHandle == newMemoryHandle
                    && width == newWidth
                    && height == newHeight
                    && this.internalFormat == internalFormat) {
                return false;
            }
            if (allocationSize <= 0 || newWidth <= 0 || newHeight <= 0 || internalFormat == 0) {
                throw new IllegalArgumentException("Invalid Remix " + debugName + " interop image");
            }
            destroy();

            memoryHandle = newMemoryHandle;
            width = newWidth;
            height = newHeight;
            this.internalFormat = internalFormat;
            try {
                drainStaleGlErrors();
                memoryObject = glCreateMemoryObjectsEXT();
                glImportMemoryWin32HandleEXT(
                        memoryObject,
                        allocationSize,
                        GL_HANDLE_TYPE_OPAQUE_WIN32_EXT,
                        newMemoryHandle);
                checkGlError("glImportMemoryWin32HandleEXT(" + debugName + ")");

                texture = glCreateTextures(GL_TEXTURE_2D);
                glTextureStorageMem2DEXT(
                        texture, 1, internalFormat, width, height, memoryObject, 0);
                glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER, filter);
                glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, filter);
                glTextureParameteri(texture, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTextureParameteri(texture, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                checkGlError("glTextureStorageMem2DEXT(" + debugName + ")");
            } catch (RuntimeException exception) {
                destroy();
                throw exception;
            }

            LOGGER.info(
                    "GlInterop: imported {} image texture={} size={}x{}",
                    debugName,
                    texture,
                    width,
                    height);
            return true;
        }

        private int framebuffer() {
            if (!color) {
                throw new IllegalStateException("Depth interop image cannot be a color target");
            }
            if (destinationFbo == 0) {
                destinationFbo = glCreateFramebuffers();
                glNamedFramebufferTexture(destinationFbo, GL_COLOR_ATTACHMENT0, texture, 0);
                checkGlError("glNamedFramebufferTexture");
            }
            return destinationFbo;
        }

        private boolean available() {
            return memoryObject != 0 && texture != 0;
        }

        private void destroy() {
            if (destinationFbo != 0) {
                glDeleteFramebuffers(destinationFbo);
                destinationFbo = 0;
            }
            if (texture != 0) {
                glDeleteTextures(texture);
                texture = 0;
            }
            if (memoryObject != 0) {
                glDeleteMemoryObjectsEXT(memoryObject);
                memoryObject = 0;
            }
            memoryHandle = 0;
            width = 0;
            height = 0;
            internalFormat = 0;
        }
    }
}

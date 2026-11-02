/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.interop;

import com.nvidia.rtxremix.InteropInfo;
import com.nvidia.rtxremix.TextureFormat;

/**
 * Imports Remix color and optional primary-depth images into the current Windows OpenGL context.
 * All methods must run on the creating render thread with that context current.
 * The bridge DLL must already be loaded through RemixAPI before use.
 */
public final class LegacyGlInterop implements AutoCloseable {
    private final long runtimeDoneHandle;
    private final long clientDoneHandle;
    private final Thread owner = Thread.currentThread();
    private long nativeState;
    private boolean acquired;

    /** Returns the HWND owning the current WGL context, or zero if no window is current. */
    public static native long currentWindowHandle();

    /** Verifies that a recreated window still hosts this session's OpenGL context. */
    public void validateContext() {
        checkThread();
        if (nativeState == 0) throw new IllegalStateException("OpenGL interop is closed");
        validateContext(nativeState);
    }

    public LegacyGlInterop(InteropInfo initial) {
        if (initial == null || initial.dxvkDoneSemaphore_Handle == 0 || initial.clientDoneSemaphore_Handle == 0) {
            throw new IllegalArgumentException("Remix must supply both interop semaphore handles");
        }
        runtimeDoneHandle = initial.dxvkDoneSemaphore_Handle;
        clientDoneHandle = initial.clientDoneSemaphore_Handle;
        nativeState = create(runtimeDoneHandle, clientDoneHandle);
        if (nativeState == 0) throw new IllegalStateException("Native OpenGL interop initialization failed");
    }

    /**
     * Waits for Remix, copies its color into the supplied GL framebuffer, and
     * signals that GL has finished reading the shared image. Returns the timeline
     * value to pass to PresentInfo.clientDoneSemaphore_ValueToWait. Call after
     * RemixAPI.submit, then present with skipVulkanSwapchain=1 for app-owned swap.
     * Existing framebuffer bindings, texture binding, sRGB and scissor state are restored.
     */
    public long copyToFramebuffer(InteropInfo frame, int targetFbo, int width, int height, boolean flipY) {
        acquireToFramebuffer(frame, targetFbo, width, height, flipY);
        return releaseAcquired();
    }

    /** Waits for the submitted native image without replacing an already drawn vanilla frame. */
    public void acquire(InteropInfo frame) {
        validateFrame(frame);
        acquire(nativeState, frame.imageMemory_Handle, frame.allocationSize, frame.width, frame.height,
                frame.interopImageFormat, frame.depthImageMemory_Handle, frame.depthAllocationSize,
                frame.depthWidth, frame.depthHeight, frame.interopDepthImageFormat,
                frame.dxvkDoneSemaphore_ValueToWait, 0, 0, 0, false, false);
        acquired = true;
    }

    /** Waits and copies native color, retaining ownership until finishFromFramebuffer or releaseAcquired. */
    public void acquireToFramebuffer(InteropInfo frame, int targetFbo, int width, int height, boolean flipY) {
        validateFrame(frame);
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Target framebuffer must be nonempty");
        acquire(nativeState, frame.imageMemory_Handle, frame.allocationSize, frame.width, frame.height,
                frame.interopImageFormat, frame.depthImageMemory_Handle, frame.depthAllocationSize,
                frame.depthWidth, frame.depthHeight, frame.interopDepthImageFormat,
                frame.dxvkDoneSemaphore_ValueToWait, targetFbo, width, height, flipY, true);
        acquired = true;
    }

    /** Copies final GL color into the owned image and releases it for Vulkan presentation, even if the copy fails. */
    public long finishFromFramebuffer(int sourceFbo, int width, int height, boolean flipY) {
        validateContext();
        if (!acquired) throw new IllegalStateException("No acquired OpenGL interop frame");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Source framebuffer must be nonempty");
        try { return finish(nativeState, sourceFbo, width, height, flipY); }
        finally { acquired = false; }
    }

    /** Releases an unfinished frame without copying; returns the last signaled value when already released. */
    public long releaseAcquired() {
        validateContext();
        try { return release(nativeState); }
        finally { acquired = false; }
    }
    public boolean hasAcquiredFrame() { checkThread(); return acquired; }

    /**
     * Borrows the read-only GL_R32F primary-depth texture while this frame is acquired.
     * Returns zero when Remix supplies no depth. Its image orientation matches the
     * unflipped native color; consumers using a flipped color blit must flip depth UVs too.
     * The name must not be retained past finishFromFramebuffer or releaseAcquired.
     */
    public int getDepthTexture() {
        validateContext();
        if (!acquired) throw new IllegalStateException("No acquired OpenGL interop frame");
        return depthTexture(nativeState);
    }

    private void validateFrame(InteropInfo frame) {
        checkThread();
        if (nativeState == 0) throw new IllegalStateException("OpenGL interop is closed");
        if (acquired) throw new IllegalStateException("Previous OpenGL interop frame is still acquired");
        if (frame == null || frame.dxvkDoneSemaphore_Handle != runtimeDoneHandle
                || frame.clientDoneSemaphore_Handle != clientDoneHandle) {
            throw new IllegalArgumentException("Interop semaphore handles changed; recreate the interop session");
        }
        if (frame.imageMemory_Handle == 0 || frame.allocationSize <= 0 || frame.width <= 0
                || frame.height <= 0) {
            throw new IllegalArgumentException("Submit must allocate a nonempty interop color image");
        }
        if (frame.depthImageMemory_Handle != 0 && (frame.depthAllocationSize <= 0
                || frame.depthWidth <= 0 || frame.depthHeight <= 0
                || frame.interopDepthImageFormat != TextureFormat.R32_SFLOAT)) {
            throw new IllegalArgumentException("Interop primary depth must be a nonempty R32_SFLOAT image");
        }
    }

    /** Releases GL imports before the Remix runtime shuts down. */
    @Override
    public void close() {
        checkThread();
        if (nativeState != 0) {
            destroy(nativeState);
            nativeState = 0;
            acquired = false;
        }
    }
    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("OpenGL interop requires its creating render thread");
    }

    private static native long create(long runtimeDoneHandle, long clientDoneHandle);
    private static native void validateContext(long state);
    private static native void acquire(long state, long imageHandle, long allocationSize,
                                    int imageWidth, int imageHeight, int imageFormat,
                                    long depthHandle, long depthAllocationSize,
                                    int depthWidth, int depthHeight, int depthFormat,
                                    long runtimeDoneValue, int targetFbo,
                                    int targetWidth, int targetHeight, boolean flipY, boolean copy);
    private static native long finish(long state, int sourceFbo, int width, int height, boolean flipY);
    private static native long release(long state);
    private static native int depthTexture(long state);
    private static native void destroy(long state);
}

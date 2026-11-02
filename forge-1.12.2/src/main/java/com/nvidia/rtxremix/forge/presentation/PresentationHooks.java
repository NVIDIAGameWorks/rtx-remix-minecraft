/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.presentation;

import org.lwjgl.opengl.Display;

/** Replaces only the GL swap after Remix acquires presentation ownership. */
public final class PresentationHooks {
    public interface Backend {
        boolean ownsSwapchain();
        void presentFrame();
    }
    private static Backend backend;
    private static Runnable nativeUpdate;
    private PresentationHooks() { }
    public static void attach(Backend value) {
        if (backend != null) throw new IllegalStateException("Presentation backend is already attached");
        backend = value;
        Runnable present = value::presentFrame;
        nativeUpdate = () -> LegacyDisplayUpdate.update(present);
    }
    public static void updateDisplay() {
        if (backend == null || !backend.ownsSwapchain()) Display.update();
        else nativeUpdate.run();
    }
}
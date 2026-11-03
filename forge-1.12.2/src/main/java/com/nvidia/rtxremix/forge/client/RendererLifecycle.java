/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.forge.RemixForgeMod;

/** Releases GL imports before Display destruction and the runtime after destruction. */
public final class RendererLifecycle {
    private static ForgeRenderer renderer;
    private RendererLifecycle() { }
    static void attach(ForgeRenderer value) { renderer = value; }
    static ForgeRenderer renderer() { return renderer; }

    public static void beforeDisplayDestroy() {
        if (renderer == null) return;
        try { renderer.beforeDisplayDestroy(); }
        catch (RuntimeException | LinkageError failure) {
            RemixForgeMod.LOG.error("Unable to release Remix resources before window destruction", failure);
        }
    }
    public static void afterDisplayDestroy() {
        if (renderer == null) return;
        try { renderer.afterDisplayDestroy(); }
        catch (RuntimeException | LinkageError failure) {
            RemixForgeMod.LOG.error("Unable to shut down Remix after window destruction", failure);
        } finally { renderer = null; }
    }
}
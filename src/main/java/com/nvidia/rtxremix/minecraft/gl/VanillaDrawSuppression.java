/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import com.mojang.blaze3d.systems.RenderSystem;

/** Render-thread scope that omits indexed geometry without omitting its surrounding GL state. */
public final class VanillaDrawSuppression {
    private static int depth;

    private VanillaDrawSuppression() {}

    public static void begin() {
        RenderSystem.assertOnRenderThread();
        depth++;
    }

    public static void end() {
        RenderSystem.assertOnRenderThread();
        if (depth == 0) {
            throw new IllegalStateException("Unbalanced vanilla draw suppression");
        }
        depth--;
    }

    public static boolean isActive() {
        return depth != 0;
    }
}

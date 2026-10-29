/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.util;

import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.math.ColorHelper;

/** Keeps Remix texture channels in the ABGR order used by its CPU pixel readers. */
public final class NativeImageColors {
    private NativeImageColors() {
    }

    public static int getAbgr(NativeImage image, int x, int y) {
        return ColorHelper.toAbgr(image.getColorArgb(x, y));
    }
}

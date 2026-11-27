/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import net.minecraft.client.Options;

/** Exposes canonical unfocused-framerate policy on Minecraft's option scale. */
public final class UnfocusedFramerateLimit {
    public static final int MIN = UnfocusedFrameratePolicy.MIN;
    public static final int UNLIMITED = Options.UNLIMITED_FRAMERATE_CUTOFF;
    public static final int STEP = UnfocusedFrameratePolicy.STEP;
    public static final int DEFAULT = UnfocusedFrameratePolicy.DEFAULT;

    private UnfocusedFramerateLimit() {}

    public static int get() {
        return UnfocusedFrameratePolicy.get();
    }

    public static void set(int framerate) {
        UnfocusedFrameratePolicy.set(framerate);
    }

    public static int apply(int vanillaLimit) {
        return UnfocusedFrameratePolicy.apply(vanillaLimit);
    }
}

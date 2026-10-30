/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import net.minecraft.client.Options;

/**
 * The framerate cap applied while the game window is unfocused.
 *
 * <p>Uses vanilla's Max Framerate scale, so {@link #UNLIMITED} is the same
 * value vanilla treats as unlimited. The cap only ever lowers vanilla's own
 * limit, which makes Unlimited mean "Max Framerate alone decides".
 */
public final class UnfocusedFramerateLimit {

    public static final int MIN = 10;
    public static final int UNLIMITED = Options.UNLIMITED_FRAMERATE_CUTOFF;
    /**
     * Vanilla's Max Framerate moves in steps of 10, and its top step is its
     * Unlimited value, so the slider needs no separate sentinel position.
     */
    public static final int STEP = 10;
    /** 100 ms per frame keeps a background window close to idle. */
    public static final int DEFAULT = 10;

    private static int limit = DEFAULT;

    private UnfocusedFramerateLimit() {}

    public static int get() {
        return limit;
    }

    public static void set(int framerate) {
        limit = framerate;
    }

    /**
     * @param vanillaLimit the limit Minecraft would use with the window focused
     * @return the limit to use while the window is unfocused
     */
    public static int apply(int vanillaLimit) {
        return Math.min(vanillaLimit, limit);
    }
}

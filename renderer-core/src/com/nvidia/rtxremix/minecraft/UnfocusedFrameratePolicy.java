/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

/**
 * The framerate cap applied while the game window is unfocused.
 *
 * <p>The cap only ever lowers the focused limit supplied by the active
 * Minecraft adapter.
 */
public final class UnfocusedFrameratePolicy {

    public static final int MIN = 10;
    /**
     * Vanilla's Max Framerate moves in steps of 10, and its top step is its
     * Unlimited value, so the slider needs no separate sentinel position.
     */
    public static final int STEP = 10;
    /** 100 ms per frame keeps a background window close to idle. */
    public static final int DEFAULT = 10;

    private static int limit = DEFAULT;

    private UnfocusedFrameratePolicy() {}

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

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import net.minecraft.client.Minecraft;

/**
 * Minecraft's render distance as the mod's cull ceilings read it.
 *
 * <p>Render-thread only: it reads the live client option. The fallback keeps
 * headless callers (tests, early init) at the 12 chunks the mod has always
 * assumed when no client exists.
 */
public final class ClientViewDistance {

    public static final int FALLBACK_CHUNKS = 12;

    private ClientViewDistance() {}

    public static int chunks() {
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.options != null) {
            return client.options.renderDistance().get();
        }
        return FALLBACK_CHUNKS;
    }

    public static double blocks() {
        return chunks() * 16.0;
    }
}

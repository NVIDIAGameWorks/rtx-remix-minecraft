/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import com.nvidia.rtxremix.RemixAPI;

/**
 * Singleton store for {@link RemixTexture} handles. Textures are uploaded from
 * atlas and dynamic-texture captures and are created and destroyed on the
 * render thread.
 */
public class RemixTextureStore extends RemixAssetStore<RemixTexture> {

    private static RemixTextureStore INSTANCE;

    private RemixTextureStore(RemixAPI api) { super(api); }

    public static void init(RemixAPI api) { INSTANCE = new RemixTextureStore(api); }

    public static RemixTextureStore get() { return INSTANCE; }

    public static void shutdown() {
        if (INSTANCE == null) return;
        INSTANCE.destroyAll();
        INSTANCE = null;
    }

    @Override
    protected void destroyLeakedHandle(RemixAPI api, long handle) {
        api.destroyTexture(handle);
    }
}

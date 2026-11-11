/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import com.nvidia.rtxremix.RemixAPI;

/**
 * Singleton store for {@link RemixMesh} handles. Worker threads build the
 * vertex data, but every mesh here is created and destroyed on the render
 * thread, which is the only thread allowed to touch Remix scene assets.
 */
public class RemixMeshStore extends RemixAssetStore<RemixMesh> {

    private static RemixMeshStore INSTANCE;

    private RemixMeshStore(RemixAPI api) { super(api); }

    public static void init(RemixAPI api) { INSTANCE = new RemixMeshStore(api); }

    public static RemixMeshStore get() { return INSTANCE; }

    public static void shutdown() {
        if (INSTANCE == null) return;
        INSTANCE.destroyAll();
        INSTANCE = null;
    }

    @Override
    protected void destroyLeakedHandle(RemixAPI api, long handle) {
        api.destroyMesh(handle);
    }
}

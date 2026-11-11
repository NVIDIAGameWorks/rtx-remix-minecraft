/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.RemixAPI;

/**
 * Singleton store for {@link RemixMaterial} handles. Materials are long lived,
 * built once per atlas, sprite or texture set as the texture managers come up,
 * and created and destroyed on the render thread.
 */
public class RemixMaterialStore extends RemixAssetStore<RemixMaterial> {

    private static RemixMaterialStore INSTANCE;

    private RemixMaterialStore(RemixAPI api) { super(api); }

    public static void init(RemixAPI api) { INSTANCE = new RemixMaterialStore(api); }

    public static RemixMaterialStore get() { return INSTANCE; }

    public void update(RemixMaterial material, MaterialInfo newInfo) {
        material.update(api, newInfo);
    }

    public static void shutdown() {
        if (INSTANCE == null) return;
        INSTANCE.destroyAll();
        INSTANCE = null;
    }

    @Override
    protected void destroyLeakedHandle(RemixAPI api, long handle) {
        api.destroyMaterial(handle);
    }
}

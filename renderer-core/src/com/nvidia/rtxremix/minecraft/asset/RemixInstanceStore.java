/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import com.nvidia.rtxremix.RemixAPI;

/**
 * Singleton store for retained {@link RemixInstance} scene handles.
 */
public class RemixInstanceStore extends RemixAssetStore<RemixInstance> {

    private static RemixInstanceStore INSTANCE;

    private RemixInstanceStore(RemixAPI api) { super(api); }

    public static void init(RemixAPI api) { INSTANCE = new RemixInstanceStore(api); }

    public static RemixInstanceStore get() { return INSTANCE; }

    public static void shutdown() {
        if (INSTANCE == null) return;
        INSTANCE.destroyAll();
        INSTANCE = null;
    }

    @Override
    protected void destroyLeakedHandle(RemixAPI api, long handle) {
        if (handle != RemixInstance.INACTIVE_HANDLE) {
            api.sceneInstanceRemove(handle);
        }
    }
}

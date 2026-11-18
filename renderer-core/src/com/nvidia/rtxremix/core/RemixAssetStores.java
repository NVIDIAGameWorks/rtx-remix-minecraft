/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.minecraft.asset.RemixInstanceStore;
import com.nvidia.rtxremix.minecraft.asset.RemixLightStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterialStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshStore;
import com.nvidia.rtxremix.minecraft.asset.RemixTextureStore;

/** Owns the process-wide Remix asset stores used by one renderer scene. */
public final class RemixAssetStores implements AutoCloseable {
    private boolean closed;

    private RemixAssetStores() {
    }

    public static RemixAssetStores open(RemixAPI remixApi) {
        if (remixApi == null) {
            throw new NullPointerException("remixApi");
        }
        try {
            RemixMeshStore.init(remixApi);
            RemixInstanceStore.init(remixApi);
            RemixTextureStore.init(remixApi);
            RemixMaterialStore.init(remixApi);
            RemixLightStore.init(remixApi);
            return new RemixAssetStores();
        } catch (RuntimeException exception) {
            shutdownStores();
            throw exception;
        }
    }

    public void collectGarbage() {
        if (closed) {
            return;
        }
        if (RemixMeshStore.get() != null) RemixMeshStore.get().gc();
        if (RemixInstanceStore.get() != null) RemixInstanceStore.get().gc();
        if (RemixTextureStore.get() != null) RemixTextureStore.get().gc();
        if (RemixMaterialStore.get() != null) RemixMaterialStore.get().gc();
        if (RemixLightStore.get() != null) RemixLightStore.get().gc();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        shutdownStores();
    }

    private static void shutdownStores() {
        RemixInstanceStore.shutdown();
        RemixMeshStore.shutdown();
        RemixLightStore.shutdown();
        RemixMaterialStore.shutdown();
        RemixTextureStore.shutdown();
    }
}

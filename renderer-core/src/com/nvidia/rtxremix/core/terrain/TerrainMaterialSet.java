/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core.terrain;

import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;

/**
 * Native materials supplied by a loader's atlas adapter. Bucket decoding stays
 * in the renderer core so every loader routes identical geometry identically.
 */
public interface TerrainMaterialSet {
    RemixMaterial atlasMaterial(int layerType);

    RemixMaterial atlasSssMaterial(int layerType);

    RemixMaterial waterMaterial();

    default RemixMaterial materialForBucket(String bucketKey) {
        boolean sss = bucketKey.endsWith("_sss");
        String baseName = sss
                ? bucketKey.substring(0, bucketKey.length() - 4)
                : bucketKey;
        if ("water".equals(baseName)) {
            return waterMaterial();
        }
        int layerType = TerrainRenderLayer.type(baseName);
        return sss ? atlasSssMaterial(layerType) : atlasMaterial(layerType);
    }
}

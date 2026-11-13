/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core.terrain;

/**
 * Texture facts needed by canonical terrain routing. Loader adapters may use
 * any native resource-key type and expose it through {@link Object}.
 */
public interface TerrainTexturePolicy {
    SpriteFacts sprite(Object resourceKey);

    boolean hasSssMaterial(int layerType);

    interface SpriteFacts {
        boolean hasTransparency();

        boolean hasSss();

        boolean hasTransparency(float minU, float minV, float maxU, float maxV);
    }
}

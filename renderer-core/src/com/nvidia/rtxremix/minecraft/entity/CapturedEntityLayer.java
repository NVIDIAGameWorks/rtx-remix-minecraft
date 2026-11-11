/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

/** Zero-copy view of one render layer captured by a loader adapter. */
public interface CapturedEntityLayer {
    EntityTextureKey textureId();
    EntitySurfaceMode surfaceMode();
    CapturedEntityGeometry geometry();
    int layerIndex();
    EntityTopology topology();
    boolean transientGeometry();
    boolean isVanillaPlayerSkinLayer(Object owner);
}

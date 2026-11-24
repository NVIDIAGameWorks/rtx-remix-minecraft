/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

/** Material behavior translated from a loader's render-layer state. */
public enum EntitySurfaceMode {
    OPAQUE,
    CUTOUT,
    TRANSLUCENT,
    EMISSIVE,
    GLASS,
    /**
     * A block-atlas cutout with its emission off: a hand-held item whose lit
     * element is out. A torch's texture glows by itself, so the torch would
     * otherwise look lit after its flame and its light have gone.
     */
    DOUSED
}

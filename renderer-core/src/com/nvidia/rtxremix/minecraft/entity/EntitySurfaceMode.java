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
    GLASS
}

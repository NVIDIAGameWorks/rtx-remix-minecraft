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
    /**
     * Alpha-tested and self-lit: an unblended surface that glows. Seen
     * through refracting glass, a blended emitter drops out wherever the ray
     * past it reaches only sky; an emitter that must stay solid, such as the
     * beacon beam's core, takes this instead.
     */
    EMISSIVE_CUTOUT,
    GLASS
}

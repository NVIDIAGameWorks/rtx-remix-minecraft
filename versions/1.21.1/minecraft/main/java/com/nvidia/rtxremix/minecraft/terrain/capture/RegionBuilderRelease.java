/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

/**
 * A render region builder's reference on the chunk snapshots it cached. The builder holds one
 * reference per snapshot from creation until its caller is done scheduling with it, so a
 * snapshot handed to several regions cannot be recycled by an early task while a later region
 * is still being assembled from the same cache.
 */
public interface RegionBuilderRelease {
    /** Drops the builder's reference on every snapshot it created. Call once, when done with the builder. */
    void rtxremix$releaseSnapshots();
}

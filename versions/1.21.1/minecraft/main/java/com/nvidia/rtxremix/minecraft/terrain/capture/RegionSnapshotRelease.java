/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

/**
 * Ownership of a render region snapshot's pooled storage. A region retains each chunk
 * snapshot it includes; the rebuild task that consumed the region releases it once, after
 * the section build, and a snapshot returns its arrays to {@link RenderRegionStoragePool}
 * when its last region lets go.
 */
public interface RegionSnapshotRelease {
    /** Called by a region on each chunk snapshot it takes a reference to. */
    void rtxremix$retain();

    /** Drops one reference; recycles the storage on the last one. Idempotent per holder. */
    void rtxremix$release();
}

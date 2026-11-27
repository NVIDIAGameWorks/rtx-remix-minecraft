/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

/**
 * Per-slot record of which horizontal neighbour columns the slot's last region snapshot
 * contained. Bit k stands for NeighbourReflagFilter.DX[k], DZ[k]. Render thread only.
 */
public interface BuiltChunkNeighbourhoodAccess {

    int rtxremix$snapshotMask();

    /**
     * Forgets a neighbour column that unloaded or is being reloaded in place, so that its
     * next load re-flags this slot instead of being dropped as already captured.
     */
    void rtxremix$clearSnapshotBit(int bit);
}

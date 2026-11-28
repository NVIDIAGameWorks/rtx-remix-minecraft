/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core.terrain;

import com.nvidia.rtxremix.minecraft.terrain.capture.SectionMeshData;
import com.nvidia.rtxremix.minecraft.light.LightEntry;

import java.util.List;

/**
 * Loader-neutral geometry and connectivity captured for one chunk section.
 *
 * <p>Version adapters own the Minecraft interception and populate this shape;
 * the canonical terrain pipeline owns storage, diffing, column rebuilding,
 * and publication from this point onward.</p>
 */
public interface TerrainSectionData extends SectionCapture {
    SectionMeshData meshData();

    long topFaceMask();
    long bottomFaceMask();
    long eastFaceMask();
    long westFaceMask();
    long southFaceMask();
    long northFaceMask();

    long[] opacityBitmap();

    List<LightEntry> lights();
}

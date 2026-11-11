/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core.terrain;

import com.nvidia.rtxremix.minecraft.terrain.capture.SectionMeshData;

/** Loader-neutral ownership contract for one captured 16-cubed section. */
public interface SectionCapture {
    int sectionX();

    int sectionY();

    int sectionZ();

    SectionMeshData meshData();

    long estimatedQueueBytes();

    default void release() {
        SectionMeshData mesh = meshData();
        if (mesh != null) {
            mesh.release();
        }
    }
}

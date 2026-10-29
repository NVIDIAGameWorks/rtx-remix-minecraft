/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

/** Routes classified faces while retaining the original vertex attributes. */
final class BlockOverlaySink implements BlockQuadSink {
    private final MeshPacking overlays;
    private MeshPacking ordinary;
    private boolean overlay;

    BlockOverlaySink(MeshPacking overlays) { this.overlays = overlays; }
    void target(MeshPacking ordinary) { this.ordinary = ordinary; overlay = false; }
    @Override public void overlay(boolean value) { overlay = value; }
    @Override public void quad(float[] positions, float[] uv, int[] colors, float nx, float ny, float nz,
                               float minU, float minV, float maxU, float maxV) {
        (overlay ? overlays : ordinary).quad(positions, uv, colors, nx, ny, nz, minU, minV, maxU, maxV);
    }
}

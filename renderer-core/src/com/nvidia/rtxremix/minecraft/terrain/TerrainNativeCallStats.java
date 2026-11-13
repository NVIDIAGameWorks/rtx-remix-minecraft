/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.minecraft.asset.RemixMesh;

/**
 * Render-thread tally of the native Remix calls the terrain pipeline makes in one frame.
 *
 * <p>Install and retirement pace themselves with time budgets, so a frame spike can only be
 * attributed to terrain when the frame trace also says how many pages were created, how many
 * bytes they carried and how many scene objects came and went. This class counts exactly that.
 * It deliberately reads no clock: the loader's profiling adapter carries the
 * duration, and a clock read per native call is not free on every benchmark machine.
 */
public final class TerrainNativeCallStats {

    private int pagesInstalled;
    private long installBytes;
    private int instancesAdded;
    private int instancesRemoved;
    private int meshesDestroyed;

    /** Clears the tallies for a new frame. */
    public void beginFrame() {
        pagesInstalled = 0;
        installBytes = 0;
        instancesAdded = 0;
        instancesRemoved = 0;
        meshesDestroyed = 0;
    }

    /** Records a mesh page created natively. */
    public void pageCreated(RemixMesh mesh) {
        pagesInstalled++;
        installBytes += mesh.estimatedNativeBytes();
    }

    /** Records a retained scene instance added for a freshly created page. */
    public void instanceAdded() {
        instancesAdded++;
    }

    /** Records a retained scene instance removed by retirement. */
    public void instanceRemoved() {
        instancesRemoved++;
    }

    /** Records a mesh destroyed by retirement. */
    public void meshDestroyed() {
        meshesDestroyed++;
    }

    /** Mesh pages created natively this frame. */
    public int pagesInstalled() {
        return pagesInstalled;
    }

    /** Estimated native bytes of the pages created this frame. */
    public long installBytes() {
        return installBytes;
    }

    public int instancesAdded() {
        return instancesAdded;
    }

    public int instancesRemoved() {
        return instancesRemoved;
    }

    public int meshesDestroyed() {
        return meshesDestroyed;
    }
}

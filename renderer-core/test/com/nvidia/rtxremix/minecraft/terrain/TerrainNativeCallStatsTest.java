/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerrainNativeCallStatsTest {

    @Test
    void talliesEachCallKind() {
        TerrainNativeCallStats stats = new TerrainNativeCallStats();
        RemixMesh mesh = twoSurfaceMesh();

        stats.beginFrame();
        stats.pageCreated(mesh);
        stats.pageCreated(mesh);
        stats.instanceAdded();
        stats.instanceRemoved();
        stats.meshDestroyed();

        assertEquals(2, stats.pagesInstalled());
        assertEquals(2 * mesh.estimatedNativeBytes(), stats.installBytes());
        assertEquals(1, stats.instancesAdded());
        assertEquals(1, stats.instancesRemoved());
        assertEquals(1, stats.meshesDestroyed());
    }

    @Test
    void beginFrameDiscardsThePreviousFrame() {
        TerrainNativeCallStats stats = new TerrainNativeCallStats();
        RemixMesh mesh = twoSurfaceMesh();

        stats.beginFrame();
        stats.pageCreated(mesh);
        stats.instanceAdded();
        stats.instanceRemoved();
        stats.meshDestroyed();
        stats.beginFrame();

        assertEquals(0, stats.pagesInstalled());
        assertEquals(0, stats.installBytes());
        assertEquals(0, stats.instancesAdded());
        assertEquals(0, stats.instancesRemoved());
        assertEquals(0, stats.meshesDestroyed());
    }

    private static RemixMesh twoSurfaceMesh() {
        return new RemixMesh(new RemixMeshSurface[]{oneQuadSurface(), oneQuadSurface()});
    }

    private static RemixMeshSurface oneQuadSurface() {
        return new RemixMeshSurface(null)
                .setVertexData(ByteBuffer.allocate(4 * HardcodedVertex.STRIDE), 4)
                .setIndexData(ByteBuffer.allocate(6 * Integer.BYTES), 6);
    }
}

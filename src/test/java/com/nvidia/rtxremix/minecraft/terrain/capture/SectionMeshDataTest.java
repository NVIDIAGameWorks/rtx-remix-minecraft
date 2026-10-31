/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.nvidia.rtxremix.minecraft.terrain.ChunkManager;
import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.minecraft.texture.RedstoneEmission;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SectionMeshDataTest {

    @Test
    void cachesHashesIndependentlyByCategory() {
        SectionMeshData original = meshData(
                surface(ChunkManager.CAT_SOLID, 1.0f, 4, 6),
                surface(ChunkManager.CAT_OVERLAY, 2.0f, 4, 6));
        SectionMeshData changedOverlay = meshData(
                surface(ChunkManager.CAT_SOLID, 1.0f, 4, 6),
                surface(ChunkManager.CAT_OVERLAY, 3.0f, 4, 6));

        assertEquals(original.categoryContentHash(ChunkManager.CAT_SOLID),
                changedOverlay.categoryContentHash(ChunkManager.CAT_SOLID));
        assertNotEquals(original.categoryContentHash(ChunkManager.CAT_OVERLAY),
                changedOverlay.categoryContentHash(ChunkManager.CAT_OVERLAY));
        assertNotEquals(original.contentHash(), changedOverlay.contentHash());
        assertEquals(0, original.categoryContentHash(ChunkManager.CAT_CUTOUT));
    }

    @Test
    void categoryHashesAreComputedAtConstruction() {
        SectionMeshData data = meshData(surface(ChunkManager.CAT_SOLID, 1.0f, 4, 6));
        int hash = data.categoryContentHash(ChunkManager.CAT_SOLID);

        data.surfaces().get(0).vertexBuffer().putFloat(0, 99.0f);

        assertEquals(hash, data.categoryContentHash(ChunkManager.CAT_SOLID));
    }

    @Test
    void estimatesMeshPayloadIndependentlyByCategory() {
        SectionMeshData data = meshData(
                surface(ChunkManager.CAT_SOLID, 1.0f, 4, 6),
                surface(ChunkManager.CAT_SOLID, 2.0f, 8, 12),
                surface(ChunkManager.CAT_TRANSLUCENT, 3.0f, 4, 6));

        long oneQuadBytes = 4L * HardcodedVertex.STRIDE + 6L * Integer.BYTES;
        assertEquals(3L * oneQuadBytes, data.categoryEstimatedMeshBytes(ChunkManager.CAT_SOLID));
        assertEquals(oneQuadBytes, data.categoryEstimatedMeshBytes(ChunkManager.CAT_TRANSLUCENT));
        assertEquals(0L, data.categoryEstimatedMeshBytes(ChunkManager.CAT_CUTOUT));
    }

    @Test
    void categoryAccessRejectsOutOfRangeValues() {
        SectionMeshData data = meshData();

        assertThrows(IllegalArgumentException.class, () -> data.categoryContentHash(-1));
        assertThrows(IllegalArgumentException.class,
                () -> data.categoryEstimatedMeshBytes(ChunkManager.CAT_COUNT));
    }

    @Test
    void lastReleaseRetiresTheBuffersButKeepsTheHashesReadable() {
        SectionMeshData data = meshData(surface(ChunkManager.CAT_SOLID, 1.0f, 4, 6));
        int hash = data.categoryContentHash(ChunkManager.CAT_SOLID);

        data.retain();
        data.release();
        assertEquals(1, data.surfaces().size(), "a reader's reference keeps the buffers");

        data.release();
        assertThrows(IllegalStateException.class, data::surfaces);
        assertThrows(IllegalStateException.class, data::retain);
        assertEquals(hash, data.categoryContentHash(ChunkManager.CAT_SOLID),
                "a replaced entry is still diffed by its cached hashes");
    }

    @Test
    void releasingMoreOftenThanRetainedFailsInsteadOfPoolingTwice() {
        SectionMeshData data = meshData(surface(ChunkManager.CAT_SOLID, 1.0f, 4, 6));
        data.release();

        assertThrows(IllegalStateException.class, data::release);
        assertThrows(IllegalStateException.class, data::surfaces);
    }

    private static SectionMeshData meshData(SectionMeshData.Surface... surfaces) {
        return new SectionMeshData(List.of(surfaces));
    }

    private static SectionMeshData.Surface surface(
            int category, float firstPosition, int vertexCount, int indexCount) {
        ByteBuffer vertices = ByteBuffer.allocate(vertexCount * HardcodedVertex.STRIDE)
                .order(ByteOrder.LITTLE_ENDIAN);
        vertices.putFloat(0, firstPosition);
        return new SectionMeshData.Surface(
                category, "solid", RedstoneEmission.NONE, vertices, vertexCount, indexCount, true);
    }
}

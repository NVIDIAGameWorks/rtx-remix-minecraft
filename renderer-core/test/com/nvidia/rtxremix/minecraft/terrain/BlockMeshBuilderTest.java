/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.core.terrain.TerrainSectionSnapshot;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionMeshData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockMeshBuilderTest {

    private static final int QUAD_BYTES =
            4 * HardcodedVertex.STRIDE + 6 * Integer.BYTES;

    @Test
    void canonicalIndexWriterRepeatsTheQuadPatternFromZero() {
        ByteBuffer indices = ByteBuffer.allocateDirect(12 * 4).order(ByteOrder.LITTLE_ENDIAN);
        BlockMeshBuilder.putCanonicalQuadIndices(indices, 8);

        assertArrayEquals(new int[] {
                0, 2, 1, 0, 3, 2,
                4, 6, 5, 4, 7, 6
        }, readInts(indices, 12));
    }

    @Test
    void canonicalTerrainSurfaceSplitsAtQuadBoundaries() {
        long pageCap = 2L * QUAD_BYTES;
        BlockMeshBuilder builder = new BlockMeshBuilder(pageCap);
        SectionMeshData meshData = meshData(canonicalSurface(
                ChunkManager.CAT_SOLID, "solid", 5));

        BlockMeshBuilder.MeshPage[] pages = builder.buildColumnMesh(
                0, 0, List.of(snapshot(0, 0, 0, meshData)),
                BlockMeshBuilder.ALL_CATEGORY_MASK,
                key -> testMaterial());
        try {
            assertEquals(3, pages.length);
            assertArrayEquals(new long[]{2L * QUAD_BYTES, 2L * QUAD_BYTES, QUAD_BYTES},
                    pageBytes(pages));
            for (BlockMeshBuilder.MeshPage page : pages) {
                assertEquals(ChunkManager.CAT_SOLID, page.category());
            }
        } finally {
            dispose(pages);
        }
    }

    @Test
    void pagesPackMaterialBucketsButNeverMixCategories() {
        long pageCap = 2L * QUAD_BYTES;
        BlockMeshBuilder builder = new BlockMeshBuilder(pageCap);
        SectionMeshData meshData = meshData(
                canonicalSurface(ChunkManager.CAT_SOLID, "solid", 1),
                canonicalSurface(ChunkManager.CAT_SOLID, "solid_sss", 1),
                canonicalSurface(ChunkManager.CAT_OVERLAY, "solid", 1));

        BlockMeshBuilder.MeshPage[] pages = builder.buildColumnMesh(
                0, 0, List.of(snapshot(0, 0, 0, meshData)),
                BlockMeshBuilder.ALL_CATEGORY_MASK,
                key -> testMaterial());
        try {
            assertEquals(2, pages.length);
            assertEquals(ChunkManager.CAT_SOLID, pages[0].category());
            assertEquals(2L * QUAD_BYTES, pages[0].estimatedNativeBytes());
            assertEquals(ChunkManager.CAT_OVERLAY, pages[1].category());
            assertEquals(QUAD_BYTES, pages[1].estimatedNativeBytes());
        } finally {
            dispose(pages);
        }
    }

    @Test
    void categoryMaskBuildsOnlySelectedCategories() {
        BlockMeshBuilder builder = new BlockMeshBuilder(2L * QUAD_BYTES);
        SectionMeshData meshData = meshData(
                canonicalSurface(ChunkManager.CAT_SOLID, "solid", 1),
                canonicalSurface(ChunkManager.CAT_OVERLAY, "solid", 1));

        BlockMeshBuilder.MeshPage[] pages = builder.buildColumnMesh(
                0, 0, List.of(snapshot(0, 0, 0, meshData)),
                1 << ChunkManager.CAT_OVERLAY,
                key -> testMaterial());
        try {
            assertEquals(1, pages.length);
            assertEquals(ChunkManager.CAT_OVERLAY, pages[0].category());
        } finally {
            dispose(pages);
        }
    }

    @Test
    void categoryMaskRejectsUnknownBits() {
        BlockMeshBuilder builder = new BlockMeshBuilder(QUAD_BYTES);

        assertThrows(IllegalArgumentException.class, () -> builder.buildColumnMesh(
                0, 0, List.of(snapshot(0, 0, 0, meshData())),
                1 << ChunkManager.CAT_COUNT, key -> testMaterial()));
    }

    @Test
    void surfaceWithoutCanonicalQuadIndicesFailsLoudly() {
        BlockMeshBuilder builder = new BlockMeshBuilder(2L * QUAD_BYTES);
        ByteBuffer vertices = ByteBuffer.allocate(4 * HardcodedVertex.STRIDE)
                .order(ByteOrder.LITTLE_ENDIAN);
        SectionMeshData ownIndices = meshData(new SectionMeshData.Surface(
                ChunkManager.CAT_SOLID, "solid", vertices, 4, 6, false));

        assertThrows(IllegalArgumentException.class, () -> builder.buildColumnMesh(
                0, 0, List.of(snapshot(0, 0, 0, ownIndices)),
                BlockMeshBuilder.ALL_CATEGORY_MASK,
                key -> testMaterial()));
    }

    @Test
    void surfaceWhoseCountsContradictItsCanonicalFlagFailsLoudly() {
        BlockMeshBuilder builder = new BlockMeshBuilder(2L * QUAD_BYTES);
        ByteBuffer vertices = ByteBuffer.allocate(5 * HardcodedVertex.STRIDE)
                .order(ByteOrder.LITTLE_ENDIAN);
        SectionMeshData partialQuad = meshData(new SectionMeshData.Surface(
                ChunkManager.CAT_SOLID, "solid", vertices, 5, 6, true));

        assertThrows(IllegalArgumentException.class, () -> builder.buildColumnMesh(
                0, 0, List.of(snapshot(0, 0, 0, partialQuad)),
                BlockMeshBuilder.ALL_CATEGORY_MASK,
                key -> testMaterial()));
    }

    @Test
    void splitPagesApplySectionOffsetWithinColumnAndRestartCanonicalIndices() throws Exception {
        BlockMeshBuilder builder = new BlockMeshBuilder(QUAD_BYTES);
        ByteBuffer vertices = ByteBuffer.allocate(3 * 4 * HardcodedVertex.STRIDE)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int quad = 0; quad < 3; quad++) {
            for (int vertex = 0; vertex < 4; vertex++) {
                int offset = (quad * 4 + vertex) * HardcodedVertex.STRIDE;
                vertices.putFloat(offset, quad);
                vertices.putFloat(offset + 4, 2.0f);
                vertices.putFloat(offset + 8, 3.0f);
            }
        }
        SectionMeshData meshData = meshData(new SectionMeshData.Surface(
                ChunkManager.CAT_SOLID, "solid", vertices, 12, 18, true));

        BlockMeshBuilder.MeshPage[] pages = builder.buildColumnMesh(
                1, -2, List.of(snapshot(1, 2, -2, meshData)),
                BlockMeshBuilder.ALL_CATEGORY_MASK,
                key -> testMaterial());
        try {
            assertEquals(3, pages.length);
            for (int pageIndex = 0; pageIndex < pages.length; pageIndex++) {
                RemixMeshSurface surface = firstSurface(pages[pageIndex].mesh());
                ByteBuffer pageVertices = surfaceBuffer(surface, "vertexData");
                ByteBuffer pageIndices = surfaceBuffer(surface, "indexData");
                assertEquals((float) pageIndex, pageVertices.getFloat(0));
                assertEquals(34.0f, pageVertices.getFloat(4));
                assertEquals(3.0f, pageVertices.getFloat(8));
                assertArrayEquals(new int[]{0, 2, 1, 0, 3, 2}, readInts(pageIndices, 6));
            }
        } finally {
            dispose(pages);
        }
    }

    @Test
    void siblingSectionsShareColumnPagesWithIndependentVerticalOffsets() throws Exception {
        BlockMeshBuilder builder = new BlockMeshBuilder(2L * QUAD_BYTES);
        SectionMeshData lower = meshData(canonicalSurface(
                ChunkManager.CAT_SOLID, "solid", 1));
        SectionMeshData upper = meshData(canonicalSurface(
                ChunkManager.CAT_SOLID, "solid", 1));

        BlockMeshBuilder.MeshPage[] pages = builder.buildColumnMesh(
                -3, 7,
                List.of(snapshot(-3, -1, 7, lower), snapshot(-3, 2, 7, upper)),
                BlockMeshBuilder.ALL_CATEGORY_MASK,
                key -> testMaterial());
        try {
            assertEquals(1, pages.length);
            RemixMeshSurface surface = firstSurface(pages[0].mesh());
            ByteBuffer vertices = surfaceBuffer(surface, "vertexData");
            assertEquals(-16.0f, vertices.getFloat(4));
            assertEquals(32.0f, vertices.getFloat(4 * HardcodedVertex.STRIDE + 4));
        } finally {
            dispose(pages);
        }
    }

    @Test
    void columnBuilderRejectsSnapshotFromAnotherColumn() {
        BlockMeshBuilder builder = new BlockMeshBuilder(QUAD_BYTES);
        TerrainSectionSnapshot foreign = snapshot(
                2, 0, 3, meshData(canonicalSurface(ChunkManager.CAT_SOLID, "solid", 1)));

        assertThrows(IllegalArgumentException.class, () -> builder.buildColumnMesh(
                1, 3, List.of(foreign), BlockMeshBuilder.ALL_CATEGORY_MASK,
                key -> testMaterial()));
    }

    private static SectionMeshData meshData(SectionMeshData.Surface... surfaces) {
        return new SectionMeshData(List.of(surfaces));
    }

    private static TerrainSectionSnapshot snapshot(
            int chunkX, int sectionY, int chunkZ, SectionMeshData meshData) {
        return new TerrainSectionSnapshot(
                chunkX * 16, sectionY * 16, chunkZ * 16, meshData);
    }

    private static SectionMeshData.Surface canonicalSurface(
            int category, String bucket, int quadCount) {
        int vertexCount = quadCount * 4;
        return new SectionMeshData.Surface(
                category,
                bucket,
                ByteBuffer.allocate(vertexCount * HardcodedVertex.STRIDE)
                        .order(ByteOrder.LITTLE_ENDIAN),
                vertexCount,
                quadCount * 6,
                true);
    }

    private static RemixMaterial testMaterial() {
        return new RemixMaterial((MaterialInfo) null);
    }

    private static long[] pageBytes(BlockMeshBuilder.MeshPage[] pages) {
        long[] bytes = new long[pages.length];
        for (int i = 0; i < pages.length; i++) bytes[i] = pages[i].estimatedNativeBytes();
        return bytes;
    }

    private static void dispose(BlockMeshBuilder.MeshPage[] pages) {
        for (BlockMeshBuilder.MeshPage page : pages) page.mesh().disposeData();
    }

    private static RemixMeshSurface firstSurface(RemixMesh mesh) throws Exception {
        return meshSurfaces(mesh)[0];
    }

    private static RemixMeshSurface[] meshSurfaces(RemixMesh mesh) throws Exception {
        Field surfaces = RemixMesh.class.getDeclaredField("surfaces");
        surfaces.setAccessible(true);
        return (RemixMeshSurface[]) surfaces.get(mesh);
    }

    private static ByteBuffer surfaceBuffer(RemixMeshSurface surface, String name) throws Exception {
        Field field = RemixMeshSurface.class.getDeclaredField(name);
        field.setAccessible(true);
        return ((ByteBuffer) field.get(surface)).duplicate().order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int[] readInts(ByteBuffer buffer, int count) {
        int[] values = new int[count];
        ByteBuffer src = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            values[i] = src.getInt(i * 4);
        }
        return values;
    }
}

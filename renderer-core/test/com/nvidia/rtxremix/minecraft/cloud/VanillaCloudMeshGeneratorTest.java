/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaCloudMeshGeneratorTest {
    private static final float DEFAULT_HEIGHT = 4.0f;
    private static final float DEFAULT_SMOOTHNESS = 1.5f;

    @Test
    void extrudesOccupiedTexelsDeterministicallyWithRoundedEdgeNormals() {
        boolean[] occupied = new boolean[16];
        occupied[5] = true;

        VanillaCloudMeshGenerator.GeneratedMesh first =
                generate(occupied, 4, 4);
        VanillaCloudMeshGenerator.GeneratedMesh second =
                generate(occupied, 4, 4);

        assertTrue(first.vertexCount() > 0);
        assertTrue(first.triangleCount() > 0);
        assertArrayEquals(first.vertices(), second.vertices());
        assertArrayEquals(first.indices(), second.indices());
        assertTrue(hasRoundedEdgeNormal(first.vertices()));
        assertTrue(hasRotatedCornerQuad(first.indices()));
        assertTrue(Arrays.stream(first.indices())
                .allMatch(index -> index >= 0 && index < first.vertexCount()));
    }

    @Test
    void concaveCornersReceiveTwoAxisNormals() {
        boolean[] occupied = new boolean[16];
        occupied[5] = true;
        occupied[6] = true;
        occupied[9] = true;

        VanillaCloudMeshGenerator.GeneratedMesh mesh =
                generate(occupied, 4, 4);
        float[] vertices = mesh.vertices();

        assertTrue(hasCornerNormal(vertices, 24.0f, 24.0f, 1.0f, 1.0f));
        assertTrue(hasPosition(vertices, 22.5f, DEFAULT_HEIGHT, 22.5f));
        assertTrue(hasCornerToMiddleDiagonal(mesh, 24.0f, 24.0f, 22.5f, 22.5f));
    }

    @Test
    void requestedWindowUsesLocalCoordinatesAndWrappedNeighbors() {
        boolean[] occupied = new boolean[16];
        occupied[3] = true;
        occupied[0] = true;

        VanillaCloudMeshGenerator.GeneratedMesh mesh =
                VanillaCloudMeshGenerator.generate(
                        occupied, 4, 4, 3, 0, 1, 1,
                        VanillaCloudMeshGenerator.CELL_SIZE,
                        VanillaCloudMeshGenerator.CELL_SIZE,
                        DEFAULT_HEIGHT, 0.0f);

        assertTrue(mesh.vertexCount() > 0);
        assertTrue(allCoordinatesWithin(mesh.vertices(), VanillaCloudMeshGenerator.CELL_SIZE));
        assertFalse(hasVerticalFaceAtX(mesh, VanillaCloudMeshGenerator.CELL_SIZE));
    }

    @Test
    void supportLoopsUseEqualWorldDistanceAndConfiguredThickness() {
        boolean[] occupied = new boolean[9];
        occupied[4] = true;
        float height = 8.0f;
        float smoothness = 1.75f;

        VanillaCloudMeshGenerator.GeneratedMesh mesh =
                VanillaCloudMeshGenerator.generate(
                        occupied, 3, 3, 0, 0, 3, 3,
                        VanillaCloudMeshGenerator.CELL_SIZE,
                        VanillaCloudMeshGenerator.CELL_SIZE,
                        height, smoothness);

        assertTrue(hasCoordinate(
                mesh.vertices(), 0, VanillaCloudMeshGenerator.CELL_SIZE + smoothness));
        assertTrue(hasCoordinate(mesh.vertices(), 1, smoothness));
        assertTrue(hasCoordinate(
                mesh.vertices(), 2, VanillaCloudMeshGenerator.CELL_SIZE + smoothness));
        assertTrue(hasCoordinate(mesh.vertices(), 1, height));
    }

    @Test
    void resourcePackTexelScaleControlsHorizontalWorldSize() {
        boolean[] occupied = new boolean[9];
        occupied[4] = true;
        float cellSizeX = 8.0f;
        float cellSizeZ = 6.0f;

        VanillaCloudMeshGenerator.GeneratedMesh mesh =
                VanillaCloudMeshGenerator.generate(
                        occupied, 3, 3, 0, 0, 3, 3,
                        cellSizeX, cellSizeZ, DEFAULT_HEIGHT, DEFAULT_SMOOTHNESS);

        assertTrue(hasCoordinate(mesh.vertices(), 0, 2.0f * cellSizeX));
        assertTrue(hasCoordinate(mesh.vertices(), 2, 2.0f * cellSizeZ));
        assertTrue(hasCoordinate(mesh.vertices(), 0, cellSizeX + DEFAULT_SMOOTHNESS));
        assertTrue(hasCoordinate(mesh.vertices(), 1, DEFAULT_SMOOTHNESS));
        assertTrue(hasCoordinate(mesh.vertices(), 2, cellSizeZ + DEFAULT_SMOOTHNESS));
    }

    @Test
    void zeroSmoothnessProducesAxisAlignedNormals() {
        boolean[] occupied = new boolean[9];
        occupied[4] = true;
        VanillaCloudMeshGenerator.GeneratedMesh mesh =
                VanillaCloudMeshGenerator.generate(
                        occupied, 3, 3, 0, 0, 3, 3,
                        VanillaCloudMeshGenerator.CELL_SIZE,
                        VanillaCloudMeshGenerator.CELL_SIZE,
                        DEFAULT_HEIGHT, 0.0f);

        assertTrue(mesh.vertexCount() > 0);
        for (int i = 0; i < mesh.vertices().length; i += 6) {
            int nonZero = 0;
            nonZero += Math.abs(mesh.vertices()[i + 3]) > 0.1f ? 1 : 0;
            nonZero += Math.abs(mesh.vertices()[i + 4]) > 0.1f ? 1 : 0;
            nonZero += Math.abs(mesh.vertices()[i + 5]) > 0.1f ? 1 : 0;
            assertTrue(nonZero == 1);
        }
    }

    @Test
    void trianglesUseRemixClockwiseWinding() {
        boolean[] occupied = new boolean[16];
        occupied[5] = true;
        occupied[6] = true;
        occupied[9] = true;

        assertClockwiseWinding(generate(occupied, 4, 4));
    }

    @Test
    void everyInteriorThreeByThreeMaskIsWatertight() {
        for (int mask = 1; mask < 1 << 9; mask++) {
            boolean[] occupied = new boolean[25];
            for (int bit = 0; bit < 9; bit++) {
                int x = bit % 3 + 1;
                int z = bit / 3 + 1;
                occupied[z * 5 + x] = (mask & 1 << bit) != 0;
            }
            assertWatertight(generate(occupied, 5, 5), "interior mask " + mask);
        }
    }

    private static void assertWatertight(
            VanillaCloudMeshGenerator.GeneratedMesh mesh, String description) {
        Map<PositionEdge, Integer> edgeUses = new HashMap<>();
        int[] indices = mesh.indices();
        for (int triangle = 0; triangle < indices.length; triangle += 3) {
            countPositionEdge(edgeUses, mesh.vertices(), indices[triangle], indices[triangle + 1]);
            countPositionEdge(
                    edgeUses, mesh.vertices(), indices[triangle + 1], indices[triangle + 2]);
            countPositionEdge(
                    edgeUses, mesh.vertices(), indices[triangle + 2], indices[triangle]);
        }

        for (Map.Entry<PositionEdge, Integer> edge : edgeUses.entrySet()) {
            assertEquals(0, edge.getValue() % 2, description + ": " + edge.getKey());
        }
    }

    private static VanillaCloudMeshGenerator.GeneratedMesh generate(
            boolean[] occupied, int width, int height) {
        return VanillaCloudMeshGenerator.generate(
                occupied,
                width,
                height,
                0,
                0,
                width,
                height,
                VanillaCloudMeshGenerator.CELL_SIZE,
                VanillaCloudMeshGenerator.CELL_SIZE,
                DEFAULT_HEIGHT,
                DEFAULT_SMOOTHNESS);
    }

    private static boolean allCoordinatesWithin(float[] vertices, float maximum) {
        for (int i = 0; i < vertices.length; i += 6) {
            if (vertices[i] < 0.0f
                    || vertices[i] > maximum
                    || vertices[i + 2] < 0.0f
                    || vertices[i + 2] > maximum) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasVerticalFaceAtX(
            VanillaCloudMeshGenerator.GeneratedMesh mesh, float x) {
        for (int i = 0; i < mesh.indices().length; i += 3) {
            boolean allAtX = true;
            for (int vertex = 0; vertex < 3; vertex++) {
                int base = mesh.indices()[i + vertex] * 6;
                allAtX &= mesh.vertices()[base] == x;
            }
            if (allAtX) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCoordinate(float[] vertices, int component, float value) {
        for (int i = 0; i < vertices.length; i += 6) {
            if (vertices[i + component] == value) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRoundedEdgeNormal(float[] vertices) {
        for (int i = 0; i < vertices.length; i += 6) {
            float nx = Math.abs(vertices[i + 3]);
            float ny = Math.abs(vertices[i + 4]);
            float nz = Math.abs(vertices[i + 5]);
            if (ny > 0.1f && (nx > 0.1f || nz > 0.1f)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCornerToMiddleDiagonal(
            VanillaCloudMeshGenerator.GeneratedMesh mesh,
            float cornerX,
            float cornerZ,
            float middleX,
            float middleZ) {
        float[] vertices = mesh.vertices();
        int[] indices = mesh.indices();
        for (int quad = 0; quad < indices.length / 6; quad++) {
            int first = quad * 4;
            if (hasQuadPosition(vertices, first, cornerX, cornerZ)
                    && hasQuadPosition(vertices, first, middleX, middleZ)) {
                int index = quad * 6;
                if (hasTriangleEdge(indices, index, first, first + 2)
                        && hasTriangleEdge(indices, index + 3, first, first + 2)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasQuadPosition(
            float[] vertices, int first, float x, float z) {
        for (int vertex = first; vertex < first + 4; vertex++) {
            int base = vertex * 6;
            if (vertices[base] == x
                    && vertices[base + 1] == DEFAULT_HEIGHT
                    && vertices[base + 2] == z) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPosition(float[] vertices, float x, float y, float z) {
        for (int i = 0; i < vertices.length; i += 6) {
            if (vertices[i] == x && vertices[i + 1] == y && vertices[i + 2] == z) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCornerNormal(
            float[] vertices, float x, float z, float normalX, float normalZ) {
        for (int i = 0; i < vertices.length; i += 6) {
            if (vertices[i] == x
                    && vertices[i + 2] == z
                    && Math.signum(vertices[i + 3]) == normalX
                    && Math.signum(vertices[i + 5]) == normalZ) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRotatedCornerQuad(int[] indices) {
        for (int i = 0; i < indices.length; i += 6) {
            int first = indices[i];
            if (indices[i + 1] == first + 3
                    && indices[i + 2] == first + 1
                    && indices[i + 3] == first + 1
                    && indices[i + 4] == first + 3
                    && indices[i + 5] == first + 2) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTriangleEdge(
            int[] indices, int triangle, int firstVertex, int secondVertex) {
        for (int edge = 0; edge < 3; edge++) {
            int first = indices[triangle + edge];
            int second = indices[triangle + (edge + 1) % 3];
            if ((first == firstVertex && second == secondVertex)
                    || (first == secondVertex && second == firstVertex)) {
                return true;
            }
        }
        return false;
    }

    private static void assertClockwiseWinding(
            VanillaCloudMeshGenerator.GeneratedMesh mesh) {
        float[] vertices = mesh.vertices();
        int[] indices = mesh.indices();
        for (int triangle = 0; triangle < indices.length; triangle += 3) {
            int a = indices[triangle] * 6;
            int b = indices[triangle + 1] * 6;
            int c = indices[triangle + 2] * 6;
            float abX = vertices[b] - vertices[a];
            float abY = vertices[b + 1] - vertices[a + 1];
            float abZ = vertices[b + 2] - vertices[a + 2];
            float acX = vertices[c] - vertices[a];
            float acY = vertices[c + 1] - vertices[a + 1];
            float acZ = vertices[c + 2] - vertices[a + 2];
            float geometricX = abY * acZ - abZ * acY;
            float geometricY = abZ * acX - abX * acZ;
            float geometricZ = abX * acY - abY * acX;
            float normalX = vertices[a + 3] + vertices[b + 3] + vertices[c + 3];
            float normalY = vertices[a + 4] + vertices[b + 4] + vertices[c + 4];
            float normalZ = vertices[a + 5] + vertices[b + 5] + vertices[c + 5];
            float alignment =
                    geometricX * normalX + geometricY * normalY + geometricZ * normalZ;
            assertTrue(alignment < 0.0f, "Triangle must be clockwise for Remix");
        }
    }

    private static void countPositionEdge(
            Map<PositionEdge, Integer> uses, float[] vertices, int first, int second) {
        Position firstPosition = position(vertices, first);
        Position secondPosition = position(vertices, second);
        PositionEdge edge = firstPosition.compareTo(secondPosition) < 0
                ? new PositionEdge(firstPosition, secondPosition)
                : new PositionEdge(secondPosition, firstPosition);
        uses.merge(edge, 1, Integer::sum);
    }

    private static Position position(float[] vertices, int index) {
        int base = index * 6;
        return new Position(
                Float.floatToIntBits(vertices[base]),
                Float.floatToIntBits(vertices[base + 1]),
                Float.floatToIntBits(vertices[base + 2]));
    }

    private record Position(int x, int y, int z) implements Comparable<Position> {
        @Override
        public int compareTo(Position other) {
            int result = Integer.compareUnsigned(x, other.x);
            if (result == 0) {
                result = Integer.compareUnsigned(y, other.y);
            }
            if (result == 0) {
                result = Integer.compareUnsigned(z, other.z);
            }
            return result;
        }
    }

    private record PositionEdge(Position first, Position second) {
    }
}

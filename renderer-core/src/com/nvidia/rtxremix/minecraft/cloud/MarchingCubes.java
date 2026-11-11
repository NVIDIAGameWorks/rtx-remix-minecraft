/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import java.util.Arrays;

/** Allocation-free workspace for the canonical Marching Cubes cases. */
final class MarchingCubes {
    private static final int[][] EDGE_CORNERS = {
            {0, 1}, {1, 2}, {3, 2}, {0, 3},
            {4, 5}, {5, 6}, {7, 6}, {4, 7},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };
    private static final int[] CORNER_X = {0, 1, 1, 0, 0, 1, 1, 0};
    private static final int[] CORNER_Y = {0, 0, 1, 1, 0, 0, 1, 1};
    private static final int[] CORNER_Z = {0, 0, 0, 0, 1, 1, 1, 1};

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final float originX;
    private final float originY;
    private final float originZ;
    private final float stepX;
    private final float stepY;
    private final float stepZ;
    private final float[] density;
    private final int[] xEdges;
    private final int[] yEdges;
    private final int[] zEdges;
    private final float[] cornerDensity = new float[8];
    private final int[] localVertices = new int[12];

    private float[] vertices = new float[6 * 4096];
    private int vertexCount;
    private int[] indices = new int[12 * 4096];
    private int indexCount;

    MarchingCubes(
            int sizeX,
            int sizeY,
            int sizeZ,
            float originX,
            float originY,
            float originZ,
            float stepX,
            float stepY,
            float stepZ,
            float[] density) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.stepX = stepX;
        this.stepY = stepY;
        this.stepZ = stepZ;
        this.density = density;
        this.xEdges = new int[(sizeX - 1) * sizeY * sizeZ];
        this.yEdges = new int[sizeX * (sizeY - 1) * sizeZ];
        this.zEdges = new int[sizeX * sizeY * (sizeZ - 1)];
    }

    MeshData extract(DensityFunction field) {
        Arrays.fill(xEdges, -1);
        Arrays.fill(yEdges, -1);
        Arrays.fill(zEdges, -1);
        vertexCount = 0;
        indexCount = 0;

        for (int z = 0; z < sizeZ - 1; z++) {
            for (int y = 0; y < sizeY - 1; y++) {
                for (int x = 0; x < sizeX - 1; x++) {
                    polygonizeCell(x, y, z, field);
                }
            }
        }

        return new MeshData(
                Arrays.copyOf(vertices, vertexCount * 6),
                Arrays.copyOf(indices, indexCount));
    }

    private void polygonizeCell(int x, int y, int z, DensityFunction field) {
        int cubeCase = 0;
        for (int corner = 0; corner < 8; corner++) {
            float value = density[index(
                    x + CORNER_X[corner],
                    y + CORNER_Y[corner],
                    z + CORNER_Z[corner])];
            cornerDensity[corner] = value;
            if (value >= 0.0f) {
                cubeCase |= 1 << corner;
            }
        }
        if (cubeCase == 0 || cubeCase == 255) {
            return;
        }

        Arrays.fill(localVertices, -1);
        for (int edge = 0; edge < 12; edge++) {
            int a = EDGE_CORNERS[edge][0];
            int b = EDGE_CORNERS[edge][1];
            if ((cornerDensity[a] >= 0.0f) != (cornerDensity[b] >= 0.0f)) {
                localVertices[edge] = edgeVertex(x, y, z, edge, field);
            }
        }

        int tableOffset = cubeCase * 16;
        for (int triangle = 0; triangle < 5; triangle++) {
            int edgeA = MarchingCubesTables.TRIANGLES[tableOffset + triangle * 3];
            if (edgeA < 0) {
                break;
            }
            int edgeB = MarchingCubesTables.TRIANGLES[tableOffset + triangle * 3 + 1];
            int edgeC = MarchingCubesTables.TRIANGLES[tableOffset + triangle * 3 + 2];
            addTriangle(
                    localVertices[edgeA],
                    localVertices[edgeB],
                    localVertices[edgeC]);
        }
    }

    private int edgeVertex(int x, int y, int z, int edge, DensityFunction field) {
        int[] cache;
        int cacheIndex;
        switch (edge) {
            case 0: cache = xEdges; cacheIndex = xEdgeIndex(x, y, z); break;
            case 1: cache = yEdges; cacheIndex = yEdgeIndex(x + 1, y, z); break;
            case 2: cache = xEdges; cacheIndex = xEdgeIndex(x, y + 1, z); break;
            case 3: cache = yEdges; cacheIndex = yEdgeIndex(x, y, z); break;
            case 4: cache = xEdges; cacheIndex = xEdgeIndex(x, y, z + 1); break;
            case 5: cache = yEdges; cacheIndex = yEdgeIndex(x + 1, y, z + 1); break;
            case 6: cache = xEdges; cacheIndex = xEdgeIndex(x, y + 1, z + 1); break;
            case 7: cache = yEdges; cacheIndex = yEdgeIndex(x, y, z + 1); break;
            case 8: cache = zEdges; cacheIndex = zEdgeIndex(x, y, z); break;
            case 9: cache = zEdges; cacheIndex = zEdgeIndex(x + 1, y, z); break;
            case 10: cache = zEdges; cacheIndex = zEdgeIndex(x + 1, y + 1, z); break;
            case 11: cache = zEdges; cacheIndex = zEdgeIndex(x, y + 1, z); break;
            default: throw new IllegalArgumentException("Invalid cube edge " + edge);
        }
        int existing = cache[cacheIndex];
        if (existing >= 0) {
            return existing;
        }

        int a = EDGE_CORNERS[edge][0];
        int b = EDGE_CORNERS[edge][1];
        float da = cornerDensity[a];
        float db = cornerDensity[b];
        float amount = da / (da - db);
        float px = originX + (x + lerp(CORNER_X[a], CORNER_X[b], amount)) * stepX;
        float py = originY + (y + lerp(CORNER_Y[a], CORNER_Y[b], amount)) * stepY;
        float pz = originZ + (z + lerp(CORNER_Z[a], CORNER_Z[b], amount)) * stepZ;
        int created = addVertex(px, py, pz, field);
        cache[cacheIndex] = created;
        return created;
    }

    private int addVertex(float x, float y, float z, DensityFunction field) {
        ensureVertexCapacity(vertexCount + 1);
        float epsilon = Math.min(stepX, Math.min(stepY, stepZ)) * 0.35f;
        float nx = field.sample(x - epsilon, y, z) - field.sample(x + epsilon, y, z);
        float ny = field.sample(x, y - epsilon, z) - field.sample(x, y + epsilon, z);
        float nz = field.sample(x, y, z - epsilon) - field.sample(x, y, z + epsilon);
        float inverseLength = 1.0f / (float) Math.sqrt(nx * nx + ny * ny + nz * nz + 1.0e-20f);
        int base = vertexCount * 6;
        vertices[base] = x;
        vertices[base + 1] = y;
        vertices[base + 2] = z;
        vertices[base + 3] = nx * inverseLength;
        vertices[base + 4] = ny * inverseLength;
        vertices[base + 5] = nz * inverseLength;
        return vertexCount++;
    }

    private void addTriangle(int a, int b, int c) {
        ensureIndexCapacity(indexCount + 3);
        indices[indexCount++] = a;
        indices[indexCount++] = b;
        indices[indexCount++] = c;
    }

    private void ensureVertexCapacity(int count) {
        int required = count * 6;
        if (required > vertices.length) {
            vertices = Arrays.copyOf(vertices, Math.max(required, vertices.length + (vertices.length >> 1)));
        }
    }

    private void ensureIndexCapacity(int count) {
        if (count > indices.length) {
            indices = Arrays.copyOf(indices, Math.max(count, indices.length + (indices.length >> 1)));
        }
    }

    private int index(int x, int y, int z) {
        return (z * sizeY + y) * sizeX + x;
    }

    private int xEdgeIndex(int x, int y, int z) {
        return (z * sizeY + y) * (sizeX - 1) + x;
    }

    private int yEdgeIndex(int x, int y, int z) {
        return (z * (sizeY - 1) + y) * sizeX + x;
    }

    private int zEdgeIndex(int x, int y, int z) {
        return (z * sizeY + y) * sizeX + x;
    }

    private static float lerp(int from, int to, float amount) {
        return from + (to - from) * amount;
    }

    interface DensityFunction {
        float sample(float x, float y, float z);
    }

    static final class MeshData {
        private final float[] vertices;
        private final int[] indices;

        MeshData(float[] vertices, int[] indices) {
            this.vertices = vertices;
            this.indices = indices;
        }

        float[] vertices() { return vertices; }
        int[] indices() { return indices; }
    }
}

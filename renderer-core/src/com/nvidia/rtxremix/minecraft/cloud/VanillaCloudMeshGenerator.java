/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import java.util.Arrays;

/** Extrudes the repeating {@code clouds.png} occupancy mask into square voxel geometry. */
final class VanillaCloudMeshGenerator {
    static final float CELL_SIZE = 12.0f;

    private VanillaCloudMeshGenerator() {
    }

    static GeneratedMesh generate(
            boolean[] occupied,
            int textureWidth,
            int textureHeight,
            int textureX,
            int textureZ,
            int width,
            int height,
            float cellSizeX,
            float cellSizeZ,
            float cloudHeight,
            float edgeSmoothness) {
        if (textureWidth <= 0
                || textureHeight <= 0
                || occupied.length != textureWidth * textureHeight
                || width <= 0
                || height <= 0
                || cellSizeX <= 0.0f
                || cellSizeZ <= 0.0f
                || cloudHeight <= 0.0f
                || edgeSmoothness < 0.0f) {
            throw new IllegalArgumentException("Invalid cloud occupancy dimensions");
        }

        long start = System.nanoTime();
        MeshBuilder mesh = new MeshBuilder();
        float supportInsetX = Math.min(edgeSmoothness, cellSizeX * 0.499f);
        float supportInsetY = Math.min(edgeSmoothness, cloudHeight * 0.499f);
        float supportInsetZ = Math.min(edgeSmoothness, cellSizeZ * 0.499f);
        boolean smoothEdges =
                supportInsetX > 0.0f || supportInsetY > 0.0f || supportInsetZ > 0.0f;
        float[] xCoordinates = new float[4];
        float[] zCoordinates = new float[4];
        float[] yCoordinates = smoothEdges
                ? new float[]{0.0f, supportInsetY, cloudHeight - supportInsetY, cloudHeight}
                : new float[]{0.0f, cloudHeight};
        for (int localZ = 0; localZ < height; localZ++) {
            int z = textureZ + localZ;
            for (int localX = 0; localX < width; localX++) {
                int x = textureX + localX;
                if (!occupied(occupied, textureWidth, textureHeight, x, z)) {
                    continue;
                }

                boolean west = !occupied(occupied, textureWidth, textureHeight, x - 1, z);
                boolean east = !occupied(occupied, textureWidth, textureHeight, x + 1, z);
                boolean north = !occupied(occupied, textureWidth, textureHeight, x, z - 1);
                boolean south = !occupied(occupied, textureWidth, textureHeight, x, z + 1);
                int xCount = supportCoordinates(
                        xCoordinates,
                        cellSizeX,
                        supportInsetX);
                int zCount = supportCoordinates(
                        zCoordinates,
                        cellSizeZ,
                        supportInsetZ);
                float originX = localX * cellSizeX;
                float originZ = localZ * cellSizeZ;

                addHorizontalFace(
                        mesh, originX, originZ, xCoordinates, xCount, zCoordinates, zCount,
                        cloudHeight, 1.0f, west, east, north, south,
                        occupied, textureWidth, textureHeight, x, z,
                        cellSizeX, cellSizeZ, smoothEdges);
                addHorizontalFace(
                        mesh, originX, originZ, xCoordinates, xCount, zCoordinates, zCount,
                        0.0f, -1.0f, west, east, north, south,
                        occupied, textureWidth, textureHeight, x, z,
                        cellSizeX, cellSizeZ, smoothEdges);
                if (west) {
                    addSideFace(
                            mesh, originX, originZ, zCoordinates, zCount, yCoordinates,
                            -1.0f, 0.0f,
                            cornerNormalX(occupied, textureWidth, textureHeight, x, z),
                            cornerNormalZ(occupied, textureWidth, textureHeight, x, z),
                            cornerNormalX(occupied, textureWidth, textureHeight, x, z + 1),
                            cornerNormalZ(occupied, textureWidth, textureHeight, x, z + 1),
                            cellSizeZ, cloudHeight, smoothEdges);
                }
                if (east) {
                    addSideFace(
                            mesh, originX + cellSizeX, originZ,
                            zCoordinates, zCount, yCoordinates,
                            1.0f, 0.0f,
                            cornerNormalX(occupied, textureWidth, textureHeight, x + 1, z),
                            cornerNormalZ(occupied, textureWidth, textureHeight, x + 1, z),
                            cornerNormalX(occupied, textureWidth, textureHeight, x + 1, z + 1),
                            cornerNormalZ(occupied, textureWidth, textureHeight, x + 1, z + 1),
                            cellSizeZ, cloudHeight, smoothEdges);
                }
                if (north) {
                    addSideFace(
                            mesh, originZ, originX, xCoordinates, xCount, yCoordinates,
                            0.0f, -1.0f,
                            cornerNormalX(occupied, textureWidth, textureHeight, x, z),
                            cornerNormalZ(occupied, textureWidth, textureHeight, x, z),
                            cornerNormalX(occupied, textureWidth, textureHeight, x + 1, z),
                            cornerNormalZ(occupied, textureWidth, textureHeight, x + 1, z),
                            cellSizeX, cloudHeight, smoothEdges);
                }
                if (south) {
                    addSideFace(
                            mesh, originZ + cellSizeZ, originX,
                            xCoordinates, xCount, yCoordinates,
                            0.0f, 1.0f,
                            cornerNormalX(occupied, textureWidth, textureHeight, x, z + 1),
                            cornerNormalZ(occupied, textureWidth, textureHeight, x, z + 1),
                            cornerNormalX(occupied, textureWidth, textureHeight, x + 1, z + 1),
                            cornerNormalZ(occupied, textureWidth, textureHeight, x + 1, z + 1),
                            cellSizeX, cloudHeight, smoothEdges);
                }
            }
        }

        return new GeneratedMesh(
                mesh.vertices(),
                mesh.indices(),
                System.nanoTime() - start);
    }

    private static int supportCoordinates(
            float[] coordinates,
            float cellSize,
            float supportInset) {
        int count = 0;
        coordinates[count++] = 0.0f;
        if (supportInset > 0.0f) {
            coordinates[count++] = supportInset;
            coordinates[count++] = cellSize - supportInset;
        }
        coordinates[count++] = cellSize;
        return count;
    }

    private static void addHorizontalFace(
            MeshBuilder mesh,
            float originX,
            float originZ,
            float[] xs,
            int xCount,
            float[] zs,
            int zCount,
            float y,
            float normalY,
            boolean west,
            boolean east,
            boolean north,
            boolean south,
            boolean[] occupied,
            int width,
            int height,
            int cellX,
            int cellZ,
            float cellSizeX,
            float cellSizeZ,
            boolean smoothEdges) {
        for (int z = 0; z < zCount - 1; z++) {
            for (int x = 0; x < xCount - 1; x++) {
                float x0 = xs[x];
                float x1 = xs[x + 1];
                float z0 = zs[z];
                float z1 = zs[z + 1];
                if (normalY > 0.0f) {
                    addHorizontalQuad(
                            mesh, originX, originZ, x0, x1, z0, z1, y, normalY,
                            west, east, north, south,
                            occupied, width, height, cellX, cellZ,
                            cellSizeX, cellSizeZ, smoothEdges);
                } else {
                    addHorizontalQuad(
                            mesh, originX, originZ, x0, x1, z1, z0, y, normalY,
                            west, east, north, south,
                            occupied, width, height, cellX, cellZ,
                            cellSizeX, cellSizeZ, smoothEdges);
                }
            }
        }
    }

    private static void addHorizontalQuad(
            MeshBuilder mesh,
            float originX,
            float originZ,
            float x0,
            float x1,
            float z0,
            float z1,
            float y,
            float normalY,
            boolean west,
            boolean east,
            boolean north,
            boolean south,
            boolean[] occupied,
            int width,
            int height,
            int cellX,
            int cellZ,
            float cellSizeX,
            float cellSizeZ,
            boolean smoothEdges) {
        int first = mesh.vertex(
                originX + x0, y, originZ + z0,
                smoothEdges ? horizontalNormalX(
                        x0, z0, west, east, occupied, width, height, cellX, cellZ,
                        cellSizeX, cellSizeZ)
                        : 0.0f,
                normalY,
                smoothEdges ? horizontalNormalZ(
                        x0, z0, north, south, occupied, width, height, cellX, cellZ,
                        cellSizeX, cellSizeZ)
                        : 0.0f);
        mesh.vertex(
                originX + x0, y, originZ + z1,
                smoothEdges ? horizontalNormalX(
                        x0, z1, west, east, occupied, width, height, cellX, cellZ,
                        cellSizeX, cellSizeZ)
                        : 0.0f,
                normalY,
                smoothEdges ? horizontalNormalZ(
                        x0, z1, north, south, occupied, width, height, cellX, cellZ,
                        cellSizeX, cellSizeZ)
                        : 0.0f);
        mesh.vertex(
                originX + x1, y, originZ + z1,
                smoothEdges ? horizontalNormalX(
                        x1, z1, west, east, occupied, width, height, cellX, cellZ,
                        cellSizeX, cellSizeZ)
                        : 0.0f,
                normalY,
                smoothEdges ? horizontalNormalZ(
                        x1, z1, north, south, occupied, width, height, cellX, cellZ,
                        cellSizeX, cellSizeZ)
                        : 0.0f);
        mesh.vertex(
                originX + x1, y, originZ + z0,
                smoothEdges ? horizontalNormalX(
                        x1, z0, west, east, occupied, width, height, cellX, cellZ,
                        cellSizeX, cellSizeZ)
                        : 0.0f,
                normalY,
                smoothEdges ? horizontalNormalZ(
                        x1, z0, north, south, occupied, width, height, cellX, cellZ,
                        cellSizeX, cellSizeZ)
                        : 0.0f);
        int concaveVertex = smoothEdges
                ? concaveVertex(
                        x0, x1, z0, z1, occupied, width, height, cellX, cellZ,
                        cellSizeX, cellSizeZ)
                : -1;
        if (concaveVertex >= 0) {
            mesh.quad(first, concaveVertex == 1 || concaveVertex == 3);
        } else {
            mesh.quad(first);
        }
    }

    private static int concaveVertex(
            float x0,
            float x1,
            float z0,
            float z1,
            boolean[] occupied,
            int width,
            int height,
            int cellX,
            int cellZ,
            float cellSizeX,
            float cellSizeZ) {
        if (isConcaveCorner(
                x0, z0, occupied, width, height, cellX, cellZ, cellSizeX, cellSizeZ)) {
            return 0;
        }
        if (isConcaveCorner(
                x0, z1, occupied, width, height, cellX, cellZ, cellSizeX, cellSizeZ)) {
            return 1;
        }
        if (isConcaveCorner(
                x1, z1, occupied, width, height, cellX, cellZ, cellSizeX, cellSizeZ)) {
            return 2;
        }
        if (isConcaveCorner(
                x1, z0, occupied, width, height, cellX, cellZ, cellSizeX, cellSizeZ)) {
            return 3;
        }
        return -1;
    }

    private static boolean isConcaveCorner(
            float x,
            float z,
            boolean[] occupied,
            int width,
            int height,
            int cellX,
            int cellZ,
            float cellSizeX,
            float cellSizeZ) {
        if (!isCellBoundary(x, cellSizeX) || !isCellBoundary(z, cellSizeZ)) {
            return false;
        }
        int vertexX = cellX + (x == cellSizeX ? 1 : 0);
        int vertexZ = cellZ + (z == cellSizeZ ? 1 : 0);
        return cornerOccupancy(occupied, width, height, vertexX, vertexZ) == 3;
    }

    private static void addSideFace(
            MeshBuilder mesh,
            float fixedCoordinate,
            float variableOrigin,
            float[] variables,
            int variableCount,
            float[] ys,
            float normalX,
            float normalZ,
            float lowNormalX,
            float lowNormalZ,
            float highNormalX,
            float highNormalZ,
            float variableCellSize,
            float cloudHeight,
            boolean smoothEdges) {
        for (int y = 0; y < ys.length - 1; y++) {
            for (int variable = 0; variable < variableCount - 1; variable++) {
                float v0 = variables[variable];
                float v1 = variables[variable + 1];
                float y0 = ys[y];
                float y1 = ys[y + 1];
                if (normalX < 0.0f || normalZ > 0.0f) {
                    addSideQuad(
                            mesh, fixedCoordinate, variableOrigin, v0, v1, y0, y1,
                            normalX, normalZ,
                            lowNormalX, lowNormalZ, highNormalX, highNormalZ,
                            variableCellSize, cloudHeight, smoothEdges);
                } else {
                    addSideQuad(
                            mesh, fixedCoordinate, variableOrigin, v1, v0, y0, y1,
                            normalX, normalZ,
                            lowNormalX, lowNormalZ, highNormalX, highNormalZ,
                            variableCellSize, cloudHeight, smoothEdges);
                }
            }
        }
    }

    private static void addSideQuad(
            MeshBuilder mesh,
            float fixedCoordinate,
            float variableOrigin,
            float v0,
            float v1,
            float y0,
            float y1,
            float normalX,
            float normalZ,
            float lowNormalX,
            float lowNormalZ,
            float highNormalX,
            float highNormalZ,
            float variableCellSize,
            float cloudHeight,
            boolean smoothEdges) {
        int first = sideVertex(
                mesh, fixedCoordinate, variableOrigin + v0, y0,
                normalX, normalZ, v0,
                lowNormalX, lowNormalZ, highNormalX, highNormalZ,
                variableCellSize, cloudHeight, smoothEdges);
        sideVertex(
                mesh, fixedCoordinate, variableOrigin + v1, y0,
                normalX, normalZ, v1,
                lowNormalX, lowNormalZ, highNormalX, highNormalZ,
                variableCellSize, cloudHeight, smoothEdges);
        sideVertex(
                mesh, fixedCoordinate, variableOrigin + v1, y1,
                normalX, normalZ, v1,
                lowNormalX, lowNormalZ, highNormalX, highNormalZ,
                variableCellSize, cloudHeight, smoothEdges);
        sideVertex(
                mesh, fixedCoordinate, variableOrigin + v0, y1,
                normalX, normalZ, v0,
                lowNormalX, lowNormalZ, highNormalX, highNormalZ,
                variableCellSize, cloudHeight, smoothEdges);
        mesh.quad(first);
    }

    private static int sideVertex(
            MeshBuilder mesh,
            float fixedCoordinate,
            float variableCoordinate,
            float y,
            float normalX,
            float normalZ,
            float localVariable,
            float lowNormalX,
            float lowNormalZ,
            float highNormalX,
            float highNormalZ,
            float variableCellSize,
            float cloudHeight,
            boolean smoothEdges) {
        float nx = normalX;
        float nz = normalZ;
        if (smoothEdges && localVariable == 0.0f) {
            nx = lowNormalX;
            nz = lowNormalZ;
        } else if (smoothEdges && localVariable == variableCellSize) {
            nx = highNormalX;
            nz = highNormalZ;
        }
        if (nx == 0.0f && nz == 0.0f) {
            nx = normalX;
            nz = normalZ;
        }
        float ny = smoothEdges
                ? y == 0.0f ? -1.0f : y == cloudHeight ? 1.0f : 0.0f
                : 0.0f;
        if (normalX != 0.0f) {
            return mesh.vertex(
                    fixedCoordinate, y, variableCoordinate, nx, ny, nz);
        }
        return mesh.vertex(
                variableCoordinate, y, fixedCoordinate, nx, ny, nz);
    }

    private static float horizontalNormalX(
            float x,
            float z,
            boolean west,
            boolean east,
            boolean[] occupied,
            int width,
            int height,
            int cellX,
            int cellZ,
            float cellSizeX,
            float cellSizeZ) {
        if (isCellBoundary(x, cellSizeX) && isCellBoundary(z, cellSizeZ)) {
            return cornerNormalX(
                    occupied,
                    width,
                    height,
                    cellX + (x == cellSizeX ? 1 : 0),
                    cellZ + (z == cellSizeZ ? 1 : 0));
        }
        return horizontalNormal(x, west, east, cellSizeX);
    }

    private static float horizontalNormalZ(
            float x,
            float z,
            boolean north,
            boolean south,
            boolean[] occupied,
            int width,
            int height,
            int cellX,
            int cellZ,
            float cellSizeX,
            float cellSizeZ) {
        if (isCellBoundary(x, cellSizeX) && isCellBoundary(z, cellSizeZ)) {
            return cornerNormalZ(
                    occupied,
                    width,
                    height,
                    cellX + (x == cellSizeX ? 1 : 0),
                    cellZ + (z == cellSizeZ ? 1 : 0));
        }
        return horizontalNormal(z, north, south, cellSizeZ);
    }

    private static boolean isCellBoundary(float coordinate, float cellSize) {
        return coordinate == 0.0f || coordinate == cellSize;
    }

    private static float horizontalNormal(
            float coordinate, boolean low, boolean high, float cellSize) {
        if (coordinate == 0.0f && low) {
            return -1.0f;
        }
        if (coordinate == cellSize && high) {
            return 1.0f;
        }
        return 0.0f;
    }

    private static float cornerNormalX(
            boolean[] occupied, int width, int height, int x, int z) {
        int west = occupancy(occupied, width, height, x - 1, z - 1)
                + occupancy(occupied, width, height, x - 1, z);
        int east = occupancy(occupied, width, height, x, z - 1)
                + occupancy(occupied, width, height, x, z);
        return west - east;
    }

    private static float cornerNormalZ(
            boolean[] occupied, int width, int height, int x, int z) {
        int north = occupancy(occupied, width, height, x - 1, z - 1)
                + occupancy(occupied, width, height, x, z - 1);
        int south = occupancy(occupied, width, height, x - 1, z)
                + occupancy(occupied, width, height, x, z);
        return north - south;
    }

    private static int cornerOccupancy(
            boolean[] occupied, int width, int height, int x, int z) {
        return occupancy(occupied, width, height, x - 1, z - 1)
                + occupancy(occupied, width, height, x, z - 1)
                + occupancy(occupied, width, height, x - 1, z)
                + occupancy(occupied, width, height, x, z);
    }

    private static int occupancy(
            boolean[] occupied, int width, int height, int x, int z) {
        return occupied(occupied, width, height, x, z) ? 1 : 0;
    }

    private static boolean occupied(
            boolean[] occupied, int width, int height, int x, int z) {
        return occupied[Math.floorMod(z, height) * width + Math.floorMod(x, width)];
    }

    static final class GeneratedMesh {
        private final float[] vertices;
        private final int[] indices;
        private final long generationNanos;

        GeneratedMesh(float[] vertices, int[] indices, long generationNanos) {
            this.vertices = vertices;
            this.indices = indices;
            this.generationNanos = generationNanos;
        }

        float[] vertices() { return vertices; }
        int[] indices() { return indices; }
        long generationNanos() { return generationNanos; }

        int vertexCount() {
            return vertices.length / 6;
        }

        int triangleCount() {
            return indices.length / 3;
        }
    }

    private static final class MeshBuilder {
        private float[] vertices = new float[6 * 4096];
        private int vertexCount;
        private int[] indices = new int[6 * 4096];
        private int indexCount;

        int vertex(float x, float y, float z, float nx, float ny, float nz) {
            ensureVertexCapacity(vertexCount + 1);
            float inverseLength = 1.0f
                    / (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            int base = vertexCount * 6;
            vertices[base] = x;
            vertices[base + 1] = y;
            vertices[base + 2] = z;
            vertices[base + 3] = nx * inverseLength;
            vertices[base + 4] = ny * inverseLength;
            vertices[base + 5] = nz * inverseLength;
            return vertexCount++;
        }

        void quad(int first) {
            int diagonal02Difference = Math.abs(
                    normalComponentCount(first) - normalComponentCount(first + 2));
            int diagonal13Difference = Math.abs(
                    normalComponentCount(first + 1) - normalComponentCount(first + 3));
            quad(first, diagonal13Difference < diagonal02Difference);
        }

        void quad(int first, boolean rotated) {
            ensureIndexCapacity(indexCount + 6);
            if (rotated) {
                indices[indexCount++] = first;
                indices[indexCount++] = first + 3;
                indices[indexCount++] = first + 1;
                indices[indexCount++] = first + 1;
                indices[indexCount++] = first + 3;
                indices[indexCount++] = first + 2;
            } else {
                indices[indexCount++] = first;
                indices[indexCount++] = first + 2;
                indices[indexCount++] = first + 1;
                indices[indexCount++] = first;
                indices[indexCount++] = first + 3;
                indices[indexCount++] = first + 2;
            }
        }

        private int normalComponentCount(int vertex) {
            int base = vertex * 6 + 3;
            int count = vertices[base] != 0.0f ? 1 : 0;
            count += vertices[base + 1] != 0.0f ? 1 : 0;
            count += vertices[base + 2] != 0.0f ? 1 : 0;
            return count;
        }

        float[] vertices() {
            return Arrays.copyOf(vertices, vertexCount * 6);
        }

        int[] indices() {
            return Arrays.copyOf(indices, indexCount);
        }

        private void ensureVertexCapacity(int count) {
            int required = count * 6;
            if (required > vertices.length) {
                vertices = Arrays.copyOf(
                        vertices, Math.max(required, vertices.length + (vertices.length >> 1)));
            }
        }

        private void ensureIndexCapacity(int count) {
            if (count > indices.length) {
                indices = Arrays.copyOf(
                        indices, Math.max(count, indices.length + (indices.length >> 1)));
            }
        }
    }
}

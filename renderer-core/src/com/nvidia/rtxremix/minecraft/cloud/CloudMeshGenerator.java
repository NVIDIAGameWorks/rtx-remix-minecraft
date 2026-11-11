/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import java.util.Arrays;

/** Deterministic Perlin-Worley cloud generation with persistent scratch storage. */
final class CloudMeshGenerator {
    private static final int GRID_X = 69;
    private static final int GRID_Y = 45;
    private static final int GRID_Z = 61;
    private static final float ORIGIN_X = -51.0f;
    private static final float ORIGIN_Y = -22.5f;
    private static final float ORIGIN_Z = -45.0f;
    private static final float STEP_X = 1.5f;
    private static final float STEP_Y = 1.25f;
    private static final float STEP_Z = 1.5f;
    private static final int MAX_GROWTH_CELLS = 128;
    private static final float COVERAGE_RADIUS_X = 14.0f;
    private static final float COVERAGE_RADIUS_Y = 8.5f;
    private static final float COVERAGE_RADIUS_Z = 14.0f;
    private static final int COVERAGE_BLUR_PASSES = 3;
    private static final float BASE_NOISE_SCALE = 26.0f;
    private static final float BASE_WORLEY_SCALE = 18.0f;
    private static final float DETAIL_WORLEY_SCALE = 7.0f;
    private static final float ISO_LEVEL = 0.12f;
    private static final float GROWTH_STEP_X = 4.5f;
    private static final float GROWTH_STEP_Y = 3.5f;
    private static final float GROWTH_STEP_Z = 4.5f;
    private static final int MIN_CELL_X = -9;
    private static final int MAX_CELL_X = 9;
    private static final int MIN_CELL_Y = -4;
    private static final int MAX_CELL_Y = 7;
    private static final int MIN_CELL_Z = -8;
    private static final int MAX_CELL_Z = 8;
    private static final ThreadLocal<Workspace> WORKSPACE = ThreadLocal.withInitial(Workspace::new);

    private CloudMeshGenerator() {
    }

    static GeneratedMesh generate(long seed) {
        long startNanos = System.nanoTime();
        Workspace workspace = WORKSPACE.get();
        workspace.configure(seed);
        workspace.sampleGrid();
        MarchingCubes.MeshData data = workspace.marchingCubes.extract(workspace);
        return new GeneratedMesh(
                data.vertices(),
                data.indices(),
                System.nanoTime() - startNanos);
    }

    static void releaseWorkspace() {
        WORKSPACE.remove();
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

    private static final class Workspace implements MarchingCubes.DensityFunction {
        private final float[] density = new float[GRID_X * GRID_Y * GRID_Z];
        private final float[] coverage = new float[density.length];
        private final float[] coverageScratch = new float[density.length];
        private final int[] cellX = new int[MAX_GROWTH_CELLS];
        private final int[] cellY = new int[MAX_GROWTH_CELLS];
        private final int[] cellZ = new int[MAX_GROWTH_CELLS];
        private final MarchingCubes marchingCubes = new MarchingCubes(
                GRID_X,
                GRID_Y,
                GRID_Z,
                ORIGIN_X,
                ORIGIN_Y,
                ORIGIN_Z,
                STEP_X,
                STEP_Y,
                STEP_Z,
                density);
        private long randomState;
        private long noiseSeed;

        void configure(long seed) {
            randomState = seed;
            noiseSeed = CloudPlacement.mix(seed ^ 0x5741_5250_4E4F_4953L);
            int growthCount = 80 + nextInt(33);
            int configuredCount = configureCell(0, 0, 0, 0);
            configuredCount = configureCell(configuredCount, -1, 0, 0);
            configuredCount = configureCell(configuredCount, 1, 0, 0);
            configuredCount = configureCell(configuredCount, 0, 0, -1);
            configuredCount = configureCell(configuredCount, 0, 0, 1);
            configuredCount = configureCell(configuredCount, 0, 1, 0);
            configuredCount = configureCell(configuredCount, 0, -1, 0);
            for (int i = configuredCount; i < growthCount; i++) {
                growCell(i);
            }

            Arrays.fill(coverage, 0.0f);
            for (int i = 0; i < growthCount; i++) {
                splatCoverage(
                        cellX[i] * GROWTH_STEP_X + signedFloat(),
                        cellY[i] * GROWTH_STEP_Y + signedFloat() * 0.75f,
                        cellZ[i] * GROWTH_STEP_Z + signedFloat());
            }
            for (int i = 0; i < COVERAGE_BLUR_PASSES; i++) {
                blurCoverage();
            }
        }

        private int configureCell(int index, int x, int y, int z) {
            cellX[index] = x;
            cellY[index] = y;
            cellZ[index] = z;
            return index + 1;
        }

        private void growCell(int index) {
            for (int attempt = 0; attempt < 128; attempt++) {
                int recentWindow = Math.min(index, 16);
                int parent = nextFloat() < 0.35f
                        ? index - 1 - nextInt(recentWindow)
                        : nextInt(index);
                int x = cellX[parent];
                int y = cellY[parent];
                int z = cellZ[parent];
                switch (growthDirection()) {
                    case 0: x--; break;
                    case 1: x++; break;
                    case 2: z--; break;
                    case 3: z++; break;
                    case 4: y++; break;
                    case 5: y--; break;
                    default: throw new IllegalStateException("Invalid growth direction");
                }
                if (isAvailable(index, x, y, z)) {
                    cellX[index] = x;
                    cellY[index] = y;
                    cellZ[index] = z;
                    return;
                }
            }
            throw new IllegalStateException("Cloud growth exhausted its lattice");
        }

        private int growthDirection() {
            int value = nextInt(100);
            if (value < 18) return 0;
            if (value < 36) return 1;
            if (value < 54) return 2;
            if (value < 72) return 3;
            if (value < 92) return 4;
            return 5;
        }

        private boolean isAvailable(int count, int x, int y, int z) {
            if (x < MIN_CELL_X || x > MAX_CELL_X
                    || y < MIN_CELL_Y || y > MAX_CELL_Y
                    || z < MIN_CELL_Z || z > MAX_CELL_Z) {
                return false;
            }
            for (int i = 0; i < count; i++) {
                if (cellX[i] == x && cellY[i] == y && cellZ[i] == z) {
                    return false;
                }
            }
            return true;
        }

        private void splatCoverage(float centerX, float centerY, float centerZ) {
            int minX = gridIndex(centerX - COVERAGE_RADIUS_X, ORIGIN_X, STEP_X, GRID_X);
            int maxX = gridIndex(centerX + COVERAGE_RADIUS_X, ORIGIN_X, STEP_X, GRID_X);
            int minY = gridIndex(centerY - COVERAGE_RADIUS_Y, ORIGIN_Y, STEP_Y, GRID_Y);
            int maxY = gridIndex(centerY + COVERAGE_RADIUS_Y, ORIGIN_Y, STEP_Y, GRID_Y);
            int minZ = gridIndex(centerZ - COVERAGE_RADIUS_Z, ORIGIN_Z, STEP_Z, GRID_Z);
            int maxZ = gridIndex(centerZ + COVERAGE_RADIUS_Z, ORIGIN_Z, STEP_Z, GRID_Z);
            for (int z = minZ; z <= maxZ; z++) {
                float dz = (ORIGIN_Z + z * STEP_Z - centerZ) / COVERAGE_RADIUS_Z;
                for (int y = minY; y <= maxY; y++) {
                    float dy = (ORIGIN_Y + y * STEP_Y - centerY) / COVERAGE_RADIUS_Y;
                    for (int x = minX; x <= maxX; x++) {
                        float dx = (ORIGIN_X + x * STEP_X - centerX) / COVERAGE_RADIUS_X;
                        float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                        if (distance < 1.0f) {
                            int index = index(x, y, z);
                            coverage[index] = Math.max(
                                    coverage[index], smoothstep(1.0f, 0.0f, distance));
                        }
                    }
                }
            }
        }

        private void blurCoverage() {
            for (int z = 0; z < GRID_Z; z++) {
                for (int y = 0; y < GRID_Y; y++) {
                    for (int x = 0; x < GRID_X; x++) {
                        float neighbors = coverageAt(x - 1, y, z)
                                + coverageAt(x + 1, y, z)
                                + coverageAt(x, y - 1, z)
                                + coverageAt(x, y + 1, z)
                                + coverageAt(x, y, z - 1)
                                + coverageAt(x, y, z + 1);
                        coverageScratch[index(x, y, z)] =
                                coverage[index(x, y, z)] * 0.5f + neighbors / 12.0f;
                    }
                }
            }
            System.arraycopy(coverageScratch, 0, coverage, 0, coverage.length);
        }

        void sampleGrid() {
            int index = 0;
            for (int z = 0; z < GRID_Z; z++) {
                float pz = ORIGIN_Z + z * STEP_Z;
                for (int y = 0; y < GRID_Y; y++) {
                    float py = ORIGIN_Y + y * STEP_Y;
                    for (int x = 0; x < GRID_X; x++) {
                        float px = ORIGIN_X + x * STEP_X;
                        density[index] = proceduralDensity(px, py, pz, coverage[index]);
                        index++;
                    }
                }
            }
        }

        private float proceduralDensity(float x, float y, float z, float coverageValue) {
            float gridX = (x - ORIGIN_X) / STEP_X;
            float gridY = (y - ORIGIN_Y) / STEP_Y;
            float gridZ = (z - ORIGIN_Z) / STEP_Z;
            float boundaryDistance = Math.min(
                    Math.min(gridX, GRID_X - 1.0f - gridX),
                    Math.min(
                            Math.min(gridY, GRID_Y - 1.0f - gridY),
                            Math.min(gridZ, GRID_Z - 1.0f - gridZ)));
            float boundaryFade = smoothstep(1.0f, 4.0f, boundaryDistance);
            float coverageMask = clamp01(coverageValue * 1.35f) * boundaryFade;
            if (coverageMask <= 0.001f) {
                return -ISO_LEVEL;
            }

            float height = clamp01((y - ORIGIN_Y) / ((GRID_Y - 1) * STEP_Y));
            float heightProfile = smoothstep(0.12f, 0.23f, height)
                    * (1.0f - smoothstep(0.68f, 0.97f, height));
            float perlin = clamp01(
                    perlinFbm(
                            x / BASE_NOISE_SCALE,
                            y / BASE_NOISE_SCALE,
                            z / BASE_NOISE_SCALE)
                            * 1.25f
                            + 0.15f);
            float worley0 = invertedWorley(
                    x / BASE_WORLEY_SCALE,
                    y / BASE_WORLEY_SCALE,
                    z / BASE_WORLEY_SCALE,
                    0x43A1_9E37L);
            float worley1 = invertedWorley(
                    x / (BASE_WORLEY_SCALE * 0.5f),
                    y / (BASE_WORLEY_SCALE * 0.5f),
                    z / (BASE_WORLEY_SCALE * 0.5f),
                    0x72B5_85EBL);
            float worleyFbm = worley0 * 0.7f + worley1 * 0.3f;
            float perlinWorley = clamp01(
                    (perlin - (1.0f - worleyFbm)) / Math.max(worleyFbm, 1.0e-3f));
            float shaped = remap01(
                    perlinWorley,
                    1.0f - coverageMask * 0.88f,
                    1.0f) * heightProfile;

            float detail = invertedWorley(
                    x / DETAIL_WORLEY_SCALE,
                    y / DETAIL_WORLEY_SCALE,
                    z / DETAIL_WORLEY_SCALE,
                    0x1AB7_C2B2L);
            float erosionSignal = lerp(
                    1.0f - detail,
                    detail,
                    smoothstep(0.12f, 0.4f, height));
            float edgeWeight = 1.0f - smoothstep(0.22f, 0.68f, shaped);
            shaped -= erosionSignal * edgeWeight * 0.04f;
            return shaped - ISO_LEVEL;
        }

        @Override
        public float sample(float x, float y, float z) {
            return trilinear(density, x, y, z);
        }

        private float perlinFbm(float x, float y, float z) {
            float result = valueNoise(x, y, z, 0x1234_5678L) * 0.5714286f;
            result += valueNoise(x * 2.0f, y * 2.0f, z * 2.0f, 0x2537_9E37L)
                    * 0.2857143f;
            result += valueNoise(x * 4.0f, y * 4.0f, z * 4.0f, 0x9182_85EBL)
                    * 0.1428571f;
            return result;
        }

        private float valueNoise(float x, float y, float z, long channel) {
            int x0 = (int) Math.floor(x);
            int y0 = (int) Math.floor(y);
            int z0 = (int) Math.floor(z);
            float tx = smooth(x - x0);
            float ty = smooth(y - y0);
            float tz = smooth(z - z0);
            float x00 = lerp(unitLattice(x0, y0, z0, channel),
                    unitLattice(x0 + 1, y0, z0, channel), tx);
            float x10 = lerp(unitLattice(x0, y0 + 1, z0, channel),
                    unitLattice(x0 + 1, y0 + 1, z0, channel), tx);
            float x01 = lerp(unitLattice(x0, y0, z0 + 1, channel),
                    unitLattice(x0 + 1, y0, z0 + 1, channel), tx);
            float x11 = lerp(unitLattice(x0, y0 + 1, z0 + 1, channel),
                    unitLattice(x0 + 1, y0 + 1, z0 + 1, channel), tx);
            return lerp(lerp(x00, x10, ty), lerp(x01, x11, ty), tz);
        }

        private float invertedWorley(float x, float y, float z, long channel) {
            int cellX = (int) Math.floor(x);
            int cellY = (int) Math.floor(y);
            int cellZ = (int) Math.floor(z);
            float nearestSquared = Float.POSITIVE_INFINITY;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int candidateX = cellX + dx;
                        int candidateY = cellY + dy;
                        int candidateZ = cellZ + dz;
                        float featureX = candidateX
                                + unitLattice(candidateX, candidateY, candidateZ, channel);
                        float featureY = candidateY
                                + unitLattice(
                                        candidateX, candidateY, candidateZ,
                                        channel ^ 0x68BC_21EBL);
                        float featureZ = candidateZ
                                + unitLattice(
                                        candidateX, candidateY, candidateZ,
                                        channel ^ 0x967A_889BL);
                        float offsetX = featureX - x;
                        float offsetY = featureY - y;
                        float offsetZ = featureZ - z;
                        nearestSquared = Math.min(
                                nearestSquared,
                                offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ);
                    }
                }
            }
            return 1.0f - clamp01((float) Math.sqrt(nearestSquared));
        }

        private float unitLattice(int x, int y, int z, long channel) {
            long coordinates = (long) x * 0x9E37_79B9L
                    ^ (long) y * 0x85EB_CA6BL
                    ^ (long) z * 0xC2B2_AE35L;
            long value = CloudPlacement.mix(noiseSeed ^ channel ^ coordinates);
            return (value >>> 40) * 0x1.0p-24f;
        }

        private float trilinear(float[] values, float x, float y, float z) {
            float gx = clamp((x - ORIGIN_X) / STEP_X, 0.0f, GRID_X - 1.001f);
            float gy = clamp((y - ORIGIN_Y) / STEP_Y, 0.0f, GRID_Y - 1.001f);
            float gz = clamp((z - ORIGIN_Z) / STEP_Z, 0.0f, GRID_Z - 1.001f);
            int x0 = (int) gx;
            int y0 = (int) gy;
            int z0 = (int) gz;
            float tx = gx - x0;
            float ty = gy - y0;
            float tz = gz - z0;
            float x00 = lerp(values[index(x0, y0, z0)],
                    values[index(x0 + 1, y0, z0)], tx);
            float x10 = lerp(values[index(x0, y0 + 1, z0)],
                    values[index(x0 + 1, y0 + 1, z0)], tx);
            float x01 = lerp(values[index(x0, y0, z0 + 1)],
                    values[index(x0 + 1, y0, z0 + 1)], tx);
            float x11 = lerp(values[index(x0, y0 + 1, z0 + 1)],
                    values[index(x0 + 1, y0 + 1, z0 + 1)], tx);
            return lerp(lerp(x00, x10, ty), lerp(x01, x11, ty), tz);
        }

        private float coverageAt(int x, int y, int z) {
            if (x < 0 || x >= GRID_X || y < 0 || y >= GRID_Y || z < 0 || z >= GRID_Z) {
                return 0.0f;
            }
            return coverage[index(x, y, z)];
        }

        private static int gridIndex(float value, float origin, float step, int size) {
            return Math.max(0, Math.min((int) Math.floor((value - origin) / step), size - 1));
        }

        private static int index(int x, int y, int z) {
            return (z * GRID_Y + y) * GRID_X + x;
        }

        private static float remap01(float value, float low, float high) {
            return clamp01((value - low) / Math.max(high - low, 1.0e-5f));
        }

        private static float smoothstep(float low, float high, float value) {
            float t = clamp01((value - low) / (high - low));
            return t * t * (3.0f - 2.0f * t);
        }

        private static float smooth(float value) {
            return value * value * (3.0f - 2.0f * value);
        }

        private static float clamp(float value, float low, float high) {
            return Math.max(low, Math.min(value, high));
        }

        private static float clamp01(float value) {
            return clamp(value, 0.0f, 1.0f);
        }

        private static float lerp(float a, float b, float amount) {
            return a + (b - a) * amount;
        }

        private int nextInt(int bound) {
            return (int) (nextLong() & 0x7fff_ffffL) % bound;
        }

        private float nextFloat() {
            return (nextLong() >>> 40) * 0x1.0p-24f;
        }

        private float signedFloat() {
            return nextFloat() * 2.0f - 1.0f;
        }

        private long nextLong() {
            randomState += 0x9e37_79b9_7f4a_7c15L;
            return CloudPlacement.mix(randomState);
        }
    }

}

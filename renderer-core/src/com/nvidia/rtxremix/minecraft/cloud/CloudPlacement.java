/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import java.util.function.Consumer;

/**
 * Stateless cloud placement in a fixed, wind-advected cell grid.
 */
final class CloudPlacement {
    static final long FIXED_SEED = 0x53A9_7C1D_D4E2_8B6FL;
    static final double CELL_SIZE = 168.0;
    static final double VIEW_DISTANCE = 1152.0;
    static final double WIND_X_PER_TICK = 0.12;
    static final double WIND_Z_PER_TICK = 0.035;

    private CloudPlacement() {
    }

    static void forEachVisible(
            double cameraX,
            double cameraZ,
            double worldTime,
            Consumer<Placement> consumer) {
        double windX = worldTime * WIND_X_PER_TICK;
        double windZ = worldTime * WIND_Z_PER_TICK;
        double advectedCameraX = cameraX - windX;
        double advectedCameraZ = cameraZ - windZ;
        int minCellX = floorCell(advectedCameraX - VIEW_DISTANCE);
        int maxCellX = floorCell(advectedCameraX + VIEW_DISTANCE);
        int minCellZ = floorCell(advectedCameraZ - VIEW_DISTANCE);
        int maxCellZ = floorCell(advectedCameraZ + VIEW_DISTANCE);
        double maxDistanceSquared = VIEW_DISTANCE * VIEW_DISTANCE;

        for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                long seed = seed(cellX, cellZ);
                if ((mix(seed) & 7L) == 0L) {
                    continue;
                }

                double offsetX = unit(mix(seed + 1L)) * CELL_SIZE;
                double offsetZ = unit(mix(seed + 2L)) * CELL_SIZE;
                double baseX = cellX * CELL_SIZE + offsetX;
                double baseZ = cellZ * CELL_SIZE + offsetZ;
                double worldX = baseX + windX;
                double worldZ = baseZ + windZ;
                double dx = worldX - cameraX;
                double dz = worldZ - cameraZ;
                if (dx * dx + dz * dz > maxDistanceSquared) {
                    continue;
                }

                float altitudeOffset = 40.0f + (float) unit(mix(seed + 3L)) * 60.0f;
                float scale = 1.625f + (float) unit(mix(seed + 4L)) * 0.65f;
                consumer.accept(new Placement(
                        key(cellX, cellZ),
                        seed,
                        worldX,
                        worldZ,
                        altitudeOffset,
                        scale));
            }
        }
    }

    static int floorCell(double coordinate) {
        return (int) Math.floor(coordinate / CELL_SIZE);
    }

    static long key(int cellX, int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xffff_ffffL);
    }

    static long seed(int cellX, int cellZ) {
        long packed = key(cellX, cellZ);
        return mix(packed ^ FIXED_SEED);
    }

    static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xbf58_476d_1ce4_e5b9L;
        value ^= value >>> 27;
        value *= 0x94d0_49bb_1331_11ebL;
        return value ^ value >>> 31;
    }

    static double unit(long value) {
        return (value >>> 11) * 0x1.0p-53;
    }

    static final class Placement {
        private final long key;
        private final long seed;
        private final double x;
        private final double z;
        private final float altitudeOffset;
        private final float scale;

        Placement(long key, long seed, double x, double z, float altitudeOffset, float scale) {
            this.key = key;
            this.seed = seed;
            this.x = x;
            this.z = z;
            this.altitudeOffset = altitudeOffset;
            this.scale = scale;
        }

        long key() { return key; }
        long seed() { return seed; }
        double x() { return x; }
        double z() { return z; }
        float altitudeOffset() { return altitudeOffset; }
        float scale() { return scale; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Placement)) return false;
            Placement placement = (Placement) other;
            return key == placement.key
                    && seed == placement.seed
                    && Double.compare(x, placement.x) == 0
                    && Double.compare(z, placement.z) == 0
                    && Float.compare(altitudeOffset, placement.altitudeOffset) == 0
                    && Float.compare(scale, placement.scale) == 0;
        }

        @Override
        public int hashCode() {
            int result = Long.hashCode(key);
            result = 31 * result + Long.hashCode(seed);
            result = 31 * result + Double.hashCode(x);
            result = 31 * result + Double.hashCode(z);
            result = 31 * result + Float.hashCode(altitudeOffset);
            result = 31 * result + Float.hashCode(scale);
            return result;
        }

        @Override
        public String toString() {
            return "Placement[key=" + key + ", seed=" + seed + ", x=" + x + ", z=" + z
                    + ", altitudeOffset=" + altitudeOffset + ", scale=" + scale + "]";
        }
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

/** Native mesh ownership includes staged and retired pages until their actual destruction. */
final class TerrainBudget {
    static final long FRAME_NANOS = 2_000_000;
    static final int COLUMNS_PER_FRAME = 32;
    static final int BLOCKS_PER_FRAME = 4096;
    static final int RETIREMENTS_PER_FRAME = 4;
    static final int PAGE_VERTICES = 16384;
    static final long MAX_MESH_BYTES = 512L * 1024 * 1024;
    private final long limit;
    private long bytes;
    private long retiring;

    TerrainBudget() { this(configuredLimit(System.getProperty("rtxremix.terrainMeshMiB"))); }
    TerrainBudget(long limit) {
        if (limit <= 0 || limit > MAX_MESH_BYTES) throw new IllegalArgumentException("Invalid terrain mesh budget");
        this.limit = limit;
    }
    static long configuredLimit(String value) {
        if (value == null) return MAX_MESH_BYTES;
        int mib;
        try { mib = Integer.parseInt(value); }
        catch (NumberFormatException error) {
            throw new IllegalArgumentException("rtxremix.terrainMeshMiB must be an integer from 8 to 512", error);
        }
        if (mib < 8 || mib > 512) {
            throw new IllegalArgumentException("rtxremix.terrainMeshMiB must be an integer from 8 to 512");
        }
        return (long) mib * 1024 * 1024;
    }

    static long bytes(int vertices) { return (long) vertices * MeshPacking.STRIDE + (long) vertices / 4 * 6 * 4; }
    boolean canReserve(long count) { return count >= 0 && count <= available(); }
    boolean canReserveAfterRetirement(long count) { return count >= 0 && count <= available() + retiring; }
    void reserve(long count) {
        if (!canReserve(count)) throw new IllegalStateException("Terrain mesh memory budget exceeded");
        bytes += count;
    }
    void retire(long count) {
        if (count < 0 || count > bytes - retiring) throw new IllegalStateException("Terrain mesh memory retired twice");
        retiring += count;
    }
    void releaseRetired(long count) {
        if (count < 0 || count > retiring) throw new IllegalStateException("Retired terrain memory released twice");
        retiring -= count;
        bytes -= count;
    }
    long bytes() { return bytes; }
    long available() { return limit - bytes; }
    long limit() { return limit; }
}

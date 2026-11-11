/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

/**
 * Repositions the horizontal ring of vanilla built-chunk slots as the camera
 * moves, and reports how many slots were re-originned.
 *
 * <p>The wrap arithmetic must match {@code BuiltChunkStorage.updateCameraPosition}
 * bit for bit: a slot that lands on a different origin from the one vanilla would
 * have chosen puts vanilla's visibility graph and the Remix terrain index on
 * different chunks. Render thread only, alongside the storage it mutates.
 */
public final class ChunkStorageTorus {
    private static final int SECTION_SIZE = 16;

    private ChunkStorageTorus() {
    }

    public static int reposition(
            StorageAccess storage,
            Workspace workspace,
            int sizeX,
            int sizeY,
            int sizeZ,
            int bottomY,
            double cameraX,
            double cameraZ) {
        workspace.resize(sizeX, sizeZ);

        for (int x = 0; x < sizeX; x++) {
            int targetX = targetOrigin(cameraX, x, sizeX);
            workspace.targetX[x] = targetX;
            workspace.changedX[x] = storage.rtxremix$getOriginX(index(x, 0, 0, sizeX, sizeY)) != targetX;
        }
        for (int z = 0; z < sizeZ; z++) {
            int targetZ = targetOrigin(cameraZ, z, sizeZ);
            workspace.targetZ[z] = targetZ;
            workspace.changedZ[z] = storage.rtxremix$getOriginZ(index(0, 0, z, sizeX, sizeY)) != targetZ;
        }

        int movedSlots = 0;
        // BuiltChunkStorage maintains one X origin per storage column and one Z
        // origin per storage row. Horizontal camera movement therefore changes
        // complete planes; slot Y never changes without rebuilding the storage.
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                if (!workspace.changedX[x] && !workspace.changedZ[z]) {
                    continue;
                }
                int targetX = workspace.targetX[x];
                int targetZ = workspace.targetZ[z];
                for (int y = 0; y < sizeY; y++) {
                    storage.rtxremix$setOrigin(
                            index(x, y, z, sizeX, sizeY),
                            targetX,
                            bottomY + y * SECTION_SIZE,
                            targetZ);
                    movedSlots++;
                }
            }
        }
        return movedSlots;
    }

    static int targetOrigin(double cameraCoordinate, int slot, int size) {
        int span = size * SECTION_SIZE;
        int start = ceil(cameraCoordinate) - 8 - span / 2;
        return start + Math.floorMod(slot * SECTION_SIZE - start, span);
    }

    public static int index(int x, int y, int z, int sizeX, int sizeY) {
        return (z * sizeY + y) * sizeX + x;
    }

    private static int ceil(double value) {
        int truncated = (int) value;
        return value > truncated ? truncated + 1 : truncated;
    }

    public interface StorageAccess {
        int rtxremix$getOriginX(int slotIndex);

        int rtxremix$getOriginZ(int slotIndex);

        void rtxremix$setOrigin(int slotIndex, int x, int y, int z);
    }

    public static final class Workspace {
        private int[] targetX = new int[0];
        private int[] targetZ = new int[0];
        private boolean[] changedX = new boolean[0];
        private boolean[] changedZ = new boolean[0];

        private void resize(int sizeX, int sizeZ) {
            if (targetX.length != sizeX) {
                targetX = new int[sizeX];
                changedX = new boolean[sizeX];
            }
            if (targetZ.length != sizeZ) {
                targetZ = new int[sizeZ];
                changedZ = new boolean[sizeZ];
            }
        }
    }
}

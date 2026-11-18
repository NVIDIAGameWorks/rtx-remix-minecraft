/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkStorageTorusTest {
    private static final int SIZE_X = 5;
    private static final int SIZE_Y = 3;
    private static final int SIZE_Z = 5;
    private static final int BOTTOM_Y = -64;

    @Test
    void stripUpdatesMatchVanillaAcrossMovementCases() {
        assertEquivalent("first constructor-layout update", null, 173.25, -211.75);
        assertEquivalent("no horizontal movement", new Camera(-24.5, 37.5), -24.5, 37.5);
        assertEquivalent("positive one-chunk move", new Camera(0.0, 0.0), 16.0, 0.0);
        assertEquivalent("positive move just over a boundary", new Camera(0.0001, 0.0001), 16.0001, 0.0001);
        assertEquivalent("negative one-chunk move", new Camera(0.0, 0.0), -16.0, 0.0);
        assertEquivalent("negative move just over a boundary", new Camera(-0.0001, -0.0001), -16.0001, -0.0001);
        assertEquivalent("diagonal move", new Camera(0.0, 0.0), 16.0, -16.0);
        assertEquivalent("multi-chunk move", new Camera(-19.25, 33.75), 44.75, -14.25);
        assertEquivalent("diameter teleport", new Camera(0.0, 0.0), SIZE_X * 16.0, 0.0);
        assertEquivalent("beyond-diameter teleport", new Camera(0.0, 0.0), 112.0, -96.0);
        assertEquivalent("negative coordinates", new Camera(-97.01, -64.0), -128.0, -31.999);
    }

    @Test
    void targetOriginMatchesVanillaAtCeilBoundariesAndExtremes() {
        double[] coordinates = {
                -32.0001, -32.0, -31.9999,
                -0.0001, -0.0, 0.0001,
                15.9999, 16.0, 16.0001,
                Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                -Double.MAX_VALUE, Double.MAX_VALUE
        };
        for (double coordinate : coordinates) {
            for (int slot = 0; slot < SIZE_X; slot++) {
                assertEquals(
                        vanillaTargetOrigin(coordinate, slot, SIZE_X),
                        ChunkStorageTorus.targetOrigin(coordinate, slot, SIZE_X),
                        "coordinate=" + coordinate + ", slot=" + slot);
            }
        }
    }

    @Test
    void renderDistanceThirtyTwoTouchesOnlyEnteringPlanes() {
        int size = 65;
        int verticalSections = 24;
        FakeStorage storage = new FakeStorage(size, verticalSections, size, -64);
        ChunkStorageTorus.Workspace workspace = new ChunkStorageTorus.Workspace();
        vanillaReposition(storage, size, verticalSections, size, -64, 0.0, 0.0);
        storage.clearMoves();

        int moved = ChunkStorageTorus.reposition(
                storage,
                workspace,
                size,
                verticalSections,
                size,
                -64,
                16.0,
                0.0);
        assertEquals(1_560, moved);

        storage.clearMoves();
        moved = ChunkStorageTorus.reposition(
                storage,
                workspace,
                size,
                verticalSections,
                size,
                -64,
                32.0,
                16.0);
        assertEquals(3_096, moved);

        storage.clearMoves();
        moved = ChunkStorageTorus.reposition(
                storage,
                workspace,
                size,
                verticalSections,
                size,
                -64,
                32.0,
                16.0);
        assertEquals(0, moved);
        assertEquals(130, storage.originReads);
    }

    private static void assertEquivalent(
            String description,
            Camera initialCamera,
            double cameraX,
            double cameraZ) {
        FakeStorage initial = new FakeStorage(SIZE_X, SIZE_Y, SIZE_Z, BOTTOM_Y);
        if (initialCamera != null) {
            vanillaReposition(
                    initial, SIZE_X, SIZE_Y, SIZE_Z, BOTTOM_Y,
                    initialCamera.x, initialCamera.z);
        }
        FakeStorage expected = initial.copy();
        FakeStorage actual = initial.copy();

        vanillaReposition(expected, SIZE_X, SIZE_Y, SIZE_Z, BOTTOM_Y, cameraX, cameraZ);
        int moved = ChunkStorageTorus.reposition(
                actual,
                new ChunkStorageTorus.Workspace(),
                SIZE_X,
                SIZE_Y,
                SIZE_Z,
                BOTTOM_Y,
                cameraX,
                cameraZ);

        assertEquals(expected.origins, actual.origins, description + " final origins");
        assertEquals(expected.moves, actual.moves, description + " setOrigin calls");
        assertEquals(expected.moves.size(), moved, description + " moved count");
    }

    private static void vanillaReposition(
            FakeStorage storage,
            int sizeX,
            int sizeY,
            int sizeZ,
            int bottomY,
            double cameraX,
            double cameraZ) {
        for (int x = 0; x < sizeX; x++) {
            int targetX = vanillaTargetOrigin(cameraX, x, sizeX);
            for (int z = 0; z < sizeZ; z++) {
                int targetZ = vanillaTargetOrigin(cameraZ, z, sizeZ);
                for (int y = 0; y < sizeY; y++) {
                    int index = ChunkStorageTorus.index(x, y, z, sizeX, sizeY);
                    Origin origin = storage.origins.get(index);
                    int targetY = bottomY + y * 16;
                    if (origin.x != targetX || origin.y != targetY || origin.z != targetZ) {
                        storage.rtxremix$setOrigin(index, targetX, targetY, targetZ);
                    }
                }
            }
        }
    }

    private static int vanillaTargetOrigin(double cameraCoordinate, int slot, int size) {
        int cameraBlock = Mth.ceil(cameraCoordinate);
        int span = size * 16;
        int start = cameraBlock - 8 - span / 2;
        return start + Math.floorMod(slot * 16 - start, span);
    }

    private record Camera(double x, double z) {
    }

    private record Origin(int x, int y, int z) {
    }

    private record Move(int index, Origin origin) {
    }

    private static final class FakeStorage implements ChunkStorageTorus.StorageAccess {
        private final List<Origin> origins;
        private final List<Move> moves = new ArrayList<>();
        private int originReads;

        private FakeStorage(int sizeX, int sizeY, int sizeZ, int bottomY) {
            origins = new ArrayList<>(sizeX * sizeY * sizeZ);
            for (int i = 0; i < sizeX * sizeY * sizeZ; i++) {
                origins.add(null);
            }
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) {
                    for (int y = 0; y < sizeY; y++) {
                        origins.set(
                                ChunkStorageTorus.index(x, y, z, sizeX, sizeY),
                                new Origin(x * 16, bottomY + y * 16, z * 16));
                    }
                }
            }
        }

        private FakeStorage(List<Origin> origins) {
            this.origins = new ArrayList<>(origins);
        }

        private FakeStorage copy() {
            return new FakeStorage(origins);
        }

        private void clearMoves() {
            moves.clear();
            originReads = 0;
        }

        @Override
        public int rtxremix$getOriginX(int slotIndex) {
            originReads++;
            return origins.get(slotIndex).x;
        }

        @Override
        public int rtxremix$getOriginZ(int slotIndex) {
            originReads++;
            return origins.get(slotIndex).z;
        }

        @Override
        public void rtxremix$setOrigin(int slotIndex, int x, int y, int z) {
            Origin origin = new Origin(x, y, z);
            origins.set(slotIndex, origin);
            moves.add(new Move(slotIndex, origin));
        }
    }
}

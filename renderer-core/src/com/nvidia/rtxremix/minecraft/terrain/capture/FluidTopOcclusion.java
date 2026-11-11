/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

/**
 * Allocation-free flood fill used to decide whether a covered fluid surface
 * can reach an exposed surface within a small horizontal neighborhood.
 */
final class FluidTopOcclusion {

    static final byte UNKNOWN = 0;
    static final byte BLOCKED = 1;
    static final byte CONNECTED = 2;
    static final byte EXPOSED = 3;

    @FunctionalInterface
    interface CellClassifier {
        byte classify(int xOffset, int zOffset);
    }

    enum Exposure {
        HIDDEN,
        EXPOSED
    }

    private FluidTopOcclusion() {}

    /**
     * Flood-fills cardinally from a center cell already known to be connected.
     * Cells are classified only when reached, allowing exposed neighbors to
     * terminate the search without evaluating the rest of the search area.
     */
    static Exposure classifyConnectedOrigin(int radius, int[] stack, CellClassifier classifier) {
        int width = radius * 2 + 1;
        int cellCount = width * width;
        if (radius < 0 || cellCount > Long.SIZE || stack.length < cellCount) {
            throw new IllegalArgumentException("Invalid fluid occlusion search storage");
        }

        int origin = radius * width + radius;
        long visited = 1L << origin;
        int stackSize = 0;
        stack[stackSize++] = origin;

        while (stackSize > 0) {
            int cell = stack[--stackSize];
            int x = cell % width;
            int z = cell / width;
            if (x > 0) {
                int next = cell - 1;
                if ((visited & (1L << next)) == 0) {
                    visited |= 1L << next;
                    byte classification = classifier.classify(x - radius - 1, z - radius);
                    if (classification == EXPOSED) return Exposure.EXPOSED;
                    if (classification == CONNECTED) stack[stackSize++] = next;
                }
            }
            if (x + 1 < width) {
                int next = cell + 1;
                if ((visited & (1L << next)) == 0) {
                    visited |= 1L << next;
                    byte classification = classifier.classify(x - radius + 1, z - radius);
                    if (classification == EXPOSED) return Exposure.EXPOSED;
                    if (classification == CONNECTED) stack[stackSize++] = next;
                }
            }
            if (z > 0) {
                int next = cell - width;
                if ((visited & (1L << next)) == 0) {
                    visited |= 1L << next;
                    byte classification = classifier.classify(x - radius, z - radius - 1);
                    if (classification == EXPOSED) return Exposure.EXPOSED;
                    if (classification == CONNECTED) stack[stackSize++] = next;
                }
            }
            if (z + 1 < width) {
                int next = cell + width;
                if ((visited & (1L << next)) == 0) {
                    visited |= 1L << next;
                    byte classification = classifier.classify(x - radius, z - radius + 1);
                    if (classification == EXPOSED) return Exposure.EXPOSED;
                    if (classification == CONNECTED) stack[stackSize++] = next;
                }
            }
        }

        return Exposure.HIDDEN;
    }
}

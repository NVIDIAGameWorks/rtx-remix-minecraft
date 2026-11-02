/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TerrainApiContractsTest {
    @Test
    void blockPositionsPackSectionCoordinatesIncludingNegativeBoundaries() {
        int[] coordinates = {-65, -64, -17, -16, -1, 0, 15, 16, 31, 32};
        BlockPos.Mutable probe = new BlockPos.Mutable();
        for (int x : coordinates) {
            for (int y : coordinates) {
                for (int z : coordinates) {
                    probe.set(x, y, z);
                    assertEquals(ChunkSectionPos.asLong(x >> 4, y >> 4, z >> 4),
                            ChunkSectionPos.toLong(probe));
                }
            }
        }
    }

    @Test
    void suppressedTerrainIteratorSupportsBothVanillaTraversalDirections() {
        ObjectListIterator<Object> iterator = ObjectList.<Object>of().listIterator();
        assertFalse(iterator.hasNext());
        assertFalse(iterator.hasPrevious());
        assertEquals(0, iterator.nextIndex());
        assertEquals(-1, iterator.previousIndex());
        assertThrows(NoSuchElementException.class, iterator::next);
        assertThrows(NoSuchElementException.class, iterator::previous);
    }
}

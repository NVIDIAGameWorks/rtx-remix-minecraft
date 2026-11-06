/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import org.junit.Test;
import static org.junit.Assert.*;

public class TerrainBudgetTest {
    @Test public void stagedAndRetiringPagesKeepTheirReservationUntilDestroyed() {
        TerrainBudget budget = new TerrainBudget();
        long page = TerrainBudget.bytes(TerrainBudget.PAGE_VERTICES);
        assertEquals(1146880, page);
        budget.reserve(TerrainBudget.MAX_MESH_BYTES - page);
        assertTrue(budget.canReserve(page));
        budget.reserve(page);
        assertFalse(budget.canReserve(1));
        assertFalse(budget.canReserve(Long.MAX_VALUE));
        budget.retire(page);
        assertFalse(budget.canReserve(page));
        budget.releaseRetired(page);
        assertTrue(budget.canReserve(page));
        budget.retire(TerrainBudget.MAX_MESH_BYTES - page);
        budget.releaseRetired(TerrainBudget.MAX_MESH_BYTES - page);
        assertEquals(0, budget.bytes());
    }

    @Test(expected = IllegalStateException.class) public void refusesOverReservation() {
        new TerrainBudget().reserve(TerrainBudget.MAX_MESH_BYTES + 1);
    }


    @Test public void retirementPlansCapacityWithoutReleasingFailedNativeOwners() {
        TerrainBudget budget = new TerrainBudget(1000);
        budget.reserve(1000);
        budget.retire(600);
        assertFalse(budget.canReserve(500));
        assertTrue(budget.canReserveAfterRetirement(500));
        assertFalse(budget.canReserveAfterRetirement(601));
        assertEquals(1000, budget.bytes());
        // A failed destroy does not call releaseRetired: ownership remains charged.
        budget.releaseRetired(200);
        assertEquals(800, budget.bytes());
        assertFalse(budget.canReserve(500));
        assertTrue(budget.canReserveAfterRetirement(500));
        budget.releaseRetired(400);
        assertTrue(budget.canReserve(500));
        budget.reserve(500);
        assertEquals(100, budget.available());
        budget.retire(900);
        budget.releaseRetired(900);
        assertEquals(0, budget.bytes());
    }

    @Test public void diagnosticBudgetIsBoundedAndLeavesTheDefaultUnchanged() {
        assertEquals(TerrainBudget.MAX_MESH_BYTES, TerrainBudget.configuredLimit(null));
        assertEquals(16L * 1024 * 1024, TerrainBudget.configuredLimit("16"));
        assertEquals(8L * 1024 * 1024, TerrainBudget.configuredLimit("8"));
        assertEquals(TerrainBudget.MAX_MESH_BYTES, TerrainBudget.configuredLimit("512"));
        for (String invalid : new String[] {"7", "513", "0", "-1", "16.5", "", "2147483648"}) {
            try {
                TerrainBudget.configuredLimit(invalid);
                fail("Accepted invalid mesh budget " + invalid);
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test(expected = IllegalStateException.class) public void refusesDoubleRetirement() {
        TerrainBudget budget = new TerrainBudget(1000);
        budget.reserve(1000);
        budget.retire(700);
        budget.retire(400);
    }


    @Test(expected = IllegalStateException.class) public void refusesDoubleRetiredRelease() {
        TerrainBudget budget = new TerrainBudget(1000);
        budget.reserve(700);
        budget.retire(700);
        budget.releaseRetired(700);
        budget.releaseRetired(1);
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The counter under test is process-global, so every build a test begins is ended in a
 * {@code finally} with the state it latched: a failing assertion must not leak a count
 * into the tests that follow.
 */
class VanillaSectionBuildsTest {

    @Test
    void vanillaBuildIsCountedUntilItEnds() {
        assertFalse(VanillaSectionBuilds.anyInFlight());
        boolean capturing = VanillaSectionBuilds.beginBuild(() -> false);
        try {
            assertFalse(capturing);
            assertTrue(VanillaSectionBuilds.anyInFlight());
        } finally {
            VanillaSectionBuilds.endBuild(capturing);
        }
        assertFalse(VanillaSectionBuilds.anyInFlight());
    }

    @Test
    void capturingBuildIsNotCounted() {
        boolean capturing = VanillaSectionBuilds.beginBuild(() -> true);
        try {
            assertTrue(capturing);
            assertFalse(VanillaSectionBuilds.anyInFlight());
        } finally {
            VanillaSectionBuilds.endBuild(capturing);
        }
        assertFalse(VanillaSectionBuilds.anyInFlight());
    }

    @Test
    void buildIsCountedBeforeItReadsTheGate() {
        boolean[] countedWhenGateRead = new boolean[1];
        boolean capturing = VanillaSectionBuilds.beginBuild(() -> {
            countedWhenGateRead[0] = VanillaSectionBuilds.anyInFlight();
            return true;
        });
        try {
            assertTrue(countedWhenGateRead[0], "the gate must be read with the count already raised");
        } finally {
            VanillaSectionBuilds.endBuild(capturing);
        }
        assertFalse(VanillaSectionBuilds.anyInFlight());
    }

    @Test
    void overlappingVanillaBuildsAreCountedIndividually() {
        boolean first = VanillaSectionBuilds.beginBuild(() -> false);
        boolean second = VanillaSectionBuilds.beginBuild(() -> false);
        try {
            VanillaSectionBuilds.endBuild(first);
            assertTrue(VanillaSectionBuilds.anyInFlight(), "the second build is still running");
        } finally {
            VanillaSectionBuilds.endBuild(second);
        }
        assertFalse(VanillaSectionBuilds.anyInFlight());
    }
}

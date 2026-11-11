/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Truth table for whether a held item's lit element is burning. */
class HeldLightPolicyTest {

    private static final boolean LOCAL = true;
    private static final boolean REMOTE = false;
    private static final boolean OFF = true;
    private static final boolean ON = false;
    private static final boolean FLAME = true;
    private static final boolean GLOW = false;
    private static final boolean UNDER = true;
    private static final boolean DRY = false;

    @Test
    void everythingBurnsByDefault() {
        assertTrue(HeldLightPolicy.isLit(LOCAL, ON, FLAME, DRY, false));
        assertTrue(HeldLightPolicy.isLit(LOCAL, ON, GLOW, DRY, false));
        assertTrue(HeldLightPolicy.isLit(REMOTE, ON, FLAME, DRY, false));
    }

    @Test
    void theSwitchPutsOutOnlyTheLocalPlayersHands() {
        assertFalse(HeldLightPolicy.isLit(LOCAL, OFF, FLAME, DRY, false));
        assertFalse(HeldLightPolicy.isLit(LOCAL, OFF, GLOW, DRY, false));
        // No packet carries the switch, so another player's torch stays lit.
        assertTrue(HeldLightPolicy.isLit(REMOTE, OFF, FLAME, DRY, false));
        assertTrue(HeldLightPolicy.isLit(REMOTE, OFF, GLOW, DRY, false));
    }

    @Test
    void waterPutsOutOpenFlamesInAnyHand() {
        assertFalse(HeldLightPolicy.isLit(LOCAL, ON, FLAME, UNDER, false));
        assertFalse(HeldLightPolicy.isLit(REMOTE, ON, FLAME, UNDER, false));
        // A glowing block is not a flame: a sea lantern keeps shining.
        assertTrue(HeldLightPolicy.isLit(LOCAL, ON, GLOW, UNDER, false));
        assertTrue(HeldLightPolicy.isLit(REMOTE, ON, GLOW, UNDER, false));
    }

    @Test
    void allowingFlamesUnderwaterLiftsOnlyTheWaterRule() {
        assertTrue(HeldLightPolicy.isLit(LOCAL, ON, FLAME, UNDER, true));
        assertTrue(HeldLightPolicy.isLit(REMOTE, ON, FLAME, UNDER, true));
        assertFalse(HeldLightPolicy.isLit(LOCAL, OFF, FLAME, UNDER, true));
    }
}

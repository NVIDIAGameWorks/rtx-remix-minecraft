/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.logic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneRegistryTest {

    @Test
    void maxCombinesZonesSharingAChannel() {
        var reg = new ZoneRegistry();
        var za = ZoneDefinition.parse("rtx zone define a --center 0 0 0 --inner 5 --outer 20 --channel mc.zone.cave", 0, 0, 0);
        var zb = ZoneDefinition.parse("rtx zone define b --center 30 0 0 --inner 5 --outer 20 --channel mc.zone.cave", 0, 0, 0);
        reg.put(za);
        reg.put(zb);

        // Inside zone a's inner radius, outside zone b entirely.
        var weights = reg.channelWeights(0, 0, 0);
        assertEquals(1.0f, weights.get("mc.zone.cave"), 1e-6f);
        assertEquals(1, weights.size());

        // (12, 0, 0) sits in BOTH falloff bands with distinct partial weights
        // (a ~= 0.564, b ~= 0.049), so max is distinguishable from sum.
        var overlapping = reg.channelWeights(12, 0, 0);
        assertEquals(Math.max(za.weight(12, 0, 0), zb.weight(12, 0, 0)), overlapping.get("mc.zone.cave"), 1e-6f);
        assertTrue(overlapping.get("mc.zone.cave") < za.weight(12, 0, 0) + zb.weight(12, 0, 0));
        assertEquals(1, overlapping.size());
    }

    @Test
    void distinctChannelsReportSeparately() {
        var reg = new ZoneRegistry();
        reg.put(ZoneDefinition.parse("rtx zone define cave --center 0 0 0 --inner 5 --outer 10", 0, 0, 0));
        reg.put(ZoneDefinition.parse("rtx zone define ship --center 0 0 0 --inner 5 --outer 10", 0, 0, 0));
        var weights = reg.channelWeights(0, 0, 0);
        assertEquals(1.0f, weights.get("mc.zone.cave"), 1e-6f);
        assertEquals(1.0f, weights.get("mc.zone.ship"), 1e-6f);
    }

    @Test
    void putReplacesByNameAndRemoveClears() {
        var reg = new ZoneRegistry();
        assertNull(reg.put(ZoneDefinition.parse("rtx zone define cave --center 0 0 0 --inner 5 --outer 10", 0, 0, 0)));
        assertNotNull(reg.put(ZoneDefinition.parse("rtx zone define cave --center 500 0 0 --inner 5 --outer 10", 0, 0, 0)));
        assertEquals(1, reg.zones().size());
        assertEquals(0.0f, reg.channelWeights(0, 0, 0).get("mc.zone.cave"), 1e-6f); // moved away
        assertTrue(reg.remove("cave"));
        assertFalse(reg.remove("cave"));
        assertTrue(reg.zones().isEmpty());
        assertTrue(reg.channelWeights(0, 0, 0).isEmpty());
    }

    @Test
    void putAllPreservesOtherProducersAndUsesLastWriterByName() {
        var reg = new ZoneRegistry();
        var marker = ZoneDefinition.parse(
                "rtx zone define marker --center 0 0 0 --inner 5 --outer 10", 0, 0, 0);
        var oldCave = ZoneDefinition.parse(
                "rtx zone define cave --center 0 0 0 --inner 5 --outer 10", 0, 0, 0);
        var savedCave = ZoneDefinition.parse(
                "rtx zone define cave --center 100 0 0 --inner 5 --outer 10", 0, 0, 0);
        reg.put(marker);
        reg.put(oldCave);

        reg.putAll(List.of(savedCave));

        assertEquals(2, reg.zones().size(), "hydration must not clear command-block markers");
        assertEquals(0.0f, reg.channelWeights(0, 0, 0).get("mc.zone.cave"), 1e-6f);
        assertEquals(1.0f, reg.channelWeights(0, 0, 0).get("mc.zone.marker"), 1e-6f);

        reg.put(oldCave);
        assertEquals(1.0f, reg.channelWeights(0, 0, 0).get("mc.zone.cave"), 1e-6f,
                "a later same-name marker scan intentionally replaces the active projection");
    }
}

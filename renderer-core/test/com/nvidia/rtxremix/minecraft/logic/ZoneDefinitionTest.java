/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.logic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneDefinitionTest {

    @Test
    void parsesSphereZoneWithExplicitCenter() {
        var z = ZoneDefinition.parse("rtx zone define cave --center 305 15 105 --inner 25 --outer 60", 0, 0, 0);
        assertEquals("cave", z.name());
        assertEquals("mc.zone.cave", z.channel());
        assertFalse(z.usedDefaultRadii(), "explicit radii are not defaults");
        assertEquals(1.0f, z.weight(305, 15, 105), 1e-6f);          // at center
        assertEquals(1.0f, z.weight(305, 15, 105 + 24), 1e-6f);     // inside inner radius
        assertEquals(0.0f, z.weight(305, 15, 105 + 61), 1e-6f);     // outside outer radius
        float mid = z.weight(305, 15, 105 + 42.5f);                 // halfway through the band
        assertTrue(mid > 0.4f && mid < 0.6f, "expected ~0.5, got " + mid);
    }

    @Test
    void bareDefineDefaultsToInnerTenOuterThirty() {
        var z = ZoneDefinition.parse("rtx zone define poi", 100, 64, -20);
        assertTrue(z.usedDefaultRadii());
        assertEquals(10.0, z.innerRadius(), 1e-9);
        assertEquals(30.0, z.outerRadius(), 1e-9);
        assertEquals(1.0f, z.weight(100 + 10, 64, -20), 1e-6f);    // inside inner
        assertEquals(0.0f, z.weight(100 + 31, 64, -20), 1e-6f);    // outside outer
    }

    @Test
    void loneInnerImpliesOuterAcrossTheFadeBand() {
        var z = ZoneDefinition.parse("rtx zone define poi --inner 5", 0, 0, 0);
        assertTrue(z.usedDefaultRadii());
        assertEquals(5.0, z.innerRadius(), 1e-9);
        assertEquals(25.0, z.outerRadius(), 1e-9);
        assertEquals(1.0f, z.weight(5, 0, 0), 1e-6f);
        assertEquals(0.0f, z.weight(26, 0, 0), 1e-6f);
    }

    @Test
    void loneOuterImpliesInnerClampedToZero() {
        var z = ZoneDefinition.parse("rtx zone define poi --outer 15", 0, 0, 0);
        assertTrue(z.usedDefaultRadii());
        assertEquals(0.0, z.innerRadius(), 1e-9);                  // max(0, 15 - 20)
        assertEquals(15.0, z.outerRadius(), 1e-9);
        assertEquals(1.0f, z.weight(0, 0, 0), 1e-6f);
        assertEquals(0.0f, z.weight(16, 0, 0), 1e-6f);

        var wide = ZoneDefinition.parse("rtx zone define poi --outer 50", 0, 0, 0);
        assertEquals(30.0, wide.innerRadius(), 1e-9);              // 50 - 20, no clamp
    }

    @Test
    void validationStillFiresAfterDefaulting() {
        assertThrows(IllegalArgumentException.class,
                () -> ZoneDefinition.parse("rtx zone define bad --inner -5", 0, 0, 0));   // inner < 0
        assertThrows(IllegalArgumentException.class,
                () -> ZoneDefinition.parse("rtx zone define bad --outer 0", 0, 0, 0));    // inner 0 >= outer 0
    }

    @Test
    void leadingSlashAndBlockPositionDefaultCenter() {
        var z = ZoneDefinition.parse("/rtx zone define lamp --inner 5 --outer 10", 100.5, 64.5, -20.5);
        assertEquals(1.0f, z.weight(100.5, 64.5, -20.5), 1e-6f);
    }

    @Test
    void channelOverride() {
        var z = ZoneDefinition.parse("rtx zone define west_cave --inner 5 --outer 10 --channel mc.zone.cave", 0, 0, 0);
        assertEquals("mc.zone.cave", z.channel());
    }

    @Test
    void parsesBoxZoneWithFade() {
        var z = ZoneDefinition.parse("rtx zone define hall --box 0 0 0 10 10 10 --fade 5", 0, 0, 0);
        assertFalse(z.usedDefaultFade(), "explicit fade is not a default");
        assertEquals(1.0f, z.weight(5, 5, 5), 1e-6f);     // inside
        assertEquals(0.0f, z.weight(5, 5, 16), 1e-6f);    // 6 blocks outside > fade
        float half = z.weight(5, 5, 12.5f);               // 2.5 outside of a 5-block fade
        assertTrue(half > 0.4f && half < 0.6f, "expected ~0.5, got " + half);
    }

    @Test
    void boxFadeDefaultsToTwenty() {
        var z = ZoneDefinition.parse("rtx zone define hall --box 0 0 0 10 10 10", 0, 0, 0);
        assertTrue(z.usedDefaultFade());
        assertEquals(20.0, z.fade(), 1e-9);
        assertEquals(1.0f, z.weight(5, 5, 5), 1e-6f);     // inside
        assertEquals(0.0f, z.weight(5, 5, 31), 1e-6f);    // 21 blocks outside > default fade
        float mid = z.weight(5, 5, 20);                   // 10 outside of a 20-block fade
        assertTrue(mid > 0.4f && mid < 0.6f, "expected ~0.5, got " + mid);
    }

    @Test
    void weightIsMonotonicAcrossTheBand() {
        var z = ZoneDefinition.parse("rtx zone define m --inner 10 --outer 20", 0, 0, 0);
        float prev = 1.0f;
        for (double d = 10; d <= 20; d += 0.5) {
            float w = z.weight(d, 0, 0);
            assertTrue(w <= prev + 1e-6f, "weight must not increase with distance");
            prev = w;
        }
    }

    @ParameterizedTest
    @CsvSource({
            "rtx zone define bad --box 0 0 0 10 10 10 --fade 0",
            "say hello",
            "rtx zone define",
            "rtx zone define x --outter 10",
            "rtx zone define x --inner",
            "rtx zone define x --inner 10 --outer 10",
            "rtx zone define n --inner 5 --outer NaN",
            "rtx zone define f --box 0 0 0 10 10 10 --fade Infinity",
            "rtx zone define s --inner 5 --outer 10 --fade 3",
            "rtx zone define b --center 1 2 3 --box 0 0 0 10 10 10 --fade 5",
            "rtx zone define d --inner 5 --inner 6 --outer 10",
            "rtx zone define c --inner 5 --outer 10 --channel --fade"
    })
    void rejectsMalformedSpecs(String spec) {
        assertThrows(IllegalArgumentException.class,
                () -> ZoneDefinition.parse(spec, 0, 0, 0));
    }

    @Test
    void reversedBoxCornersNormalize() {
        var z = ZoneDefinition.parse("rtx zone define r --box 10 10 10 0 0 0 --fade 5", 0, 0, 0);
        assertEquals(1.0f, z.weight(5, 5, 5), 1e-6f);
    }

    @Test
    void boxDistanceIsEuclideanAtDiagonalCorners() {
        var z = ZoneDefinition.parse("rtx zone define d --box 0 0 0 10 10 10 --fade 5", 0, 0, 0);
        assertEquals(0.0f, z.weight(13, 13, 13), 1e-6f);   // sqrt(27) ~ 5.196 > fade
        float w = z.weight(12, 12, 12);                    // sqrt(12) ~ 3.464 outside -> smoothstep(~0.307)
        assertTrue(w > 0.15f && w < 0.30f, "expected ~0.223, got " + w);
    }
}

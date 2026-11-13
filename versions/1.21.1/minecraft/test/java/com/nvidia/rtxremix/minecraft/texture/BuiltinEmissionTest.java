/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceLocation;

class BuiltinEmissionTest {

    /** Centre of texel (col, row) of a 16x16 layout, in sprite UV space. */
    private static float texel(int index) {
        return (index + 0.5f) / 16f;
    }

    @Test
    void seaPickleBurnsAtTheTipAndFadesOutWithinTheSprout() {
        BuiltinEmission.Mask m = BuiltinEmission.SEA_PICKLE;
        float tip = m.factor(texel(1), texel(0));
        float second = m.factor(texel(2), texel(1));
        float third = m.factor(texel(13), texel(2));
        float fourth = m.factor(texel(14), texel(3));

        assertTrue(tip > 0.7f * BuiltinEmission.SEA_PICKLE_TIP_PEAK, "the tip row is hot: " + tip);
        assertTrue(tip > second && second > third && third > fourth, "monotonic fade down the sprout");
        assertTrue(second > 0.25f * tip, "the fade is gradual, not a single bright texel: " + second);
        assertTrue(fourth < 0.05f, "nearly out by the fourth row: " + fourth);
        assertEquals(0f, m.factor(texel(1), texel(4)), "the sprout's bottom row is dark");
    }

    @Test
    void seaPickleBodyStaysDark() {
        BuiltinEmission.Mask m = BuiltinEmission.SEA_PICKLE;
        // Body side faces read rows 5 to 11 across the whole width; the top face, rim and
        // opening read columns 4 to 12 of rows 1 to 5. None of it glows.
        for (int col = 0; col < 16; col++) {
            assertEquals(0f, m.factor(texel(col), texel(8)), "body column " + col);
        }
        for (int col = 4; col < 12; col++) {
            assertEquals(0f, m.factor(texel(col), texel(2)), "top face column " + col);
        }
    }

    @Test
    void onlyListedSpritesGetAMask() {
        assertSame(BuiltinEmission.SEA_PICKLE, BuiltinEmission.mask(ResourceLocation.withDefaultNamespace("block/sea_pickle")));
        assertNull(BuiltinEmission.mask(ResourceLocation.withDefaultNamespace("block/lantern")));
    }

    @Test
    void gainAppliesToLanternsOnly() {
        assertEquals(2.5f, BuiltinEmission.gain(ResourceLocation.withDefaultNamespace("block/lantern")));
        assertEquals(2.5f, BuiltinEmission.gain(ResourceLocation.withDefaultNamespace("block/soul_lantern")));
        assertEquals(1.0f, BuiltinEmission.gain(ResourceLocation.withDefaultNamespace("block/torch")));
    }
}

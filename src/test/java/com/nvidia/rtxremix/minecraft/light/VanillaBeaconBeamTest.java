/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VanillaBeaconBeamTest {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int RED = 0xFFB02E26;
    private static final int PURPLE = 0xFF6A3A88;

    @Test
    void anUntintedBeamIsLitFromTheTopFaceToTheCap() {
        // Vanilla's single segment counts the beacon block itself.
        assertEquals(
                List.of(new VanillaBeaconBeam.Span(VanillaBeaconBeam.LIGHT_BOTTOM, VanillaBeaconBeam.MAX_LIGHT_TOP, WHITE)),
                VanillaBeaconBeam.spans(new int[] {1}, new int[] {WHITE}));
    }

    @Test
    void eachTintedSegmentKeepsItsOwnStretchAndColour() {
        assertEquals(
                List.of(
                        new VanillaBeaconBeam.Span(1, 2, WHITE),
                        new VanillaBeaconBeam.Span(2, 6, RED),
                        new VanillaBeaconBeam.Span(6, VanillaBeaconBeam.MAX_LIGHT_TOP, PURPLE)),
                VanillaBeaconBeam.spans(new int[] {2, 4, 3}, new int[] {WHITE, RED, PURPLE}));
    }

    @Test
    void theLastSegmentReachesTheCapWhateverItsOwnHeight() {
        // Vanilla stretches the last segment to MAX_BEAM_HEIGHT however tall it measured.
        List<VanillaBeaconBeam.Span> spans = VanillaBeaconBeam.spans(new int[] {1, 1}, new int[] {WHITE, RED});

        assertEquals(VanillaBeaconBeam.MAX_LIGHT_TOP, spans.get(spans.size() - 1).top());
    }

    @Test
    void segmentsAboveTheCapCarryNoLight() {
        int belowCap = VanillaBeaconBeam.MAX_LIGHT_TOP - 1;

        assertEquals(
                List.of(new VanillaBeaconBeam.Span(1, VanillaBeaconBeam.MAX_LIGHT_TOP, WHITE)),
                VanillaBeaconBeam.spans(new int[] {belowCap + 10, 5}, new int[] {WHITE, RED}));
    }

    @Test
    void aSegmentInsideTheBeaconBlockCarriesNoLight() {
        // The core rule lights the block, so the beam's own light starts above it.
        assertEquals(
                List.of(new VanillaBeaconBeam.Span(1, VanillaBeaconBeam.MAX_LIGHT_TOP, RED)),
                VanillaBeaconBeam.spans(new int[] {1, 3}, new int[] {WHITE, RED}));
    }
}

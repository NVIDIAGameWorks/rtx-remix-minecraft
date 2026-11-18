/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins how vanilla's painter's order for text becomes depth. Glyph cells overlap their
 * neighbours' ink with transparent padding and glowing text stacks outline copies under
 * the ink, so a later quad must sit in front of every earlier one and a later text layer
 * in front of everything the layer below could hold.
 */
class RemixEntitySubmitterTextDepthTest {

    private static final int MAX_QUADS_PER_LAYER = RemixCapturingConsumer.MAX_VERTICES / 4;

    @Test
    void laterQuadsOfATextLayerSitInFront() {
        float first = RemixEntitySubmitter.surfaceDepth(true, 1, 0);
        float second = RemixEntitySubmitter.surfaceDepth(true, 1, 1);
        float last = RemixEntitySubmitter.surfaceDepth(true, 1, MAX_QUADS_PER_LAYER - 1);

        assertTrue(second > first);
        assertTrue(last > second);
    }

    @Test
    void aLaterTextLayerSitsPastEveryQuadOfTheLayerBelow() {
        float lastOutlineQuad = RemixEntitySubmitter.surfaceDepth(true, 1, MAX_QUADS_PER_LAYER - 1);
        float firstInkQuad = RemixEntitySubmitter.surfaceDepth(true, 2, 0);

        assertTrue(firstInkQuad > lastOutlineQuad);
    }

    @Test
    void aQuadStepSurvivesAdditionToAGlyphCoordinate() {
        // Sign text spans roughly -45..45 pixels; a step that float addition rounds away
        // would leave neighbouring quads coplanar again.
        float step = RemixEntitySubmitter.surfaceDepth(true, 0, 1) - RemixEntitySubmitter.surfaceDepth(true, 0, 0);

        assertNotEquals(45f, 45f + step);
    }

    @Test
    void textStaysCloseToItsSurface() {
        // A glowing sign's ink is on the second text layer; two layers must stay well
        // inside the sign's own text offset scale of a few pixels.
        assertTrue(RemixEntitySubmitter.surfaceDepth(true, 2, MAX_QUADS_PER_LAYER - 1) < 2f);
    }

    @Test
    void otherPartsKeepTheirShellOffsetWhateverTheirQuadOrder() {
        assertEquals(RemixEntitySubmitter.surfaceDepth(false, 3, 0),
                RemixEntitySubmitter.surfaceDepth(false, 3, 200));
        assertTrue(RemixEntitySubmitter.surfaceDepth(false, 3, 0)
                > RemixEntitySubmitter.surfaceDepth(false, 2, 0));
    }
}

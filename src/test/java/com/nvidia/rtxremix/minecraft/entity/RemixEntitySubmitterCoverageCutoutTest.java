/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.texture.DynamicTextureCache;
import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.MapColor;
import com.nvidia.rtxremix.InstanceCategoryBit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the material and instance classification used for map coverage textures and
 * the particle atlas.
 */
class RemixEntitySubmitterCoverageCutoutTest {

    private static final Identifier MAP_TEXTURE = Identifier.fromNamespaceAndPath("minecraft", "dynamic/map/42_1");

    @Test
    void dynamicMapsCutAtHalfCoverage() {
        assertEquals(128, RemixEntitySubmitter.cutoutAlphaReferenceValue(MAP_TEXTURE));
    }

    @Test
    void mapDecorationsCutAtHalfCoverage() {
        assertEquals(128, RemixEntitySubmitter.cutoutAlphaReferenceValue(
                Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/map_decorations.png")));
    }

    @Test
    void mapDecorationsAreAlreadyOffsetDecals() {
        assertEquals(InstanceCategoryBit.DECAL_NO_OFFSET,
                RemixEntitySubmitter.additionalCategoryFlagsForTexture(
                        Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/map_decorations.png")));
        assertEquals(0, RemixEntitySubmitter.additionalCategoryFlagsForTexture(MAP_TEXTURE));
    }

    @Test
    void particleAtlasPartsAreParticles() {
        assertEquals(InstanceCategoryBit.PARTICLE,
                RemixEntitySubmitter.additionalCategoryFlagsForTexture(
                        Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/particles.png")));
    }

    @Test
    void glyphPagesCutAtHalfCoverage() {
        Identifier page = Identifier.fromNamespaceAndPath("minecraft", "default/7");
        DynamicTextureCache.onRegisterGlyphPage(page, new GlyphPageMirror(false));

        assertEquals(128, RemixEntitySubmitter.cutoutAlphaReferenceValue(page));
    }

    @Test
    void vanillaMapPaletteHasBinaryAlpha() {
        for (int color = 0; color <= 255; color++) {
            int alpha = MapColor.getColorFromPackedId(color) >>> 24;
            assertTrue(alpha == 0 || alpha == 255, "map color " + color + " has alpha " + alpha);
        }
    }

    @Test
    void otherTexturesShowAnyTexelWithAlpha() {
        assertEquals(1, RemixEntitySubmitter.cutoutAlphaReferenceValue(
                Identifier.fromNamespaceAndPath("minecraft", "textures/entity/slime/slime.png")));
        assertEquals(1, RemixEntitySubmitter.cutoutAlphaReferenceValue(
                Identifier.fromNamespaceAndPath("example", "dynamic/map/42_1")));
    }
}

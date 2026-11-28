/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;




class RemixEntitySubmitterAtlasSurfaceModeTest {

    private static final EntityTextureKey BLOCK_ATLAS =
            EntityTextureKey.of("minecraft", "textures/atlas/blocks.png");
    private static final EntityTextureKey ENTITY_TEXTURE =
            EntityTextureKey.of("minecraft", "textures/entity/creeper/creeper.png");

    @Test
    void aHeldTranslucentBlockKeepsItsTranslucentSurface() {
        assertEquals(EntitySurfaceMode.TRANSLUCENT,
                RemixEntitySubmitter.atlasSurfaceMode(BLOCK_ATLAS,
                        EntitySurfaceMode.TRANSLUCENT, BlockTextureManager.L_TRANS));
    }

    @Test
    void anOrdinaryItemOnTheTranslucentItemLayerFallsBackToCutout() {
        // Minecraft draws opaque items through its translucent item layer, so a
        // captured TRANSLUCENT on the atlas is not evidence of a translucent block.
        assertEquals(EntitySurfaceMode.CUTOUT,
                RemixEntitySubmitter.atlasSurfaceMode(BLOCK_ATLAS,
                        EntitySurfaceMode.TRANSLUCENT, EntityRenderContext.NO_BLOCK_ITEM));
        // Nor is an opaque block item drawn through it.
        assertEquals(EntitySurfaceMode.CUTOUT,
                RemixEntitySubmitter.atlasSurfaceMode(BLOCK_ATLAS,
                        EntitySurfaceMode.TRANSLUCENT, BlockTextureManager.L_SOLID));
    }

    @Test
    void aHeldClearGlassBlockTakesTheGlassSurfaceWhateverItWasCapturedAs() {
        // Vanilla draws clear glass through its cutout item layer, while terrain
        // gives the placed block a refracting layer of its own.
        for (EntitySurfaceMode captured : new EntitySurfaceMode[] {
                EntitySurfaceMode.CUTOUT, EntitySurfaceMode.TRANSLUCENT}) {
            assertEquals(EntitySurfaceMode.GLASS,
                    RemixEntitySubmitter.atlasSurfaceMode(BLOCK_ATLAS,
                            captured, BlockTextureManager.L_GLASS), captured.name());
        }
    }

    @Test
    void otherAtlasSurfaceModesAreLeftAlone() {
        for (EntitySurfaceMode mode : EntitySurfaceMode.values()) {
            if (mode == EntitySurfaceMode.TRANSLUCENT) {
                continue;
            }
            assertEquals(mode, RemixEntitySubmitter.atlasSurfaceMode(
                    BLOCK_ATLAS, mode, EntityRenderContext.NO_BLOCK_ITEM),
                    mode.name());
        }
    }

    @Test
    void texturesOtherThanTheAtlasAreNeverNarrowed() {
        // A genuinely translucent entity layer, a ghast or a slime, must keep it,
        // and an entity texture is never glass.
        assertEquals(EntitySurfaceMode.TRANSLUCENT,
                RemixEntitySubmitter.atlasSurfaceMode(ENTITY_TEXTURE,
                        EntitySurfaceMode.TRANSLUCENT, EntityRenderContext.NO_BLOCK_ITEM));
        assertEquals(EntitySurfaceMode.CUTOUT,
                RemixEntitySubmitter.atlasSurfaceMode(ENTITY_TEXTURE,
                        EntitySurfaceMode.CUTOUT, BlockTextureManager.L_GLASS));
    }

    @Test
    void aHeldBlockBorrowsTheAtlasMaterialItsPlacedCounterpartUses() {
        assertEquals(BlockTextureManager.L_TRANS,
                RemixEntitySubmitter.atlasLayerType(EntitySurfaceMode.TRANSLUCENT));
        assertEquals(BlockTextureManager.layerType("glass"),
                RemixEntitySubmitter.atlasLayerType(EntitySurfaceMode.GLASS));
        assertEquals(BlockTextureManager.L_CUTOUT,
                RemixEntitySubmitter.atlasLayerType(EntitySurfaceMode.CUTOUT));
        assertEquals(BlockTextureManager.L_CUTOUT,
                RemixEntitySubmitter.atlasLayerType(EntitySurfaceMode.OPAQUE));
    }
}

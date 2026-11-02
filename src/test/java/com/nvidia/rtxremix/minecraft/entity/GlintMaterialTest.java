/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlintMaterialTest {
    private static final Identifier GLINT = Identifier.of("minecraft", "textures/misc/enchanted_glint_item.png");

    @Test
    void glintAddsTextureColorWithoutUsingItsOpaqueAlphaAsCoverage() {
        MaterialInfoOpaqueEXT material = RemixEntitySubmitter.materialSurfaceState(GLINT,
                RenderLayerUtils.SurfaceMode.GLINT).build();

        assertEquals(1, material.blendType_hasvalue);
        assertEquals(MaterialInfoOpaqueEXT.BLEND_TYPE_COLOR_EMISSIVE, material.blendType_value,
                "glint uses source-color additive blending, not texture-alpha coverage");
        assertEquals(7, material.alphaTestType);
        assertEquals(0, material.useDrawCallAlphaState,
                "the ordinary texture-stage extension must not turn glint back into an opaque material");
        assertTrue(RenderLayerUtils.SurfaceMode.GLINT.emitsAlbedo());
        assertFalse(RemixEntitySubmitter.shouldAttachSubsurface(true, RenderLayerUtils.SurfaceMode.GLINT));
        assertEquals(RenderLayerUtils.SurfaceMode.GLINT, RemixEntitySubmitter.atlasSurfaceMode(
                BlockTextureManager.BLOCK_ATLAS_ID, RenderLayerUtils.SurfaceMode.GLINT, BlockTextureManager.L_GLASS),
                "the substrate's glass classification must not replace the enchantment blend");
    }

    @Test
    void ordinaryItemTexturesKeepTheirCutoutCoverage() {
        MaterialInfoOpaqueEXT material = RemixEntitySubmitter.materialSurfaceState(
                Identifier.of("minecraft", "textures/item/golden_pickaxe.png"), RenderLayerUtils.SurfaceMode.CUTOUT).build();

        assertEquals(0, material.blendType_hasvalue);
        assertEquals(4, material.alphaTestType);
        assertFalse(RenderLayerUtils.SurfaceMode.CUTOUT.emitsAlbedo());
    }

    @Test
    void existingEmissiveLayersKeepTheirAlphaBasedBlend() {
        MaterialInfoOpaqueEXT material = RemixEntitySubmitter.materialSurfaceState(GLINT,
                RenderLayerUtils.SurfaceMode.EMISSIVE).build();

        assertEquals(1, material.blendType_hasvalue);
        assertEquals(MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA_EMISSIVE, material.blendType_value);
        assertTrue(RenderLayerUtils.SurfaceMode.EMISSIVE.emitsAlbedo());
    }
}

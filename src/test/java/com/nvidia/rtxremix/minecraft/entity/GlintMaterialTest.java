/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePurpose;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class GlintMaterialTest {
    private static final Identifier GLINT = Identifier.of("minecraft", "textures/misc/enchanted_glint_item.png");

    @Test
    void glintMaterialRetainsVisibleEmissionInTheEntityLightingScale() {
        var textures = new RemixEntitySubmitter.EntityTextureSet();
        textures.albedo = new RemixTexture(1, 1, TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR, null) {
            @Override public long getHandle() { return 123; }
        };
        var glint = RemixEntitySubmitter.buildMaterialInfo(
                new RemixEntitySubmitter.EntityMaterialKey(GLINT, RenderLayerUtils.SurfaceMode.GLINT), textures);
        var ordinary = RemixEntitySubmitter.buildMaterialInfo(
                new RemixEntitySubmitter.EntityMaterialKey(GLINT, RenderLayerUtils.SurfaceMode.CUTOUT), textures);
        var luminous = RemixEntitySubmitter.buildMaterialInfo(
                new RemixEntitySubmitter.EntityMaterialKey(GLINT, RenderLayerUtils.SurfaceMode.EMISSIVE), textures);

        assertEquals(123, glint.albedoTexture);
        assertEquals(glint.albedoTexture, glint.emissiveTexture, "the purple texture supplies the shimmer color");
        assertEquals(300f, glint.emissiveIntensity, "glint must not disappear under the scene's daylight exposure");
        assertEquals(100f, luminous.emissiveIntensity, "ordinary emissive materials keep their existing scale");
        assertEquals(MaterialInfoOpaqueEXT.BLEND_TYPE_COLOR_EMISSIVE, glint.opaqueExt.blendType_value);
        assertEquals(0, ordinary.emissiveTexture, "an unenchanted item must not acquire the glow");
        assertEquals(0f, ordinary.emissiveIntensity);

        var custom = RemixEntitySubmitter.buildMaterialInfo(new RemixEntitySubmitter.EntityMaterialKey(
                Identifier.of("test", "textures/misc/custom_glint.png"), RenderLayerUtils.SurfaceMode.GLINT), textures);
        assertEquals(MaterialInfoOpaqueEXT.BLEND_TYPE_COLOR_EMISSIVE, custom.opaqueExt.blendType_value);
        assertEquals(100f, custom.emissiveIntensity,
                "the tuned brightness is for vanilla's dim glint textures, not a mod's own");
    }

    @Test
    void glintIgnoresModelTintAndFollowsAccessibilityStrength() {
        RemixEntitySubmitter submitter = new RemixEntitySubmitter(null, null);
        try {
            var full = submitter.blendExtFor(GLINT, RenderLayerUtils.SurfaceMode.GLINT, true,
                    0xFFFF0000, RemixEntitySubmitter.PatternCanvasRole.NONE);
            assertEquals(0xFFFFFFFF, full.tFactor);
            assertEquals(InstanceInfoBlendEXT.RT_TEXTURE_ARG_TFACTOR, full.textureColorArg2Source);
            submitter.setGlintStrength(0.5);
            var half = submitter.blendExtFor(GLINT, RenderLayerUtils.SurfaceMode.GLINT, false,
                    0xFF00FF00, RemixEntitySubmitter.PatternCanvasRole.NONE);
            assertEquals(0xFF808080, half.tFactor);
            submitter.setGlintStrength(0);
            var zero = submitter.blendExtFor(GLINT, RenderLayerUtils.SurfaceMode.GLINT, true,
                    0xFFFFFFFF, RemixEntitySubmitter.PatternCanvasRole.NONE);
            assertEquals(0xFF000000, zero.tFactor);
        } finally {
            submitter.close();
        }
    }

    @Test
    void baseLayerTextMeshKeysIncludePerQuadSpacing() {
        var lookup = new RemixEntitySubmitter.RigidMeshLookupKey();
        float worldStep = RemixEntitySubmitter.surfaceDepth(true, 0, 1);
        lookup.setLayer(GLINT, RenderLayerUtils.SurfaceMode.CUTOUT, 0, worldStep);
        lookup.setHashed(RenderLayerUtils.Topology.QUADS, 8, 123);
        var world = lookup.immutableCopy();
        float heldStep = worldStep * ViewModelSpace.layerSpacingScale(InstanceCategoryBit.VIEW_MODEL);
        lookup.setLayer(GLINT, RenderLayerUtils.SurfaceMode.CUTOUT, 0, heldStep);
        lookup.setHashed(RenderLayerUtils.Topology.QUADS, 8, 123);
        var held = lookup.immutableCopy();
        assertNotEquals(world, held);
        assertFalse(lookup.equals(world));
        assertTrue(lookup.equals(held));
        assertEquals(held.hashCode(), lookup.hashCode());
        float[] normal = new float[8], x = new float[8], y = new float[8], z = new float[8];
        java.util.Arrays.fill(normal, 1);
        RemixEntitySubmitter.textOffsets(x, y, normal, 0, 8, 0, heldStep, x, y, z);
        assertEquals(worldStep, z[4] * ViewModelSpace.SCALE, 1e-9f);
    }

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
    void viewModelGlintDepthCannotReuseTheThirdPersonMesh() {
        float worldDepth = RemixEntitySubmitter.meshLayerDepth(false, 1, 0);
        float viewDepth = RemixEntitySubmitter.meshLayerDepth(false, 1, InstanceCategoryBit.VIEW_MODEL);
        assertEquals(worldDepth, viewDepth * ViewModelSpace.SCALE, 1e-9f);
        var worldKey = new RemixEntitySubmitter.HashedMeshKey(GLINT, RenderLayerUtils.SurfaceMode.GLINT,
                RenderLayerUtils.Topology.QUADS, worldDepth, 4, 123);
        var lookup = new RemixEntitySubmitter.RigidMeshLookupKey();
        lookup.setLayer(GLINT, RenderLayerUtils.SurfaceMode.GLINT, viewDepth);
        lookup.setHashed(RenderLayerUtils.Topology.QUADS, 4, 123);
        var viewKey = lookup.immutableCopy();
        assertNotEquals(worldKey, viewKey);
        assertEquals(viewKey, lookup.immutableCopy());
        assertEquals(viewKey.hashCode(), lookup.hashCode());
        assertTrue(lookup.equals(viewKey));
        assertFalse(lookup.equals(worldKey));
    }

    @Test
    void ordinaryAndTextViewModelLayersKeepTheirSeparation() {
        for (boolean text : new boolean[] {false, true}) {
            float ordinary = RemixEntitySubmitter.meshLayerDepth(text, 2, 0);
            float held = RemixEntitySubmitter.meshLayerDepth(text, 2, InstanceCategoryBit.VIEW_MODEL);
            assertEquals(ordinary, held * ViewModelSpace.SCALE, 1e-8f);
            assertEquals(0, RemixEntitySubmitter.meshLayerDepth(text, 0, InstanceCategoryBit.VIEW_MODEL));
        }
    }

    @Test
    void anAdditiveAtlasMaterialIsOwnedWhileTerrainCoverageIsBorrowed() {
        assertFalse(RemixEntitySubmitter.isBorrowedMaterial(new RemixEntitySubmitter.EntityMaterialKey(
                BlockTextureManager.BLOCK_ATLAS_ID, RenderLayerUtils.SurfaceMode.GLINT)),
                "an owned glint variant must be destroyed on invalidation and reload");
        assertTrue(RemixEntitySubmitter.isBorrowedMaterial(new RemixEntitySubmitter.EntityMaterialKey(
                BlockTextureManager.BLOCK_ATLAS_ID, RenderLayerUtils.SurfaceMode.CUTOUT)));
        assertFalse(RemixEntitySubmitter.isBorrowedMaterial(new RemixEntitySubmitter.EntityMaterialKey(
                GLINT, RenderLayerUtils.SurfaceMode.GLINT)));
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

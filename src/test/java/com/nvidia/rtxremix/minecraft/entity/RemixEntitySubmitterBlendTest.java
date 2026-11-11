/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.resources.ResourceLocation;
import com.nvidia.rtxremix.minecraft.entity.RemixEntitySubmitter.PatternCanvasRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.nvidia.rtxremix.InstanceInfoBlendEXT.RT_TEXTURE_ARG_NONE;
import static com.nvidia.rtxremix.InstanceInfoBlendEXT.RT_TEXTURE_ARG_TEXTURE;
import static com.nvidia.rtxremix.InstanceInfoBlendEXT.RT_TEXTURE_ARG_TFACTOR;
import static com.nvidia.rtxremix.InstanceInfoBlendEXT.RT_TEXTURE_ARG_VERTEX_COLOR;
import static com.nvidia.rtxremix.InstanceInfoBlendEXT.RT_TEX_OP_MODULATE;
import static com.nvidia.rtxremix.InstanceInfoBlendEXT.RT_TEX_OP_SELECT_ARG1;
import static com.nvidia.rtxremix.InstanceInfoBlendEXT.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
import static com.nvidia.rtxremix.InstanceInfoBlendEXT.VK_BLEND_FACTOR_SRC_ALPHA;
import static com.nvidia.rtxremix.InstanceInfoBlendEXT.VK_COMPARE_OP_GREATER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins the instance blend state a tinted cuboid receives. Cuboid meshes are baked
 * white, so these extensions are the only place a dyed banner base, a pattern
 * decal or a coloured mob gets its colour from.
 */
class RemixEntitySubmitterBlendTest {

    private static final ResourceLocation BANNER_ATLAS =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/banner_patterns.png");
    private static final ResourceLocation CREEPER =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/creeper/creeper.png");
    private static final int WHITE = 0xFFFFFFFF;
    private static final int TINT = 0xFF3366CC;

    // The constructor registers the submitter as the static atlas-upload target, so
    // each test gets its own and closes it rather than leaving one reachable.
    private RemixEntitySubmitter submitter;

    @BeforeEach
    void createSubmitter() {
        submitter = new RemixEntitySubmitter(null, null);
    }

    @AfterEach
    void closeSubmitter() {
        submitter.close();
    }

    @Test
    void theBaseIsOpaqueTextureTimesTintWithTheTexturesOwnAlpha() {
        InstanceInfoBlendEXT ext = RemixEntitySubmitter.canvasBaseBlend(TINT);

        assertEquals(RT_TEXTURE_ARG_TEXTURE, ext.textureColorArg1Source);
        assertEquals(RT_TEXTURE_ARG_TFACTOR, ext.textureColorArg2Source);
        assertEquals(RT_TEX_OP_MODULATE, ext.textureColorOperation);
        // Coverage comes from the sprite alone; a tint with alpha must not thin the cloth.
        assertEquals(RT_TEXTURE_ARG_TEXTURE, ext.textureAlphaArg1Source);
        assertEquals(RT_TEXTURE_ARG_NONE, ext.textureAlphaArg2Source);
        assertEquals(RT_TEX_OP_SELECT_ARG1, ext.textureAlphaOperation);
        assertEquals(0, ext.alphaBlendEnabled);
        assertEquals(0, ext.alphaTestEnabled);
        assertEquals(TINT, ext.tFactor);
    }

    @Test
    void thePatternStackBlendsOverTheBaseThroughTextureAlphaWithItsTintsInTheVertices() {
        // A canvas's patterns are baked into one mesh in paint order, each layer
        // carrying its dye as vertex colour, so the stack takes the vertex-colour
        // blend that hashed canvas parts always took.
        InstanceInfoBlendEXT ext = RemixEntitySubmitter.BANNER_PATTERN_BLEND;

        assertEquals(RT_TEXTURE_ARG_TEXTURE, ext.textureColorArg1Source);
        assertEquals(RT_TEXTURE_ARG_VERTEX_COLOR, ext.textureColorArg2Source);
        assertEquals(RT_TEX_OP_MODULATE, ext.textureColorOperation);
        // Coverage comes from the sprite alone; a dye's alpha must not thin a pattern.
        assertEquals(RT_TEXTURE_ARG_TEXTURE, ext.textureAlphaArg1Source);
        assertEquals(RT_TEX_OP_SELECT_ARG1, ext.textureAlphaOperation);
        assertEquals(1, ext.alphaBlendEnabled);
        assertEquals(VK_BLEND_FACTOR_SRC_ALPHA, ext.srcColorBlendFactor);
        assertEquals(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, ext.dstColorBlendFactor);
        assertEquals(1, ext.alphaTestEnabled);
        assertEquals(VK_COMPARE_OP_GREATER, ext.alphaTestCompareOp);
        assertEquals(1, ext.alphaTestReferenceValue);
    }

    @Test
    void aPatternCuboidTakesTheVertexColourBlendWhateverItsTint() {
        assertSame(RemixEntitySubmitter.BANNER_PATTERN_BLEND, submitter.blendExtFor(
                BANNER_ATLAS, RenderLayerUtils.SurfaceMode.TRANSLUCENT, true, TINT, PatternCanvasRole.PATTERN));
        assertSame(RemixEntitySubmitter.BANNER_PATTERN_BLEND, submitter.blendExtFor(
                BANNER_ATLAS, RenderLayerUtils.SurfaceMode.TRANSLUCENT, true, WHITE, PatternCanvasRole.PATTERN));
    }

    @Test
    void aPlainTintModulatesAlphaAsWellAsColour() {
        // The warden fade and wet wolves tint through alpha, not only colour.
        InstanceInfoBlendEXT ext = RemixEntitySubmitter.tintModulateBlend(TINT);

        assertEquals(RT_TEXTURE_ARG_TFACTOR, ext.textureColorArg2Source);
        assertEquals(RT_TEXTURE_ARG_TFACTOR, ext.textureAlphaArg2Source);
        assertEquals(RT_TEX_OP_MODULATE, ext.textureAlphaOperation);
        assertEquals(0, ext.alphaBlendEnabled);
        assertEquals(TINT, ext.tFactor);
    }

    @Test
    void aPlacedBeaconLensUsesItsInstanceTintInsteadOfTheGenericBlockAtlasBlend() {
        InstanceInfoBlendEXT lens = submitter.blendExtForPart(
                BlockTextureManager.BLOCK_ATLAS_ID,
                RenderLayerUtils.SurfaceMode.CUTOUT,
                true,
                TINT,
                PatternCanvasRole.NONE,
                true);

        assertEquals(RT_TEXTURE_ARG_TFACTOR, lens.textureColorArg2Source);
        assertEquals(TINT, lens.tFactor);
        assertSame(
                BlockTextureManager.ATLAS_CUTOUT_BLEND,
                submitter.blendExtForPart(
                        BlockTextureManager.BLOCK_ATLAS_ID,
                        RenderLayerUtils.SurfaceMode.CUTOUT,
                        true,
                        TINT,
                        PatternCanvasRole.NONE,
                        false));
    }

    @Test
    void theSameRoleAndTintReturnOneInstance() {
        // setBlendExt dirty-checks by reference, so a new object per frame would be a
        // scene-instance update per part per frame for a banner that never changes.
        InstanceInfoBlendEXT base = submitter.blendExtFor(
                BANNER_ATLAS, RenderLayerUtils.SurfaceMode.TRANSLUCENT, true, TINT, PatternCanvasRole.BASE);
        assertSame(base, submitter.blendExtFor(
                BANNER_ATLAS, RenderLayerUtils.SurfaceMode.TRANSLUCENT, true, TINT, PatternCanvasRole.BASE));

        InstanceInfoBlendEXT plain = submitter.blendExtFor(
                CREEPER, RenderLayerUtils.SurfaceMode.CUTOUT, true, TINT, PatternCanvasRole.NONE);
        assertSame(plain, submitter.blendExtFor(
                CREEPER, RenderLayerUtils.SurfaceMode.CUTOUT, true, TINT, PatternCanvasRole.NONE));
    }

    @Test
    void differentRolesWithOneTintAreDifferentInstances() {
        InstanceInfoBlendEXT base = submitter.blendExtFor(
                BANNER_ATLAS, RenderLayerUtils.SurfaceMode.TRANSLUCENT, true, TINT, PatternCanvasRole.BASE);
        InstanceInfoBlendEXT pattern = submitter.blendExtFor(
                BANNER_ATLAS, RenderLayerUtils.SurfaceMode.TRANSLUCENT, true, TINT, PatternCanvasRole.PATTERN);
        InstanceInfoBlendEXT plain = submitter.blendExtFor(
                CREEPER, RenderLayerUtils.SurfaceMode.CUTOUT, true, TINT, PatternCanvasRole.NONE);

        assertNotSame(base, pattern);
        assertNotSame(base, plain);
        assertNotSame(pattern, plain);
        assertEquals(0, base.alphaBlendEnabled);
        assertEquals(1, pattern.alphaBlendEnabled);
        assertEquals(RT_TEX_OP_MODULATE, plain.textureAlphaOperation);
    }

    @Test
    void aWhiteBaseStillTakesTheBaseBlend() {
        // The white shortcut is for parts that carry no tint at all; a white banner
        // base is still a canvas base and must keep its own alpha rule.
        InstanceInfoBlendEXT ext = submitter.blendExtFor(
                BANNER_ATLAS, RenderLayerUtils.SurfaceMode.TRANSLUCENT, true, WHITE, PatternCanvasRole.BASE);

        assertNotSame(RemixEntitySubmitter.VERTEX_COLOR_MODULATE, ext);
        assertEquals(RT_TEX_OP_SELECT_ARG1, ext.textureAlphaOperation);
        assertEquals(WHITE, ext.tFactor);
    }

    @Test
    void aHashedCanvasPartKeepsTheVertexColourBlend() {
        assertSame(RemixEntitySubmitter.BANNER_PATTERN_BLEND, submitter.blendExtFor(
                BANNER_ATLAS, RenderLayerUtils.SurfaceMode.TRANSLUCENT, false, TINT, PatternCanvasRole.BASE));
        assertSame(RemixEntitySubmitter.BANNER_PATTERN_BLEND, submitter.blendExtFor(
                BANNER_ATLAS, RenderLayerUtils.SurfaceMode.OPAQUE, false, WHITE, PatternCanvasRole.NONE));
    }

    @Test
    void frameCuboidsAndUntintedCuboidsModulateByVertexColour() {
        // A white mesh under vertex-colour modulate is the texture itself, so the
        // pole, the bar and every untinted mob part need no interned extension.
        assertSame(RemixEntitySubmitter.VERTEX_COLOR_MODULATE, submitter.blendExtFor(
                BANNER_ATLAS, RenderLayerUtils.SurfaceMode.OPAQUE, true, WHITE, PatternCanvasRole.FRAME));
        assertSame(RemixEntitySubmitter.VERTEX_COLOR_MODULATE, submitter.blendExtFor(
                CREEPER, RenderLayerUtils.SurfaceMode.CUTOUT, true, WHITE, PatternCanvasRole.NONE));
        assertSame(RemixEntitySubmitter.VERTEX_COLOR_MODULATE, submitter.blendExtFor(
                CREEPER, RenderLayerUtils.SurfaceMode.CUTOUT, false, TINT, PatternCanvasRole.NONE));
    }

    @Test
    void heldClearGlassBlendsAsTerrainBlendsPlacedGlass() {
        // Terrain buckets clear glass with its translucent pages, which take the
        // translucent blend, not the alpha test the atlas cutout material is paired with.
        assertSame(BlockTextureManager.ATLAS_TRANSLUCENT_BLEND, submitter.blendExtFor(
                BlockTextureManager.BLOCK_ATLAS_ID, RenderLayerUtils.SurfaceMode.GLASS,
                false, WHITE, PatternCanvasRole.NONE));
        assertSame(BlockTextureManager.ATLAS_CUTOUT_BLEND, submitter.blendExtFor(
                BlockTextureManager.BLOCK_ATLAS_ID, RenderLayerUtils.SurfaceMode.CUTOUT,
                false, WHITE, PatternCanvasRole.NONE));
    }
}

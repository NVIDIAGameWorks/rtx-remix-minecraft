/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.nvidia.rtxremix.core.terrain.TerrainMaterialSet;
import com.nvidia.rtxremix.core.terrain.TerrainRenderLayer;
import com.nvidia.rtxremix.core.terrain.TerrainTexturePolicy;
import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.MaterialInfoOpaqueSubsurfaceEXT;
import com.nvidia.rtxremix.MaterialInfoTranslucentEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixException;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePurpose;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterialStore;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.asset.RemixTextureStore;
import com.nvidia.rtxremix.minecraft.profiling.PerformanceTimer;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Per-sprite texture and material management for Minecraft's block atlas.
 *
 * <p>Each atlas sprite becomes an individual Remix material with [0,1] UV space.
 * LabPBR sprites (_n/_s variants) get full PBR textures including POM and SSS.
 * Non-PBR sprites get simple albedo + constant materials.
 */
public class BlockTextureManager implements TerrainTexturePolicy, TerrainMaterialSet {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlockTextureManager.class);
    /** Blend state for draw calls using the atlas cutout material, which sets useDrawCallAlphaState=true. */
    public static final InstanceInfoBlendEXT ATLAS_CUTOUT_BLEND =
            InstanceInfoBlendEXT.textureTimesVertexColorAlphaTest(128);
    /** Blend state for draw calls using the atlas translucent material, as terrain pairs its translucent pages. */
    public static final InstanceInfoBlendEXT ATLAS_TRANSLUCENT_BLEND =
            InstanceInfoBlendEXT.textureTimesVertexColor();
    private static final float POM_DISPLACE_IN = 0.25f;
    private static final float COMMON_GLASS_IOR = 1.52f;
    private static final float THIN_SSS_MEASUREMENT_DISTANCE = 0.75f;
    private static final int THIN_SSS_DEFAULT_THICKNESS = Math.round(THIN_SSS_MEASUREMENT_DISTANCE * 255.0f);
    private static final float DEFAULT_CUTOUT_SSS_TRANSMISSION_DISTANCE = THIN_SSS_MEASUREMENT_DISTANCE;
    private static final float DEFAULT_CUTOUT_SSS_ANISOTROPY = 0.0f;
    private static final float DEFAULT_CUTOUT_SSS_ALBEDO = 0.5f;
    public static final float DEFAULT_TERRAIN_EMISSION_MULTIPLIER = 1.0f;
    public static final float MAX_TERRAIN_EMISSION_MULTIPLIER = 50.0f;
    private static final float BASE_TERRAIN_EMISSION_INTENSITY = 100.0f;
    public static final int L_SOLID = TerrainRenderLayer.SOLID;
    public static final int L_CUTOUT = TerrainRenderLayer.CUTOUT;
    public static final int L_CUTOUT_MIPPED = TerrainRenderLayer.CUTOUT_MIPPED;
    public static final int L_TRANS = TerrainRenderLayer.TRANSLUCENT;
    public static final int L_GLASS = TerrainRenderLayer.GLASS;
    public static final int LAYER_COUNT = TerrainRenderLayer.COUNT;

    private static BlockTextureManager INSTANCE;

    public static BlockTextureManager get() { return INSTANCE; }

    private final RemixAPI api;
    private final AtlasSource source;
    private byte[] albedoRGBA;
    private RemixTexture atlasAlbedo;
    private RemixTexture atlasEmissive;
    private RemixTexture atlasNormalTex;
    private RemixTexture atlasHeightTex;
    private RemixTexture atlasRoughnessTex;
    private RemixTexture atlasMetallicTex;
    private RemixTexture atlasSssTex;
    private RemixTexture atlasSssAlbedoTex;
    private int atlasW, atlasH;
    private final List<SpriteEntry> sprites = new ArrayList<>();
    private final Map<Object, SpriteEntry> spriteById = new HashMap<>();
    private final Map<Long, RemixMaterial> materials = new HashMap<>();
    private final RemixMaterial[] atlasMaterials = new RemixMaterial[LAYER_COUNT];
    private final RemixMaterial[] atlasMaterialsSSS = new RemixMaterial[LAYER_COUNT];
    private final List<RemixTexture> managedTextures = new ArrayList<>();
    private final List<RemixMaterial> managedMaterials = new ArrayList<>();
    private RemixMaterial waterSurfaceMaterial;
    private RemixMaterial waterCameraMediumMaterial;
    private long waterNormalHandle;
    private boolean initialized, initFailed;
    private float cutoutSssTransmissionDistance = DEFAULT_CUTOUT_SSS_TRANSMISSION_DISTANCE;
    private float cutoutSssAnisotropy = DEFAULT_CUTOUT_SSS_ANISOTROPY;
    private float cutoutSssAlbedoR = DEFAULT_CUTOUT_SSS_ALBEDO;
    private float cutoutSssAlbedoG = DEFAULT_CUTOUT_SSS_ALBEDO;
    private float cutoutSssAlbedoB = DEFAULT_CUTOUT_SSS_ALBEDO;
    private boolean cutoutSssAlbedoTextureEnabled = true;
    private float terrainEmissionMultiplier = DEFAULT_TERRAIN_EMISSION_MULTIPLIER;
    private float appliedTerrainEmissionMultiplier = DEFAULT_TERRAIN_EMISSION_MULTIPLIER;
    private boolean terrainEmissionMaterialsInitialized;

    /** Per-sprite data: UV bounds (from Minecraft), texture handles, PBR flags. */
    public static final class SpriteEntry implements TerrainTexturePolicy.SpriteFacts {
        public final int index;
        public final Object id;
        public final String name;
        public final float u0, v0, u1, v1;
        public final boolean hasTransparency;
        final int pixW, pixH, pixX, pixY;
        private final BitSet transparentPixels;
        RemixTexture albedo, normal, roughness, metallic, height, emissive, sss, sssAlbedo;
        public boolean hasPBR, hasSSS;
        float[] lightColor;

        SpriteEntry(int i, SpriteData sprite) {
            this.index = i;
            this.id = sprite.id;
            this.name = sprite.name;
            u0 = sprite.u0;
            v0 = sprite.v0;
            u1 = sprite.u1;
            v1 = sprite.v1;
            pixX = sprite.x;
            pixY = sprite.y;
            pixW = sprite.width;
            pixH = sprite.height;
            transparentPixels = sprite.transparentPixels;
            hasTransparency = transparentPixels != null && !transparentPixels.isEmpty();
        }

        /**
         * Returns true if the atlas-space UV rectangle used by one quad samples
         * any transparent texel in this sprite.
         */
        public boolean hasTransparency(float minU, float minV, float maxU, float maxV) {
            if (!hasTransparency) return false;

            float uMin = clamp(Math.min(minU, maxU), u0, u1);
            float uMax = clamp(Math.max(minU, maxU), u0, u1);
            float vMin = clamp(Math.min(minV, maxV), v0, v1);
            float vMax = clamp(Math.max(minV, maxV), v0, v1);
            if (uMax <= uMin || vMax <= vMin) return true;

            int x0 = uvToMinTexel(uMin, u0, u1, pixW);
            int x1 = uvToMaxTexel(uMax, u0, u1, pixW);
            int y0 = uvToMinTexel(vMin, v0, v1, pixH);
            int y1 = uvToMaxTexel(vMax, v0, v1, pixH);
            if (x1 < x0 || y1 < y0) return false;

            for (int y = y0; y <= y1; y++) {
                int rowStart = y * pixW + x0;
                int rowEnd = y * pixW + x1;
                int transparent = transparentPixels.nextSetBit(rowStart);
                if (transparent >= 0 && transparent <= rowEnd) return true;
            }
            return false;
        }

        @Override
        public boolean hasTransparency() {
            return hasTransparency;
        }

        @Override
        public boolean hasSss() {
            return hasSSS;
        }

        private static int uvToMinTexel(float uv, float spriteMin, float spriteMax, int size) {
            float local = (uv - spriteMin) / (spriteMax - spriteMin) * size;
            return Math.max(0, Math.min(size - 1, (int) Math.floor(local + 1.0E-4f)));
        }

        private static int uvToMaxTexel(float uv, float spriteMin, float spriteMax, int size) {
            float local = (uv - spriteMin) / (spriteMax - spriteMin) * size;
            return Math.max(0, Math.min(size - 1, (int) Math.ceil(local - 1.0E-4f) - 1));
        }

        private static float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    public BlockTextureManager(RemixAPI api, AtlasSource source) {
        this.api = api;
        this.source = source;
        INSTANCE = this;
    }

    // ====================================================================
    //  Init
    // ====================================================================

    public void initialize() {
        if (initialized || initFailed) return;
        try {
            if (api == null || source == null) return;

            AtlasData atlas = source.load();
            if (atlas == null) return;

            captureAtlas(atlas);
            try (PerformanceTimer.ProfileScopeHandle ignored =
                         PerformanceTimer.beginProfileScope("LabPBR.variantScan")) {
                source.scanVariants();
            }
            buildSprites(atlas);
            if (!terrainEmissionMaterialsInitialized) {
                appliedTerrainEmissionMultiplier = terrainEmissionMultiplier;
            }
            buildAtlasMaterials();
            buildWaterMaterials();
            Map<String, AnimatedSpriteMaterialData> labPBRMap =
                    buildAnimatedSpriteLabPBRMap(atlas);
            Map<Object, String> spriteNames = new HashMap<>();
            for (SpriteEntry sprite : sprites) {
                spriteNames.put(sprite.id, sprite.name);
            }
            source.initializeAnimation(labPBRMap, spriteNames);

            initialized = true;
            terrainEmissionMaterialsInitialized = true;
        } catch (Exception e) {
            LOGGER.error("Init failed", e);
            initFailed = true;
        }
    }

    private void captureAtlas(AtlasData atlas) {
        atlasW = atlas.width;
        atlasH = atlas.height;
        if (atlasW == 0 || atlasH == 0) {
            LOGGER.warn("Atlas dimensions are zero");
            return;
        }
        albedoRGBA = atlas.albedoRgba;
        atlasAlbedo = upload(api, "block_atlas", albedoRGBA, atlasW, atlasH,
                TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR);
        LOGGER.info("Atlas {}x{} (stitched from {} sprites)",
                atlasW, atlasH, atlas.sprites.size());
    }

    // ====================================================================
    //  Per-sprite texture extraction
    // ====================================================================

    private void buildSprites(AtlasData atlas) {
        Map<String, Integer> lumMap = atlas.luminanceBySprite;
        int pbr = 0, emissiveCount = 0, idx = 0;

        for (SpriteData sprite : atlas.sprites) {
            SpriteEntry se = new SpriteEntry(idx++, sprite);
            byte[] alb = extractRGBA(se.pixX, se.pixY, se.pixW, se.pixH);
            Integer mappedLuminance = lumMap.get(se.name);
            int luminance = mappedLuminance == null ? 0 : mappedLuminance;

            ImageResource nI = source.loadVariant(se.id, "_n");
            ImageResource sI = source.loadVariant(se.id, "_s");
            ImageResource sssI = source.loadVariant(se.id, "_sss");
            try {
                if (nI != null) LabPBRConverter.applyAO(
                        alb, se.pixW, nI, 0, 0, se.pixW, se.pixH);
                se.albedo = upload(api, "s" + se.index + "_a", alb, se.pixW, se.pixH,
                        TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR);
                if (nI != null || sI != null || sssI != null) {
                    buildPBR(api, se, nI, sI, sssI, alb);
                    pbr++;
                }
                if (se.emissive != null) emissiveCount++;
                if (se.lightColor != null) {
                    spriteLightColors.put(se.name, se.lightColor);
                } else if (luminance > 0) {
                    spriteLightColors.put(se.name, computeLightColor(alb, null, se.pixW, se.pixH));
                }
            } finally {
                if (nI != null) nI.close();
                if (sI != null) sI.close();
                if (sssI != null) sssI.close();
            }
            sprites.add(se);
            spriteById.put(se.id, se);
        }
        LOGGER.info("{} sprites indexed ({} by id), {} with LabPBR, {} emissive",
                sprites.size(), spriteById.size(), pbr, emissiveCount);
    }

    // ====================================================================
    //  Animated-sprite LabPBR cache
    // ====================================================================

    /**
     * Builds per-animated-sprite LabPBR data tables so the atlas animation
     * forwarder can keep AO and animated _s material channels in phase with
     * each animated frame. Without this, animated albedo updates would
     * overwrite the AO baked into the atlas at init while roughness,
     * metallic, SSS, and emissive atlases stayed on the init-time sidecar
     * frame.
     *
     * <p>Only animated sprites with at least one of {@code _n} / {@code _s}
     * are cached. Non-LabPBR animated sprites are copied as albedo only.
     */
    private Map<String, AnimatedSpriteMaterialData> buildAnimatedSpriteLabPBRMap(
            AtlasData atlas) {
        Map<String, AnimatedSpriteMaterialData> result = new HashMap<>();

        for (SpriteData sprite : atlas.sprites) {
            AnimationData animation = sprite.animation;
            if (animation == null) continue;

            ImageResource nI = source.loadVariant(sprite.id, "_n");
            ImageResource sI = source.loadVariant(sprite.id, "_s");
            try {
                if (nI == null && sI == null) continue;
                byte[] aoTable = (nI != null)
                        ? LabPBRConverter.buildAOTable(nI)
                        : null;
                int aoW            = (nI != null) ? nI.width()                        : 0;
                int aoH            = (nI != null) ? nI.height()                       : 0;
                byte[] sAlphaTable = (sI != null)
                        ? LabPBRConverter.applyEmissionGain(
                                LabPBRConverter.buildSAlphaTable(sI),
                                BuiltinEmissionPolicy.gain(sprite.name))
                        : null;
                byte[] sRoughnessTable = (sI != null)
                        ? LabPBRConverter.buildSRoughnessTable(sI)
                        : null;
                byte[] sMetallicTable = (sI != null)
                        ? LabPBRConverter.buildSMetallicTable(sI)
                        : null;
                byte[] sSssTable = (sI != null)
                        ? LabPBRConverter.buildSSssTable(sI)
                        : null;
                int sW             = (sI != null) ? sI.width()                        : 0;
                int sH             = (sI != null) ? sI.height()                       : 0;
                boolean hasPerPixelEmissive = sI != null
                        && LabPBRConverter.hasEmissiveData(sI);
                boolean hasSss = sI != null
                        && LabPBRConverter.hasSSSData(sI);
                int variantW = (sI != null) ? sW : aoW;
                int frameColumns = Math.max(1, variantW / Math.max(1, sprite.width));
                result.put(sprite.name,
                    new AnimatedSpriteMaterialData(aoTable, aoW, aoH,
                                             sAlphaTable, sRoughnessTable, sMetallicTable, sSssTable,
                                             sW, sH, hasPerPixelEmissive, hasSss,
                                             animation.frameIndices,
                                             animation.frameTimes,
                                             frameColumns));
            } finally {
                if (nI != null) nI.close();
                if (sI != null) sI.close();
            }
        }
        LOGGER.info("Animated LabPBR cache: {} sprites", result.size());
        return result;
    }

    // ====================================================================
    //  Atlas-level PBR textures & materials
    // ====================================================================

    private void buildAtlasMaterials() {
        int pixels = atlasW * atlasH;

        byte[] normalAtlas    = new byte[pixels * 4];
        byte[] heightAtlas    = new byte[pixels];
        byte[] roughnessAtlas = new byte[pixels];
        byte[] metallicAtlas  = new byte[pixels];
        byte[] emissiveAtlas  = new byte[pixels * 4];
        byte[] sssThicknessAtlas = new byte[pixels * 4];
        byte[] sssAlbedoAtlas = LabPBRConverter.createSingleScatteringAlbedoFallback(albedoRGBA);

        Arrays.fill(heightAtlas, (byte) 255);
        Arrays.fill(roughnessAtlas, (byte) 180);
        fillRGBA(sssThicknessAtlas, THIN_SSS_DEFAULT_THICKNESS, THIN_SSS_DEFAULT_THICKNESS,
                THIN_SSS_DEFAULT_THICKNESS, 255);
        for (int i = 0; i < pixels; i++) {
            int idx4 = i * 4;
            normalAtlas[idx4]     = (byte) 128;
            normalAtlas[idx4 + 1] = (byte) 128;
            normalAtlas[idx4 + 2] = (byte) 255;
            normalAtlas[idx4 + 3] = (byte) 255;
        }

        boolean hasAnyNormal = false, hasAnyHeight = false, hasAnyEmissive = false, hasAnySSS = false;

        for (SpriteEntry se : sprites) {
            ImageResource nI = source.loadVariant(se.id, "_n");
            ImageResource sI = source.loadVariant(se.id, "_s");
            ImageResource sssI = source.loadVariant(se.id, "_sss");
            try {
                if (nI != null) {
                    LabPBRConverter.PixelImage nPixels = nI;
                    LabPBRConverter.blitNormal(normalAtlas, atlasW, nPixels, se.pixX, se.pixY, se.pixW, se.pixH);
                    LabPBRConverter.blitHeight(heightAtlas, atlasW, nPixels, se.pixX, se.pixY, se.pixW, se.pixH);
                    LabPBRConverter.applyAO(albedoRGBA, atlasW, nPixels, se.pixX, se.pixY, se.pixW, se.pixH);
                    LabPBRConverter.applyAO(sssAlbedoAtlas, atlasW, nPixels, se.pixX, se.pixY, se.pixW, se.pixH);
                    hasAnyNormal = true;
                    hasAnyHeight = true;
                }
                if (sI != null) {
                    LabPBRConverter.PixelImage sPixels = sI;
                    LabPBRConverter.blitRoughness(roughnessAtlas, atlasW, sPixels, se.pixX, se.pixY, se.pixW, se.pixH);
                    LabPBRConverter.blitMetallic(metallicAtlas, atlasW, sPixels, se.pixX, se.pixY, se.pixW, se.pixH);
                    if (LabPBRConverter.hasSSSData(sPixels)) {
                        LabPBRConverter.blitSSS(sssThicknessAtlas, atlasW, sPixels, se.pixX, se.pixY, se.pixW, se.pixH);
                        se.hasSSS = true;
                        hasAnySSS = true;
                    }
                }
                if (sssI != null) {
                    LabPBRConverter.blitSingleScatteringAlbedo(
                            sssAlbedoAtlas, atlasW, sssI,
                            se.pixX, se.pixY, se.pixW, se.pixH);
                    se.hasSSS = true;
                    hasAnySSS = true;
                }
                boolean needsEmissive = sI != null
                        && LabPBRConverter.hasEmissiveData(sI);
                if (needsEmissive) {
                    LabPBRConverter.blitEmissive(
                            emissiveAtlas, atlasW, albedoRGBA, sI,
                            se.pixX, se.pixY, se.pixW, se.pixH, 0f,
                            BuiltinEmissionPolicy.gain(se.name));
                    hasAnyEmissive = true;
                } else if (BuiltinEmissionPolicy.mask(se.name) != null) {
                    LabPBRConverter.blitEmissiveMask(emissiveAtlas, atlasW, albedoRGBA,
                            se.pixX, se.pixY, se.pixW, se.pixH,
                            BuiltinEmissionPolicy.mask(se.name));
                    hasAnyEmissive = true;
                }
            } finally {
                if (nI != null) nI.close();
                if (sI != null) sI.close();
                if (sssI != null) sssI.close();
            }
        }

        atlasAlbedo = upload(api, "atlas_albedo", albedoRGBA, atlasW, atlasH,
                TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR);

        RemixTexture normalTex    = hasAnyNormal ? upload(api, "atlas_normal", normalAtlas, atlasW, atlasH,
                TextureFormat.RGBA8_UNORM, TexturePurpose.NORMAL_DX) : null;
        RemixTexture heightTex    = hasAnyHeight ? upload(api, "atlas_height", heightAtlas, atlasW, atlasH,
                TextureFormat.R8_UNORM, TexturePurpose.HEIGHTMAP) : null;
        RemixTexture roughnessTex = upload(api, "atlas_roughness", roughnessAtlas, atlasW, atlasH,
                TextureFormat.R8_UNORM, TexturePurpose.DATA);
        RemixTexture metallicTex  = upload(api, "atlas_metallic", metallicAtlas, atlasW, atlasH,
                TextureFormat.R8_UNORM, TexturePurpose.DATA);
        RemixTexture emissiveTex  = hasAnyEmissive ? upload(api, "atlas_emissive", emissiveAtlas, atlasW, atlasH,
                TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR) : null;
        atlasEmissive = emissiveTex;
        RemixTexture sssTex       = hasAnySSS ? upload(api, "atlas_sss", sssThicknessAtlas, atlasW, atlasH,
                TextureFormat.RGBA8_UNORM, TexturePurpose.DATA) : null;
        RemixTexture sssAlbedoTex = hasAnySSS ? upload(api, "atlas_sss_albedo",
                sssAlbedoAtlas, atlasW, atlasH, TextureFormat.RGBA8_UNORM, TexturePurpose.DATA) : null;
        atlasNormalTex = normalTex;
        atlasHeightTex = heightTex;
        atlasRoughnessTex = roughnessTex;
        atlasMetallicTex = metallicTex;
        atlasSssTex = sssTex;
        atlasSssAlbedoTex = sssAlbedoTex;

        for (int lt = 0; lt < LAYER_COUNT; lt++) {
            if (lt == L_TRANS) {
                atlasMaterials[lt] = createAtlasTransMaterial(emissiveTex, normalTex);
            } else if (lt == L_GLASS) {
                atlasMaterials[lt] = createAtlasGlassMaterial(emissiveTex, normalTex);
            } else {
                atlasMaterials[lt] = createAtlasOpaqueMaterial(lt, normalTex, heightTex,
                        roughnessTex, metallicTex, emissiveTex, null, null);
                atlasMaterialsSSS[lt] = hasAnySSS ? createAtlasOpaqueMaterial(lt, normalTex, heightTex,
                        roughnessTex, metallicTex, emissiveTex,
                        sssTex, sssAlbedoTex) : null;
            }
        }
        int sssCount = 0;
        StringBuilder sssSprites = new StringBuilder();
        for (SpriteEntry se : sprites) {
            if (se.hasSSS) {
                sssCount++;
                int maxBlue = 0;
                for (int y = 0; y < se.pixH; y++)
                    for (int x = 0; x < se.pixW; x++) {
                        int idx = ((se.pixY + y) * atlasW + (se.pixX + x)) * 4;
                        maxBlue = Math.max(maxBlue, sssThicknessAtlas[idx] & 0xFF);
                    }
                if (sssSprites.length() < 1000)
                    sssSprites.append("\n  ").append(se.id).append(" (maxSSS=").append(maxBlue).append(')');
            }
        }
        LOGGER.info("Atlas materials created (normal={} height={} emissive={} sss={}, sssSprites={}{})",
                hasAnyNormal, hasAnyHeight, hasAnyEmissive, hasAnySSS, sssCount, sssSprites);
    }

    private RemixMaterial createAtlasOpaqueMaterial(int lt, RemixTexture normalTex,
                                               RemixTexture heightTex, RemixTexture roughnessTex,
                                               RemixTexture metallicTex, RemixTexture emissiveTex,
                                               RemixTexture sssTex,
                                               RemixTexture sssAlbedoTex) {
        return createMaterialViaStore(buildAtlasOpaqueMaterialInfo(
                lt, normalTex, heightTex, roughnessTex, metallicTex, emissiveTex,
                sssTex, sssAlbedoTex, terrainEmissionIntensity()));
    }

    private MaterialInfo buildAtlasOpaqueMaterialInfo(int lt, RemixTexture normalTex,
                                               RemixTexture heightTex, RemixTexture roughnessTex,
                                               RemixTexture metallicTex, RemixTexture emissiveTex,
                                               RemixTexture sssTex,
                                               RemixTexture sssAlbedoTex,
                                               float emissionIntensity) {
        MaterialInfoOpaqueEXT.Builder ob = MaterialInfoOpaqueEXT.builder()
                .albedoConstant(1f, 1f, 1f).roughnessConstant(1f).metallicConstant(1f)
                .useDrawCallAlphaState(true);
        if (lt != L_SOLID) ob.alphaTestType(4).alphaReferenceValue(128);
        if (roughnessTex != null) ob.roughnessTexture(roughnessTex.getHandle());
        if (metallicTex != null)  ob.metallicTexture(metallicTex.getHandle());
        if (heightTex != null)    ob.heightTexture(heightTex.getHandle()).displaceIn(POM_DISPLACE_IN);
        if (sssTex != null)       ob.subsurfaceExt(buildAtlasSSS(
                lt == L_SOLID, sssTex, sssAlbedoTex));

        MaterialInfo.Builder mb = MaterialInfo.builder()
                .albedoTexture(atlasAlbedo.getHandle()).filterMode(0);
        if (normalTex != null)   mb.normalTexture(normalTex.getHandle());
        if (emissiveTex != null) {
            mb.emissiveTexture(emissiveTex.getHandle())
                    .emissiveIntensity(emissionIntensity);
        }
        return mb.opaqueExt(ob.build()).build();
    }

    /**
     * Creates a cutout atlas material whose emission can be updated independently
     * without duplicating any atlas textures.
     */
    public RemixMaterial createEmissionScaledAtlasMaterial(float emissionMultiplier) {
        return createMaterialViaStore(emissionScaledAtlasMaterialInfo(emissionMultiplier));
    }

    /** Updates a material created by {@link #createEmissionScaledAtlasMaterial(float)}. */
    public void updateEmissionScaledAtlasMaterial(
            RemixMaterial material, float emissionMultiplier) {
        RemixMaterialStore.get().update(
                material, emissionScaledAtlasMaterialInfo(emissionMultiplier));
    }

    /** Releases a material created by {@link #createEmissionScaledAtlasMaterial(float)}. */
    public void destroyEmissionScaledAtlasMaterial(RemixMaterial material) {
        destroyManagedMaterial(material);
    }

    private MaterialInfo emissionScaledAtlasMaterialInfo(float emissionMultiplier) {
        return buildAtlasOpaqueMaterialInfo(
                L_CUTOUT,
                atlasNormalTex,
                atlasHeightTex,
                atlasRoughnessTex,
                atlasMetallicTex,
                atlasEmissive,
                null,
                null,
                scaledEmissionIntensity(
                        terrainEmissionIntensity(), emissionMultiplier));
    }

    static float scaledEmissionIntensity(
            float baseEmissionIntensity, float emissionMultiplier) {
        return baseEmissionIntensity * Math.max(0.0f, emissionMultiplier);
    }

    private MaterialInfoOpaqueSubsurfaceEXT buildAtlasSSS(boolean solid, RemixTexture sssTex,
                                                          RemixTexture sssAlbedoTex) {
        MaterialInfoOpaqueSubsurfaceEXT.Builder sb = MaterialInfoOpaqueSubsurfaceEXT.builder()
                .transmittanceTexture(atlasAlbedo.getHandle());
        if (solid) {
            RemixTexture radiusTexture = (sssAlbedoTex != null) ? sssAlbedoTex : atlasAlbedo;
            sb.diffusionProfile(true).radiusTexture(radiusTexture.getHandle())
              .radiusScale(LabPBRConverter.DIFFUSION_SSS_RADIUS_SCALE)
              .maxSampleRadius(LabPBRConverter.DIFFUSION_SSS_MAX_SAMPLE_RADIUS);
        } else {
            sb.diffusionProfile(false).thicknessTexture(sssTex.getHandle())
              .singleScatteringAlbedo(cutoutSssAlbedoR, cutoutSssAlbedoG, cutoutSssAlbedoB)
              .measurementDistance(cutoutSssTransmissionDistance)
              .volumetricAnisotropy(cutoutSssAnisotropy);
            if (cutoutSssAlbedoTextureEnabled && sssAlbedoTex != null) {
                sb.singleScatteringAlbedoTexture(sssAlbedoTex.getHandle());
            }
        }
        return sb.build();
    }


    private RemixMaterial createAtlasTransMaterial(RemixTexture emissiveTex, RemixTexture normalTex) {
        return createMaterialViaStore(buildAtlasTransMaterialInfo(
                emissiveTex, normalTex, terrainEmissionIntensity()));
    }

    private MaterialInfo buildAtlasTransMaterialInfo(
            RemixTexture emissiveTex, RemixTexture normalTex, float emissionIntensity) {
        MaterialInfo.Builder mb = MaterialInfo.builder()
                .albedoTexture(atlasAlbedo.getHandle()).filterMode(0);
        if (normalTex != null)   mb.normalTexture(normalTex.getHandle());
        if (emissiveTex != null) {
            mb.emissiveTexture(emissiveTex.getHandle())
                    .emissiveIntensity(emissionIntensity);
        }
        return mb.translucentExt(MaterialInfoTranslucentEXT.builder()
                .refractiveIndex(COMMON_GLASS_IOR).transmittanceColor(0.97f, 0.97f, 0.97f)
                .transmittanceTexture(atlasAlbedo.getHandle()).transmittanceMeasurementDistance(0.35f)
                .useDiffuseLayer(false).build()).build();
    }

    /**
     * Clear glass. Vanilla's glass texture is alpha 0 between its frame pixels, with
     * mid-grey RGB there, so the stained-glass material (transmittance from the
     * albedo) would make it grey. Here the glass itself is a constant near-clear
     * transmission and the albedo rides on top as a diffuse layer blended by its
     * alpha, which draws the frame and streaks opaque and leaves the rest to refract.
     */
    private RemixMaterial createAtlasGlassMaterial(RemixTexture emissiveTex, RemixTexture normalTex) {
        MaterialInfo.Builder mb = MaterialInfo.builder()
                .albedoTexture(atlasAlbedo.getHandle()).filterMode(0);
        if (normalTex != null) mb.normalTexture(normalTex.getHandle());
        if (emissiveTex != null) {
            mb.emissiveTexture(emissiveTex.getHandle())
                    .emissiveIntensity(terrainEmissionIntensity());
        }
        return createMaterialViaStore(mb.translucentExt(MaterialInfoTranslucentEXT.builder()
                .refractiveIndex(COMMON_GLASS_IOR).transmittanceColor(0.97f, 0.97f, 0.97f)
                .transmittanceMeasurementDistance(0.35f)
                .useDiffuseLayer(true).build()).build());
    }

    // ====================================================================
    //  Custom water material (animated normal flipbook, translucent)
    // ====================================================================

    private static final float WATER_TILE_SIZE = 10.0f;
    private static final float DEFAULT_WATER_IOR = 1.33f;
    private static final float DEFAULT_WATER_TRANSMITTANCE_R = 0.6f;
    private static final float DEFAULT_WATER_TRANSMITTANCE_G = 0.9f;
    private static final float DEFAULT_WATER_TRANSMITTANCE_B = 0.89f;
    private static final float DEFAULT_WATER_TRANSMITTANCE_DISTANCE = 1f;
    private static final float WATER_CAMERA_MEDIUM_TRANSMITTANCE = 0.98f;
    private static final float WATER_CAMERA_MEDIUM_TRANSMITTANCE_DISTANCE = 1f;
    private static final String WATER_WHITE_TEXTURE_NAME = "water_white";

    private RemixTexture waterWhiteTexture;
    private float waterIor = DEFAULT_WATER_IOR;
    private float waterTransmittanceR = DEFAULT_WATER_TRANSMITTANCE_R;
    private float waterTransmittanceG = DEFAULT_WATER_TRANSMITTANCE_G;
    private float waterTransmittanceB = DEFAULT_WATER_TRANSMITTANCE_B;
    private float waterTransmittanceDistance = DEFAULT_WATER_TRANSMITTANCE_DISTANCE;

    private void destroyManagedMaterial(RemixMaterial material) {
        if (material == null) {
            return;
        }
        managedMaterials.remove(material);
        if (!material.isDeleted()) {
            material.destroy();
        }
    }

    private void destroyManagedTexture(RemixTexture texture) {
        if (texture == null) {
            return;
        }
        managedTextures.remove(texture);
        if (!texture.isDeleted()) {
            texture.destroy();
        }
    }

    private void destroyWaterResources(RemixAPI api) {
        destroyManagedMaterial(waterSurfaceMaterial);
        waterSurfaceMaterial = null;
        destroyManagedMaterial(waterCameraMediumMaterial);
        waterCameraMediumMaterial = null;

        destroyManagedTexture(waterWhiteTexture);
        waterWhiteTexture = null;

        if (waterNormalHandle != 0) {
            try {
                api.destroyTexture(waterNormalHandle);
            } catch (Exception e) {
                LOGGER.warn("Failed to destroy water normal texture: {}", e.getMessage());
            }
            waterNormalHandle = 0;
        }
    }

    private void buildWaterMaterials() {
        boolean success = false;
        try {
            destroyWaterResources(api);

            waterNormalHandle = api.createTextureFromFile(source.waterNormalsPath());

            byte[] white = {(byte) 255, (byte) 255, (byte) 255, (byte) 255};
            waterWhiteTexture = upload(api, WATER_WHITE_TEXTURE_NAME, white, 1, 1,
                    TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR);
            if (waterWhiteTexture == null) {
                LOGGER.warn("Failed to create white texture for water material");
                return;
            }

            waterSurfaceMaterial = createMaterialViaStore(MaterialInfo.builder()
                    .albedoTexture(waterWhiteTexture.getHandle())
                    .normalTexture(waterNormalHandle)
                    .filterMode(1)
                    .spriteSheetRow(10)
                    .spriteSheetCol(12)
                    .spriteSheetFps(20)
                    .translucentExt(MaterialInfoTranslucentEXT.builder()
                            .refractiveIndex(waterIor)
                            .transmittanceColor(waterTransmittanceR, waterTransmittanceG, waterTransmittanceB)
                            .transmittanceMeasurementDistance(waterTransmittanceDistance)
                            .useDiffuseLayer(false)
                            .build())
                    .build());

            waterCameraMediumMaterial = createMaterialViaStore(MaterialInfo.builder()
                    .albedoTexture(waterWhiteTexture.getHandle())
                    .filterMode(1)
                    .translucentExt(MaterialInfoTranslucentEXT.builder()
                            .refractiveIndex(waterIor)
                            .transmittanceColor(
                                    WATER_CAMERA_MEDIUM_TRANSMITTANCE,
                                    WATER_CAMERA_MEDIUM_TRANSMITTANCE,
                                    WATER_CAMERA_MEDIUM_TRANSMITTANCE)
                            .transmittanceMeasurementDistance(WATER_CAMERA_MEDIUM_TRANSMITTANCE_DISTANCE)
                            .useDiffuseLayer(false)
                            .build())
                    .build());

            if (waterSurfaceMaterial != null && waterCameraMediumMaterial != null) {
                LOGGER.info("Water surface and camera-medium materials created "
                                + "(ior={}, surfaceTransmittance=({}, {}, {}), distance={}, "
                                + "cameraTransmittance={}, cameraDistance={}, tileSize={})",
                        waterIor, waterTransmittanceR, waterTransmittanceG, waterTransmittanceB,
                        waterTransmittanceDistance, WATER_CAMERA_MEDIUM_TRANSMITTANCE,
                        WATER_CAMERA_MEDIUM_TRANSMITTANCE_DISTANCE, WATER_TILE_SIZE);
                success = true;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to create water materials (falling back to atlas): {}", e.getMessage());
        } finally {
            if (!success) {
                destroyWaterResources(api);
            }
        }
    }

    public RemixMaterial getWaterSurfaceMaterial() { return waterSurfaceMaterial; }
    public RemixMaterial getWaterCameraMediumMaterial() { return waterCameraMediumMaterial; }
    public static float getWaterTileSize() { return WATER_TILE_SIZE; }
    public float getWaterIor() { return waterIor; }
    public float getWaterTransmittanceR() { return waterTransmittanceR; }
    public float getWaterTransmittanceG() { return waterTransmittanceG; }
    public float getWaterTransmittanceB() { return waterTransmittanceB; }
    public float getWaterTransmittanceDistance() { return waterTransmittanceDistance; }
    public float getCutoutSssTransmissionDistance() { return cutoutSssTransmissionDistance; }
    public float getCutoutSssAnisotropy() { return cutoutSssAnisotropy; }
    public float getCutoutSssAlbedoR() { return cutoutSssAlbedoR; }
    public float getCutoutSssAlbedoG() { return cutoutSssAlbedoG; }
    public float getCutoutSssAlbedoB() { return cutoutSssAlbedoB; }
    public boolean isCutoutSssAlbedoTextureEnabled() { return cutoutSssAlbedoTextureEnabled; }
    public float getTerrainEmissionMultiplier() { return terrainEmissionMultiplier; }
    public boolean hasCutoutSssAtlasMaterial() {
        return atlasMaterialsSSS[L_CUTOUT] != null || atlasMaterialsSSS[L_CUTOUT_MIPPED] != null;
    }

    public void setTerrainEmissionMultiplier(float multiplier) {
        terrainEmissionMultiplier = Math.max(
                0.0f, Math.min(MAX_TERRAIN_EMISSION_MULTIPLIER, multiplier));
    }

    public boolean commitTerrainEmissionMultiplier() {
        if (Float.compare(
                appliedTerrainEmissionMultiplier, terrainEmissionMultiplier) == 0) {
            return false;
        }
        appliedTerrainEmissionMultiplier = terrainEmissionMultiplier;
        return true;
    }

    float terrainEmissionIntensity() {
        return terrainEmissionIntensity(appliedTerrainEmissionMultiplier);
    }

    private static float terrainEmissionIntensity(float multiplier) {
        return BASE_TERRAIN_EMISSION_INTENSITY * multiplier;
    }

    public boolean updateWaterIor(float ior) {
        waterIor = ior;
        return rebuildWaterMaterials();
    }

    public boolean updateWaterTransmittanceColor(float r, float g, float b) {
        waterTransmittanceR = r;
        waterTransmittanceG = g;
        waterTransmittanceB = b;
        return rebuildWaterMaterials();
    }

    public boolean updateWaterTransmittanceDistance(float distance) {
        waterTransmittanceDistance = distance;
        return rebuildWaterMaterials();
    }

    public boolean updateCutoutSssTransmissionDistance(float distance) {
        cutoutSssTransmissionDistance = Math.max(0.0001f, distance);
        return rebuildCutoutSssMaterials();
    }

    public boolean updateCutoutSssAnisotropy(float anisotropy) {
        cutoutSssAnisotropy = Math.max(-1.0f, Math.min(1.0f, anisotropy));
        return rebuildCutoutSssMaterials();
    }

    public boolean updateCutoutSssAlbedo(float r, float g, float b) {
        cutoutSssAlbedoR = clamp01(r);
        cutoutSssAlbedoG = clamp01(g);
        cutoutSssAlbedoB = clamp01(b);
        return rebuildCutoutSssMaterials();
    }

    public boolean updateCutoutSssAlbedoTextureEnabled(boolean enabled) {
        cutoutSssAlbedoTextureEnabled = enabled;
        return rebuildCutoutSssMaterials();
    }

    public boolean resetCutoutSss() {
        cutoutSssTransmissionDistance = DEFAULT_CUTOUT_SSS_TRANSMISSION_DISTANCE;
        cutoutSssAnisotropy = DEFAULT_CUTOUT_SSS_ANISOTROPY;
        cutoutSssAlbedoR = DEFAULT_CUTOUT_SSS_ALBEDO;
        cutoutSssAlbedoG = DEFAULT_CUTOUT_SSS_ALBEDO;
        cutoutSssAlbedoB = DEFAULT_CUTOUT_SSS_ALBEDO;
        cutoutSssAlbedoTextureEnabled = true;
        return rebuildCutoutSssMaterials();
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private boolean rebuildCutoutSssMaterials() {
        if (api == null) {
            LOGGER.warn("Cannot rebuild cutout SSS materials: Remix API unavailable");
            return false;
        }
        if (!initialized || atlasAlbedo == null || atlasSssTex == null) {
            return false;
        }

        rebuildAtlasSssMaterial(L_CUTOUT);
        rebuildAtlasSssMaterial(L_CUTOUT_MIPPED);
        forgetCachedCutoutSpriteMaterials();

        LOGGER.info("Cutout SSS updated (transmissionDistance={}, anisotropy={}, albedo=({}, {}, {}), albedoTexture={})",
                cutoutSssTransmissionDistance, cutoutSssAnisotropy,
                cutoutSssAlbedoR, cutoutSssAlbedoG, cutoutSssAlbedoB,
                cutoutSssAlbedoTextureEnabled);
        return hasCutoutSssAtlasMaterial();
    }

    private void rebuildAtlasSssMaterial(int layerType) {
        destroyManagedMaterial(atlasMaterialsSSS[layerType]);
        atlasMaterialsSSS[layerType] = createAtlasOpaqueMaterial(layerType, atlasNormalTex,
                atlasHeightTex, atlasRoughnessTex, atlasMetallicTex, atlasEmissive, atlasSssTex,
                atlasSssAlbedoTex);
    }

    private void forgetCachedCutoutSpriteMaterials() {
        Iterator<Map.Entry<Long, RemixMaterial>> it = materials.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, RemixMaterial> entry = it.next();
            int layerType = (int) (entry.getKey() % LAYER_COUNT);
            if (layerType == L_CUTOUT || layerType == L_CUTOUT_MIPPED) {
                destroyManagedMaterial(entry.getValue());
                it.remove();
            }
        }
    }

    private boolean rebuildWaterMaterials() {
        if (api == null) {
            LOGGER.warn("Cannot rebuild water materials: Remix API unavailable");
            return false;
        }
        buildWaterMaterials();
        return waterSurfaceMaterial != null && waterCameraMediumMaterial != null;
    }

    /** Returns the atlas-based material (no SSS) for the given layer type. */
    public RemixMaterial getAtlasMaterial(int layerType) {
        return atlasMaterials[layerType];
    }

    @Override
    public RemixMaterial atlasMaterial(int layerType) {
        return getAtlasMaterial(layerType);
    }

    @Override
    public RemixMaterial atlasSssMaterial(int layerType) {
        return getAtlasMaterialSSS(layerType);
    }

    @Override
    public RemixMaterial waterMaterial() {
        return getWaterSurfaceMaterial();
    }

    /** Returns the atlas-based SSS material for the given layer type, or null if none. */
    public RemixMaterial getAtlasMaterialSSS(int layerType) {
        return atlasMaterialsSSS[layerType];
    }

    private void buildPBR(RemixAPI api, SpriteEntry se,
                          ImageResource nI, ImageResource sI, ImageResource sssI,
                          byte[] alb) {
        int w = se.pixW, h = se.pixH;
        String p = "s" + se.index + "_";
        se.hasPBR = nI != null || sI != null;

        if (nI != null) {
            byte[] nd = new byte[w * h * 4], hd = new byte[w * h];
            LabPBRConverter.PixelImage pixels = nI;
            LabPBRConverter.blitNormal(nd, w, pixels, 0, 0, w, h);
            LabPBRConverter.blitHeight(hd, w, pixels, 0, 0, w, h);
            se.normal = upload(api, p + "n", nd, w, h, TextureFormat.RGBA8_UNORM, TexturePurpose.NORMAL_DX);
            se.height = upload(api, p + "h", hd, w, h, TextureFormat.R8_UNORM, TexturePurpose.HEIGHTMAP);
        }
        if (sI != null) {
            byte[] rd = new byte[w * h], md = new byte[w * h], sd = new byte[w * h * 4];
            LabPBRConverter.PixelImage pixels = sI;
            LabPBRConverter.blitRoughness(rd, w, pixels, 0, 0, w, h);
            LabPBRConverter.blitMetallic(md, w, pixels, 0, 0, w, h);
            LabPBRConverter.blitSSS(sd, w, pixels, 0, 0, w, h);
            se.roughness = upload(api, p + "r", rd, w, h, TextureFormat.R8_UNORM, TexturePurpose.DATA);
            se.metallic  = upload(api, p + "m", md, w, h, TextureFormat.R8_UNORM, TexturePurpose.DATA);
            if (LabPBRConverter.hasSSSData(pixels)) {
                se.sss = upload(api, p + "s", sd, w, h, TextureFormat.RGBA8_UNORM, TexturePurpose.DATA);
                se.hasSSS = true;
            }
        }
        if (sssI != null) {
            byte[] ssa = new byte[w * h * 4];
            LabPBRConverter.blitSingleScatteringAlbedo(
                    ssa, w, sssI, 0, 0, w, h);
            se.sssAlbedo = upload(api, p + "ssa", ssa, w, h, TextureFormat.RGBA8_UNORM, TexturePurpose.DATA);
            se.hasSSS = true;
        }
        boolean needsEmissive = sI != null
                && LabPBRConverter.hasEmissiveData(sI);
        if (needsEmissive) {
            byte[] ed = new byte[w * h * 4];
            LabPBRConverter.blitEmissive(
                    ed, w, alb, sI, 0, 0, w, h, 0f);
            se.emissive = upload(api, p + "e", ed, w, h, TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR);
            se.lightColor = computeLightColor(alb, ed, w, h);
        }
    }

    private byte[] extractRGBA(int sx, int sy, int sw, int sh) {
        byte[] out = new byte[sw * sh * 4];
        for (int y = 0; y < sh; y++)
            System.arraycopy(albedoRGBA, ((sy + y) * atlasW + sx) * 4, out, y * sw * 4, sw * 4);
        return out;
    }

    private static void fillRGBA(byte[] dst, int r, int g, int b, int a) {
        for (int i = 0; i < dst.length; i += 4) {
            dst[i]     = (byte) r;
            dst[i + 1] = (byte) g;
            dst[i + 2] = (byte) b;
            dst[i + 3] = (byte) a;
        }
    }

    // ====================================================================
    //  Sprite lookup + material factory
    // ====================================================================

    /** Finds a sprite by its content identifier (O(1) HashMap lookup). */
    public SpriteEntry getSpriteById(Object id) {
        return spriteById.get(id);
    }

    @Override
    public SpriteEntry sprite(Object resourceKey) {
        return getSpriteById(resourceKey);
    }

    @Override
    public boolean hasSssMaterial(int layerType) {
        return getAtlasMaterialSSS(layerType) != null;
    }

    /** Returns the material for a sprite + layer, creating lazily. */
    public RemixMaterial getMaterial(SpriteEntry se, int layerType) {
        if (se == null) return null;
        long key = (long) se.index * LAYER_COUNT + layerType;
        return materials.computeIfAbsent(key, k -> createMaterial(se, layerType));
    }

    private RemixMaterial createMaterial(SpriteEntry se, int lt) {
        if (api == null) return null;

        if (lt == L_TRANS) return createTransMaterial(api, se);
        if (lt == L_GLASS) return createGlassMaterial(se);

        MaterialInfoOpaqueEXT.Builder ob = MaterialInfoOpaqueEXT.builder()
                .albedoConstant(1f, 1f, 1f).useDrawCallAlphaState(true);
        if (lt != L_SOLID) ob.alphaTestType(4).alphaReferenceValue(128);

        MaterialInfo.Builder mb = MaterialInfo.builder()
                .albedoTexture(se.albedo.getHandle()).filterMode(0);

        if (se.hasPBR) {
            ob.roughnessConstant(1f).metallicConstant(1f);
            if (se.roughness != null) ob.roughnessTexture(se.roughness.getHandle());
            if (se.metallic != null)  ob.metallicTexture(se.metallic.getHandle());
            if (se.height != null)    ob.heightTexture(se.height.getHandle()).displaceIn(POM_DISPLACE_IN);
            if (se.normal != null)    mb.normalTexture(se.normal.getHandle());
        } else {
            ob.roughnessConstant(0.7f).metallicConstant(0f);
        }
        if (se.hasSSS) ob.subsurfaceExt(buildSSS(se, lt == L_SOLID));
        if (se.emissive != null)
            mb.emissiveTexture(se.emissive.getHandle()).emissiveIntensity(100.0f);

        return createMaterialViaStore(mb.opaqueExt(ob.build()).build());
    }

    private RemixMaterial createTransMaterial(RemixAPI api, SpriteEntry se) {
        MaterialInfo.Builder mb = MaterialInfo.builder()
                .albedoTexture(se.albedo.getHandle()).filterMode(0);
        if (se.normal != null)   mb.normalTexture(se.normal.getHandle());
        if (se.emissive != null) mb.emissiveTexture(se.emissive.getHandle()).emissiveIntensity(100.0f);
        return createMaterialViaStore(mb.translucentExt(MaterialInfoTranslucentEXT.builder()
                .refractiveIndex(COMMON_GLASS_IOR).transmittanceColor(0.97f, 0.97f, 0.97f)
                .transmittanceTexture(se.albedo.getHandle()).transmittanceMeasurementDistance(0.012f)
                .useDiffuseLayer(false).build()).build());
    }

    /** Per-sprite form of {@link #createAtlasGlassMaterial}: clear transmission with the sprite as a diffuse layer. */
    private RemixMaterial createGlassMaterial(SpriteEntry se) {
        MaterialInfo.Builder mb = MaterialInfo.builder()
                .albedoTexture(se.albedo.getHandle()).filterMode(0);
        if (se.normal != null) mb.normalTexture(se.normal.getHandle());
        if (se.emissive != null) mb.emissiveTexture(se.emissive.getHandle()).emissiveIntensity(100.0f);
        return createMaterialViaStore(mb.translucentExt(MaterialInfoTranslucentEXT.builder()
                .refractiveIndex(COMMON_GLASS_IOR).transmittanceColor(0.97f, 0.97f, 0.97f)
                .transmittanceMeasurementDistance(0.35f)
                .useDiffuseLayer(true).build()).build());
    }

    private MaterialInfoOpaqueSubsurfaceEXT buildSSS(SpriteEntry se, boolean solid) {
        MaterialInfoOpaqueSubsurfaceEXT.Builder sb = MaterialInfoOpaqueSubsurfaceEXT.builder()
                .transmittanceTexture(se.albedo.getHandle());
        if (solid) {
            RemixTexture radiusTexture = (se.sssAlbedo != null) ? se.sssAlbedo : se.albedo;
            sb.diffusionProfile(true).radiusTexture(radiusTexture.getHandle())
              .radiusScale(LabPBRConverter.DIFFUSION_SSS_RADIUS_SCALE)
              .maxSampleRadius(LabPBRConverter.DIFFUSION_SSS_MAX_SAMPLE_RADIUS);
        } else {
            sb.diffusionProfile(false)
              .singleScatteringAlbedo(cutoutSssAlbedoR, cutoutSssAlbedoG, cutoutSssAlbedoB)
              .measurementDistance(cutoutSssTransmissionDistance)
              .volumetricAnisotropy(cutoutSssAnisotropy);
            if (se.sss != null) {
                sb.thicknessTexture(se.sss.getHandle());
            }
            if (cutoutSssAlbedoTextureEnabled && se.sssAlbedo != null) {
                sb.singleScatteringAlbedoTexture(se.sssAlbedo.getHandle());
            }
        }
        return sb.build();
    }

    public static int layerType(String name) {
        return TerrainRenderLayer.type(name);
    }

    // ====================================================================
    //  Light color
    // ====================================================================

    public static final float[] FALLBACK_WARM_WHITE = {1f, 0.95f, 0.9f};
    private final Map<String, float[]> spriteLightColors = new HashMap<>();

    private static final float[] WARM   = {1.00f, 0.57f, 0.30f};
    private static final float[] SOUL   = {0.45f, 0.73f, 1.00f};
    private static final float[] RED    = {1.00f, 0.18f, 0.10f};
    private static final float[] WHITE  = {1.00f, 1.00f, 1.00f};
    private static final float[] GREEN  = {0.50f, 0.80f, 0.40f};
    private static final float[] PURPLE = {0.70f, 0.50f, 0.90f};
    private static final float[] SCULK  = {0.20f, 0.50f, 0.80f};
    private static final float[] COPPER = {0.85f, 1.00f, 0.90f};

    /** Curated colors for vanilla light-emitting blocks. */
    private static final Map<String, float[]> KNOWN_LIGHT_COLORS = knownLightColors();

    private static Map<String, float[]> knownLightColors() {
        Map<String, float[]> colors = new HashMap<>();
        addColors(colors, WARM, "torch", "wall_torch", "lantern", "campfire", "furnace",
                "smoker", "blast_furnace", "brewing_stand", "cave_vines", "cave_vines_plant");
        addColors(colors, SOUL, "soul_torch", "soul_wall_torch", "soul_lantern", "soul_campfire");
        addColors(colors, RED, "redstone_torch", "redstone_wall_torch");
        addColors(colors, WHITE, "end_rod", "beacon", "conduit");
        addColors(colors, GREEN, "glow_lichen");
        addColors(colors, PURPLE, "amethyst_cluster", "large_amethyst_bud",
                "medium_amethyst_bud", "small_amethyst_bud", "respawn_anchor", "dragon_egg");
        addColors(colors, SCULK, "sculk_sensor", "calibrated_sculk_sensor", "sculk_catalyst");
        addColors(colors, COPPER, "copper_bulb", "waxed_copper_bulb",
                "exposed_copper_bulb", "waxed_exposed_copper_bulb",
                "weathered_copper_bulb", "waxed_weathered_copper_bulb",
                "oxidized_copper_bulb", "waxed_oxidized_copper_bulb");
        return colors;
    }

    private static void addColors(
            Map<String, float[]> colors,
            float[] color,
            String... names) {
        for (String name : names) {
            colors.put(name, color);
        }
    }

    /**
     * Brightness-weighted average color of the sprite's emissive or albedo pixels.
     * Dark pixels (torch stick) contribute almost nothing; bright pixels (flame) dominate.
     */
    static float[] computeLightColor(byte[] rgba, byte[] emissiveRGBA, int w, int h) {
        byte[] src = (emissiveRGBA != null) ? emissiveRGBA : rgba;
        double rSum = 0, gSum = 0, bSum = 0, wSum = 0;
        for (int i = 0; i < w * h; i++) {
            float r = (src[i * 4] & 0xFF) / 255f;
            float g = (src[i * 4 + 1] & 0xFF) / 255f;
            float b = (src[i * 4 + 2] & 0xFF) / 255f;
            float lum = (r + g + b)/3.0f;
            rSum += r * lum; gSum += g * lum; bSum += b * lum; wSum += lum;
        }
        if (wSum < 0.001) return FALLBACK_WARM_WHITE;
        return new float[]{(float) (rSum / wSum), (float) (gSum / wSum), (float) (bSum / wSum)};
    }

    /**
     * Returns the light color for a block. Lookup order:
     * 1. Curated table (vanilla blocks with known colors)
     * 2. Texture-derived brightness-weighted average (from atlas)
     * 3. Warm white fallback
     */
    public float[] getLightColorForBlock(String namespace, String path) {

        float[] known = KNOWN_LIGHT_COLORS.get(path);
        if (known != null) return known;

        if (path.contains("candle")) return WARM;

        float[] derived = spriteLightColors.get(namespace + ":block/" + path);
        return derived != null ? derived : FALLBACK_WARM_WHITE;
    }

    // ====================================================================
    //  Upload helper
    // ====================================================================

    private RemixTexture upload(RemixAPI api, String name, byte[] data, int w, int h, int fmt, int purpose) {
        ByteBuffer buf = DirectBufferPool.acquire(data.length);
        buf.put(data).flip();
        try {
            RemixTexture tex = new RemixTexture(w, h, fmt, purpose, buf);
            RemixTextureStore.get().create(tex);
            managedTextures.add(tex);
            return tex;
        } catch (RemixException e) {
            LOGGER.error("Failed to upload texture {}: {}", name, e.getMessage());
            return null;
        }
    }

    private RemixMaterial createMaterialViaStore(MaterialInfo matInfo) {
        try {
            RemixMaterial mat = new RemixMaterial(matInfo, null, null);
            RemixMaterialStore.get().create(mat);
            managedMaterials.add(mat);
            return mat;
        } catch (RemixException e) {
            LOGGER.error("Failed to create material: {}", e.getMessage());
            return null;
        }
    }

    // ====================================================================
    //  Accessors & lifecycle
    // ====================================================================

    public boolean isInitialized() { return initialized && !sprites.isEmpty(); }
    public RemixTexture getBlockAtlasTexture() { ensureInit(); return atlasAlbedo; }
    public RemixTexture getEmissiveAtlasTexture() { ensureInit(); return atlasEmissive; }
    public RemixTexture getRoughnessAtlasTexture() { ensureInit(); return atlasRoughnessTex; }
    public RemixTexture getMetallicAtlasTexture() { ensureInit(); return atlasMetallicTex; }
    public RemixTexture getSssAtlasTexture() { ensureInit(); return atlasSssTex; }
    private void ensureInit() { if (!initialized) initialize(); }

    public void flushAnimationPatches() {
        RemixTexture albedo = getBlockAtlasTexture();
        if (albedo == null || albedo.getHandle() == 0L) {
            return;
        }
        AtlasAnimationForwarder.flush(
                api,
                albedo.getHandle(),
                handle(getEmissiveAtlasTexture()),
                handle(getRoughnessAtlasTexture()),
                handle(getMetallicAtlasTexture()),
                handle(getSssAtlasTexture()));
    }

    private static long handle(RemixTexture texture) {
        return texture == null ? 0L : texture.getHandle();
    }

    public void reload() { cleanup(); initialized = initFailed = false; initialize(); }

    public void cleanup() {
        for (RemixMaterial mat : managedMaterials)
            if (!mat.isDeleted()) mat.destroy();
        managedMaterials.clear();
        for (RemixTexture tex : managedTextures)
            if (!tex.isDeleted()) tex.destroy();
        managedTextures.clear();
        materials.clear(); sprites.clear(); spriteById.clear(); spriteLightColors.clear();
        Arrays.fill(atlasMaterials, null);
        Arrays.fill(atlasMaterialsSSS, null);
        atlasNormalTex = null;
        atlasHeightTex = null;
        atlasRoughnessTex = null;
        atlasMetallicTex = null;
        atlasSssTex = null;
        atlasSssAlbedoTex = null;
        if (api != null) {
            destroyWaterResources(api);
        } else {
            waterSurfaceMaterial = null;
            waterCameraMediumMaterial = null;
            waterWhiteTexture = null;
            waterNormalHandle = 0;
        }
        albedoRGBA = null; initialized = false;
    }

    /**
     * Loader/version boundary for block-atlas pixels and resource-pack sidecars.
     * Calls happen only while (re)building materials, never in terrain hot loops.
     */
    public interface AtlasSource {
        void scanVariants();

        AtlasData load() throws Exception;

        ImageResource loadVariant(Object spriteId, String suffix);

        String waterNormalsPath();

        void initializeAnimation(
                Map<String, AnimatedSpriteMaterialData> materialData,
                Map<Object, String> spriteNames);
    }

    public interface ImageResource extends LabPBRConverter.PixelImage, AutoCloseable {
        @Override
        void close();
    }

    public static final class AtlasData {
        public final int width;
        public final int height;
        public final byte[] albedoRgba;
        public final List<SpriteData> sprites;
        public final Map<String, Integer> luminanceBySprite;

        public AtlasData(
                int width,
                int height,
                byte[] albedoRgba,
                List<SpriteData> sprites,
                Map<String, Integer> luminanceBySprite) {
            this.width = width;
            this.height = height;
            this.albedoRgba = albedoRgba;
            this.sprites = sprites;
            this.luminanceBySprite = luminanceBySprite;
        }
    }

    public static final class SpriteData {
        public final Object id;
        public final String name;
        public final float u0;
        public final float v0;
        public final float u1;
        public final float v1;
        public final int x;
        public final int y;
        public final int width;
        public final int height;
        public final BitSet transparentPixels;
        public final AnimationData animation;

        public SpriteData(
                Object id,
                String name,
                float u0,
                float v0,
                float u1,
                float v1,
                int x,
                int y,
                int width,
                int height,
                BitSet transparentPixels,
                AnimationData animation) {
            this.id = id;
            this.name = name;
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.transparentPixels = transparentPixels;
            this.animation = animation;
        }
    }

    public static final class AnimationData {
        public final int[] frameIndices;
        public final int[] frameTimes;

        public AnimationData(int[] frameIndices, int[] frameTimes) {
            this.frameIndices = frameIndices;
            this.frameTimes = frameTimes;
        }
    }
}

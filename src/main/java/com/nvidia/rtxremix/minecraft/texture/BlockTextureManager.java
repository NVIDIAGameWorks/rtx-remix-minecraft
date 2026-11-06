/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.MaterialInfoOpaqueSubsurfaceEXT;
import com.nvidia.rtxremix.MaterialInfoTranslucentEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixException;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePurpose;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterialStore;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.asset.RemixTextureStore;
import com.nvidia.rtxremix.minecraft.mixin.SpriteAtlasAccessor;
import com.nvidia.rtxremix.minecraft.mixin.SpriteContentsAccessor;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-sprite texture and material management for Minecraft's block atlas.
 *
 * <p>Each atlas sprite becomes an individual Remix material with [0,1] UV space.
 * LabPBR sprites (_n/_s variants) get full PBR textures including POM and SSS.
 * Non-PBR sprites get simple albedo + constant materials.
 */
public class BlockTextureManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlockTextureManager.class);
    public static final Identifier BLOCK_ATLAS_ID = Identifier.of("minecraft", "textures/atlas/blocks.png");
    /** A cutout texel is drawn when its alpha is greater than this. */
    private static final int CUTOUT_ALPHA_REFERENCE = 128;
    /** Blend state for draw calls using the atlas cutout material, which sets useDrawCallAlphaState=true. */
    public static final InstanceInfoBlendEXT ATLAS_CUTOUT_BLEND =
            InstanceInfoBlendEXT.textureTimesVertexColorAlphaTest(CUTOUT_ALPHA_REFERENCE);
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
    public static final int L_SOLID = 0, L_CUTOUT = 1, L_CUTOUT_MIPPED = 2, L_TRANS = 3, L_GLASS = 4, LAYER_COUNT = 5;
    private static final RedstoneEmission[] REDSTONE_EMISSIONS = RedstoneEmission.values();
    private static final int PLAIN = 0, SUBSURFACE = 1;

    private static BlockTextureManager INSTANCE;

    public static BlockTextureManager get() { return INSTANCE; }

    private final RemixRenderer renderer;
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
    private final Map<Identifier, SpriteEntry> spriteById = new HashMap<>();
    private final Map<Long, RemixMaterial> materials = new HashMap<>();
    /**
     * Atlas materials by {@link RedstoneEmission}, then {@link #PLAIN} or {@link #SUBSURFACE},
     * then layer type. Blended layers have neither subsurface nor dust variants.
     */
    private final RemixMaterial[][][] atlasMaterials =
            new RemixMaterial[REDSTONE_EMISSIONS.length][2][LAYER_COUNT];
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

    /**
     * Cached LabPBR data for one animated sprite, built once at atlas init
     * so the animation forwarder can re-apply AO and animated _s material
     * channels on every animation frame without re-loading _n / _s images.
     *
     * <p>{@code aoTable} comes from the {@code _n} image and the {@code s*}
     * tables from {@code _s}; any of them is null when the sprite has no LabPBR
     * sibling of that type. Tables are stored at the source image's dimensions and
     * sampled with modulo wrap so a small _n / _s can modulate a larger
     * animation frame.
     */
    public static final class AnimatedSpriteLabPBR {
        public final byte[] aoTable;
        public final int aoW, aoH;
        public final byte[] sAlphaTable;
        public final byte[] sRoughnessTable;
        public final byte[] sMetallicTable;
        public final byte[] sSssTable;
        public final int sW, sH;
        /** Whether any pixel of the sprite's {@code _s} alpha channel is below 255. */
        public final boolean hasPerPixelEmissive;
        public final boolean hasSss;
        public final int[] frameIndices;
        public final int[] frameTimes;
        public final int frameColumns;

        AnimatedSpriteLabPBR(byte[] aoTable, int aoW, int aoH,
                             byte[] sAlphaTable, byte[] sRoughnessTable,
                             byte[] sMetallicTable, byte[] sSssTable,
                             int sW, int sH,
                             boolean hasPerPixelEmissive, boolean hasSss,
                             int[] frameIndices, int[] frameTimes, int frameColumns) {
            this.aoTable = aoTable; this.aoW = aoW; this.aoH = aoH;
            this.sAlphaTable = sAlphaTable;
            this.sRoughnessTable = sRoughnessTable;
            this.sMetallicTable = sMetallicTable;
            this.sSssTable = sSssTable;
            this.sW = sW; this.sH = sH;
            this.hasPerPixelEmissive = hasPerPixelEmissive;
            this.hasSss = hasSss;
            this.frameIndices = frameIndices;
            this.frameTimes = frameTimes;
            this.frameColumns = frameColumns;
        }
    }

    /** Per-sprite data: UV bounds (from Minecraft), texture handles, PBR flags. */
    public static final class SpriteEntry {
        public final int index;
        public final Identifier id;
        public final float u0, v0, u1, v1;
        public final boolean hasTransparency;
        final int pixW, pixH, pixX, pixY;
        private final BitSet transparentPixels;
        /** Texels the cutout alpha test discards, or null when it keeps them all. */
        private final BitSet cutoutClearPixels;
        boolean animated;
        RemixTexture albedo, normal, roughness, metallic, height, emissive, sss, sssAlbedo;
        public boolean hasPBR, hasSSS;
        float[] lightColor;

        SpriteEntry(int i, Identifier id, Sprite s) {
            this.index = i; this.id = id;
            u0 = s.getMinU(); v0 = s.getMinV(); u1 = s.getMaxU(); v1 = s.getMaxV();
            pixX = s.getX(); pixY = s.getY();
            SpriteContents contents = s.getContents();
            pixW = contents.getWidth(); pixH = contents.getHeight();
            NativeImage image = ((SpriteContentsAccessor) contents).getImage();
            transparentPixels = scanAlphaAtMost(image, pixW, pixH, 254);
            cutoutClearPixels = scanAlphaAtMost(image, pixW, pixH, CUTOUT_ALPHA_REFERENCE);
            hasTransparency = transparentPixels != null;
            animated = ((SpriteContentsAccessor) contents).getAnimation() != null;
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

        /** The sprite's width in texels. */
        public int texelWidth() {
            return pixW;
        }

        /** The sprite's height in texels. */
        public int texelHeight() {
            return pixH;
        }

        /**
         * Whether the sprite is animated. Its transparency is scanned from one frame only, so
         * it says nothing about the others.
         */
        public boolean isAnimated() {
            return animated;
        }

        /** The index of the texel under an atlas-space UV, clamped to the sprite. */
        public int texelAt(float u, float v) {
            return uvToMinTexel(v, v0, v1, pixH) * pixW + uvToMinTexel(u, u0, u1, pixW);
        }

        /** Whether the cutout alpha test keeps a texel, by its {@link #texelAt} index. */
        public boolean isOpaqueTexel(int texel) {
            return cutoutClearPixels == null || !cutoutClearPixels.get(texel);
        }

        /** The texels whose alpha is at most {@code maxAlpha}, or null when there are none. */
        private static BitSet scanAlphaAtMost(NativeImage img, int w, int h, int maxAlpha) {
            if (img == null) return null;
            BitSet matching = null;
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    if (((img.getColor(x, y) >> 24) & 0xFF) <= maxAlpha) {
                        if (matching == null) matching = new BitSet(w * h);
                        matching.set(y * w + x);
                    }
            return matching;
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

    public BlockTextureManager(RemixRenderer renderer) {
        this.renderer = renderer;
        INSTANCE = this;
    }

    // ====================================================================
    //  Init
    // ====================================================================

    public void initialize() {
        if (initialized || initFailed) return;
        try {
            RemixAPI api = renderer.getRemixApi();
            MinecraftClient client = MinecraftClient.getInstance();
            if (api == null || client == null || client.getTextureManager() == null) return;

            AbstractTexture tex = client.getTextureManager().getTexture(BLOCK_ATLAS_ID);
            if (!(tex instanceof SpriteAtlasTexture spriteAtlas)) return;

            captureAtlas(api, spriteAtlas);
            LabPBRConverter.VariantCache labPBRVariants;
            try (ProfileScope ignored = ProfileScope.begin("LabPBR.variantScan")) {
                labPBRVariants = LabPBRConverter.buildVariantCache(client.getResourceManager());
            }
            buildSprites(api, spriteAtlas, labPBRVariants);
            if (!terrainEmissionMaterialsInitialized) {
                appliedTerrainEmissionMultiplier = terrainEmissionMultiplier;
            }
            buildAtlasMaterials(api, labPBRVariants);
            buildWaterMaterials(api);
            Map<String, AnimatedSpriteLabPBR> labPBRMap =
                buildAnimatedSpriteLabPBRMap(labPBRVariants, spriteAtlas);
            AtlasAnimationForwarder.init(labPBRMap, Set.copyOf(spriteById.keySet()));

            initialized = true;
            terrainEmissionMaterialsInitialized = true;
        } catch (Exception e) {
            LOGGER.error("Init failed", e);
            initFailed = true;
        }
    }

    private void captureAtlas(RemixAPI api, SpriteAtlasTexture atlas) {
        atlasW = atlasWidth(atlas);
        atlasH = atlasHeight(atlas);
        if (atlasW == 0 || atlasH == 0) {
            LOGGER.warn("Atlas dimensions are zero");
            return;
        }
        albedoRGBA = stitchAtlasRGBA(atlas);
        atlasAlbedo = upload(api, "block_atlas", albedoRGBA, atlasW, atlasH,
                TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR);
        LOGGER.info("Atlas {}x{} (stitched from {} sprites)",
                atlasW, atlasH, ((SpriteAtlasAccessor) atlas).getSprites().size());
    }

    // ====================================================================
    //  Per-sprite texture extraction
    // ====================================================================

    private void buildSprites(RemixAPI api, SpriteAtlasTexture atlas, LabPBRConverter.VariantCache labPBRVariants) {
        Map<Identifier, Sprite> map = ((SpriteAtlasAccessor) atlas).getSprites();
        if (map == null) return;

        Map<String, Integer> lumMap = buildLuminanceMap();
        int pbr = 0, emissiveCount = 0, idx = 0;

        for (var e : map.entrySet()) {
            SpriteEntry se = new SpriteEntry(idx++, e.getKey(), e.getValue());
            byte[] alb = extractRGBA(se.pixX, se.pixY, se.pixW, se.pixH);
            int luminance = lumMap.getOrDefault(e.getKey().toString(), 0);

            NativeImage nI = labPBRVariants.loadVariant(e.getKey(), "_n");
            NativeImage sI = labPBRVariants.loadVariant(e.getKey(), "_s");
            NativeImage sssI = labPBRVariants.loadVariant(e.getKey(), "_sss");
            try {
                if (nI != null) LabPBRConverter.applyAO(alb, se.pixW, nI, 0, 0, se.pixW, se.pixH);
                se.albedo = upload(api, "s" + se.index + "_a", alb, se.pixW, se.pixH,
                        TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR);
                if (nI != null || sI != null || sssI != null) {
                    buildPBR(api, se, nI, sI, sssI, alb);
                    pbr++;
                }
                if (se.emissive != null) emissiveCount++;
                if (se.lightColor != null) {
                    spriteLightColors.put(e.getKey().toString(), se.lightColor);
                } else if (luminance > 0) {
                    spriteLightColors.put(e.getKey().toString(), computeLightColor(alb, null, se.pixW, se.pixH));
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
    private Map<String, AnimatedSpriteLabPBR> buildAnimatedSpriteLabPBRMap(
            LabPBRConverter.VariantCache labPBRVariants, SpriteAtlasTexture atlas) {
        Map<String, AnimatedSpriteLabPBR> result = new HashMap<>();
        Map<Identifier, Sprite> map = ((SpriteAtlasAccessor) atlas).getSprites();
        if (map == null) return result;

        for (var e : map.entrySet()) {
            SpriteContents contents = e.getValue().getContents();
            SpriteContents.Animation animation = ((SpriteContentsAccessor) contents).getAnimation();
            if (animation == null) continue;

            NativeImage nI = labPBRVariants.loadVariant(e.getKey(), "_n");
            NativeImage sI = labPBRVariants.loadVariant(e.getKey(), "_s");
            try {
                if (nI == null && sI == null) continue;
                byte[] aoTable     = (nI != null) ? LabPBRConverter.buildAOTable(nI)     : null;
                int aoW            = (nI != null) ? nI.getWidth()                        : 0;
                int aoH            = (nI != null) ? nI.getHeight()                       : 0;
                byte[] sAlphaTable = (sI != null)
                        ? LabPBRConverter.applyEmissionGain(LabPBRConverter.buildSAlphaTable(sI), BuiltinEmission.gain(e.getKey()))
                        : null;
                byte[] sRoughnessTable = (sI != null) ? LabPBRConverter.buildSRoughnessTable(sI) : null;
                byte[] sMetallicTable = (sI != null) ? LabPBRConverter.buildSMetallicTable(sI) : null;
                byte[] sSssTable = (sI != null) ? LabPBRConverter.buildSSssTable(sI) : null;
                int sW             = (sI != null) ? sI.getWidth()                        : 0;
                int sH             = (sI != null) ? sI.getHeight()                       : 0;
                boolean hasPerPixelEmissive = sI != null && LabPBRConverter.hasEmissiveData(sI);
                boolean hasSss = sI != null && LabPBRConverter.hasSSSData(sI);
                List<SpriteContents.AnimationFrame> frames = animation.frames;
                int[] frameIndices = new int[frames.size()];
                int[] frameTimes = new int[frames.size()];
                for (int i = 0; i < frames.size(); i++) {
                    SpriteContents.AnimationFrame frame = frames.get(i);
                    frameIndices[i] = frame.index;
                    frameTimes[i] = frame.time;
                }
                int variantW = (sI != null) ? sW : aoW;
                int frameColumns = Math.max(1, variantW / Math.max(1, contents.getWidth()));
                result.put(e.getKey().toString(),
                    new AnimatedSpriteLabPBR(aoTable, aoW, aoH,
                                             sAlphaTable, sRoughnessTable, sMetallicTable, sSssTable,
                                             sW, sH, hasPerPixelEmissive, hasSss,
                                             frameIndices, frameTimes, frameColumns));
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

    private void buildAtlasMaterials(RemixAPI api, LabPBRConverter.VariantCache labPBRVariants) {
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
            NativeImage nI = labPBRVariants.loadVariant(se.id, "_n");
            NativeImage sI = labPBRVariants.loadVariant(se.id, "_s");
            NativeImage sssI = labPBRVariants.loadVariant(se.id, "_sss");
            try {
                if (nI != null) {
                    LabPBRConverter.blitNormal(normalAtlas, atlasW, nI, se.pixX, se.pixY, se.pixW, se.pixH);
                    LabPBRConverter.blitHeight(heightAtlas, atlasW, nI, se.pixX, se.pixY, se.pixW, se.pixH);
                    LabPBRConverter.applyAO(albedoRGBA, atlasW, nI, se.pixX, se.pixY, se.pixW, se.pixH);
                    LabPBRConverter.applyAO(sssAlbedoAtlas, atlasW, nI, se.pixX, se.pixY, se.pixW, se.pixH);
                    hasAnyNormal = true;
                    hasAnyHeight = true;
                }
                if (sI != null) {
                    LabPBRConverter.blitRoughness(roughnessAtlas, atlasW, sI, se.pixX, se.pixY, se.pixW, se.pixH);
                    LabPBRConverter.blitMetallic(metallicAtlas, atlasW, sI, se.pixX, se.pixY, se.pixW, se.pixH);
                    if (LabPBRConverter.hasSSSData(sI)) {
                        LabPBRConverter.blitSSS(sssThicknessAtlas, atlasW, sI, se.pixX, se.pixY, se.pixW, se.pixH);
                        se.hasSSS = true;
                        hasAnySSS = true;
                    }
                }
                if (sssI != null) {
                    LabPBRConverter.blitSingleScatteringAlbedo(
                            sssAlbedoAtlas, atlasW, sssI, se.pixX, se.pixY, se.pixW, se.pixH);
                    se.hasSSS = true;
                    hasAnySSS = true;
                }
                boolean needsEmissive = sI != null && LabPBRConverter.hasEmissiveData(sI);
                if (needsEmissive) {
                    LabPBRConverter.blitEmissive(emissiveAtlas, atlasW, albedoRGBA, sI,
                            se.pixX, se.pixY, se.pixW, se.pixH, 0f, BuiltinEmission.gain(se.id));
                    hasAnyEmissive = true;
                } else if (BuiltinEmission.mask(se.id) != null) {
                    LabPBRConverter.blitEmissiveMask(emissiveAtlas, atlasW, albedoRGBA,
                            se.pixX, se.pixY, se.pixW, se.pixH, BuiltinEmission.mask(se.id));
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

        for (RedstoneEmission emission : REDSTONE_EMISSIONS) {
            for (int lt = 0; lt < LAYER_COUNT; lt++) {
                boolean blended = isBlendedLayerType(lt);
                if (blended && emission.isDust()) continue;
                atlasMaterials[emission.ordinal()][PLAIN][lt] =
                        createMaterialViaStore(atlasMaterialInfo(lt, false, emission));
                if (hasAnySSS && !blended) {
                    atlasMaterials[emission.ordinal()][SUBSURFACE][lt] =
                            createMaterialViaStore(atlasMaterialInfo(lt, true, emission));
                }
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

    private static boolean isBlendedLayerType(int layerType) {
        return layerType == L_TRANS || layerType == L_GLASS;
    }

    /**
     * Describes one atlas material. Every variant samples the same atlas textures, so an
     * animated sprite stays in step across all of them; they differ only in layer type,
     * subsurface scattering, and the emission {@link RedstoneEmission} asks for.
     */
    MaterialInfo atlasMaterialInfo(int layerType, boolean subsurface, RedstoneEmission emission) {
        RemixTexture emissive = switch (emission) {
            case NONE -> atlasEmissive;
            case UNPOWERED -> null;
            case POWERED -> atlasAlbedo;
        };
        float intensity = terrainEmissionIntensity();
        if (layerType == L_TRANS) return buildAtlasTransMaterialInfo(emissive, atlasNormalTex, intensity);
        if (layerType == L_GLASS) return buildAtlasGlassMaterialInfo(emissive, atlasNormalTex, intensity);
        return buildAtlasOpaqueMaterialInfo(layerType, atlasNormalTex, atlasHeightTex,
                atlasRoughnessTex, atlasMetallicTex, emissive,
                subsurface ? atlasSssTex : null, subsurface ? atlasSssAlbedoTex : null, intensity);
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
        if (lt != L_SOLID) ob.alphaTestType(4).alphaReferenceValue(CUTOUT_ALPHA_REFERENCE);
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
    private MaterialInfo buildAtlasGlassMaterialInfo(
            RemixTexture emissiveTex, RemixTexture normalTex, float emissionIntensity) {
        MaterialInfo.Builder mb = MaterialInfo.builder()
                .albedoTexture(atlasAlbedo.getHandle()).filterMode(0);
        if (normalTex != null) mb.normalTexture(normalTex.getHandle());
        if (emissiveTex != null) {
            mb.emissiveTexture(emissiveTex.getHandle())
                    .emissiveIntensity(emissionIntensity);
        }
        return mb.translucentExt(MaterialInfoTranslucentEXT.builder()
                .refractiveIndex(COMMON_GLASS_IOR).transmittanceColor(0.97f, 0.97f, 0.97f)
                .transmittanceMeasurementDistance(0.35f)
                .useDiffuseLayer(true).build()).build();
    }

    // ====================================================================
    //  Custom water material (animated normal flipbook, translucent)
    // ====================================================================

    private static final String WATER_NORMALS_DDS = "textures/water_normals_flipbook.dds";
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

    private void buildWaterMaterials(RemixAPI api) {
        boolean success = false;
        try {
            destroyWaterResources(api);

            String mcDir = MinecraftClient.getInstance().runDirectory.getAbsolutePath();
            String ddsPath = mcDir + File.separator + WATER_NORMALS_DDS;

            waterNormalHandle = api.createTextureFromFile(ddsPath);

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
        return getAtlasMaterialSSS(L_CUTOUT) != null || getAtlasMaterialSSS(L_CUTOUT_MIPPED) != null;
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
        RemixAPI api = renderer.getRemixApi();
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
        for (RedstoneEmission emission : REDSTONE_EMISSIONS) {
            RemixMaterial[] subsurface = atlasMaterials[emission.ordinal()][SUBSURFACE];
            destroyManagedMaterial(subsurface[layerType]);
            subsurface[layerType] = createMaterialViaStore(atlasMaterialInfo(layerType, true, emission));
        }
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
        RemixAPI api = renderer.getRemixApi();
        if (api == null) {
            LOGGER.warn("Cannot rebuild water materials: Remix API unavailable");
            return false;
        }
        buildWaterMaterials(api);
        return waterSurfaceMaterial != null && waterCameraMediumMaterial != null;
    }

    /** Returns the atlas-based material (no SSS) for the given layer type. */
    public RemixMaterial getAtlasMaterial(int layerType) {
        return getAtlasMaterial(layerType, false, RedstoneEmission.NONE);
    }

    /** Returns the atlas-based SSS material for the given layer type, or null if none. */
    public RemixMaterial getAtlasMaterialSSS(int layerType) {
        return getAtlasMaterial(layerType, true, RedstoneEmission.NONE);
    }

    /**
     * Returns the atlas material for a terrain surface, or null when the atlas has no such
     * variant: subsurface scattering without SSS in the atlas, or dust in a blended layer.
     * Materials are created during render-thread atlas setup, so capture workers may read them.
     */
    public RemixMaterial getAtlasMaterial(int layerType, boolean subsurface, RedstoneEmission emission) {
        return atlasMaterials[emission.ordinal()][subsurface ? SUBSURFACE : PLAIN][layerType];
    }

    /** Maps sprite identifiers (e.g. "minecraft:block/glowstone") to max block luminance. */
    private static Map<String, Integer> buildLuminanceMap() {
        Map<String, Integer> map = new HashMap<>();
        for (Block block : Registries.BLOCK) {
            int maxLum = 0;
            for (BlockState state : block.getStateManager().getStates())
                maxLum = Math.max(maxLum, state.getLuminance());
            if (maxLum > 0) {
                Identifier id = Registries.BLOCK.getId(block);
                map.merge(id.getNamespace() + ":block/" + id.getPath(), maxLum, Math::max);
            }
        }
        return map;
    }

    private void buildPBR(RemixAPI api, SpriteEntry se, NativeImage nI, NativeImage sI, NativeImage sssI,
                          byte[] alb) {
        int w = se.pixW, h = se.pixH;
        String p = "s" + se.index + "_";
        se.hasPBR = nI != null || sI != null;

        if (nI != null) {
            byte[] nd = new byte[w * h * 4], hd = new byte[w * h];
            LabPBRConverter.blitNormal(nd, w, nI, 0, 0, w, h);
            LabPBRConverter.blitHeight(hd, w, nI, 0, 0, w, h);
            se.normal = upload(api, p + "n", nd, w, h, TextureFormat.RGBA8_UNORM, TexturePurpose.NORMAL_DX);
            se.height = upload(api, p + "h", hd, w, h, TextureFormat.R8_UNORM, TexturePurpose.HEIGHTMAP);
        }
        if (sI != null) {
            byte[] rd = new byte[w * h], md = new byte[w * h], sd = new byte[w * h * 4];
            LabPBRConverter.blitRoughness(rd, w, sI, 0, 0, w, h);
            LabPBRConverter.blitMetallic(md, w, sI, 0, 0, w, h);
            LabPBRConverter.blitSSS(sd, w, sI, 0, 0, w, h);
            se.roughness = upload(api, p + "r", rd, w, h, TextureFormat.R8_UNORM, TexturePurpose.DATA);
            se.metallic  = upload(api, p + "m", md, w, h, TextureFormat.R8_UNORM, TexturePurpose.DATA);
            if (LabPBRConverter.hasSSSData(sI)) {
                se.sss = upload(api, p + "s", sd, w, h, TextureFormat.RGBA8_UNORM, TexturePurpose.DATA);
                se.hasSSS = true;
            }
        }
        if (sssI != null) {
            byte[] ssa = new byte[w * h * 4];
            LabPBRConverter.blitSingleScatteringAlbedo(ssa, w, sssI, 0, 0, w, h);
            se.sssAlbedo = upload(api, p + "ssa", ssa, w, h, TextureFormat.RGBA8_UNORM, TexturePurpose.DATA);
            se.hasSSS = true;
        }
        boolean needsEmissive = sI != null && LabPBRConverter.hasEmissiveData(sI);
        if (needsEmissive) {
            byte[] ed = new byte[w * h * 4];
            LabPBRConverter.blitEmissive(ed, w, alb, sI, 0, 0, w, h, 0f);
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

    /** Reads per-sprite NativeImages and stitches into an RGBA byte[] atlas buffer. */
    public static byte[] stitchAtlasRGBA(SpriteAtlasTexture atlas) {
        SpriteAtlasAccessor acc = (SpriteAtlasAccessor) atlas;
        int w = acc.getWidth(), h = acc.getHeight();
        byte[] buf = new byte[w * h * 4];
        for (Sprite sprite : acc.getSprites().values()) {
            NativeImage img = ((SpriteContentsAccessor) sprite.getContents()).getImage();
            if (img == null) continue;
            int sx = sprite.getX(), sy = sprite.getY();
            int sw = sprite.getContents().getWidth(), sh = sprite.getContents().getHeight();
            for (int y = 0; y < sh; y++)
                for (int x = 0; x < sw; x++) {
                    int abgr = img.getColor(x, y);
                    int idx = ((sy + y) * w + (sx + x)) * 4;
                    buf[idx]     = (byte) (abgr & 0xFF);
                    buf[idx + 1] = (byte) ((abgr >> 8) & 0xFF);
                    buf[idx + 2] = (byte) ((abgr >> 16) & 0xFF);
                    buf[idx + 3] = (byte) ((abgr >> 24) & 0xFF);
                }
        }
        return buf;
    }

    /** Returns the atlas width, or 0 if not initialized. */
    public static int atlasWidth(SpriteAtlasTexture atlas) {
        return ((SpriteAtlasAccessor) atlas).getWidth();
    }

    /** Returns the atlas height, or 0 if not initialized. */
    public static int atlasHeight(SpriteAtlasTexture atlas) {
        return ((SpriteAtlasAccessor) atlas).getHeight();
    }

    // ====================================================================
    //  Sprite lookup + material factory
    // ====================================================================

    /** Finds a sprite by its content identifier (O(1) HashMap lookup). */
    public SpriteEntry getSpriteById(Identifier id) {
        return spriteById.get(id);
    }

    /** Returns the material for a sprite + layer, creating lazily. */
    public RemixMaterial getMaterial(SpriteEntry se, int layerType) {
        if (se == null) return null;
        long key = (long) se.index * LAYER_COUNT + layerType;
        return materials.computeIfAbsent(key, k -> createMaterial(se, layerType));
    }

    private RemixMaterial createMaterial(SpriteEntry se, int lt) {
        RemixAPI api = renderer.getRemixApi();
        if (api == null) return null;

        if (lt == L_TRANS) return createTransMaterial(api, se);
        if (lt == L_GLASS) return createGlassMaterial(se);

        MaterialInfoOpaqueEXT.Builder ob = MaterialInfoOpaqueEXT.builder()
                .albedoConstant(1f, 1f, 1f).useDrawCallAlphaState(true);
        if (lt != L_SOLID) ob.alphaTestType(4).alphaReferenceValue(CUTOUT_ALPHA_REFERENCE);

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

    /** Per-sprite form of {@link #buildAtlasGlassMaterialInfo}: clear transmission with the sprite as a diffuse layer. */
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
        return switch (name) {
            case "cutout" -> L_CUTOUT;
            case "cutout_mipped" -> L_CUTOUT_MIPPED;
            case "translucent" -> L_TRANS;
            case "glass" -> L_GLASS;
            default -> L_SOLID;
        };
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
    private static final Map<String, float[]> KNOWN_LIGHT_COLORS = Map.ofEntries(
            Map.entry("torch", WARM), Map.entry("wall_torch", WARM),
            Map.entry("lantern", WARM), Map.entry("campfire", WARM),
            Map.entry("furnace", WARM), Map.entry("smoker", WARM),
            Map.entry("blast_furnace", WARM), Map.entry("brewing_stand", WARM),
            Map.entry("cave_vines", WARM), Map.entry("cave_vines_plant", WARM),

            Map.entry("soul_torch", SOUL), Map.entry("soul_wall_torch", SOUL),
            Map.entry("soul_lantern", SOUL), Map.entry("soul_campfire", SOUL),

            Map.entry("redstone_torch", RED), Map.entry("redstone_wall_torch", RED),

            Map.entry("end_rod", WHITE), Map.entry("beacon", WHITE),
            Map.entry("conduit", WHITE),

            Map.entry("glow_lichen", GREEN),

            Map.entry("amethyst_cluster", PURPLE), Map.entry("large_amethyst_bud", PURPLE),
            Map.entry("medium_amethyst_bud", PURPLE), Map.entry("small_amethyst_bud", PURPLE),
            Map.entry("respawn_anchor", PURPLE), Map.entry("dragon_egg", PURPLE),

            Map.entry("sculk_sensor", SCULK), Map.entry("calibrated_sculk_sensor", SCULK),
            Map.entry("sculk_catalyst", SCULK),

            Map.entry("copper_bulb", COPPER), Map.entry("waxed_copper_bulb", COPPER),
            Map.entry("exposed_copper_bulb", COPPER), Map.entry("waxed_exposed_copper_bulb", COPPER),
            Map.entry("weathered_copper_bulb", COPPER), Map.entry("waxed_weathered_copper_bulb", COPPER),
            Map.entry("oxidized_copper_bulb", COPPER), Map.entry("waxed_oxidized_copper_bulb", COPPER)
    );

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
    public float[] getLightColorForBlock(Block block) {
        Identifier id = Registries.BLOCK.getId(block);
        String path = id.getPath();

        float[] known = KNOWN_LIGHT_COLORS.get(path);
        if (known != null) return known;

        if (path.contains("candle")) return WARM;

        float[] derived = spriteLightColors.get(id.getNamespace() + ":block/" + path);
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

    public void reload() { cleanup(); initialized = initFailed = false; initialize(); }

    public void cleanup() {
        for (RemixMaterial mat : managedMaterials)
            if (!mat.isDeleted()) mat.destroy();
        managedMaterials.clear();
        for (RemixTexture tex : managedTextures)
            if (!tex.isDeleted()) tex.destroy();
        managedTextures.clear();
        materials.clear(); sprites.clear(); spriteById.clear(); spriteLightColors.clear();
        for (RemixMaterial[][] byEmission : atlasMaterials) {
            for (RemixMaterial[] byLayer : byEmission) Arrays.fill(byLayer, null);
        }
        atlasNormalTex = null;
        atlasHeightTex = null;
        atlasRoughnessTex = null;
        atlasMetallicTex = null;
        atlasSssTex = null;
        atlasSssAlbedoTex = null;
        RemixAPI api = renderer.getRemixApi();
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
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.texture.RedstoneEmission;
import com.nvidia.rtxremix.minecraft.terrain.ChunkManager;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.HardcodedVertex;
import net.minecraft.util.Identifier;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes block quad data directly into per-bucket interleaved
 * {@link ByteBuffer}s in HardcodedVertex layout (64-byte stride). Positions
 * stay section-local so column meshes can be placed with an instance transform
 * without baking large world-space coordinates into BLAS vertex data.
 *
 * <p>Two-pass per block:
 * <ol>
 *   <li>{@link #stageQuad}: store vertex data + metadata in flat staging arrays.</li>
 *   <li>{@link #flushBlock}: overlay detection then write to final bucket buffers.</li>
 * </ol>
 *
 * <p>After all blocks: {@link #buildMeshData} produces a compact {@link SectionMeshData}.
 *
 * <p>One instance per worker thread (ThreadLocal in {@link BlockQuadEmitter}).
 */
public final class DirectQuadWriter {

    private static final int STRIDE = HardcodedVertex.STRIDE; // 64
    private static final int INDICES_PER_QUAD = 6;
    private static final int MAX_BLOCK_QUADS = 128;
    private static final float DECAL_NORMAL_DOT_EPSILON = 0.999f;
    private static final float DECAL_PLANE_DISTANCE_EPSILON = 1.0E-3f;
    /** Texels per block edge beyond which a dust stack is left uncut; bounds the owner grid. */
    private static final int MAX_STACK_RESOLUTION = 64;
    private static final float GRID_EPSILON = 1.0E-4f;
    /**
     * How far toward its corners, as a fraction of its half-width, a stack cell is sampled to
     * check that it lies in one texel. The margin leaves room for vanilla's bake, which pulls
     * a face's UVs toward their centre by a few hundredths of a texel.
     */
    private static final float CELL_SAMPLE_REACH = 0.8f;
    /**
     * How far, in texels, a quad's fourth UV may sit from the parallelogram of its other three,
     * or any UV outside its sprite.
     */
    private static final float UV_TOLERANCE = 0.01f;
    private static final float DECAL_OVERLAP_EPSILON = 1.0E-4f;

    /**
     * Worst-case quads per 16x16x16 section.  4096 blocks x 6 faces = 24576.
     * Used as the growth ceiling for bucket buffers, not for initial sizing.
     */
    private static final int MAX_SECTION_QUADS = 24576;
    /**
     * The quads that cutting dust stacks may add to a section beyond those it replaces. A
     * stack that would exceed it is left uncut, so no bucket outgrows its ceiling.
     */
    private static final int MAX_CUT_EXTRA_QUADS = MAX_SECTION_QUADS;
    private static final int MAX_VERT_BUF_SIZE = (MAX_SECTION_QUADS + MAX_CUT_EXTRA_QUADS) * 4 * STRIDE;
    /** The colour of a quad its model leaves untinted. */
    private static final int UNTINTED = 0xFFFFFFFF;
    /** Initial bucket capacity: 128 quads keeps small section buckets compact. */
    private static final int INITIAL_QUADS    = 128;
    private static final int INITIAL_VERT_SIZE = INITIAL_QUADS * 4 * STRIDE;       // 32 KB

    /** Total bytes allocated across all DirectQuadWriter bucket buffers, all threads. */
    private static final AtomicLong totalAllocatedBytes = new AtomicLong();
    private static final AtomicLong totalGrowthEvents = new AtomicLong();
    private static final AtomicLong totalGrowthCopiedBytes = new AtomicLong();

    /** Returns total bucket buffer memory in bytes across all worker threads. */
    public static long getTotalAllocatedBytes() { return totalAllocatedBytes.get(); }
    public static long drainGrowthEvents() { return totalGrowthEvents.getAndSet(0); }
    public static long drainGrowthCopiedBytes() { return totalGrowthCopiedBytes.getAndSet(0); }

    // --- per-bucket output ---

    private static final RedstoneEmission[] REDSTONE_EMISSIONS = RedstoneEmission.values();

    /** Buckets by category, then {@link RedstoneEmission}, then material key. */
    @SuppressWarnings("unchecked")
    private final Map<String, BucketBuffer>[][] buckets =
            new Map[ChunkManager.CAT_COUNT][REDSTONE_EMISSIONS.length];
    private final List<BucketBuffer> bucketOrder = new ArrayList<>();
    private int lastBucketCategory = -1;
    private String lastBucketKey;
    private RedstoneEmission lastBucketEmission;
    private BucketBuffer lastBucket;

    public DirectQuadWriter() {
        for (Map<String, BucketBuffer>[] byEmission : buckets) {
            for (int i = 0; i < byEmission.length; i++) {
                byEmission[i] = new LinkedHashMap<>();
            }
        }
    }

    static final class BucketBuffer {
        final int category;
        final String key;
        final RedstoneEmission redstoneEmission;
        ByteBuffer vertices;
        int vertexCount;
        int indexCount;
        int vertexHash;

        BucketBuffer(int category, String key, RedstoneEmission redstoneEmission) {
            this.category = category;
            this.key = key;
            this.redstoneEmission = redstoneEmission;
            vertices = ByteBuffer.allocate(INITIAL_VERT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            totalAllocatedBytes.addAndGet(INITIAL_VERT_SIZE);
        }

        int allocatedBytes() { return vertices.capacity(); }

        void writeQuad(float[] pos, int posOff,
                        float[] uvs, int uvOff,
                        float nx, float ny, float nz,
                        int color, int boundsMin, int boundsMax) {
            ensureVertexCapacity((vertexCount + 4) * STRIDE);

            for (int v = 0; v < 4; v++) {
                int wo = (vertexCount + v) * STRIDE;
                float x = pos[posOff + v * 3];
                float y = pos[posOff + v * 3 + 1];
                float z = pos[posOff + v * 3 + 2];
                vertices.putFloat(wo,      x);
                vertices.putFloat(wo + 4,  y);
                vertices.putFloat(wo + 8,  z);
                vertices.putFloat(wo + 12, nx);
                vertices.putFloat(wo + 16, ny);
                vertices.putFloat(wo + 20, nz);
                vertices.putFloat(wo + 24, uvs[uvOff + v * 2]);
                vertices.putFloat(wo + 28, uvs[uvOff + v * 2 + 1]);
                vertices.putInt(wo + 32,   color);
                vertices.putInt(wo + 36,   boundsMin);
                vertices.putInt(wo + 40,   boundsMax);
                hashVertex(x, y, z,
                           nx, ny, nz,
                           uvs[uvOff + v * 2],
                           uvs[uvOff + v * 2 + 1],
                           color, boundsMin, boundsMax);
            }
            vertexCount += 4;
            indexCount += INDICES_PER_QUAD;
        }

        private void ensureVertexCapacity(int requiredBytes) {
            if (requiredBytes <= vertices.capacity()) return;
            int oldCap = vertices.capacity();
            int newCap = oldCap;
            while (newCap < requiredBytes) newCap *= 2;
            if (newCap > MAX_VERT_BUF_SIZE) newCap = MAX_VERT_BUF_SIZE;
            ByteBuffer grown = ByteBuffer.allocate(newCap).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer src = vertices.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            src.position(0).limit(vertexCount * STRIDE);
            grown.put(src);
            vertices = grown;
            totalAllocatedBytes.addAndGet(newCap - oldCap);
            totalGrowthEvents.incrementAndGet();
            totalGrowthCopiedBytes.addAndGet((long) vertexCount * STRIDE);
        }

        void reset() {
            vertexCount = 0;
            indexCount = 0;
            vertexHash = 0;
        }

        int appendToContentHash(int h) {
            int surfaceHash = category;
            surfaceHash = surfaceHash * 31 + key.hashCode();
            surfaceHash = surfaceHash * 31 + redstoneEmission.ordinal();
            surfaceHash = surfaceHash * 31 + vertexCount;
            surfaceHash = surfaceHash * 31 + indexCount;
            surfaceHash = surfaceHash * 31 + 1; // canonical quad indices
            surfaceHash = appendHash(
                    surfaceHash, vertexHash, vertexCount * (STRIDE / Integer.BYTES));
            return h * 31 + surfaceHash;
        }

        private void hashVertex(float px, float py, float pz,
                                float nx, float ny, float nz,
                                float u, float v,
                                int color, int boundsMin, int boundsMax) {
            int h = vertexHash;
            h = mixFloat(h, px);
            h = mixFloat(h, py);
            h = mixFloat(h, pz);
            h = mixFloat(h, nx);
            h = mixFloat(h, ny);
            h = mixFloat(h, nz);
            h = mixFloat(h, u);
            h = mixFloat(h, v);
            h = h * 31 + color;
            h = h * 31 + boundsMin;
            h = h * 31 + boundsMax;

            // SectionMeshData hashes the full 64-byte vertex stride, including
            // HardcodedVertex's zeroed padding ints.
            for (int i = 0; i < 5; i++) h *= 31;
            vertexHash = h;
        }
    }

    // --- per-block staging (flat arrays, zero alloc) ---

    private int stageCount;
    private final float[] stagePos = new float[MAX_BLOCK_QUADS * 12];
    private final float[] stageUvs = new float[MAX_BLOCK_QUADS * 8];
    private final float[] stageNx  = new float[MAX_BLOCK_QUADS];
    private final float[] stageNy  = new float[MAX_BLOCK_QUADS];
    private final float[] stageNz  = new float[MAX_BLOCK_QUADS];
    private final int[]   stageColor = new int[MAX_BLOCK_QUADS];
    private final Identifier[] stageSpriteId = new Identifier[MAX_BLOCK_QUADS];
    private final float[] stageSU0 = new float[MAX_BLOCK_QUADS];
    private final float[] stageSV0 = new float[MAX_BLOCK_QUADS];
    private final float[] stageSU1 = new float[MAX_BLOCK_QUADS];
    private final float[] stageSV1 = new float[MAX_BLOCK_QUADS];
    private final String[] stageLayer = new String[MAX_BLOCK_QUADS];
    private final boolean[] stageOverlay = new boolean[MAX_BLOCK_QUADS];
    private final boolean[] stageNeedsAlphaBlend = new boolean[MAX_BLOCK_QUADS];
    private final BlockTextureManager.SpriteEntry[] stageSpriteEntry =
            new BlockTextureManager.SpriteEntry[MAX_BLOCK_QUADS];
    private final int[] stageCategory = new int[MAX_BLOCK_QUADS];
    private final boolean[] stageDiscarded = new boolean[MAX_BLOCK_QUADS];
    private final boolean[] stageStacked = new boolean[MAX_BLOCK_QUADS];

    // --- one dust layer stack being cut apart (see writeDustLayerStacks) ---

    private final int[] stackMembers = new int[MAX_BLOCK_QUADS];
    private final int[] stackOwners = new int[MAX_STACK_RESOLUTION * MAX_STACK_RESOLUTION];
    /** Quads that cut stacks have added to this section beyond the layers they replaced. */
    private int sectionCutExtraQuads;
    private final float[] piecePos = new float[12];
    private final float[] pieceUvs = new float[8];
    private int stackAxisA, stackAxisB, stackResolution, stackColumns, stackRows;
    private float stackMinA, stackMinB;
    private float quadS, quadT;

    // --- public API ---

    /** Resets all bucket buffers for a new section. */
    public void beginSection() {
        beginSection(0, 0, 0);
    }

    /** Resets all bucket buffers for a new section. */
    public void beginSection(int sectionMinY) {
        beginSection(0, sectionMinY, 0);
    }

    /** Resets all bucket buffers for a new section. */
    public void beginSection(int sectionMinX, int sectionMinY, int sectionMinZ) {
        for (BucketBuffer bb : bucketOrder) bb.reset();
        sectionCutExtraQuads = 0;
        lastBucketCategory = -1;
        lastBucketKey = null;
        lastBucketEmission = null;
        lastBucket = null;
        stageCount = 0;
    }

    /** Releases all bucket buffers so GC can reclaim the memory. */
    public void releaseBuffers() {
        for (BucketBuffer bb : bucketOrder) {
            totalAllocatedBytes.addAndGet(-(long) bb.allocatedBytes());
        }
        for (Map<String, BucketBuffer>[] byEmission : buckets) {
            for (Map<String, BucketBuffer> byKey : byEmission) {
                byKey.clear();
            }
        }
        bucketOrder.clear();
        lastBucketCategory = -1;
        lastBucketKey = null;
        lastBucketEmission = null;
        lastBucket = null;
    }

    /**
     * Stages one quad for the current block. Positions must already include
     * the block's section-local offset. Does NOT write to output buffers yet.
     */
    public void stageQuad(float[] positions, float[] uvs,
                           float nx, float ny, float nz,
                           int color, Identifier spriteId,
                           float su0, float sv0, float su1, float sv1,
                           String renderLayer) {
        if (stageCount >= MAX_BLOCK_QUADS) return;
        int i = stageCount++;
        System.arraycopy(positions, 0, stagePos, i * 12, 12);
        System.arraycopy(uvs, 0, stageUvs, i * 8, 8);
        stageNx[i] = nx; stageNy[i] = ny; stageNz[i] = nz;
        stageColor[i] = color;
        stageSpriteId[i] = spriteId;
        stageSU0[i] = su0; stageSV0[i] = sv0;
        stageSU1[i] = su1; stageSV1[i] = sv1;
        stageLayer[i] = renderLayer;
        stageOverlay[i] = false;
        stageDiscarded[i] = false;
    }

    /**
     * Writes a fluid quad directly to output buffers (fluids are never overlays).
     */
    public void writeFluidQuad(float[] positions, float[] uvs,
                                float nx, float ny, float nz,
                                int color, Identifier spriteId,
                                float su0, float sv0, float su1, float sv1,
                                String renderLayer, BlockTextureManager tex) {

        int layerType = BlockTextureManager.layerType(renderLayer);
        boolean isWater = spriteId == null;

        int cat = isBlendedLayer(renderLayer) ? ChunkManager.CAT_TRANSLUCENT
                                              : ChunkManager.CAT_SOLID;

        String bk = isWater ? "water" : renderLayer;
        BlockTextureManager.SpriteEntry se = (spriteId != null && tex != null)
                ? tex.getSpriteById(spriteId) : null;
        if (se != null && se.hasSSS && tex != null && tex.getAtlasMaterialSSS(layerType) != null) {
            bk = sssBucketKeyForOriginalLayer(renderLayer, bk);
        }
        final String bucketKey = bk;

        BucketBuffer bb = getBucket(cat, bucketKey, RedstoneEmission.NONE);

        int boundsMin = isWater ? HardcodedVertex.packHalf2x16(0, 0)
                                : HardcodedVertex.packHalf2x16(su0, sv0);
        int boundsMax = isWater ? HardcodedVertex.packHalf2x16(1, 1)
                                : HardcodedVertex.packHalf2x16(su1, sv1);

        bb.writeQuad(positions, 0, uvs, 0, nx, ny, nz, color, boundsMin, boundsMax);
    }

    /**
     * Runs overlay detection on staged quads and flushes them all to
     * the appropriate bucket buffers. Call once per block after all
     * {@link #stageQuad} calls for that block.
     *
     * @param redstoneEmission the block's dust state, which every quad of the block shares;
     *                         {@link RedstoneEmission#NONE} for anything but redstone wire
     */
    public void flushBlock(BlockTextureManager tex, RedstoneEmission redstoneEmission) {
        if (stageCount == 0) return;
        detectOverlays(tex);

        int solidType = BlockTextureManager.layerType("solid");
        classifyStagedQuads(solidType);
        discardDuplicateCutoutBackfaces();
        if (redstoneEmission.isDust()) writeDustLayerStacks(tex, solidType, redstoneEmission);

        for (int i = 0; i < stageCount; i++) {
            if (stageDiscarded[i]) continue;
            writeStagedQuad(i, bucketFor(i, tex, solidType, redstoneEmission), stagePos, i * 12, stageUvs, i * 8);
        }

        Arrays.fill(stageSpriteId, 0, stageCount, null);
        Arrays.fill(stageLayer, 0, stageCount, null);
        Arrays.fill(stageSpriteEntry, 0, stageCount, null);
        Arrays.fill(stageDiscarded, 0, stageCount, false);
        Arrays.fill(stageStacked, 0, stageCount, false);
        stageCount = 0;
    }

    private BucketBuffer bucketFor(int i, BlockTextureManager tex, int solidType, RedstoneEmission redstoneEmission) {
        String layer = stageLayer[i];
        int layerType = BlockTextureManager.layerType(layer);
        BlockTextureManager.SpriteEntry se = stageSpriteEntry[i];
        int cat = stageCategory[i];

        String bk = layer;
        if (cat == ChunkManager.CAT_SOLID && layerType != solidType) {
            bk = "solid";
        }
        if (se != null && se.hasSSS && tex != null && tex.getAtlasMaterialSSS(layerType) != null) {
            bk = sssBucketKeyForOriginalLayer(layer, bk);
        }
        // Dust materials exist only for the alpha-tested and opaque layer types.
        RedstoneEmission emission = isBlendedLayer(layer) ? RedstoneEmission.NONE : redstoneEmission;
        // Dust glows under vanilla's power tint; a layer its model leaves untinted, such as a
        // pack's overlay, has no power colour to glow with.
        if (emission == RedstoneEmission.POWERED && stageColor[i] == UNTINTED) emission = RedstoneEmission.UNPOWERED;
        return getBucket(cat, bk, emission);
    }

    private void writeStagedQuad(int i, BucketBuffer bb, float[] positions, int posOff, float[] uvs, int uvOff) {
        int boundsMin = HardcodedVertex.packHalf2x16(stageSU0[i], stageSV0[i]);
        int boundsMax = HardcodedVertex.packHalf2x16(stageSU1[i], stageSV1[i]);
        bb.writeQuad(positions, posOff, uvs, uvOff,
                     stageNx[i], stageNy[i], stageNz[i],
                     stageColor[i], boundsMin, boundsMax);
    }

    // --- dust layer stacks ---

    /**
     * Cuts each stack of coplanar dust layers into pieces that never overlap: every texel
     * cell of the plane is drawn only by the last layer in paint order that is opaque there.
     * Remix continues a ray through a transparent alpha-tested texel from just past that
     * triangle's plane, so of overlapping coplanar layers only the first one a ray reaches is
     * ever seen: at a corner the full-block dot would hide the half-length lines under it.
     * A stack the cut cannot reproduce exactly (see {@link #resolveDustStack}), or one whose
     * pieces would overrun the section's {@link #MAX_CUT_EXTRA_QUADS}, is left as it was
     * staged, overlays included.
     */
    private void writeDustLayerStacks(BlockTextureManager tex, int solidType, RedstoneEmission redstoneEmission) {
        for (int first = 0; first < stageCount; first++) {
            if (stageDiscarded[first] || stageStacked[first] || isBlendedLayer(stageLayer[first])) continue;
            int members = 0;
            for (int j = first; j < stageCount; j++) {
                if (stageDiscarded[j] || stageStacked[j] || isBlendedLayer(stageLayer[j])) continue;
                if (j != first && !isCoplanar(first, j)) continue;
                stackMembers[members++] = j;
                stageStacked[j] = true;
            }
            if (members < 2 || !resolveDustStack(members)) continue;
            int extraQuads = countStackPieces() - members;
            if (extraQuads > MAX_CUT_EXTRA_QUADS - sectionCutExtraQuads) continue;
            sectionCutExtraQuads += Math.max(0, extraQuads);

            for (int k = 0; k < members; k++) {
                int quad = stackMembers[k];
                stageDiscarded[quad] = true;
                // Cut layers no longer overlap, so none stays over another as a decal.
                if (stageOverlay[quad]) {
                    stageOverlay[quad] = false;
                    classifyStagedQuad(quad, solidType);
                }
                BucketBuffer bb = bucketFor(quad, tex, solidType, redstoneEmission);
                for (int y = 0; y < stackRows; y++) {
                    int row = y * stackColumns;
                    for (int x = 0; x < stackColumns; x++) {
                        if (stackOwners[row + x] != k) continue;
                        int end = x + 1;
                        while (end < stackColumns && stackOwners[row + end] == k) end++;
                        writeOwnedPiece(quad, x, end, y, bb);
                        x = end - 1;
                    }
                }
            }
        }
    }

    /** The pieces a resolved stack is drawn with: one per run of a single owner along a row. */
    private int countStackPieces() {
        int pieces = 0;
        for (int y = 0; y < stackRows; y++) {
            int previous = -1;
            for (int x = 0; x < stackColumns; x++) {
                int owner = stackOwners[y * stackColumns + x];
                if (owner >= 0 && owner != previous) pieces++;
                previous = owner;
            }
        }
        return pieces;
    }

    /**
     * Fills {@link #stackOwners} with, per texel cell, the index into {@link #stackMembers} of
     * the layer drawn there, or -1. Returns false when the stack cannot be cut on a grid: a
     * layer is animated, since one cut cannot follow every frame's transparency, or a cell
     * spans more than one texel of a layer, as when a model scales a sprite's UVs, or a layer's
     * UVs are not affine across it, since pieces take their UVs from three of its corners, or
     * leave its sprite, whose transparency is all the cut knows.
     */
    private boolean resolveDustStack(int members) {
        int first = stackMembers[0];
        int axis = dominantAxis(stageNx[first], stageNy[first], stageNz[first]);
        int axisA = axis == 0 ? 1 : 0;
        int axisB = axis == 2 ? 1 : 2;

        int resolution = 0;
        float minA = Float.POSITIVE_INFINITY, maxA = Float.NEGATIVE_INFINITY;
        float minB = Float.POSITIVE_INFINITY, maxB = Float.NEGATIVE_INFINITY;
        for (int k = 0; k < members; k++) {
            int quad = stackMembers[k];
            BlockTextureManager.SpriteEntry sprite = stageSpriteEntry[quad];
            if (sprite == null || sprite.isAnimated() || !isAxisAlignedRectangle(quad, axisA, axisB)
                    || !hasAffineUvsInSprite(quad, sprite)) return false;
            resolution = Math.max(resolution, sprite.texelWidth());
            for (int v = 0; v < 4; v++) {
                float a = stagePos[quad * 12 + v * 3 + axisA];
                float b = stagePos[quad * 12 + v * 3 + axisB];
                minA = Math.min(minA, a); maxA = Math.max(maxA, a);
                minB = Math.min(minB, b); maxB = Math.max(maxB, b);
            }
        }
        if (resolution <= 0 || resolution > MAX_STACK_RESOLUTION) return false;

        int columns = Math.round((maxA - minA) * resolution);
        int rows = Math.round((maxB - minB) * resolution);
        if (columns <= 0 || rows <= 0 || columns * rows > stackOwners.length) return false;
        for (int k = 0; k < members; k++) {
            int quad = stackMembers[k];
            for (int v = 0; v < 4; v++) {
                if (!onGrid(stagePos[quad * 12 + v * 3 + axisA], resolution)
                        || !onGrid(stagePos[quad * 12 + v * 3 + axisB], resolution)) return false;
            }
        }

        stackAxisA = axisA;
        stackAxisB = axisB;
        stackMinA = minA;
        stackMinB = minB;
        stackResolution = resolution;
        stackColumns = columns;
        stackRows = rows;
        for (int y = 0; y < rows; y++) {
            float b = minB + (y + 0.5f) / resolution;
            for (int x = 0; x < columns; x++) {
                float a = minA + (x + 0.5f) / resolution;
                int owner = -1;
                for (int k = members - 1; k >= 0 && owner < 0; k--) {
                    int quad = stackMembers[k];
                    int texel = layerTexelAt(quad, a, b);
                    if (texel < 0) continue;
                    if (!cellLiesInTexel(quad, a, b, texel)) return false;
                    if (stageSpriteEntry[quad].isOpaqueTexel(texel)) owner = k;
                }
                stackOwners[y * columns + x] = owner;
            }
        }
        return true;
    }

    /** Whether a quad is a rectangle whose edges run along the plane's two axes. */
    private boolean isAxisAlignedRectangle(int quad, int axisA, int axisB) {
        int p = quad * 12;
        for (int c = 0; c < 3; c++) {
            float parallelogram = stagePos[p + 3 + c] + stagePos[p + 9 + c] - stagePos[p + c];
            if (Math.abs(stagePos[p + 6 + c] - parallelogram) > GRID_EPSILON) return false;
        }
        boolean edge1AlongA = Math.abs(stagePos[p + 3 + axisB] - stagePos[p + axisB]) <= GRID_EPSILON;
        boolean edge1AlongB = Math.abs(stagePos[p + 3 + axisA] - stagePos[p + axisA]) <= GRID_EPSILON;
        boolean edge2AlongA = Math.abs(stagePos[p + 9 + axisB] - stagePos[p + axisB]) <= GRID_EPSILON;
        boolean edge2AlongB = Math.abs(stagePos[p + 9 + axisA] - stagePos[p + axisA]) <= GRID_EPSILON;
        return (edge1AlongA && edge2AlongB) || (edge1AlongB && edge2AlongA);
    }

    /**
     * Whether a quad's UVs form a parallelogram inside its sprite. A parallelogram means one
     * affine mapping covers both of its triangles; otherwise each triangle maps the texture
     * its own way, and a piece cut from the quad could not reproduce either.
     */
    private boolean hasAffineUvsInSprite(int quad, BlockTextureManager.SpriteEntry sprite) {
        int uv = quad * 8;
        float texelsPerU = sprite.texelWidth() / (sprite.u1 - sprite.u0);
        float texelsPerV = sprite.texelHeight() / (sprite.v1 - sprite.v0);
        float du = stageUvs[uv + 4] - (stageUvs[uv + 2] + stageUvs[uv + 6] - stageUvs[uv]);
        float dv = stageUvs[uv + 5] - (stageUvs[uv + 3] + stageUvs[uv + 7] - stageUvs[uv + 1]);
        if (Math.abs(du * texelsPerU) > UV_TOLERANCE || Math.abs(dv * texelsPerV) > UV_TOLERANCE) return false;
        for (int corner = 0; corner < 4; corner++) {
            float u = stageUvs[uv + corner * 2], v = stageUvs[uv + corner * 2 + 1];
            if ((sprite.u0 - u) * texelsPerU > UV_TOLERANCE || (u - sprite.u1) * texelsPerU > UV_TOLERANCE
                    || (sprite.v0 - v) * texelsPerV > UV_TOLERANCE || (v - sprite.v1) * texelsPerV > UV_TOLERANCE) {
                return false;
            }
        }
        return true;
    }

    private static boolean onGrid(float coordinate, int resolution) {
        float cells = coordinate * resolution;
        return Math.abs(cells - Math.round(cells)) <= GRID_EPSILON * resolution;
    }

    /** The texel a quad samples at a plane point, or -1 when the point is outside the quad. */
    private int layerTexelAt(int quad, float a, float b) {
        if (!quadParameters(quad, a, b)) return -1;
        float s = quadS, t = quadT;
        if (s <= 0 || s >= 1 || t <= 0 || t >= 1) return -1;
        int uv = quad * 8;
        float u = stageUvs[uv] + s * (stageUvs[uv + 2] - stageUvs[uv]) + t * (stageUvs[uv + 6] - stageUvs[uv]);
        float v = stageUvs[uv + 1] + s * (stageUvs[uv + 3] - stageUvs[uv + 1])
                + t * (stageUvs[uv + 7] - stageUvs[uv + 1]);
        return stageSpriteEntry[quad].texelAt(u, v);
    }

    /**
     * Whether the cell centred on a plane point samples one texel of a quad throughout, so the
     * cell can be drawn or cut as a whole. A quad's UVs are affine across the cell, so its
     * four sampled corners bound everything but a thin margin at its edges.
     */
    private boolean cellLiesInTexel(int quad, float a, float b, int texel) {
        float reach = CELL_SAMPLE_REACH * 0.5f / stackResolution;
        return layerTexelAt(quad, a - reach, b - reach) == texel
                && layerTexelAt(quad, a + reach, b - reach) == texel
                && layerTexelAt(quad, a - reach, b + reach) == texel
                && layerTexelAt(quad, a + reach, b + reach) == texel;
    }

    /**
     * Solves a plane point for its position across the quad, from corner 0 along the edge to
     * corner 1 ({@link #quadS}) and along the edge to corner 3 ({@link #quadT}).
     */
    private boolean quadParameters(int quad, float a, float b) {
        int p = quad * 12;
        float originA = stagePos[p + stackAxisA], originB = stagePos[p + stackAxisB];
        float e1a = stagePos[p + 3 + stackAxisA] - originA, e1b = stagePos[p + 3 + stackAxisB] - originB;
        float e2a = stagePos[p + 9 + stackAxisA] - originA, e2b = stagePos[p + 9 + stackAxisB] - originB;
        float det = e1a * e2b - e2a * e1b;
        if (Math.abs(det) < GRID_EPSILON) return false;
        float da = a - originA, db = b - originB;
        quadS = (da * e2b - e2a * db) / det;
        quadT = (e1a * db - da * e1b) / det;
        return true;
    }

    /** Writes a run of texel cells in one row of a stack as a piece of the layer that owns them. */
    private void writeOwnedPiece(int quad, int x, int end, int y, BucketBuffer bb) {
        float a0 = stackMinA + (float) x / stackResolution, a1 = stackMinA + (float) end / stackResolution;
        float b0 = stackMinB + (float) y / stackResolution, b1 = stackMinB + (float) (y + 1) / stackResolution;
        quadParameters(quad, a0, b0);
        float sA = quadS, tA = quadT;
        quadParameters(quad, a1, b1);
        float s0 = Math.min(sA, quadS), s1 = Math.max(sA, quadS);
        float t0 = Math.min(tA, quadT), t1 = Math.max(tA, quadT);
        // Corners in the staged quad's own order keep its winding.
        writePieceCorner(quad, 0, s0, t0);
        writePieceCorner(quad, 1, s1, t0);
        writePieceCorner(quad, 2, s1, t1);
        writePieceCorner(quad, 3, s0, t1);
        writeStagedQuad(quad, bb, piecePos, 0, pieceUvs, 0);
    }

    private void writePieceCorner(int quad, int corner, float s, float t) {
        int p = quad * 12, uv = quad * 8;
        for (int c = 0; c < 3; c++) {
            piecePos[corner * 3 + c] = stagePos[p + c] + s * (stagePos[p + 3 + c] - stagePos[p + c])
                    + t * (stagePos[p + 9 + c] - stagePos[p + c]);
        }
        for (int c = 0; c < 2; c++) {
            pieceUvs[corner * 2 + c] = stageUvs[uv + c] + s * (stageUvs[uv + 2 + c] - stageUvs[uv + c])
                    + t * (stageUvs[uv + 6 + c] - stageUvs[uv + c]);
        }
    }

    private void classifyStagedQuads(int solidType) {
        for (int i = 0; i < stageCount; i++) classifyStagedQuad(i, solidType);
    }

    private void classifyStagedQuad(int i, int solidType) {
        String layer = stageLayer[i];
        int layerType = BlockTextureManager.layerType(layer);
        BlockTextureManager.SpriteEntry sprite = stageSpriteEntry[i];

        if (stageOverlay[i]) {
            stageCategory[i] = ChunkManager.CAT_OVERLAY;
        } else if (isBlendedLayer(layer)) {
            stageCategory[i] = ChunkManager.CAT_TRANSLUCENT;
        } else if (layerType == solidType || (sprite != null && !sprite.hasTransparency)) {
            stageCategory[i] = ChunkManager.CAT_SOLID;
        } else {
            stageCategory[i] = ChunkManager.CAT_CUTOUT;
        }
    }

    private void discardDuplicateCutoutBackfaces() {
        for (int i = 0; i < stageCount; i++) {
            if (stageDiscarded[i] || stageCategory[i] != ChunkManager.CAT_CUTOUT) continue;

            for (int j = i + 1; j < stageCount; j++) {
                if (!stageDiscarded[j]
                        && stageCategory[j] == ChunkManager.CAT_CUTOUT
                        && isDuplicateCutoutBackface(i, j)) {
                    stageDiscarded[j] = true;
                }
            }
        }
    }

    private boolean isDuplicateCutoutBackface(int a, int b) {
        float normalDot = stageNx[a] * stageNx[b]
                + stageNy[a] * stageNy[b]
                + stageNz[a] * stageNz[b];
        if (normalDot > -DECAL_NORMAL_DOT_EPSILON) return false;

        if (stageColor[a] != stageColor[b]
                || !Objects.equals(stageSpriteId[a], stageSpriteId[b])
                || !Objects.equals(stageLayer[a], stageLayer[b])
                || stageSU0[a] != stageSU0[b]
                || stageSV0[a] != stageSV0[b]
                || stageSU1[a] != stageSU1[b]
                || stageSV1[a] != stageSV1[b]) {
            return false;
        }

        return haveSameUnorderedPositions(a * 12, b * 12);
    }

    private boolean haveSameUnorderedPositions(int aPos, int bPos) {
        int matchedVertices = 0;
        for (int aVertex = 0; aVertex < 4; aVertex++) {
            int ai = aPos + aVertex * 3;
            boolean matched = false;
            for (int bVertex = 0; bVertex < 4; bVertex++) {
                int bit = 1 << bVertex;
                if ((matchedVertices & bit) != 0) continue;

                int bi = bPos + bVertex * 3;
                if (stagePos[ai] == stagePos[bi]
                        && stagePos[ai + 1] == stagePos[bi + 1]
                        && stagePos[ai + 2] == stagePos[bi + 2]) {
                    matchedVertices |= bit;
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        return true;
    }

    /**
     * Produces a compact {@link SectionMeshData} from all accumulated bucket
     * buffers. Each surface is an exact-length copy in a {@link DirectBufferPool}
     * buffer, owned by the returned data; the oversized work buffers stay owned
     * by this writer for reuse.
     */
    public SectionMeshData buildMeshData() {
        int activeBuckets = 0;
        int totalVerts = 0;
        for (BucketBuffer bb : bucketOrder) {
            if (bb.vertexCount > 0) {
                activeBuckets++;
                totalVerts += bb.vertexCount;
            }
        }
        try (ProfileScope buildScope = ProfileScope.begin("buildMeshData")) {
            buildScope.surfaceCount(activeBuckets)
                    .vertexCount(totalVerts)
                    .bytes((long) totalVerts * STRIDE);
            List<SectionMeshData.Surface> surfaces = new ArrayList<>();
            int contentHash = activeBuckets;

            // The buffers belong to the SectionMeshData once it exists; until then they
            // are owned here, and a throw (an acquire failing, the constructor rejecting
            // the surfaces) must return the ones already copied.
            boolean transferred = false;
            try {
                for (BucketBuffer bb : bucketOrder) {
                    if (bb.vertexCount == 0) continue;
                    contentHash = bb.appendToContentHash(contentHash);

                    int vBytes = bb.vertexCount * STRIDE;
                    ByteBuffer vb = DirectBufferPool.acquire(vBytes);
                    ByteBuffer srcV = bb.vertices.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                    srcV.position(0).limit(vBytes);
                    vb.put(srcV);
                    vb.flip();

                    surfaces.add(new SectionMeshData.Surface(
                            bb.category, bb.key, bb.redstoneEmission, vb, bb.vertexCount, bb.indexCount, true));
                }

                SectionMeshData data = new SectionMeshData(surfaces, contentHash);
                transferred = true;
                return data;
            } finally {
                if (!transferred) {
                    for (SectionMeshData.Surface surface : surfaces) {
                        DirectBufferPool.release(surface.vertexBuffer());
                    }
                }
            }
        }
    }

    private static int mixFloat(int h, float value) {
        return h * 31 + Float.floatToRawIntBits(value);
    }

    static int appendHash(int seed, int valueHash, int valueCount) {
        return seed * pow31(valueCount) + valueHash;
    }

    private static int pow31(int exponent) {
        int result = 1;
        int base = 31;
        int remaining = exponent;
        while (remaining != 0) {
            if ((remaining & 1) != 0) result *= base;
            base *= base;
            remaining >>>= 1;
        }
        return result;
    }

    // --- overlay detection ---

    private void detectOverlays(BlockTextureManager tex) {
        boolean hasCandidate = false;
        boolean hasBase = false;
        for (int i = 0; i < stageCount; i++) {
            Identifier spriteId = stageSpriteId[i];
            BlockTextureManager.SpriteEntry sprite = spriteId != null && tex != null
                    ? tex.getSpriteById(spriteId)
                    : null;
            stageSpriteEntry[i] = sprite;
            boolean needsBlend = isBlendedLayer(stageLayer[i])
                    || (sprite != null && quadSamplesTransparentTexels(i, sprite));
            stageNeedsAlphaBlend[i] = needsBlend;
            hasCandidate |= needsBlend;
            hasBase |= !needsBlend;
        }
        if (!hasCandidate || !hasBase) return;

        for (int i = 0; i < stageCount; i++) {
            if (!stageNeedsAlphaBlend[i]) continue;

            for (int j = 0; j < stageCount; j++) {
                if (i == j || stageNeedsAlphaBlend[j]) continue;
                if (isCoplanarOverlappingDecal(i, j)) {
                    stageOverlay[i] = true;
                    break;
                }
            }
        }
    }

    private boolean quadSamplesTransparentTexels(int quadIdx, BlockTextureManager.SpriteEntry se) {
        int uvOff = quadIdx * 8;
        float minU = Float.POSITIVE_INFINITY;
        float minV = Float.POSITIVE_INFINITY;
        float maxU = Float.NEGATIVE_INFINITY;
        float maxV = Float.NEGATIVE_INFINITY;
        for (int v = 0; v < 4; v++) {
            float u = stageUvs[uvOff + v * 2];
            float vv = stageUvs[uvOff + v * 2 + 1];
            minU = Math.min(minU, u);
            minV = Math.min(minV, vv);
            maxU = Math.max(maxU, u);
            maxV = Math.max(maxV, vv);
        }
        return se.hasTransparency(minU, minV, maxU, maxV);
    }

    private boolean isCoplanarOverlappingDecal(int overlayIdx, int baseIdx) {
        if (!isCoplanar(overlayIdx, baseIdx)) {
            return false;
        }
        int axis = dominantAxis(stageNx[overlayIdx], stageNy[overlayIdx], stageNz[overlayIdx]);
        return projectedBoundsOverlap(overlayIdx * 12, baseIdx * 12, axis);
    }

    /** Whether two quads face the same way in one plane. */
    private boolean isCoplanar(int a, int b) {
        float anx = stageNx[a], any = stageNy[a], anz = stageNz[a];
        float bnx = stageNx[b], bny = stageNy[b], bnz = stageNz[b];
        float dot = anx * bnx + any * bny + anz * bnz;
        if (dot < DECAL_NORMAL_DOT_EPSILON) {
            return false;
        }
        float aPlane = planeDistance(anx, any, anz, a * 12);
        float bPlane = planeDistance(bnx, bny, bnz, b * 12);
        return Math.abs(aPlane - bPlane) <= DECAL_PLANE_DISTANCE_EPSILON;
    }

    private float planeDistance(float nx, float ny, float nz, int posOff) {
        return nx * stagePos[posOff] + ny * stagePos[posOff + 1] + nz * stagePos[posOff + 2];
    }

    private static int dominantAxis(float nx, float ny, float nz) {
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        if (ax >= ay && ax >= az) return 0;
        return ay >= az ? 1 : 2;
    }

    private boolean projectedBoundsOverlap(int aPos, int bPos, int droppedAxis) {
        float aMin0 = Float.POSITIVE_INFINITY, aMax0 = Float.NEGATIVE_INFINITY;
        float aMin1 = Float.POSITIVE_INFINITY, aMax1 = Float.NEGATIVE_INFINITY;
        float bMin0 = Float.POSITIVE_INFINITY, bMax0 = Float.NEGATIVE_INFINITY;
        float bMin1 = Float.POSITIVE_INFINITY, bMax1 = Float.NEGATIVE_INFINITY;

        for (int v = 0; v < 4; v++) {
            int ai = aPos + v * 3;
            int bi = bPos + v * 3;
            float a0 = projectedCoord(stagePos[ai], stagePos[ai + 1], stagePos[ai + 2], droppedAxis, 0);
            float a1 = projectedCoord(stagePos[ai], stagePos[ai + 1], stagePos[ai + 2], droppedAxis, 1);
            float b0 = projectedCoord(stagePos[bi], stagePos[bi + 1], stagePos[bi + 2], droppedAxis, 0);
            float b1 = projectedCoord(stagePos[bi], stagePos[bi + 1], stagePos[bi + 2], droppedAxis, 1);

            aMin0 = Math.min(aMin0, a0); aMax0 = Math.max(aMax0, a0);
            aMin1 = Math.min(aMin1, a1); aMax1 = Math.max(aMax1, a1);
            bMin0 = Math.min(bMin0, b0); bMax0 = Math.max(bMax0, b0);
            bMin1 = Math.min(bMin1, b1); bMax1 = Math.max(bMax1, b1);
        }

        return rangesOverlap(aMin0, aMax0, bMin0, bMax0)
                && rangesOverlap(aMin1, aMax1, bMin1, bMax1);
    }

    private static float projectedCoord(float x, float y, float z, int droppedAxis, int component) {
        return switch (droppedAxis) {
            case 0 -> component == 0 ? y : z;
            case 1 -> component == 0 ? x : z;
            default -> component == 0 ? x : y;
        };
    }

    private static boolean rangesOverlap(float aMin, float aMax, float bMin, float bMax) {
        return aMin < bMax - DECAL_OVERLAP_EPSILON
                && bMin < aMax - DECAL_OVERLAP_EPSILON;
    }

    private BucketBuffer getBucket(int category, String bucketKey, RedstoneEmission emission) {
        if (category == lastBucketCategory
                && emission == lastBucketEmission
                && (bucketKey == lastBucketKey
                    || (bucketKey != null && bucketKey.equals(lastBucketKey)))) {
            return lastBucket;
        }

        Map<String, BucketBuffer> byKey = buckets[category][emission.ordinal()];
        BucketBuffer bb = byKey.get(bucketKey);
        if (bb == null) {
            bb = new BucketBuffer(category, bucketKey, emission);
            byKey.put(bucketKey, bb);
            bucketOrder.add(bb);
        }
        lastBucketCategory = category;
        lastBucketKey = bucketKey;
        lastBucketEmission = emission;
        lastBucket = bb;
        return bb;
    }

    static String sssBucketKeyForOriginalLayer(String layer, String bucketKey) {
        return switch (layer) {
            case "cutout" -> "cutout_sss";
            case "cutout_mipped" -> "cutout_mipped_sss";
            default -> sssBucketKey(bucketKey);
        };
    }

    private static String sssBucketKey(String bucketKey) {
        return switch (bucketKey) {
            case "solid" -> "solid_sss";
            case "cutout" -> "cutout_sss";
            case "cutout_mipped" -> "cutout_mipped_sss";
            case "translucent" -> "translucent_sss";
            case "glass" -> "glass_sss";
            case "water" -> "water_sss";
            default -> bucketKey + "_sss";
        };
    }

    /** Layers whose material blends rather than tests alpha: vanilla's translucent layer and clear glass. */
    static boolean isBlendedLayer(String layer) {
        return "translucent".equals(layer) || "glass".equals(layer);
    }

}

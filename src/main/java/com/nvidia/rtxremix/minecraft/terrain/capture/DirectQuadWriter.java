/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.terrain.ChunkManager;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.HardcodedVertex;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.Identifier;

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
    private static final float DECAL_OVERLAP_EPSILON = 1.0E-4f;

    /**
     * Worst-case quads per 16x16x16 section.  4096 blocks x 6 faces = 24576.
     * Used as the growth ceiling for bucket buffers, not for initial sizing.
     */
    private static final int MAX_SECTION_QUADS = 24576;
    private static final int MAX_VERT_BUF_SIZE = MAX_SECTION_QUADS * 4 * STRIDE;
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

    @SuppressWarnings("unchecked")
    private final Map<String, BucketBuffer>[] bucketsByCategory = new Map[ChunkManager.CAT_COUNT];
    private final List<BucketBuffer> bucketOrder = new ArrayList<>();
    private int lastBucketCategory = -1;
    private String lastBucketKey;
    private BucketBuffer lastBucket;

    public DirectQuadWriter() {
        for (int i = 0; i < bucketsByCategory.length; i++) {
            bucketsByCategory[i] = new LinkedHashMap<>();
        }
    }

    static final class BucketBuffer {
        final int category;
        final String key;
        ByteBuffer vertices;
        int vertexCount;
        int indexCount;
        int vertexHash;

        BucketBuffer(int category, String key) {
            this.category = category;
            this.key = key;
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
        lastBucketCategory = -1;
        lastBucketKey = null;
        lastBucket = null;
        stageCount = 0;
    }

    /** Releases all bucket buffers so GC can reclaim the memory. */
    public void releaseBuffers() {
        for (BucketBuffer bb : bucketOrder) {
            totalAllocatedBytes.addAndGet(-(long) bb.allocatedBytes());
        }
        for (Map<String, BucketBuffer> buckets : bucketsByCategory) {
            buckets.clear();
        }
        bucketOrder.clear();
        lastBucketCategory = -1;
        lastBucketKey = null;
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

        BucketBuffer bb = getBucket(cat, bucketKey);

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
     */
    public void flushBlock(BlockTextureManager tex) {
        if (stageCount == 0) return;
        detectOverlays(tex);

        int solidType = BlockTextureManager.layerType("solid");
        classifyStagedQuads(solidType);
        discardDuplicateCutoutBackfaces();

        for (int i = 0; i < stageCount; i++) {
            if (stageDiscarded[i]) continue;

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
            final String bucketKey = bk;

            BucketBuffer bb = getBucket(cat, bucketKey);

            int boundsMin = HardcodedVertex.packHalf2x16(stageSU0[i], stageSV0[i]);
            int boundsMax = HardcodedVertex.packHalf2x16(stageSU1[i], stageSV1[i]);

            bb.writeQuad(stagePos, i * 12, stageUvs, i * 8,
                         stageNx[i], stageNy[i], stageNz[i],
                         stageColor[i], boundsMin, boundsMax);
        }

        Arrays.fill(stageSpriteId, 0, stageCount, null);
        Arrays.fill(stageLayer, 0, stageCount, null);
        Arrays.fill(stageSpriteEntry, 0, stageCount, null);
        Arrays.fill(stageDiscarded, 0, stageCount, false);
        stageCount = 0;
    }

    private void classifyStagedQuads(int solidType) {
        for (int i = 0; i < stageCount; i++) {
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
                            bb.category, bb.key, vb, bb.vertexCount, bb.indexCount, true));
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
        float onx = stageNx[overlayIdx], ony = stageNy[overlayIdx], onz = stageNz[overlayIdx];
        float bnx = stageNx[baseIdx], bny = stageNy[baseIdx], bnz = stageNz[baseIdx];
        float dot = onx * bnx + ony * bny + onz * bnz;
        if (dot < DECAL_NORMAL_DOT_EPSILON) {
            return false;
        }

        int overlayPos = overlayIdx * 12;
        int basePos = baseIdx * 12;
        float overlayPlane = planeDistance(onx, ony, onz, overlayPos);
        float basePlane = planeDistance(bnx, bny, bnz, basePos);
        if (Math.abs(overlayPlane - basePlane) > DECAL_PLANE_DISTANCE_EPSILON) {
            return false;
        }

        int axis = dominantAxis(onx, ony, onz);
        return projectedBoundsOverlap(overlayPos, basePos, axis);
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

    private BucketBuffer getBucket(int category, String bucketKey) {
        if (category == lastBucketCategory
                && (bucketKey == lastBucketKey
                    || (bucketKey != null && bucketKey.equals(lastBucketKey)))) {
            return lastBucket;
        }

        Map<String, BucketBuffer> buckets = bucketsByCategory[category];
        BucketBuffer bb = buckets.get(bucketKey);
        if (bb == null) {
            bb = new BucketBuffer(category, bucketKey);
            buckets.put(bucketKey, bb);
            bucketOrder.add(bb);
        }
        lastBucketCategory = category;
        lastBucketKey = bucketKey;
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

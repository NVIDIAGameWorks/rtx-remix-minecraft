/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.texture.RedstoneEmission;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionMeshData;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Merges the section captures from one chunk column into category-specific,
 * byte-capped {@link RemixMesh} pages. Captured vertices are section-local;
 * the builder offsets them into column-local space and the instance transform
 * places each page in world space.
 */
public final class BlockMeshBuilder {

    private static final int STRIDE = HardcodedVertex.STRIDE; // 64
    private static final int VERTS_PER_QUAD = 4;
    private static final int INDICES_PER_QUAD = 6;
    private static final int BYTES_PER_CANONICAL_QUAD =
            VERTS_PER_QUAD * STRIDE + INDICES_PER_QUAD * Integer.BYTES;
    private static final int INITIAL_BUCKETS = 8;
    private static final long MIB = 1024L * 1024L;
    private static final long MIN_CONFIGURED_PAGE_BYTES = 256L * 1024L;
    private static final long DEFAULT_MAX_PAGE_BYTES = 8L * MIB;
    private static final long MAX_CONFIGURED_PAGE_BYTES = 64L * MIB;
    public static final int ALL_CATEGORY_MASK = (1 << ChunkManager.CAT_COUNT) - 1;
    private static volatile int[] canonicalIndexCache = new int[0];
    private static volatile int canonicalIndexCacheFilled;
    private final long maxPageBytes;

    /** One independently installable native mesh page and its render category. */
    public record MeshPage(int category, RemixMesh mesh) {
        public MeshPage {
            if (category < 0 || category >= ChunkManager.CAT_COUNT) {
                throw new IllegalArgumentException("Invalid terrain mesh category " + category);
            }
            Objects.requireNonNull(mesh, "mesh");
        }

        public long estimatedNativeBytes() {
            return mesh.estimatedNativeBytes();
        }
    }

    public BlockMeshBuilder() {
        this(configuredPageBytes());
    }

    BlockMeshBuilder(long maxPageBytes) {
        this.maxPageBytes = Math.max(BYTES_PER_CANONICAL_QUAD,
                Math.min(MAX_CONFIGURED_PAGE_BYTES, maxPageBytes));
    }

    /**
     * Builds Remix meshes for one 16x16 chunk column.
     * Returns byte-capped pages. Every page carries the category metadata
     * needed to create its instance independently on the render thread.
     */
    public MeshPage[] buildColumnMesh(int chunkX,
                                      int chunkZ,
                                      List<TerrainState.SectionSnapshot> sections,
                                      int categoryMask,
                                      BlockTextureManager tex) {
        return buildColumnMesh(chunkX, chunkZ, sections, categoryMask,
                (key, emission) -> resolveMaterial(key, emission, tex));
    }

    MeshPage[] buildColumnMesh(int chunkX,
                               int chunkZ,
                               List<TerrainState.SectionSnapshot> sections,
                               int categoryMask,
                               BiFunction<String, RedstoneEmission, RemixMaterial> materialResolver) {
        Objects.requireNonNull(sections, "sections");
        Objects.requireNonNull(materialResolver, "materialResolver");
        validateCategoryMask(categoryMask);

        List<MeshPage> pages = new ArrayList<>();
        PageAccumulator accumulator = null;
        try (ProfileScope buildScope = ProfileScope.begin("buildColumnMesh")) {
            buildScope.captureCount(sections.size());
            int catCount = ChunkManager.CAT_COUNT;
            int columnWorldX = chunkX * 16;
            int columnWorldZ = chunkZ * 16;
            SurfaceBucket[] buckets = new SurfaceBucket[INITIAL_BUCKETS];
            int bucketCount = 0;

            for (TerrainState.SectionSnapshot snapshot : sections) {
                if (snapshot.xOff() != columnWorldX || snapshot.zOff() != columnWorldZ) {
                    throw new IllegalArgumentException(
                            "Section snapshot does not belong to chunk column ("
                                    + chunkX + "," + chunkZ + ")");
                }
                if (!(snapshot.data() instanceof SectionMeshData meshData) || meshData.isEmpty()) {
                    continue;
                }

                for (SectionMeshData.Surface surf : meshData.surfaces()) {
                    int cat = surf.category();
                    if (cat < 0 || cat >= catCount || !categorySelected(categoryMask, cat)) continue;

                    String bucketKey = surf.bucketKey();
                    RedstoneEmission emission = surf.redstoneEmission();
                    int bucketIndex = findBucket(buckets, bucketCount, cat, bucketKey, emission);
                    SurfaceBucket bucket;
                    if (bucketIndex >= 0) {
                        bucket = buckets[bucketIndex];
                    } else {
                        if (bucketCount == buckets.length) {
                            buckets = Arrays.copyOf(buckets, buckets.length * 2);
                        }
                        bucket = new SurfaceBucket(cat, bucketKey, emission);
                        buckets[bucketCount++] = bucket;
                    }
                    bucket.add(surf,
                            snapshot.xOff() - columnWorldX,
                            snapshot.yOff(),
                            snapshot.zOff() - columnWorldZ);
                }
            }

            for (int c = 0; c < catCount; c++) {
                if (!categorySelected(categoryMask, c)) continue;
                accumulator = new PageAccumulator(c, pages, maxPageBytes);
                for (int i = 0; i < bucketCount; i++) {
                    SurfaceBucket bucket = buckets[i];
                    if (bucket.category != c || bucket.count == 0) continue;

                    RemixMaterial mat = materialResolver.apply(bucket.key, bucket.emission);
                    if (mat == null) continue;

                    requireCanonicalQuadIndices(bucket);
                    appendCanonicalPages(bucket, mat, accumulator);
                }
                accumulator.flush();
                accumulator = null;
            }
            buildScope.installCount(pages.size());
            return pages.toArray(new MeshPage[0]);
        } catch (RuntimeException | Error failure) {
            if (accumulator != null) accumulator.disposePending();
            for (MeshPage page : pages) {
                page.mesh().disposeData();
            }
            throw failure;
        }
    }

    // --- internals ---

    private static final class SurfaceBucket {
        final int category;
        final String key;
        final RedstoneEmission emission;
        SurfaceRef[] surfaces = new SurfaceRef[4];
        int count;
        int totalVerts;

        SurfaceBucket(int category, String key, RedstoneEmission emission) {
            this.category = category;
            this.key = key;
            this.emission = emission;
        }

        void add(SectionMeshData.Surface surface, int xOff, int yOff, int zOff) {
            if (count == surfaces.length) {
                int next = count * 2;
                surfaces = Arrays.copyOf(surfaces, next);
            }
            surfaces[count] = new SurfaceRef(surface, xOff, yOff, zOff);
            count++;
            totalVerts += surface.vertexCount();
        }
    }

    private static final class PageAccumulator {
        private final int category;
        private final List<MeshPage> output;
        private final long maxPageBytes;
        private final List<RemixMeshSurface> surfaces = new ArrayList<>();
        private long bytes;

        PageAccumulator(int category, List<MeshPage> output, long maxPageBytes) {
            this.category = category;
            this.output = output;
            this.maxPageBytes = maxPageBytes;
        }

        void add(RemixMeshSurface surface, long surfaceBytes) {
            if (surfaceBytes <= 0 || surfaceBytes > maxPageBytes) {
                surface.disposeData();
                throw new IllegalArgumentException(
                        "Terrain surface page payload " + surfaceBytes
                                + " is outside the configured cap " + maxPageBytes);
            }
            boolean adopted = false;
            try {
                if (!surfaces.isEmpty() && bytes + surfaceBytes > maxPageBytes) {
                    flush();
                }
                surfaces.add(surface);
                adopted = true;
                bytes += surfaceBytes;
                if (bytes == maxPageBytes) {
                    flush();
                }
            } finally {
                if (!adopted) surface.disposeData();
            }
        }

        void flush() {
            if (surfaces.isEmpty()) return;
            RemixMesh mesh = null;
            try {
                mesh = new RemixMesh(surfaces.toArray(new RemixMeshSurface[0]));
                output.add(new MeshPage(category, mesh));
            } catch (RuntimeException | Error failure) {
                if (mesh != null) {
                    mesh.disposeData();
                    surfaces.clear();
                    bytes = 0;
                } else {
                    disposePending();
                }
                throw failure;
            }
            surfaces.clear();
            bytes = 0;
        }

        void disposePending() {
            for (RemixMeshSurface surface : surfaces) {
                surface.disposeData();
            }
            surfaces.clear();
            bytes = 0;
        }
    }

    private record SurfaceRef(SectionMeshData.Surface surface,
                              int xOff, int yOff, int zOff) {}

    private static long configuredPageBytes() {
        long configured = Long.getLong("rtxremix.chunk.meshPageBytes", DEFAULT_MAX_PAGE_BYTES);
        return Math.max(MIN_CONFIGURED_PAGE_BYTES,
                Math.min(MAX_CONFIGURED_PAGE_BYTES, configured));
    }

    private static boolean categorySelected(int categoryMask, int category) {
        return (categoryMask & (1 << category)) != 0;
    }

    private static void validateCategoryMask(int categoryMask) {
        if ((categoryMask & ~ALL_CATEGORY_MASK) != 0) {
            throw new IllegalArgumentException(
                    "Invalid terrain category mask 0x" + Integer.toHexString(categoryMask));
        }
    }

    private void appendCanonicalPages(SurfaceBucket bucket,
                                      RemixMaterial material,
                                      PageAccumulator accumulator) {
        int totalQuads = bucket.totalVerts / VERTS_PER_QUAD;
        int maxQuadsPerSurface = (int) Math.max(1L,
                Math.min(Integer.MAX_VALUE, maxPageBytes / BYTES_PER_CANONICAL_QUAD));
        for (int firstQuad = 0; firstQuad < totalQuads; ) {
            int quadCount = Math.min(maxQuadsPerSurface, totalQuads - firstQuad);
            RemixMeshSurface surface = mergeCanonicalSurfaceRange(
                    bucket, material, firstQuad, quadCount);
            accumulator.add(surface, (long) quadCount * BYTES_PER_CANONICAL_QUAD);
            firstQuad += quadCount;
        }
    }

    private static RemixMeshSurface mergeCanonicalSurfaceRange(
            SurfaceBucket bucket,
            RemixMaterial material,
            int firstQuad,
            int quadCount) {
        int totalVerts = Math.multiplyExact(quadCount, VERTS_PER_QUAD);
        int totalIdx = Math.multiplyExact(quadCount, INDICES_PER_QUAD);
        ByteBuffer vm = null;
        ByteBuffer im = null;
        boolean transferred = false;

        try {
            vm = DirectBufferPool.acquire(Math.multiplyExact(totalVerts, STRIDE));
            im = DirectBufferPool.acquire(Math.multiplyExact(totalIdx, Integer.BYTES));

            int wantedStart = firstQuad;
            int wantedEnd = firstQuad + quadCount;
            int sourceStart = 0;
            int outputVerts = 0;
            for (int s = 0; s < bucket.count && sourceStart < wantedEnd; s++) {
                SurfaceRef ref = bucket.surfaces[s];
                SectionMeshData.Surface source = ref.surface();
                int sourceQuads = source.vertexCount() / VERTS_PER_QUAD;
                int sourceEnd = sourceStart + sourceQuads;
                int overlapStart = Math.max(wantedStart, sourceStart);
                int overlapEnd = Math.min(wantedEnd, sourceEnd);
                if (overlapStart < overlapEnd) {
                    int localFirstQuad = overlapStart - sourceStart;
                    int copyQuads = overlapEnd - overlapStart;
                    int copyVerts = copyQuads * VERTS_PER_QUAD;
                    int sourceByte = localFirstQuad * VERTS_PER_QUAD * STRIDE;
                    int copyBytes = copyVerts * STRIDE;

                    ByteBuffer src = source.vertexBuffer().duplicate().order(ByteOrder.LITTLE_ENDIAN);
                    src.position(sourceByte).limit(sourceByte + copyBytes);
                    vm.position(outputVerts * STRIDE);
                    vm.put(src);
                    addVertexOffset(vm, outputVerts, copyVerts,
                            ref.xOff(), ref.yOff(), ref.zOff());
                    outputVerts += copyVerts;
                }
                sourceStart = sourceEnd;
            }

            if (outputVerts != totalVerts) {
                throw new IllegalArgumentException(
                        "Canonical terrain page range exceeds the bucket payload");
            }
            putCanonicalQuadIndices(im, totalVerts);
            RemixMeshSurface surface = new RemixMeshSurface(material)
                    .setVertexData(vm, totalVerts)
                    .setIndexData(im, totalIdx)
                    .setFlags(MeshInfoSurfaceTriangles.TEXCOORD_BOUNDS);
            transferred = true;
            return surface;
        } finally {
            if (!transferred) {
                DirectBufferPool.release(vm);
                DirectBufferPool.release(im);
            }
        }
    }

    /**
     * Asserts the pipeline invariant that {@code DirectQuadWriter} emits: whole quads
     * whose indices follow the fixed per-quad pattern. Paging rewrites the indices from
     * that pattern rather than copying them, so a surface that breaks the invariant has
     * no representation here and must fail rather than be silently reinterpreted.
     */
    private static void requireCanonicalQuadIndices(SurfaceBucket bucket) {
        for (int s = 0; s < bucket.count; s++) {
            SectionMeshData.Surface surface = bucket.surfaces[s].surface();
            if (!surface.canonicalQuadIndices()
                    || surface.vertexCount() % VERTS_PER_QUAD != 0
                    || surface.indexCount() != canonicalIndexCount(surface.vertexCount())) {
                throw new IllegalArgumentException(
                        "Terrain surface in bucket " + bucket.key + " with "
                                + surface.vertexCount() + " vertices and "
                                + surface.indexCount()
                                + " indices does not carry canonical quad indices");
            }
        }
    }

    private static int findBucket(SurfaceBucket[] buckets, int bucketCount, int category, String key,
                                  RedstoneEmission emission) {
        for (int i = 0; i < bucketCount; i++) {
            SurfaceBucket bucket = buckets[i];
            if (bucket.category == category && bucket.emission == emission
                    && Objects.equals(bucket.key, key)) {
                return i;
            }
        }
        return -1;
    }

    private RemixMaterial resolveMaterial(String bucketKey, RedstoneEmission emission,
                                          BlockTextureManager tex) {
        boolean isSss = bucketKey.endsWith("_sss");
        String baseName = isSss ? bucketKey.substring(0, bucketKey.length() - 4) : bucketKey;

        if ("water".equals(baseName)) return tex.getWaterSurfaceMaterial();

        return tex.getAtlasMaterial(BlockTextureManager.layerType(baseName), isSss, emission);
    }


    private static void addVertexOffset(ByteBuffer vertices, int vertBase, int vertexCount,
                                        int xOff, int yOff, int zOff) {
        if (xOff == 0 && yOff == 0 && zOff == 0) return;
        int baseByte = vertBase * STRIDE;
        for (int v = 0; v < vertexCount; v++) {
            int pos = baseByte + v * STRIDE;
            vertices.putFloat(pos, vertices.getFloat(pos) + xOff);
            vertices.putFloat(pos + 4, vertices.getFloat(pos + 4) + yOff);
            vertices.putFloat(pos + 8, vertices.getFloat(pos + 8) + zOff);
        }
    }

    private static int canonicalIndexCount(int vertexCount) {
        return (vertexCount / VERTS_PER_QUAD) * INDICES_PER_QUAD;
    }

    /**
     * Writes the fixed per-quad index pattern for a whole page. Every page starts its own
     * vertex numbering at zero, so the pattern is position-independent and comes from the
     * shared cache rather than being generated per page.
     */
    static void putCanonicalQuadIndices(ByteBuffer dst, int vertexCount) {
        int indexCount = canonicalIndexCount(vertexCount);
        ByteBuffer indexBytes = dst.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        indexBytes.position(0);
        IntBuffer ints = indexBytes.slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        ints.put(canonicalIndices(indexCount), 0, indexCount);
    }

    private static int[] canonicalIndices(int indexCount) {
        int[] cached = canonicalIndexCache;
        if (cached.length >= indexCount && canonicalIndexCacheFilled >= indexCount) {
            return cached;
        }

        synchronized (BlockMeshBuilder.class) {
            cached = canonicalIndexCache;
            int filled = canonicalIndexCacheFilled;
            if (cached.length < indexCount) {
                int newLength = Math.max(indexCount, Math.max(INDICES_PER_QUAD, cached.length * 2));
                cached = Arrays.copyOf(cached, newLength);
            }
            fillCanonicalIndices(cached, filled, indexCount);
            canonicalIndexCache = cached;
            canonicalIndexCacheFilled = indexCount;
            return cached;
        }
    }

    private static void fillCanonicalIndices(int[] dst, int fromIndex, int toIndex) {
        int startQuad = fromIndex / INDICES_PER_QUAD;
        int endQuad = toIndex / INDICES_PER_QUAD;
        for (int q = startQuad; q < endQuad; q++) {
            int base = q * VERTS_PER_QUAD;
            int i = q * INDICES_PER_QUAD;
            dst[i] = base;
            dst[i + 1] = base + 2;
            dst[i + 2] = base + 1;
            dst[i + 3] = base;
            dst[i + 4] = base + 3;
            dst[i + 5] = base + 2;
        }
    }
}

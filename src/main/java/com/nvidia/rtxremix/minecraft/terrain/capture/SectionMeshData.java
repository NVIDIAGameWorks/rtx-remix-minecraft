/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import com.nvidia.rtxremix.minecraft.terrain.ChunkManager;
import com.nvidia.rtxremix.minecraft.texture.RedstoneEmission;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pre-categorized interleaved mesh data for a single chunk section.
 * Each {@link Surface} is one (category, bucketKey, redstoneEmission) bucket with vertex
 * data already in HardcodedVertex layout (64-byte stride).
 *
 * <p>Positions are section-local. The column mesh builder adds each section's
 * offset within the column, and the Remix instance transform places the
 * finished column mesh in world space.
 *
 * <p>The vertex buffers live in {@link DirectBufferPool} memory rather than on
 * the Java heap: a loaded world retains one of these per section for column
 * re-merging, and as heap arrays they were a large share of the live set every
 * major collection had to mark and relocate. Off-heap memory has no collector,
 * so ownership is explicit. An instance starts with one reference, held by
 * whoever carries it (worker, capture queue, then the terrain entry). A column
 * build that reads the buffers on another thread takes its own reference with
 * {@link #retain()} and drops it when done; the owner drops the last reference
 * when the section is replaced, removed, or rejected. The final
 * {@link #release()} returns the buffers to the pool, after which
 * {@link #surfaces()} refuses to hand them out. The cached hashes and byte
 * estimates stay readable, so a replaced entry can still be diffed.
 */
public final class SectionMeshData {

    /**
     * One mesh surface bucket within a section.
     *
     * @param category     chunk manager category (CAT_SOLID, CAT_CUTOUT, etc.)
     * @param bucketKey    material bucket key (e.g. "solid", "cutout_mipped", "water", "solid_sss")
     * @param redstoneEmission the dust state that selects the material's emission
     * @param vertexBuffer HardcodedVertex interleaved (64 bytes/vert); a pooled direct
     *                     buffer in production, positioned at 0 with the payload as limit
     * @param vertexCount  number of vertices
     * @param indexCount   number of indices
     * @param canonicalQuadIndices true when indices follow DirectQuadWriter's
     *                             fixed per-quad pattern; the only value this
     *                             pipeline produces, and paging requires it
     */
    public record Surface(
        int category,
        String bucketKey,
        RedstoneEmission redstoneEmission,
        ByteBuffer vertexBuffer,
        int vertexCount,
        int indexCount,
        boolean canonicalQuadIndices
    ) {}

    private final List<Surface> surfaces;
    private final int cachedHash;
    private final int[] categoryHashes;
    private final long[] categoryEstimatedMeshBytes;
    private final AtomicInteger references = new AtomicInteger(1);

    public SectionMeshData(List<Surface> surfaces) {
        this(surfaces, computeHash(surfaces));
    }

    SectionMeshData(List<Surface> surfaces, int contentHash) {
        this.surfaces = List.copyOf(surfaces);
        this.cachedHash = contentHash;
        this.categoryHashes = computeCategoryHashes(this.surfaces);
        this.categoryEstimatedMeshBytes = estimateCategoryMeshBytes(this.surfaces);
    }

    /**
     * Returns the surfaces for reading or merging.
     *
     * @throws IllegalStateException once the last reference has been released
     *                               and the buffers may already belong to another section
     */
    public List<Surface> surfaces() {
        if (references.get() <= 0) {
            throw new IllegalStateException("section mesh data read after release");
        }
        return surfaces;
    }

    /**
     * Whether the section produced any surface. Structural, so it stays valid after
     * release like the cached hashes and byte estimates: the list survives, only the
     * buffers it references are recycled.
     */
    public boolean isEmpty() { return surfaces.isEmpty(); }

    /** Adds a reference for a reader on another thread; pair with {@link #release()}. */
    public void retain() {
        int previous = references.getAndUpdate(count -> count > 0 ? count + 1 : count);
        if (previous <= 0) {
            throw new IllegalStateException("section mesh data retained after release");
        }
    }

    /**
     * Drops one reference. The last release returns every buffer to
     * {@link DirectBufferPool}; releasing more often than retained is a
     * double free and fails loudly rather than pooling a buffer twice.
     */
    public void release() {
        int remaining = references.decrementAndGet();
        if (remaining > 0) {
            return;
        }
        if (remaining < 0) {
            references.incrementAndGet();
            throw new IllegalStateException("section mesh data released more often than retained");
        }
        for (Surface surface : surfaces) {
            DirectBufferPool.release(surface.vertexBuffer);
        }
    }

    /** Returns the estimated native vertex and index payload for one category. */
    public long categoryEstimatedMeshBytes(int category) {
        validateCategory(category);
        return categoryEstimatedMeshBytes[category];
    }

    /**
     * Returns the precomputed content hash (computed at construction time
     * on the worker thread, not on the render thread).
     */
    public int contentHash() { return cachedHash; }

    /**
     * Returns the precomputed content hash for one render category. Empty
     * categories have a stable hash of zero.
     */
    public int categoryContentHash(int category) {
        validateCategory(category);
        return categoryHashes[category];
    }

    private static int computeHash(List<Surface> surfaces) {
        int h = surfaces.size();
        for (Surface s : surfaces) {
            h = h * 31 + surfaceHash(s);
        }
        return h;
    }

    private static int[] computeCategoryHashes(List<Surface> surfaces) {
        int[] hashes = new int[ChunkManager.CAT_COUNT];
        boolean[] present = new boolean[ChunkManager.CAT_COUNT];
        for (Surface surface : surfaces) {
            int category = surface.category;
            if (!isValidCategory(category)) continue;
            if (!present[category]) {
                hashes[category] = 1;
                present[category] = true;
            }
            hashes[category] = hashes[category] * 31 + surfaceHash(surface);
        }
        for (int category = 0; category < hashes.length; category++) {
            if (present[category] && hashes[category] == 0) hashes[category] = 1;
        }
        return hashes;
    }

    private static int surfaceHash(Surface surface) {
        int hash = surface.category;
        hash = hash * 31 + surface.bucketKey.hashCode();
        hash = hash * 31 + surface.redstoneEmission.ordinal();
        hash = hash * 31 + surface.vertexCount;
        hash = hash * 31 + surface.indexCount;
        hash = hash * 31 + (surface.canonicalQuadIndices ? 1 : 0);

        ByteBuffer vertices = surface.vertexBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        vertices.position(0).limit(surface.vertexCount * 64);
        while (vertices.remaining() >= Integer.BYTES) hash = hash * 31 + vertices.getInt();

        return hash;
    }

    private static long[] estimateCategoryMeshBytes(List<Surface> surfaces) {
        long[] bytes = new long[ChunkManager.CAT_COUNT];
        for (Surface s : surfaces) {
            if (!isValidCategory(s.category)) continue;
            bytes[s.category] += (long) s.vertexCount * 64L;
            bytes[s.category] += (long) s.indexCount * Integer.BYTES;
        }
        return bytes;
    }

    private static boolean isValidCategory(int category) {
        return category >= 0 && category < ChunkManager.CAT_COUNT;
    }

    private static void validateCategory(int category) {
        if (!isValidCategory(category)) {
            throw new IllegalArgumentException("Invalid terrain mesh category " + category);
        }
    }
}

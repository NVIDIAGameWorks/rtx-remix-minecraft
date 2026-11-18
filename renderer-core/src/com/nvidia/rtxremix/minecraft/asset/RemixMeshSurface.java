/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Intermediary data container for a single mesh surface. Mirrors what the
 * Remix C API expects in {@link MeshInfoSurfaceTriangles} but uses type-safe
 * {@link RemixMaterial} references instead of raw handle longs.
 *
 * <p>Not an "asset": this is consumed by {@link RemixMesh} during creation
 * and has no native lifecycle of its own. Supports two vertex data paths:
 * raw {@link ByteBuffer} (terrain) or structured {@link HardcodedVertex}
 * arrays (entities).
 */
public class RemixMeshSurface {

    private final RemixMaterial material;

    private ByteBuffer vertexData;
    private int vertexCount;
    private ByteBuffer indexData;
    private int indexCount;
    private int flags;

    private HardcodedVertex[] structuredVertices;
    private int structuredVertexCount;
    private int[] structuredIndices;
    private int structuredIndexCount;

    private final List<ByteBuffer> extraNativeBuffers = new ArrayList<>();

    public RemixMeshSurface(RemixMaterial material) {
        this.material = material;
    }

    /** Raw memory path (terrain): sets pre-built native vertex buffer. */
    public RemixMeshSurface setVertexData(ByteBuffer buf, int count) {
        this.vertexData = buf;
        this.vertexCount = count;
        return this;
    }

    /** Raw memory path (terrain): sets pre-built native index buffer. */
    public RemixMeshSurface setIndexData(ByteBuffer buf, int count) {
        this.indexData = buf;
        this.indexCount = count;
        return this;
    }

    /** Sets the {@link MeshInfoSurfaceTriangles} flag bits for this surface. */
    public RemixMeshSurface setFlags(int flags) {
        this.flags = flags;
        return this;
    }

    /** Structured path (entities): sets HardcodedVertex array. */
    public RemixMeshSurface setVertices(HardcodedVertex[] vertices) {
        this.structuredVertices = vertices;
        this.structuredVertexCount = vertices.length;
        return this;
    }

    /** Structured path with a valid prefix of a reusable vertex array. */
    public RemixMeshSurface setVertices(HardcodedVertex[] vertices, int count) {
        this.structuredVertices = vertices;
        this.structuredVertexCount = count;
        return this;
    }

    /** Structured path (entities): sets index array. */
    public RemixMeshSurface setIndices(int[] indices) {
        this.structuredIndices = indices;
        this.structuredIndexCount = indices.length;
        return this;
    }

    /** Structured path with a valid prefix of a reusable index array. */
    public RemixMeshSurface setIndices(int[] indices, int count) {
        this.structuredIndices = indices;
        this.structuredIndexCount = count;
        return this;
    }

    /**
     * Writes one vertex in {@link HardcodedVertex}'s native layout at the buffer's position,
     * for callers that build a surface straight into a pooled buffer instead of staging a
     * {@code HardcodedVertex} per vertex. Field order and padding match {@code writeTo}.
     */
    public static void putVertex(ByteBuffer buf,
                                 float px, float py, float pz,
                                 float nx, float ny, float nz,
                                 float u, float v,
                                 int color, int texcoordBoundsMin, int texcoordBoundsMax) {
        buf.putFloat(px).putFloat(py).putFloat(pz);
        buf.putFloat(nx).putFloat(ny).putFloat(nz);
        buf.putFloat(u).putFloat(v);
        buf.putInt(color);
        buf.putInt(texcoordBoundsMin);
        buf.putInt(texcoordBoundsMax);
        buf.putInt(0).putInt(0).putInt(0).putInt(0).putInt(0);
    }

    /** Builds the index buffer for {@code count} indices from a reusable array; the surface owns the buffer. */
    public RemixMeshSurface setIndexDataFrom(int[] indices, int count) {
        ByteBuffer iBuf = DirectBufferPool.acquire(count * Integer.BYTES);
        for (int i = 0; i < count; i++) {
            iBuf.putInt(indices[i]);
        }
        iBuf.flip();
        this.indexData = iBuf;
        this.indexCount = count;
        return this;
    }

    /** Tracks additional ByteBuffer allocations for lifecycle management. */
    public RemixMeshSurface addNativeBuffer(ByteBuffer buf) {
        extraNativeBuffers.add(buf);
        return this;
    }

    public RemixMaterial getMaterial() { return material; }

    /** Converts to the JNI bridge structure for mesh creation. Package-private. */
    MeshInfoSurfaceTriangles toNative() {
        MeshInfoSurfaceTriangles surf = new MeshInfoSurfaceTriangles();

        if (structuredVertices != null) {
            int stride = HardcodedVertex.STRIDE;
            ByteBuffer vBuf = DirectBufferPool.acquire(structuredVertexCount * stride);
            for (int i = 0; i < structuredVertexCount; i++) {
                structuredVertices[i].writeTo(vBuf);
            }
            vBuf.flip();
            surf.vertexBuffer = vBuf;
            surf.vertexCount = structuredVertexCount;
            extraNativeBuffers.add(vBuf);
        } else if (vertexData != null) {
            surf.vertexBuffer = vertexData;
            surf.vertexCount = vertexCount;
        }

        if (structuredIndices != null) {
            ByteBuffer iBuf = DirectBufferPool.acquire(structuredIndexCount * 4);
            for (int i = 0; i < structuredIndexCount; i++) {
                iBuf.putInt(structuredIndices[i]);
            }
            iBuf.flip();
            surf.indexBuffer = iBuf;
            surf.indexCount = structuredIndexCount;
            extraNativeBuffers.add(iBuf);
        } else if (indexData != null) {
            surf.indexBuffer = indexData;
            surf.indexCount = indexCount;
        }

        surf.material = material.getHandle();
        surf.flags = flags;
        return surf;
    }

    /** Collects all native ByteBuffer objects for lifecycle management by RemixMesh. */
    List<ByteBuffer> collectNativeBuffers() {
        List<ByteBuffer> buffers = new ArrayList<>();
        if (vertexData != null) buffers.add(vertexData);
        if (indexData != null) buffers.add(indexData);
        buffers.addAll(extraNativeBuffers);
        return buffers;
    }

    long estimatedNativeBytes() {
        long bytes = structuredVertices != null
                ? (long) structuredVertexCount * HardcodedVertex.STRIDE
                : (long) vertexCount * HardcodedVertex.STRIDE;
        bytes += structuredIndices != null
                ? (long) structuredIndexCount * Integer.BYTES
                : (long) indexCount * Integer.BYTES;
        return bytes;
    }

    /** Releases all data references, returning pooled direct buffers. */
    public void disposeData() {
        DirectBufferPool.release(vertexData);
        DirectBufferPool.release(indexData);
        for (ByteBuffer b : extraNativeBuffers) DirectBufferPool.release(b);
        vertexData = null;
        indexData = null;
        structuredVertices = null;
        structuredVertexCount = 0;
        structuredIndices = null;
        structuredIndexCount = 0;
        extraNativeBuffers.clear();
    }
}

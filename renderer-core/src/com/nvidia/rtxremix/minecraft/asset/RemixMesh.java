/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import com.nvidia.rtxremix.MeshInfo;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import com.nvidia.rtxremix.RemixAPI;

import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Wraps a single Remix mesh resource. Holds {@link RemixMeshSurface} data
 * until {@code create()} is called, after which native buffers are optionally
 * disposed.
 *
 * <p>Create via {@link RemixMeshStore#create(RemixAssetBase)}.
 * Destroy via the public {@link #destroy()} method.
 */
public class RemixMesh extends RemixAssetBase {

    private RemixMeshSurface[] surfaces;
    private final List<ByteBuffer> nativeBuffers = new ArrayList<>();
    private final long estimatedNativeBytes;

    public RemixMesh(RemixMeshSurface[] surfaces) {
        this.surfaces = surfaces;
        long bytes = 0;
        for (RemixMeshSurface s : surfaces) {
            nativeBuffers.addAll(s.collectNativeBuffers());
            bytes += s.estimatedNativeBytes();
        }
        estimatedNativeBytes = bytes;
    }

    @Override
    protected long doCreate(RemixAPI api, long hash) {
        MeshInfoSurfaceTriangles[] nativeSurfaces = new MeshInfoSurfaceTriangles[surfaces.length];
        for (int i = 0; i < surfaces.length; i++) {
            nativeSurfaces[i] = surfaces[i].toNative();
        }
        MeshInfo info = MeshInfo.builder()
                .hash(hash)
                .surfaces(nativeSurfaces)
                .build();
        long result = api.createMesh(info);
        Reference.reachabilityFence(nativeBuffers);
        Reference.reachabilityFence(info);
        return result;
    }

    @Override
    protected void doDestroy(RemixAPI api, long handle) {
        api.destroyMesh(handle);
    }

    @Override
    protected void doDisposeData() {
        if (surfaces != null) {
            for (RemixMeshSurface s : surfaces) s.disposeData();
        }
        nativeBuffers.clear();
        surfaces = null;
    }

    /**
     * Releases buffer data without going through the asset store lifecycle.
     * Used for meshes that were built but never submitted (stale results).
     */
    public void disposeData() {
        if (!dataDisposed) {
            doDisposeData();
            dataDisposed = true;
        }
    }

    /** Approximate native vertex/index payload retained by the Remix mesh. */
    public long estimatedNativeBytes() {
        return estimatedNativeBytes;
    }

    @Override
    protected String typeName() {
        return "RemixMesh";
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.RemixAPI;

import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Wraps a single Remix material resource. Holds references to its constituent
 * {@link RemixTexture}s so the system can detect use-after-delete of textures
 * through a material.
 *
 * <p>Create via {@link RemixMaterialStore#create(RemixAssetBase)}.
 * Destroy via the public {@link #destroy()} method.
 */
public class RemixMaterial extends RemixAssetBase {

    private MaterialInfo materialInfo;
    private final List<RemixTexture> textures = new ArrayList<>();
    private final List<ByteBuffer> nativeBuffers = new ArrayList<>();

    public RemixMaterial(MaterialInfo materialInfo, List<RemixTexture> textures, List<ByteBuffer> nativeBuffers) {
        this.materialInfo = materialInfo;
        if (textures != null) this.textures.addAll(textures);
        if (nativeBuffers != null) this.nativeBuffers.addAll(nativeBuffers);
    }

    public RemixMaterial(MaterialInfo materialInfo) {
        this.materialInfo = materialInfo;
    }

    public List<RemixTexture> getTextures() {
        return Collections.unmodifiableList(textures);
    }

    void update(RemixAPI api, MaterialInfo newInfo) {
        rwLock.writeLock().lock();
        try {
            if (handle == 0 || deletionFlag != DeletionReason.NOT_DELETED) {
                throw new IllegalStateException("Cannot update a material that is not alive");
            }
            newInfo.hash = hash;
            api.updateMaterial(handle, newInfo);
            materialInfo = newInfo;
            Reference.reachabilityFence(newInfo);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    protected long doCreate(RemixAPI api, long hash) {
        for (RemixTexture tex : textures) {
            if (tex.isDeleted()) {
                throw new IllegalStateException("Cannot create material: referenced "
                        + "RemixTexture(hash=0x" + Long.toHexString(tex.getHash())
                        + ") is deleted (" + tex.getDeletionFlag() + ")");
            }
        }
        materialInfo.hash = hash;
        long result = api.createMaterial(materialInfo);
        Reference.reachabilityFence(nativeBuffers);
        Reference.reachabilityFence(materialInfo);
        return result;
    }

    @Override
    protected void doDestroy(RemixAPI api, long handle) {
        api.destroyMaterial(handle);
    }

    @Override
    protected void doDisposeData() {
        nativeBuffers.clear();
        textures.clear();
        materialInfo = null;
    }

    @Override
    protected String typeName() {
        return "RemixMaterial";
    }
}

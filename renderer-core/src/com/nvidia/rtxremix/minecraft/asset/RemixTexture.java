/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureFromMemoryInfo;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;

import java.lang.ref.Reference;
import java.nio.ByteBuffer;

/**
 * Wraps a single Remix texture resource backed by pixel data in memory.
 *
 * <p>Create via {@link RemixTextureStore#create(RemixAssetBase)}.
 * Destroy via the public {@link #destroy()} method.
 */
public class RemixTexture extends RemixAssetBase {

    private int width;
    private int height;
    private int format;
    private int purpose;
    private ByteBuffer pixelData;

    public RemixTexture(int width, int height, int format, int purpose, ByteBuffer pixelData) {
        this.width = width;
        this.height = height;
        this.format = format;
        this.purpose = purpose;
        this.pixelData = pixelData;
    }

    @Override
    protected long doCreate(RemixAPI api, long hash) {
        TextureFromMemoryInfo info = new TextureFromMemoryInfo();
        info.hash = hash;
        info.width = width;
        info.height = height;
        info.format = format;
        info.purpose = purpose;
        info.pixelData = pixelData;

        long result = api.createTextureFromMemory(info);
        Reference.reachabilityFence(pixelData);
        return result;
    }

    @Override
    protected void doDestroy(RemixAPI api, long handle) {
        api.destroyTexture(handle);
    }

    @Override
    protected void doDisposeData() {
        DirectBufferPool.release(pixelData);
        pixelData = null;
        width = 0;
        height = 0;
    }

    @Override
    protected String typeName() {
        return "RemixTexture";
    }
}

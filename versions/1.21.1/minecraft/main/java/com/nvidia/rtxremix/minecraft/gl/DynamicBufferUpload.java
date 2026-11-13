/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;

/**
 * Upload policy for vanilla's {@code DYNAMIC} vertex and index buffer objects.
 *
 * <p>Vanilla re-specifies the same buffer object with {@code glBufferData} on
 * every upload so the previous storage is orphaned while the GPU may still read
 * it. Under the GL-Vulkan interop the NVIDIA driver never reclaims those
 * orphans and the process grows by roughly 100 KiB per frame with the F3 chart
 * open. Each buffer object therefore keeps one allocation that is overwritten
 * with {@code glBufferSubData}, leaving synchronisation against in-flight reads
 * to the driver, and is re-specified only when an upload outgrows it.
 */
public final class DynamicBufferUpload {
    /** Capacity of one buffer object's allocation, owned by the {@code VertexBuffer} that owns the name. */
    public static final class Storage {
        private int capacityBytes;

        /**
         * Records an upload of {@code sizeBytes}.
         *
         * @return whether the allocation must be re-specified because the upload does not fit
         */
        boolean claim(int sizeBytes) {
            if (sizeBytes <= capacityBytes) {
                return false;
            }
            capacityBytes = sizeBytes;
            return true;
        }

        int capacityBytes() {
            return capacityBytes;
        }
    }

    private DynamicBufferUpload() {
    }

    /** @return whether uploads with this GL usage hint go through {@link #upload}. */
    public static boolean handles(int usage) {
        return usage == GL15.GL_DYNAMIC_DRAW;
    }

    /**
     * Uploads {@code data} into the buffer object bound at {@code target}.
     *
     * @param storage the bound buffer object's allocation record
     */
    public static void upload(int target, ByteBuffer data, int usage, Storage storage) {
        if (storage.claim(data.remaining())) {
            GL15.glBufferData(target, data, usage);
        } else {
            GL15.glBufferSubData(target, 0L, data);
        }
    }
}

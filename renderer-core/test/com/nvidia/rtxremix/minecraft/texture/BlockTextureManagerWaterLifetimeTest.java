/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePurpose;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class BlockTextureManagerWaterLifetimeTest {
    @Test
    void reloadPreservesWaterNormalsButRendererCleanupReleasesThem() throws Exception {
        BlockTextureManager manager = new BlockTextureManager(null, null);
        ByteBuffer pixels = DirectBufferPool.acquire(2);
        pixels.put((byte) 128).put((byte) 128).flip();
        RemixTexture texture = new RemixTexture(
                1, 1, TextureFormat.RG8_UNORM, TexturePurpose.NORMAL_OTH, pixels);

        Field field = BlockTextureManager.class.getDeclaredField("waterNormalTexture");
        field.setAccessible(true);
        field.set(manager, texture);
        Method cleanup = BlockTextureManager.class.getDeclaredMethod("cleanup", boolean.class);
        cleanup.setAccessible(true);

        cleanup.invoke(manager, false);
        assertSame(texture, field.get(manager));
        assertFalse(texture.isDeleted());

        cleanup.invoke(manager, true);
        assertNull(field.get(manager));
        assertTrue(texture.isDeleted());
    }
}

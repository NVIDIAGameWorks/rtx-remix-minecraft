/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL15;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicBufferUploadTest {

    @Test
    void firstUploadReSpecifiesEmptyStorage() {
        DynamicBufferUpload.Storage storage = new DynamicBufferUpload.Storage();

        assertTrue(storage.claim(4096));
        assertEquals(4096, storage.capacityBytes());
    }

    @Test
    void uploadsThatFitReuseTheAllocation() {
        DynamicBufferUpload.Storage storage = new DynamicBufferUpload.Storage();
        storage.claim(4096);

        assertFalse(storage.claim(4096));
        assertFalse(storage.claim(1));
        assertFalse(storage.claim(0));
        assertEquals(4096, storage.capacityBytes());
    }

    @Test
    void largerUploadGrowsToItsExactSize() {
        DynamicBufferUpload.Storage storage = new DynamicBufferUpload.Storage();
        storage.claim(4096);

        assertTrue(storage.claim(4097));
        assertEquals(4097, storage.capacityBytes());
        assertFalse(storage.claim(4096));
    }

    @Test
    void onlyDynamicUsageIsHandled() {
        assertTrue(DynamicBufferUpload.handles(GL15.GL_DYNAMIC_DRAW));
        assertFalse(DynamicBufferUpload.handles(GL15.GL_STATIC_DRAW));
        assertFalse(DynamicBufferUpload.handles(GL15.GL_STREAM_DRAW));
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BufferResizePolicyTest {
    @Test
    void initializedDynamicUploadsRetainCapacity() {
        assertTrue(BufferResizePolicy.canRetain(true, true, false, 128, 128));
        assertTrue(BufferResizePolicy.canRetain(true, true, false, 128, 64));
        assertTrue(BufferResizePolicy.canRetain(true, true, false, 128, 0));
    }

    @Test
    void growthAndFirstUseFollowVanillaAllocation() {
        assertFalse(BufferResizePolicy.canRetain(true, true, false, 128, 129));
        assertFalse(BufferResizePolicy.canRetain(true, false, false, 128, 64));
    }

    @Test
    void otherUsagesAndInvalidOperationsKeepVanillaBehavior() {
        assertFalse(BufferResizePolicy.canRetain(false, true, false, 128, 64));
        assertFalse(BufferResizePolicy.canRetain(true, true, true, 128, 64));
        assertFalse(BufferResizePolicy.canRetain(true, true, false, 128, -1));
    }
}

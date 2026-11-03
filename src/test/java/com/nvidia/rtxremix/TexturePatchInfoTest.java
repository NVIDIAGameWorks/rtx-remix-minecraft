/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TexturePatchInfoTest {
    @Test
    void reusedBatchRefreshesContentsWithoutAllocatingJNIArrayStorage() {
        var info = new TexturePatchInfo();
        var entry = new TextureBatchEntry();
        var patch = new TexturePatch();
        patch.pixelData = ByteBuffer.allocateDirect(16);
        patch.width = patch.height = 2;
        patch.rowPitch = 8;
        entry.handle = 7;
        entry.patches = new TexturePatch[] {patch};
        info.entries = new TextureBatchEntry[] {entry};
        info.pack();
        long[] handles = info.handles;
        int[] widths = info.widths;
        ByteBuffer[] buffers = info.pixelBuffers;
        entry.handle = 9;
        patch.width = 1;
        patch.pixelData = ByteBuffer.allocateDirect(8);
        info.pack();
        assertSame(handles, info.handles);
        assertSame(widths, info.widths);
        assertSame(buffers, info.pixelBuffers);
        assertEquals(9, handles[0]);
        assertEquals(1, widths[0]);
        assertSame(patch.pixelData, buffers[0]);
        info.entries = new TextureBatchEntry[] {entry, entry};
        info.pack();
        assertArrayEquals(new long[] {9, 9}, info.handles);
        assertArrayEquals(new int[] {1, 1}, info.entryPatchCounts);
        assertEquals(2, info.pixelBuffers.length);
        assertSame(patch.pixelData, info.pixelBuffers[1]);
    }
}

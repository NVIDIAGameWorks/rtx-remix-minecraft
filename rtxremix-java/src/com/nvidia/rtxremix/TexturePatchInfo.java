/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import java.nio.ByteBuffer;

/**
 * Multi-handle batch descriptor for {@code remixapi_UpdateTextureFromMemory}.
 * A single call can target any number of textures by supplying multiple
 * {@link TextureBatchEntry} items, amortizing the API-crossing cost.
 */
public final class TexturePatchInfo {
    public TextureBatchEntry[] entries;

    // Reusing a descriptor also reuses its JNI arrays; descriptors are not thread-safe.
    long[] handles;
    int[] entryPatchCounts;
    int[] dstXs, dstYs, widths, heights, rowPitches, formats;
    ByteBuffer[] pixelBuffers;

    void pack() {
        int count = 0;
        for (TextureBatchEntry entry : entries) {
            if (entry == null || entry.patches == null || entry.patches.length == 0) {
                throw new RemixException("UpdateTextureFromMemory entry is missing patches", ErrorCode.INVALID_ARGUMENTS);
            }
            count += entry.patches.length;
        }
        if (handles == null || handles.length != entries.length) {
            handles = new long[entries.length];
            entryPatchCounts = new int[entries.length];
        }
        if (pixelBuffers == null || pixelBuffers.length != count) {
            dstXs = new int[count]; dstYs = new int[count];
            widths = new int[count]; heights = new int[count];
            rowPitches = new int[count]; formats = new int[count];
            pixelBuffers = new ByteBuffer[count];
        }
        int flat = 0;
        for (int e = 0; e < entries.length; e++) {
            TextureBatchEntry entry = entries[e];
            handles[e] = entry.handle;
            entryPatchCounts[e] = entry.patches.length;
            for (TexturePatch patch : entry.patches) {
                if (patch == null || patch.pixelData == null) {
                    throw new RemixException("UpdateTextureFromMemory patch is missing pixel data", ErrorCode.INVALID_ARGUMENTS);
                }
                dstXs[flat] = patch.dstX; dstYs[flat] = patch.dstY;
                widths[flat] = patch.width; heights[flat] = patch.height;
                pixelBuffers[flat] = patch.pixelData;
                rowPitches[flat] = patch.rowPitch; formats[flat] = patch.format;
                flat++;
            }
        }
    }
}

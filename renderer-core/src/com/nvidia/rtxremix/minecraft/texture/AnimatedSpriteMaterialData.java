/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

/**
 * Canonical LabPBR tables and playback metadata for one animated sprite.
 */
public final class AnimatedSpriteMaterialData {
    public final byte[] aoTable;
    public final int aoW;
    public final int aoH;
    public final byte[] sAlphaTable;
    public final byte[] sRoughnessTable;
    public final byte[] sMetallicTable;
    public final byte[] sSssTable;
    public final int sW;
    public final int sH;
    public final boolean hasPerPixelEmissive;
    public final boolean hasSss;
    public final int[] frameIndices;
    public final int[] frameTimes;
    public final int frameColumns;

    public AnimatedSpriteMaterialData(
            byte[] aoTable,
            int aoW,
            int aoH,
            byte[] sAlphaTable,
            byte[] sRoughnessTable,
            byte[] sMetallicTable,
            byte[] sSssTable,
            int sW,
            int sH,
            boolean hasPerPixelEmissive,
            boolean hasSss,
            int[] frameIndices,
            int[] frameTimes,
            int frameColumns) {
        this.aoTable = aoTable;
        this.aoW = aoW;
        this.aoH = aoH;
        this.sAlphaTable = sAlphaTable;
        this.sRoughnessTable = sRoughnessTable;
        this.sMetallicTable = sMetallicTable;
        this.sSssTable = sSssTable;
        this.sW = sW;
        this.sH = sH;
        this.hasPerPixelEmissive = hasPerPixelEmissive;
        this.hasSss = hasSss;
        this.frameIndices = frameIndices;
        this.frameTimes = frameTimes;
        this.frameColumns = frameColumns;
    }
}

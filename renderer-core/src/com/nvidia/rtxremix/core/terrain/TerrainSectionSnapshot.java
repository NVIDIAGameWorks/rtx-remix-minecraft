/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core.terrain;

import java.util.Objects;

/**
 * Immutable section payload positioned within one chunk column.
 *
 * <p>The payload remains opaque here so the canonical column state can also
 * be exercised by lightweight tests. Production captures carry
 * {@code SectionMeshData}.
 */
public final class TerrainSectionSnapshot {
    private final int xOff;
    private final int yOff;
    private final int zOff;
    private final Object data;

    public TerrainSectionSnapshot(int xOff, int yOff, int zOff, Object data) {
        this.xOff = xOff;
        this.yOff = yOff;
        this.zOff = zOff;
        this.data = data;
    }

    public int xOff() {
        return xOff;
    }

    public int yOff() {
        return yOff;
    }

    public int zOff() {
        return zOff;
    }

    public Object data() {
        return data;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TerrainSectionSnapshot)) return false;
        TerrainSectionSnapshot snapshot = (TerrainSectionSnapshot) other;
        return xOff == snapshot.xOff
                && yOff == snapshot.yOff
                && zOff == snapshot.zOff
                && Objects.equals(data, snapshot.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(xOff, yOff, zOff, data);
    }

    @Override
    public String toString() {
        return "TerrainSectionSnapshot[xOff=" + xOff
                + ", yOff=" + yOff
                + ", zOff=" + zOff
                + ", data=" + data + "]";
    }
}

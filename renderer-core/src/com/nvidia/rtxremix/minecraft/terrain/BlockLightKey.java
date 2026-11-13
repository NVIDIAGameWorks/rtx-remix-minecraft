/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import java.util.Objects;

/**
 * Loader-neutral stable identity for one block-attached Remix light.
 */
public final class BlockLightKey {
    private final long packedPosition;
    private final String ruleId;
    private final int lightIndex;

    public BlockLightKey(long packedPosition, String ruleId, int lightIndex) {
        this.packedPosition = packedPosition;
        this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
        this.lightIndex = lightIndex;
    }

    public long packedPosition() {
        return packedPosition;
    }

    public String ruleId() {
        return ruleId;
    }

    public int lightIndex() {
        return lightIndex;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BlockLightKey)) return false;
        BlockLightKey key = (BlockLightKey) other;
        return packedPosition == key.packedPosition
                && lightIndex == key.lightIndex
                && ruleId.equals(key.ruleId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(packedPosition, ruleId, lightIndex);
    }
}

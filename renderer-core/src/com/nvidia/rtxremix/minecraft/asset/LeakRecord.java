/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

/**
 * Lightweight value pushed by the reference-queue thread when a leaked asset is
 * detected. The owning store drains these during its {@code gc()} sweep.
 */
public final class LeakRecord {
    private final long hash;
    private final long handle;

    public LeakRecord(long hash, long handle) {
        this.hash = hash;
        this.handle = handle;
    }

    public long hash() {
        return hash;
    }

    public long handle() {
        return handle;
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

/** Retains dynamic upload capacity so repeated uploads do not orphan GL allocations. */
public final class BufferResizePolicy {
    private BufferResizePolicy() {}

    public static boolean canRetain(boolean dynamicWrite, boolean initialized, boolean closed,
                                    int capacity, int requested) {
        return dynamicWrite && initialized && !closed && requested >= 0 && requested <= capacity;
    }
}

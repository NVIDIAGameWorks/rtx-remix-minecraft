/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Selects ownership of the output window and presentation path.
 */
public enum WindowOwnership {
    HOST_WINDOW(0),
    EXTERNAL(1),
    REMIX_OWNED_CHILD_WINDOW(2);

    private final int nativeValue;

    WindowOwnership(int nativeValue) {
        this.nativeValue = nativeValue;
    }

    public int nativeValue() {
        return nativeValue;
    }
}

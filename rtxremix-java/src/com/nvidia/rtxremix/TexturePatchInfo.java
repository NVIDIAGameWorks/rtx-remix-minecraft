/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/**
 * Multi-handle batch descriptor for {@code remixapi_UpdateTextureFromMemory}.
 * A single call can target any number of textures by supplying multiple
 * {@link TextureBatchEntry} items, amortizing the API-crossing cost.
 */
public final class TexturePatchInfo {
    public TextureBatchEntry[] entries;
}

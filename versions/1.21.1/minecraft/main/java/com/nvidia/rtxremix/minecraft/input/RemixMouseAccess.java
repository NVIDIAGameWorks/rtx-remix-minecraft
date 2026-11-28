/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.input;

/** Mouse state the Remix overlay's input capture needs beyond vanilla's public surface. */
public interface RemixMouseAccess {
    /** Finishes Minecraft's current pointer interaction when Remix takes input ownership. */
    void rtxremix$releaseActiveButton();
}

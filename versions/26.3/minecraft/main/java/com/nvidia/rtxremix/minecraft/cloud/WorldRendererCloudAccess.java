/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

/** Exposes Minecraft's cloud animation clock to the Remix cloud renderer. */
public interface WorldRendererCloudAccess {
    int rtxremix$getCloudTicks();
}

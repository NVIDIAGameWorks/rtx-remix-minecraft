/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

/** Capture metadata retained alongside vanilla's extracted item render state. */
public interface ItemRenderStateCapture {
    ItemCaptureData rtxremix$getItemCaptureData();
}

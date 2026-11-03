/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

/** A vanilla wrapper's capture leaf, resolved once when its delegate is installed. */
public interface RemixCaptureSource {
    RemixCapturingConsumer remixCapture();
}

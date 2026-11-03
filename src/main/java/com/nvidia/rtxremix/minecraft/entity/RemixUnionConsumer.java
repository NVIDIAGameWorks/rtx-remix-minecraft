/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.client.render.VertexConsumer;

/** Cached union delegates; a dual keeps its two references without allocating an array. */
public interface RemixUnionConsumer extends RemixCaptureSource {
    VertexConsumer remixFirst();
    VertexConsumer remixSecond();
    VertexConsumer[] remixDelegates();
}

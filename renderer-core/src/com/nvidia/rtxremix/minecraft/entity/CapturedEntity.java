/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import java.util.List;

/** Zero-copy view of one complete entity capture. */
public interface CapturedEntity {
    Object owner();
    double worldX();
    double worldY();
    double worldZ();
    List<? extends CapturedEntityLayer> layers();
    float[] boneData();
    int boneCount();
    boolean firstPersonLocalPlayer();
}

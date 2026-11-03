/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.InstanceCategoryBit;

/** Camera-relative scale shared by view-model capture, overlay spacing and hand lights. */
public final class ViewModelSpace {
    /** Pulls the held model ahead of nearby world surfaces without changing its projected size. */
    public static final float SCALE = 0.1f;

    static float layerSpacingScale(int categoryFlags) {
        return (categoryFlags & InstanceCategoryBit.VIEW_MODEL) != 0 ? 1f / SCALE : 1f;
    }

    private ViewModelSpace() {}
}

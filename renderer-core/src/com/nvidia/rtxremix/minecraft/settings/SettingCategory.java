/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

/** Top-level pages in the RTX settings menu. */
enum SettingCategory {
    PERFORMANCE("rtxremix.settings.category.performance"),
    LIGHTING("rtxremix.settings.category.lighting"),
    IMAGE("rtxremix.settings.category.image"),
    MATERIALS("rtxremix.settings.category.materials");

    private final String labelKey;

    SettingCategory(String labelKey) {
        this.labelKey = labelKey;
    }

    String labelKey() {
        return labelKey;
    }
}

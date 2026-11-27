/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import net.minecraft.resources.ResourceLocation;

/** Canonical identities for built-in block-light rules. */
final class BlockLightRuleIds {

    private BlockLightRuleIds() {}

    static ResourceLocation builtIn(String path) {
        return BlockLightRuleManager.id("rtxremix", path + ".json");
    }
}

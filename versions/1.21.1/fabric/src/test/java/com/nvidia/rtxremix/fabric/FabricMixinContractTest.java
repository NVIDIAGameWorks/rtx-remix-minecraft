/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.fabric;

import com.nvidia.rtxremix.minecraft.MixinContractAssertions;
import org.junit.jupiter.api.Test;

/** Resolves Fabric-only required selectors before a live launch. */
class FabricMixinContractTest {
    @Test
    void requiredSelectorsResolveAgainstFabricProductionClasses() throws Exception {
        MixinContractAssertions.assertRequiredSelectorsResolve(
                getClass(),
                "Fabric",
                "rtx-remix-renderer.vanilla-section.mixins.json",
                "rtx-remix-renderer.fabric.mixins.json");
    }
}

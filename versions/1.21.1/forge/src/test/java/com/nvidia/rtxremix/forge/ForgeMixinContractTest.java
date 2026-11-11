/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge;

import com.nvidia.rtxremix.minecraft.MixinContractAssertions;
import org.junit.jupiter.api.Test;

/**
 * Resolves required mixin selectors against the mapped, Forge-patched game
 * classes used to build the production artifact.
 */
class ForgeMixinContractTest {
    @Test
    void requiredSelectorsResolveAgainstForgeProductionClasses() throws Exception {
        MixinContractAssertions.assertRequiredSelectorsResolve(
                getClass(),
                "Forge",
                "rtx-remix-renderer.mixins.json",
                "rtx-remix-renderer.vanilla-section.mixins.json",
                "rtx-remix-renderer.forge.mixins.json");
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.neoforge;

import com.nvidia.rtxremix.minecraft.MixinContractAssertions;
import org.junit.jupiter.api.Test;

/**
 * Resolves required mixin selectors against the mapped, NeoForge-patched game
 * classes used to build the production artifact.
 */
class NeoForgeMixinContractTest {
    @Test
    void requiredSelectorsResolveAgainstNeoForgeProductionClasses() throws Exception {
        MixinContractAssertions.assertRequiredSelectorsResolve(
                getClass(),
                "NeoForge",
                "rtx-remix-renderer.mixins.json",
                "rtx-remix-renderer.neoforge.mixins.json");
    }
}

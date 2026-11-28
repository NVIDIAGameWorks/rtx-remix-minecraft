/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Truth-table coverage for the loader-neutral feature state.
 */
class RtxToggleStateTest {

    @AfterEach
    void resetFlags() {
        publish(true, true, true, true, true, true, true);
    }

    @Test
    void worldIsInactiveWhenTheMasterToggleIsOff() {
        publish(false, true, true, true, true, true, true);

        assertFalse(MinecraftRuntime.isWorldActive());
    }

    @Test
    void worldIsActiveOnlyWhenBothGatesAreOn() {
        publish(true, true, true, true, true, true, true);
        assertTrue(MinecraftRuntime.isWorldActive());

        publish(true, true, false, true, true, true, true);
        assertFalse(MinecraftRuntime.isWorldActive());
    }

    @Test
    void publishedFeatureStatePreservesEverySharedFlag() {
        publish(false, true, false, true, false, true, false);

        assertFalse(MinecraftRuntime.isRtxEnabled());
        assertTrue(MinecraftRuntime.isEntitiesEnabled());
        assertFalse(MinecraftRuntime.isWorldEnabled());
        assertTrue(MinecraftRuntime.isSkyEnabled());
        assertFalse(MinecraftRuntime.isBlockEntitiesEnabled());
        assertTrue(MinecraftRuntime.isParticlesEnabled());
        assertFalse(MinecraftRuntime.isFirstPersonSmokeEnabled());
    }

    private static void publish(
            boolean rtx,
            boolean entities,
            boolean world,
            boolean sky,
            boolean blockEntities,
            boolean particles,
            boolean firstPersonSmoke) {
        MinecraftRuntime.publishFeatureState(
                rtx, entities, world, sky, blockEntities, particles, firstPersonSmoke);
    }
}

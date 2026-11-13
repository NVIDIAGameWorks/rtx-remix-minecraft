/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemixFirstPersonPassTest {

    @Test
    void blockAtlasComparisonUsesLoaderNeutralTextureIdentity() {
        assertTrue(RemixFirstPersonPass.isBlockAtlasTexture(
                EntityTextureKey.of("minecraft", "textures/atlas/blocks.png")));
        assertTrue(RemixFirstPersonPass.isBlockAtlasTexture(
                new EntityTextureKey(
                        "minecraft", "textures/atlas/blocks.png", new Object())));
        assertFalse(RemixFirstPersonPass.isBlockAtlasTexture(
                EntityTextureKey.of("minecraft", "textures/entity/player/wide/steve.png")));
    }
}

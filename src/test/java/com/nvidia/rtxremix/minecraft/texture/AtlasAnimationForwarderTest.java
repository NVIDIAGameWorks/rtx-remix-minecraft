/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AtlasAnimationForwarderTest {

    @AfterEach
    void clearForwarder() {
        AtlasAnimationForwarder.clear();
    }

    @Test
    void addPatchIgnoresSpritesOutsideTheBlockAtlasAllowList() throws Exception {
        ResourceLocation blockSprite = ResourceLocation.fromNamespaceAndPath("minecraft", "block/lava_still");
        ResourceLocation guiSprite = ResourceLocation.fromNamespaceAndPath("minecraft", "gui/sprites/icon");

        AtlasAnimationForwarder.init(Map.of(), Set.of(blockSprite));
        AtlasAnimationForwarder.beginAtlasUpload(BlockTextureManager.BLOCK_ATLAS_ID);

        try {
            AtlasAnimationForwarder.addPatch(guiSprite, 1, 2, 0, 0, 4, 4, 4, 4, 0L, 16);
            assertEquals(0, pendingPatchCount());

            AtlasAnimationForwarder.addPatch(blockSprite, 1, 2, 0, 0, 4, 4, 4, 4, 0L, 16);
            assertEquals(1, pendingPatchCount());
        } finally {
            AtlasAnimationForwarder.endAtlasUpload();
        }
    }

    @Test
    void addPatchIgnoresAllowedSpriteIdsFromOtherAtlases() throws Exception {
        ResourceLocation blockSprite = ResourceLocation.fromNamespaceAndPath("minecraft", "block/lava_still");
        ResourceLocation particleAtlas = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/particles.png");

        AtlasAnimationForwarder.init(Map.of(), Set.of(blockSprite));
        AtlasAnimationForwarder.beginAtlasUpload(particleAtlas);

        try {
            AtlasAnimationForwarder.addPatch(blockSprite, 1, 2, 0, 0, 4, 4, 4, 4, 0L, 16);
            assertEquals(0, pendingPatchCount());
        } finally {
            AtlasAnimationForwarder.endAtlasUpload();
        }
    }

    private static int pendingPatchCount() throws Exception {
        Field pendingPatches = AtlasAnimationForwarder.class.getDeclaredField("pendingPatches");
        pendingPatches.setAccessible(true);
        return ((List<?>) pendingPatches.get(null)).size();
    }
}

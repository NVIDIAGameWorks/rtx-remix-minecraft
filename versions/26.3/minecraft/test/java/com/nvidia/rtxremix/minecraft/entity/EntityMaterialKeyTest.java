/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;




/**
 * The material cache stores records and is probed with a mutable key. HashMap calls
 * probe.equals(stored) and requires equal hash codes, so a drift between the two
 * misses every lookup and silently drops every entity layer.
 */
class EntityMaterialKeyTest {

    private static final EntityTextureKey TEX =
            EntityTextureKey.of("minecraft", "textures/entity/cow/cow.png");

    @Test
    void lookupProbeAgreesWithTheStoredRecordOnHashAndEquality() {
        RemixEntitySubmitter.EntityMaterialKey stored =
                new RemixEntitySubmitter.EntityMaterialKey(TEX, EntitySurfaceMode.CUTOUT);
        RemixEntitySubmitter.EntityMaterialLookupKey probe =
                new RemixEntitySubmitter.EntityMaterialLookupKey().set(TEX, EntitySurfaceMode.CUTOUT);

        assertEquals(stored.hashCode(), probe.hashCode());
        assertTrue(probe.equals(stored));
    }

    @Test
    void surfaceModeSeparatesMaterialsOnOneTexture() {
        RemixEntitySubmitter.EntityMaterialLookupKey probe =
                new RemixEntitySubmitter.EntityMaterialLookupKey().set(TEX, EntitySurfaceMode.CUTOUT);

        assertFalse(probe.equals(
                new RemixEntitySubmitter.EntityMaterialKey(TEX, EntitySurfaceMode.TRANSLUCENT)));
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BlockQuadEmitterTest {
    @Test
    void clearGlassKeepsItsCanonicalLayerOnLoaderModelPaths() {
        assertEquals(
                "glass",
                BlockQuadEmitter.resolveCustomLayer("translucent", "glass"));
        assertEquals(
                "glass",
                BlockQuadEmitter.resolveCustomLayer("cutout", "glass"));
    }

    @Test
    void ordinaryCustomModelsKeepTheirReportedRenderLayer() {
        assertEquals(
                "cutout",
                BlockQuadEmitter.resolveCustomLayer("cutout", "solid"));
        assertEquals(
                "solid",
                BlockQuadEmitter.resolveCustomLayer(null, "solid"));
    }
}

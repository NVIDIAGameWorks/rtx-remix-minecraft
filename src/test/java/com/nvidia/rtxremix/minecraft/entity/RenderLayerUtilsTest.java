/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the two layer verdicts that decide what the submitter does with a layer's parts.
 *
 * <p>The surface-mode verdict decides the material a layer is built with. The
 * streamed-per-frame verdict decides which layers bypass the mesh pool: a false positive
 * rebuilds a shareable mesh every frame, a false negative pools a mesh whose content
 * never repeats and fills the pool with dead entries. {@code RenderLayer} instances are
 * not constructed here, because their static initialiser reaches shader programs, so
 * both classifications are tested through their pure helpers.
 */
class RenderLayerUtilsTest {

    @ParameterizedTest
    @CsvSource({
            "false, false, false, true, false, true, CUTOUT",
            "false, false, false, true, false, false, TRANSLUCENT",
            "true, false, false, true, false, true, EMISSIVE",
            "false, false, true, false, false, false, EMISSIVE",
            "false, false, false, false, true, false, OPAQUE",
            // The beacon beam's translucent glow blends; its opaque core stays solid.
            "false, true, false, true, false, false, EMISSIVE",
            "false, true, false, false, false, false, EMISSIVE_CUTOUT"
    })
    void surfaceModeFollowsProgramAndTransparencyPrecedence(
            boolean emissiveProgram, boolean beaconBeamProgram, boolean additiveTransparency,
            boolean translucentTransparency, boolean solidProgram,
            boolean textProgram, RenderLayerUtils.SurfaceMode expected) {
        assertEquals(expected, RenderLayerUtils.classifySurfaceMode(
                emissiveProgram, beaconBeamProgram, additiveTransparency, translucentTransparency,
                solidProgram, textProgram));
    }

    @ParameterizedTest
    @CsvSource({
            "TRIANGLE_STRIP, false, true",
            "QUADS, true, true",
            "TRIANGLE_STRIP, true, true",
            "QUADS, false, false"
    })
    void stripsAndBeaconBeamsAreStreamedPerFrame(
            RenderLayerUtils.Topology topology, boolean beaconBeamProgram, boolean expected) {
        assertEquals(expected, RenderLayerUtils.classifyStreamedPerFrame(topology, beaconBeamProgram));
    }
}

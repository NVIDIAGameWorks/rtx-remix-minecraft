/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.minecraft.asset.RemixLight;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainLightReconcilerTest {

    @Test
    void deferredLightRetirementHonorsExpiredFrameDeadline() {
        TerrainState terrain = new TerrainState(128);
        TerrainLightReconciler reconciler = reconciler(terrain);
        try {
            TerrainState.SectionEntry entry = terrain.addSection(2, 3, -4, 1, "section");
            int lightCount = 12;
            RemixLight[] lights = lights(lightCount);
            attach(entry, lights);
            assertTrue(terrain.adjustSectionLightCount(2, 3, -4, entry, lightCount));

            reconciler.retire(terrain.removeSectionForRetirement(2, 3, -4));
            assertEquals(lightCount, reconciler.pendingOps());
            for (RemixLight light : lights) assertFalse(light.isDeleted());

            reconciler.drain(0L);

            assertTrue(lights[0].isDeleted(), "one indivisible native operation must make progress");
            for (int i = 1; i < lights.length; i++) assertFalse(lights[i].isDeleted());
            assertEquals(lightCount - 1L, reconciler.pendingOps());
        } finally {
            reconciler.clear();
        }
    }

    @Test
    void deferredLightRetirementHonorsNativeCallCap() {
        TerrainState terrain = new TerrainState(128);
        TerrainLightReconciler reconciler = reconciler(terrain);
        try {
            TerrainState.SectionEntry entry = terrain.addSection(0, 0, 0, 1, "section");
            RemixLight[] lights = lights(40);
            attach(entry, lights);

            reconciler.retire(terrain.removeSectionForRetirement(0, 0, 0));
            reconciler.drain(Long.MAX_VALUE);

            assertEquals(32, Arrays.stream(lights).filter(RemixLight::isDeleted).count());
            assertEquals(8L, reconciler.pendingOps());
        } finally {
            reconciler.clear();
        }
    }

    @Test
    void failedRetirementRetainsOwnershipAndDoesNotStarveLaterLights() {
        TerrainState terrain = new TerrainState(128);
        TerrainLightReconciler reconciler = reconciler(terrain);
        try {
            TerrainState.SectionEntry entry = terrain.addSection(1, 1, 1, 1, "section");
            FailingOnceLight failing = new FailingOnceLight();
            RemixLight later = new RemixLight(null, null);
            attach(entry, new RemixLight[]{failing, later});

            reconciler.retire(terrain.removeSectionForRetirement(1, 1, 1));
            reconciler.drain(0L);
            assertEquals(2L, reconciler.pendingOps());
            assertFalse(failing.isDeleted());
            assertFalse(later.isDeleted());

            reconciler.drain(0L);
            assertTrue(later.isDeleted());
            assertEquals(1L, reconciler.pendingOps());

            reconciler.drain(0L);
            assertTrue(failing.isDeleted());
            assertEquals(0L, reconciler.pendingOps());
        } finally {
            reconciler.clear();
        }
    }

    @Test
    void clearRetainsFailedRetirementForTheNextAttempt() {
        TerrainState terrain = new TerrainState(128);
        TerrainLightReconciler reconciler = reconciler(terrain);
        TerrainState.SectionEntry entry = terrain.addSection(4, 0, 4, 1, "section");
        FailingOnceLight failing = new FailingOnceLight();
        RemixLight later = new RemixLight(null, null);
        attach(entry, new RemixLight[]{failing, later});
        reconciler.retire(terrain.clearForRetirement());

        assertThrows(IllegalStateException.class, reconciler::clear);
        assertEquals(2L, reconciler.pendingOps());
        assertFalse(failing.isDeleted());
        assertFalse(later.isDeleted());

        reconciler.clear();
        assertTrue(failing.isDeleted());
        assertTrue(later.isDeleted());
        assertEquals(0L, reconciler.pendingOps());
    }

    private static RemixLight[] lights(int count) {
        RemixLight[] lights = new RemixLight[count];
        Arrays.setAll(lights, ignored -> new RemixLight(null, null));
        return lights;
    }

    private static void attach(TerrainState.SectionEntry entry, RemixLight[] lights) {
        for (int i = 0; i < lights.length; i++) {
            entry.putLight(
                    new BlockLightKey(i, "test:light_" + i, 0),
                    new TerrainLight(lights[i]));
        }
    }

    private static TerrainLightReconciler reconciler(TerrainState terrain) {
        return new TerrainLightReconciler(
                terrain,
                new TerrainLightReconciler.Resolver() {
                    @Override
                    public TerrainLightReconciler.Radiance radiance(
                            com.nvidia.rtxremix.minecraft.light.LightEntry entry) {
                        throw new AssertionError("Retirement must not resolve radiance");
                    }
                },
                failure -> {});
    }

    private static final class FailingOnceLight extends RemixLight {
        private boolean failed;

        FailingOnceLight() {
            super(null, null);
        }

        @Override
        public void destroy() {
            if (!failed) {
                failed = true;
                throw new IllegalStateException("expected test failure");
            }
            super.destroy();
        }
    }

}

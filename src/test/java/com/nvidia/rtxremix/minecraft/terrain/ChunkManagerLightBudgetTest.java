/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.minecraft.asset.RemixLight;
import com.nvidia.rtxremix.minecraft.light.BlockLightKey;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkManagerLightBudgetTest {

    @Test
    void deferredLightRetirementHonorsExpiredFrameDeadline() throws Exception {
        ChunkManager manager = new ChunkManager(null);
        try {
            TerrainState terrain = field(manager, "terrain", TerrainState.class);
            TerrainState.SectionEntry entry = terrain.addSection(2, 3, -4, 1, "section");
            int lightCount = 12;
            RemixLight[] lights = lights(lightCount);
            attach(entry, lights);
            assertTrue(terrain.adjustSectionLightCount(2, 3, -4, entry, lightCount));

            invoke(manager, "removeSectionDeferred",
                    new Class<?>[]{int.class, int.class, int.class}, 2, 3, -4);
            assertEquals(lightCount, longField(manager, "pendingLightOps"));
            for (RemixLight light : lights) assertFalse(light.isDeleted());

            invoke(manager, "drainLightWork", new Class<?>[]{long.class}, 0L);

            assertTrue(lights[0].isDeleted(), "one indivisible native operation must make progress");
            for (int i = 1; i < lights.length; i++) assertFalse(lights[i].isDeleted());
            assertEquals(lightCount - 1L, longField(manager, "pendingLightOps"));
        } finally {
            manager.destroy();
        }
    }

    @Test
    void deferredLightRetirementHonorsNativeCallCap() throws Exception {
        ChunkManager manager = new ChunkManager(null);
        try {
            TerrainState terrain = field(manager, "terrain", TerrainState.class);
            TerrainState.SectionEntry entry = terrain.addSection(0, 0, 0, 1, "section");
            RemixLight[] lights = lights(40);
            attach(entry, lights);

            invoke(manager, "removeSectionDeferred",
                    new Class<?>[]{int.class, int.class, int.class}, 0, 0, 0);
            invoke(manager, "drainLightWork",
                    new Class<?>[]{long.class}, Long.MAX_VALUE);

            assertEquals(32, Arrays.stream(lights).filter(RemixLight::isDeleted).count());
            assertEquals(8L, longField(manager, "pendingLightOps"));
        } finally {
            manager.destroy();
        }
    }

    @Test
    void failedRetirementRetainsOwnershipAndDoesNotStarveLaterLights() throws Exception {
        ChunkManager manager = new ChunkManager(null);
        try {
            TerrainState terrain = field(manager, "terrain", TerrainState.class);
            TerrainState.SectionEntry entry = terrain.addSection(1, 1, 1, 1, "section");
            FailingOnceLight failing = new FailingOnceLight();
            RemixLight later = new RemixLight(null, null);
            attach(entry, new RemixLight[]{failing, later});

            invoke(manager, "removeSectionDeferred",
                    new Class<?>[]{int.class, int.class, int.class}, 1, 1, 1);
            invoke(manager, "drainLightWork", new Class<?>[]{long.class}, 0L);
            assertEquals(2L, longField(manager, "pendingLightOps"));
            assertFalse(failing.isDeleted());
            assertFalse(later.isDeleted());

            invoke(manager, "drainLightWork", new Class<?>[]{long.class}, 0L);
            assertTrue(later.isDeleted());
            assertEquals(1L, longField(manager, "pendingLightOps"));

            invoke(manager, "drainLightWork", new Class<?>[]{long.class}, 0L);
            assertTrue(failing.isDeleted());
            assertEquals(0L, longField(manager, "pendingLightOps"));
        } finally {
            manager.destroy();
        }
    }

    @Test
    void clearRetainsFailedRetirementForTheNextAttempt() throws Exception {
        ChunkManager manager = new ChunkManager(null);
        try {
            TerrainState terrain = field(manager, "terrain", TerrainState.class);
            TerrainState.SectionEntry entry = terrain.addSection(4, 0, 4, 1, "section");
            FailingOnceLight failing = new FailingOnceLight();
            RemixLight later = new RemixLight(null, null);
            attach(entry, new RemixLight[]{failing, later});

            assertThrows(IllegalStateException.class, manager::clear);
            assertEquals(2L, longField(manager, "pendingLightOps"));
            assertFalse(failing.isDeleted());
            assertFalse(later.isDeleted());

            manager.clear();
            assertTrue(failing.isDeleted());
            assertTrue(later.isDeleted());
            assertEquals(0L, longField(manager, "pendingLightOps"));
        } finally {
            manager.destroy();
        }
    }

    private static RemixLight[] lights(int count) {
        RemixLight[] lights = new RemixLight[count];
        Arrays.setAll(lights, ignored -> new RemixLight(null, null));
        return lights;
    }

    private static void attach(TerrainState.SectionEntry entry, RemixLight[] lights) {
        for (int i = 0; i < lights.length; i++) {
            entry.putLight(
                    new BlockLightKey(new BlockPos(i, 0, 0),
                            ResourceLocation.fromNamespaceAndPath("test", "light_" + i), 0),
                    new TerrainLight(lights[i]));
        }
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

    private static void invoke(Object target, String name, Class<?>[] parameterTypes,
                               Object... arguments) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        method.invoke(target, arguments);
    }

    private static long longField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(target);
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }
}

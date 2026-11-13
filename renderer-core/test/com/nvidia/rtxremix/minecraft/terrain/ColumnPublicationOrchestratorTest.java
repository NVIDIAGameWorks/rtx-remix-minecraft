/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.core.terrain.TerrainCategory;
import com.nvidia.rtxremix.minecraft.asset.RemixInstance;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColumnPublicationOrchestratorTest {

    @Test
    void oneInstallPassPublishesMultipleCompletedColumnsWithoutNativeWork() throws Exception {
        ColumnPublicationOrchestrator manager = manager();
        try {
            Object firstOwner = readyEmptyColumn(manager, 1, 2);
            Object secondOwner = readyEmptyColumn(manager, 2, 2);
            Object thirdOwner = readyEmptyColumn(manager, 3, 2);

            invoke(manager, "installReadyMeshes",
                    new Class<?>[]{RemixAPI.class}, new Object[]{null});

            assertTrue(booleanField(firstOwner, "hasPublishedState"));
            assertTrue(booleanField(secondOwner, "hasPublishedState"));
            assertTrue(booleanField(thirdOwner, "hasPublishedState"));
            assertFalse(booleanField(firstOwner, "buildInFlight"));
            assertFalse(booleanField(secondOwner, "buildInFlight"));
            assertFalse(booleanField(thirdOwner, "buildInFlight"));
            assertEquals(0, atomicInteger(manager, "pendingResults").get());
            assertTrue(queue(manager, "readyQueue").isEmpty());
        } finally {
            manager.destroy();
        }
    }

    @Test
    void rollbackHidesActivatedPrefixInReverseAndPreservesProgress() throws Exception {
        ColumnPublicationOrchestrator manager = manager();
        TrackingInstance first = new TrackingInstance(false);
        TrackingInstance second = new TrackingInstance(false);
        try {
            Object cursor = newCursor(newDetachedResult(0));
            addPage(cursor, first, TerrainCategory.SOLID, 0x20);
            addPage(cursor, second, TerrainCategory.CUTOUT, 0x40);
            set(cursor, "staged", true);
            set(cursor, "nextActivation", 2);

            assertTrue(rollbackOne(manager, cursor));
            assertEquals(1, intField(cursor, "nextActivation"));
            assertEquals(0, first.drawCalls);
            assertEquals(1, second.drawCalls);
            assertEquals(0x40 | InstanceCategoryBit.HIDDEN, second.categoryFlags);

            assertTrue(rollbackOne(manager, cursor));
            assertEquals(0, intField(cursor, "nextActivation"));
            assertEquals(1, first.drawCalls);
            assertEquals(0x20 | InstanceCategoryBit.HIDDEN, first.categoryFlags);
            assertFalse(rollbackOne(manager, cursor));
        } finally {
            first.destroy();
            second.destroy();
            manager.destroy();
        }
    }

    @Test
    void failedRollbackKeepsActivationOwnedByCursor() throws Exception {
        ColumnPublicationOrchestrator manager = manager();
        TrackingInstance instance = new TrackingInstance(true);
        try {
            Object cursor = newCursor(newDetachedResult(0));
            addPage(cursor, instance, TerrainCategory.SOLID, 0x80);
            set(cursor, "staged", true);
            set(cursor, "nextActivation", 1);

            assertTrue(rollbackOne(manager, cursor));
            assertEquals(1, intField(cursor, "nextActivation"),
                    "Progress must advance only after the native hide succeeds");
            assertTrue(booleanField(cursor, "rollbackFailed"));
            assertEquals(0x80 | InstanceCategoryBit.HIDDEN, instance.categoryFlags);
        } finally {
            instance.destroy();
            manager.destroy();
        }
    }

    @Test
    void staleResultRequiresExactOwnerGenerationAndEpochAcrossReload() throws Exception {
        ColumnPublicationOrchestrator manager = manager();
        try {
            TerrainState terrain = terrain(manager);
            terrain.addSection(7, 3, -9, 1, "first load");

            Object firstOwner = markColumnDirty(manager, 7, -9, 1 << TerrainCategory.SOLID);
            int firstGeneration = intField(firstOwner, "generation");
            int currentEpoch = intField(manager, "epoch");
            Object firstResult = newResult(firstOwner, firstGeneration,
                    1 << TerrainCategory.SOLID, new int[TerrainCategory.COUNT],
                    currentEpoch, 0);
            assertFalse(isStale(manager, firstResult));

            markColumnDirty(manager, 7, -9, 1 << TerrainCategory.CUTOUT);
            assertTrue(isStale(manager, firstResult),
                    "A newer capture of the same column must invalidate the old generation");

            Object currentFirstResult = newResult(firstOwner, intField(firstOwner, "generation"),
                    1 << TerrainCategory.CUTOUT, new int[TerrainCategory.COUNT],
                    currentEpoch, 0);
            assertFalse(isStale(manager, currentFirstResult));

            invoke(manager, "removeColumn", new Class<?>[]{int.class, int.class}, 7, -9);
            terrain.addSection(7, 3, -9, 2, "second load");
            Object secondOwner = markColumnDirty(manager, 7, -9,
                    1 << TerrainCategory.TRANSLUCENT);
            assertNotSame(firstOwner, secondOwner);
            assertTrue(isStale(manager, currentFirstResult),
                    "A result must not attach to a reloaded column with the same coordinates");

            Object secondResult = newResult(secondOwner, intField(secondOwner, "generation"),
                    1 << TerrainCategory.TRANSLUCENT, new int[TerrainCategory.COUNT],
                    currentEpoch, 0);
            assertFalse(isStale(manager, secondResult));

            set(manager, "epoch", currentEpoch + 1);
            assertTrue(isStale(manager, secondResult),
                    "World/reload epoch changes must invalidate every outstanding result");
        } finally {
            manager.destroy();
        }
    }

    @Test
    void categoryReplacementPreservesUnchangedPageIdentities() throws Exception {
        ColumnPublicationOrchestrator manager = manager();
        try {
            Object owner = markColumnDirty(manager, 2, 5, 1 << TerrainCategory.CUTOUT);
            int[] ownerFingerprints = (int[]) field(owner, "categoryFingerprints");
            int[] originalFingerprints = {11, 22, 33, 44};
            System.arraycopy(originalFingerprints, 0, ownerFingerprints, 0,
                    originalFingerprints.length);
            set(owner, "dirtyMask", 0);
            set(owner, "buildInFlight", true);

            TrackingInstance solid = new TrackingInstance(false);
            TrackingInstance replacedCutout = new TrackingInstance(false);
            TrackingInstance translucent = new TrackingInstance(false);
            TrackingInstance replacementCutout = new TrackingInstance(false);
            List<Object> activePages = pages(owner, "activePages");
            activePages.add(newInstalledPage(solid, TerrainCategory.SOLID, 0));
            activePages.add(newInstalledPage(replacedCutout, TerrainCategory.CUTOUT, 0));
            activePages.add(newInstalledPage(translucent, TerrainCategory.TRANSLUCENT, 0));

            int[] resultFingerprints = {101, 202, 303, 404};
            long accountedBytes = 4096;
            Object result = newResult(owner, intField(owner, "generation"),
                    1 << TerrainCategory.CUTOUT, resultFingerprints,
                    intField(manager, "epoch"), accountedBytes);
            Object cursor = newCursor(result);
            addPage(cursor, replacementCutout, TerrainCategory.CUTOUT, 0);
            atomicLong(manager, "pendingMeshBytes").set(accountedBytes);

            invoke(manager, "finishInstall", new Class<?>[]{nested("MeshInstallCursor")}, cursor);

            assertEquals(3, activePages.size());
            assertSame(solid, pageInstance(activePages.get(0)));
            assertSame(translucent, pageInstance(activePages.get(1)));
            assertSame(replacementCutout, pageInstance(activePages.get(2)));
            assertArrayEquals(new int[]{11, 202, 33, 44}, ownerFingerprints,
                    "Only categories present in the build result may advance fingerprints");
            assertTrue(booleanField(owner, "hasPublishedState"));
            assertFalse(booleanField(owner, "buildInFlight"));
            assertEquals(0, atomicLong(manager, "pendingMeshBytes").get());
            assertEquals(1, retirementQueue(manager).pendingRetirements(),
                    "Only the replaced category page should enter retirement");
        } finally {
            manager.destroy();
        }
    }

    @Test
    void abortedInstallRestoresDirtyMaskAndBalancesByteAccounting() throws Exception {
        ColumnPublicationOrchestrator manager = manager();
        TrackingMesh uninstalledMesh = new TrackingMesh();
        try {
            terrain(manager).addSection(-2, 1, 4, 1, "live");
            int categoryMask = (1 << TerrainCategory.SOLID)
                    | (1 << TerrainCategory.OVERLAY);
            Object owner = markColumnDirty(manager, -2, 4, categoryMask);
            set(owner, "dirtyMask", 0);
            set(owner, "buildInFlight", true);

            long accountedBytes = 8192;
            Object result = newResult(owner, intField(owner, "generation"), categoryMask,
                    new int[TerrainCategory.COUNT], intField(manager, "epoch"),
                    accountedBytes);
            set(result, "pages", new BlockMeshBuilder.MeshPage[]{
                    new BlockMeshBuilder.MeshPage(TerrainCategory.SOLID, uninstalledMesh)
            });
            Object cursor = newCursor(result);
            TrackingInstance staged = new TrackingInstance(false);
            addPage(cursor, staged, TerrainCategory.OVERLAY, 0);
            atomicLong(manager, "pendingMeshBytes").set(accountedBytes);

            invoke(manager, "abortInstall", new Class<?>[]{nested("MeshInstallCursor")}, cursor);

            assertEquals(categoryMask, intField(owner, "dirtyMask"));
            assertFalse(booleanField(owner, "buildInFlight"));
            assertEquals(0, atomicLong(manager, "pendingMeshBytes").get());
            assertEquals(1, uninstalledMesh.disposeCalls);
            assertEquals(1, retirementQueue(manager).pendingRetirements());
        } finally {
            uninstalledMesh.destroy();
            manager.destroy();
        }
    }

    @Test
    void freshSectionStreamingSettlesWhileReplacementBuildsImmediately() throws Exception {
        ColumnPublicationOrchestrator manager = manager();
        try {
            set(manager, "frame", 100L);
            Object owner = markColumnDirty(manager, 4, -6, 1 << TerrainCategory.SOLID);

            assertFalse(booleanField(owner, "urgentBuild"));
            assertEquals(100L, longField(owner, "dirtySinceFrame"));
            assertEquals(104L, longField(owner, "settleUntilFrame"));

            set(manager, "frame", 102L);
            markColumnDirty(manager, 4, -6, 1 << TerrainCategory.CUTOUT);
            assertEquals(100L, longField(owner, "dirtySinceFrame"));
            assertEquals(106L, longField(owner, "settleUntilFrame"),
                    "Each fresh streamed section extends the short coalescing window");

            invoke(manager, "markDirty",
                    new Class<?>[]{int.class, int.class, int.class, boolean.class},
                    4, -6, 1 << TerrainCategory.OVERLAY, true);
            assertFalse(booleanField(owner, "urgentBuild"),
                    "Repeated captures of an unpublished column must keep settling");
            assertEquals(106L, longField(owner, "settleUntilFrame"));

            set(owner, "hasPublishedState", true);
            set(manager, "frame", 103L);
            invoke(manager, "markDirty",
                    new Class<?>[]{int.class, int.class, int.class, boolean.class},
                    4, -6, 1 << TerrainCategory.OVERLAY, true);
            assertTrue(booleanField(owner, "urgentBuild"));
            assertEquals(103L, longField(owner, "settleUntilFrame"),
                    "A replacement must bypass stream settling");
        } finally {
            manager.destroy();
        }
    }

    private static Object newDetachedResult(long accountedBytes) throws Exception {
        Class<?> ownerType = nested("ColumnRenderState");
        Constructor<?> ownerConstructor = ownerType.getDeclaredConstructor(
                long.class, int.class, int.class);
        ownerConstructor.setAccessible(true);
        Object owner = ownerConstructor.newInstance(0L, 0, 0);
        return newResult(owner, 0, 0, new int[TerrainCategory.COUNT],
                0, accountedBytes);
    }

    private static Object readyEmptyColumn(ColumnPublicationOrchestrator manager, int cx, int cz)
            throws Exception {
        terrain(manager).addSection(cx, 0, cz, 1, "live");
        Object owner = markColumnDirty(manager, cx, cz, 1 << TerrainCategory.SOLID);
        set(owner, "dirtyMask", 0);
        set(owner, "buildInFlight", true);
        Object result = newResult(owner, intField(owner, "generation"),
                1 << TerrainCategory.SOLID, new int[TerrainCategory.COUNT],
                intField(manager, "epoch"), 0);
        set(result, "pages", new BlockMeshBuilder.MeshPage[0]);
        set(result, "complete", true);
        queue(manager, "readyQueue").add(result);
        atomicInteger(manager, "pendingResults").incrementAndGet();
        return owner;
    }

    private static Object newResult(Object owner, int generation, int categoryMask,
                                    int[] fingerprints, int epoch,
                                    long accountedBytes) throws Exception {
        Class<?> resultType = nested("MeshBuildResult");
        Constructor<?> constructor = resultType.getDeclaredConstructor(
                nested("ColumnRenderState"), int.class, int.class,
                int[].class, int.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(owner, generation, categoryMask,
                fingerprints, epoch, accountedBytes);
    }

    private static Object newCursor(Object result) throws Exception {
        Class<?> cursorType = nested("MeshInstallCursor");
        Constructor<?> constructor = cursorType.getDeclaredConstructor(nested("MeshBuildResult"));
        constructor.setAccessible(true);
        return constructor.newInstance(result);
    }

    private static Object markColumnDirty(ColumnPublicationOrchestrator manager, int cx, int cz,
                                          int categoryMask) throws Exception {
        return invoke(manager, "markDirty",
                new Class<?>[]{int.class, int.class, int.class, boolean.class},
                cx, cz, categoryMask, false);
    }

    private static Object newInstalledPage(TrackingInstance instance, int category,
                                           int categoryFlags) throws Exception {
        Class<?> pageType = nested("InstalledMeshPage");
        Constructor<?> constructor = pageType.getDeclaredConstructor(
                RemixInstance.class, int.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(instance, category, categoryFlags);
    }

    private static void addPage(Object cursor, TrackingInstance instance, int category,
                                int categoryFlags) throws Exception {
        pages(cursor, "installedPages").add(
                newInstalledPage(instance, category, categoryFlags));
    }

    private static boolean rollbackOne(ColumnPublicationOrchestrator manager, Object cursor) throws Exception {
        return (boolean) invoke(manager, "rollbackNextActiveInstallPage",
                new Class<?>[]{nested("MeshInstallCursor"), RemixAPI.class}, cursor, null);
    }

    private static boolean isStale(ColumnPublicationOrchestrator manager, Object result) throws Exception {
        return (boolean) invoke(manager, "isStale",
                new Class<?>[]{nested("MeshBuildResult")}, result);
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static Class<?> nested(String simpleName) {
        return Arrays.stream(ColumnPublicationOrchestrator.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals(simpleName))
                .findFirst()
                .orElseThrow();
    }

    private static ColumnPublicationOrchestrator manager() {
        return new ColumnPublicationOrchestrator(new TerrainState(128));
    }

    private static TerrainState terrain(ColumnPublicationOrchestrator manager) throws Exception {
        return (TerrainState) field(manager, "terrain");
    }

    private static TerrainAssetRetirementQueue retirementQueue(ColumnPublicationOrchestrator manager)
            throws Exception {
        return (TerrainAssetRetirementQueue) field(manager, "retirementQueue");
    }

    private static AtomicLong atomicLong(Object target, String name) throws Exception {
        return (AtomicLong) field(target, name);
    }

    private static AtomicInteger atomicInteger(Object target, String name) throws Exception {
        return (AtomicInteger) field(target, name);
    }

    @SuppressWarnings("unchecked")
    private static <T> ConcurrentLinkedQueue<T> queue(Object target, String name)
            throws Exception {
        return (ConcurrentLinkedQueue<T>) field(target, name);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> pages(Object target, String name) throws Exception {
        return (List<Object>) field(target, name);
    }

    private static RemixInstance pageInstance(Object page) throws Exception {
        return (RemixInstance) field(page, "instance");
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static int intField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static long longField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(target);
    }

    private static boolean booleanField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static final class TrackingMesh extends RemixMesh {
        int disposeCalls;

        TrackingMesh() {
            super(new RemixMeshSurface[0]);
        }

        @Override
        public void disposeData() {
            disposeCalls++;
            super.disposeData();
        }
    }

    private static final class TrackingInstance extends RemixInstance {
        int categoryFlags;
        int drawCalls;
        final boolean failDraw;

        TrackingInstance(boolean failDraw) {
            super(new RemixMesh(new RemixMeshSurface[0]));
            this.failDraw = failDraw;
        }

        @Override
        public TrackingInstance setCategoryFlags(int flags) {
            categoryFlags = flags;
            return this;
        }

        @Override
        public void drawFast(RemixAPI api) {
            drawCalls++;
            if (failDraw) throw new IllegalStateException("synthetic hide failure");
        }
    }

}

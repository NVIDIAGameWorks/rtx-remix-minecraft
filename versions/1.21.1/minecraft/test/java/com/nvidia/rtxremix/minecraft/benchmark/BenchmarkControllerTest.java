/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.benchmark;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nvidia.rtxremix.minecraft.camera.CameraController;
import com.nvidia.rtxremix.minecraft.profiling.PerformanceTimer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkControllerTest {

    /**
     * Warmup frames must not be counted while a world-load overlay is up; the
     * screen classes are matched by simple name (obfuscation-mapped at runtime).
     */
    @Test
    void isWorldLoadScreenNameMatchesLoadingScreensOnly() {
        assertTrue(BenchmarkController.isWorldLoadScreenName("DownloadingTerrainScreen"));
        assertTrue(BenchmarkController.isWorldLoadScreenName("LevelLoadingScreen"));
        assertFalse(BenchmarkController.isWorldLoadScreenName("GameMenuScreen"));
        assertFalse(BenchmarkController.isWorldLoadScreenName("ChatScreen"));
    }

    @Test
    void cameraFixtureNameResolvesVersionedAndUnversionedWorlds() {
        assertEquals("demo_v1", CameraController.fixtureName("demo", "v1"));
        assertEquals("cyberpunk_city", CameraController.fixtureName("cyberpunk_city", ""));
        assertEquals("demo_v1", CameraController.fixtureName("demo_v1", "v1"));
    }

    @Test
    void triggerWithoutCanonicalDirectoryLeavesManagedTargetUnset() {
        BenchmarkController controller = new BenchmarkController();

        controller.applyTrigger(triggerJson("{\"benchmarkWorld\":\"demo\",\"cameraStorageKey\":\"demo_v1\"}"));

        assertNull(controller.cameraCanonicalDirectory());
        assertEquals("demo", controller.benchmarkWorldName());
    }

    @Test
    void triggerCanonicalDirectoryIsUsedVerbatim() {
        BenchmarkController controller = new BenchmarkController();

        controller.applyTrigger(triggerJson("{\"benchmarkWorld\":\"demo\",\"cameraStorageKey\":\"demo_v1\","
                + "\"cameraCanonicalDirectory\":\"D:/fixtures/demo_v1/cameras\"}"));

        assertEquals(Path.of("D:/fixtures/demo_v1/cameras"), controller.cameraCanonicalDirectory());
    }

    @Test
    void triggerWithoutWorldIsRejected() {
        BenchmarkController controller = new BenchmarkController();

        assertThrows(
                IllegalArgumentException.class,
                () -> controller.applyTrigger(triggerJson("{\"cameraStorageKey\":\"demo_v1\"}"))
        );
    }

    @Test
    void stillsTriggerCarriesItsSetFilterAndSettleBudget() {
        BenchmarkController controller = new BenchmarkController();

        controller.applyTrigger(triggerJson("{\"benchmarkWorld\":\"demo\",\"mode\":\"stills\","
                + "\"shotSet\":\"docs\",\"shots\":[\"rapids\",\"village-night\"],"
                + "\"shotSetDirectory\":\"D:/fixtures/shots\","
                + "\"settleFrames\":120,\"quietFrames\":15,\"maxSettleFrames\":600}"));

        assertEquals("stills", controller.autoTriggerMode());
        assertEquals(Path.of("D:/fixtures/shots"), controller.shotSetDirectory());
        assertEquals("docs", controller.shotSetName());
        assertEquals(List.of("rapids", "village-night"), controller.shotFilter());
        assertEquals(new StillShotSession.Settle(120, 15, 600), controller.shotSettle());
    }

    @Test
    void triggerWithoutShotFieldsDefaultsToTheDocsSetAndEveryShot() {
        BenchmarkController controller = new BenchmarkController();

        controller.applyTrigger(triggerJson("{\"benchmarkWorld\":\"demo\",\"mode\":\"nonsense\"}"));

        assertEquals("benchmark", controller.autoTriggerMode());
        assertEquals("docs", controller.shotSetName());
        assertTrue(controller.shotFilter().isEmpty());
        assertEquals(StillShotSession.Settle.DEFAULT, controller.shotSettle());
    }

    @Test
    void benchmarkFrameCountIncludesItsExactEndpoint() {
        assertEquals(61, BenchmarkController.benchmarkFrameCount(1_000));
    }

    @Test
    void benchmarkDurationLimitStillIncludesItsExactEndpoint() {
        long maximum = BenchmarkController.MAX_BENCHMARK_CAMERA_DURATION_MS;

        assertTrue(BenchmarkController.isBenchmarkCameraDurationSupported(maximum));
        assertFalse(BenchmarkController.isBenchmarkCameraDurationSupported(maximum + 1L));
        assertFalse(BenchmarkController.isBenchmarkCameraDurationSupported(-1L));
        assertEquals(Integer.MAX_VALUE, BenchmarkController.benchmarkFrameCount(maximum));
        assertEquals(
                maximum,
                BenchmarkController.benchmarkCameraTimeMs(Integer.MAX_VALUE - 1)
        );
    }

    @Test
    void disconnectClearsRunningAndPendingBenchmarkState() throws Exception {
        BenchmarkController controller = new BenchmarkController(new CameraController());
        Field stateField = BenchmarkController.class.getDeclaredField("state");
        stateField.setAccessible(true);
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object waitingForWorld = Enum.valueOf((Class) stateField.getType(), "WAITING_FOR_WORLD");
        stateField.set(controller, waitingForWorld);

        Field pendingField = BenchmarkController.class.getDeclaredField("autoTriggerPending");
        pendingField.setAccessible(true);
        pendingField.setBoolean(controller, true);
        Field joinField = BenchmarkController.class.getDeclaredField("worldJoinRequested");
        joinField.setAccessible(true);
        joinField.setBoolean(controller, true);

        controller.onDisconnect(new PerformanceTimer());

        assertFalse(controller.isRunning());
        assertEquals("idle", controller.getStatus());
        assertFalse(pendingField.getBoolean(controller));
        assertFalse(joinField.getBoolean(controller));
    }

    @Test
    void initialWorldJoinPreservesPendingAutoTrigger() throws Exception {
        BenchmarkController controller =
                new BenchmarkController(new CameraController());
        Field pendingField =
                BenchmarkController.class.getDeclaredField("autoTriggerPending");
        pendingField.setAccessible(true);
        pendingField.setBoolean(controller, true);
        Field joinField =
                BenchmarkController.class.getDeclaredField("worldJoinRequested");
        joinField.setAccessible(true);
        joinField.setBoolean(controller, true);

        controller.onWorldJoin(new PerformanceTimer());

        assertTrue(pendingField.getBoolean(controller));
        assertTrue(joinField.getBoolean(controller));
        assertFalse(controller.isRunning());
    }

    @Test
    void warmupLapSweepsThePathAtTwiceSpeedThenSettlesAtTheStart() {
        int total = 1200;
        int warmup = BenchmarkController.warmupLapFrames(total);
        assertEquals(600 + BenchmarkController.WARMUP_SETTLE_FRAMES, warmup);
        int lapFrames = warmup - BenchmarkController.WARMUP_SETTLE_FRAMES;

        assertEquals(0, BenchmarkController.warmupLapPathFrame(0, warmup, total));
        assertEquals(2, BenchmarkController.warmupLapPathFrame(1, warmup, total));
        assertEquals(1198, BenchmarkController.warmupLapPathFrame(lapFrames - 1, warmup, total));
        assertEquals(0, BenchmarkController.warmupLapPathFrame(lapFrames, warmup, total));
        assertEquals(0, BenchmarkController.warmupLapPathFrame(warmup - 1, warmup, total));
    }

    @Test
    void warmupLapSlowsDownWhenWarmupOutlastsTheLap() {
        int total = 1200;
        int warmup = 2400;
        int lapFrames = warmup - BenchmarkController.WARMUP_SETTLE_FRAMES;

        assertEquals(526, BenchmarkController.warmupLapPathFrame(1000, warmup, total));
        assertEquals(total - 1, BenchmarkController.warmupLapPathFrame(lapFrames - 1, warmup, total));
    }

    @Test
    void warmupLapNeverSweepsFasterThanTwiceSpeed() {
        assertEquals(448, BenchmarkController.warmupLapPathFrame(224, 300, 1200));
        assertEquals(0, BenchmarkController.warmupLapPathFrame(225, 300, 1200));
    }

    @Test
    void warmupLapFramesCoverAShortPathExactly() {
        int total = 100;
        int warmup = BenchmarkController.warmupLapFrames(total);

        assertEquals(170, warmup);
        assertEquals(99, BenchmarkController.warmupLapPathFrame(127, 170, 100));
    }

    private static JsonObject triggerJson(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}

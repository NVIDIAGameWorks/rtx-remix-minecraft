/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraControllerTest {

    @Test
    void resetSessionClearsWorldScopedState() {
        CameraController controller = new CameraController();
        controller.arm("demo");

        controller.resetSession();

        assertEquals(CameraController.State.IDLE, controller.state());
        assertEquals("Camera: idle", controller.status());
        assertNull(controller.loadedPath());
        assertNull(controller.loadedPathName());
        assertEquals("", controller.lastError());
    }

    @Test
    void playbackStatusExplainsClockMovementAndOnlyShowsTimeClockLag() {
        String timeStatus = CameraController.formatPlaybackClockStatus(
                CameraPlaybackMode.TIME,
                50_000_000L,
                20_000_000L,
                30_000_000L
        );
        assertEquals(
                " | clock time | raw frame 50.000ms | camera advance 20.000ms"
                        + " | smoothing lag 0.030s",
                timeStatus
        );

        String fixedStatus = CameraController.formatPlaybackClockStatus(
                CameraPlaybackMode.FIXED_60,
                50_000_000L,
                17_000_000L,
                30_000_000L
        );
        assertEquals(
                " | clock fixed-60 | raw frame 50.000ms | camera advance 17.000ms",
                fixedStatus
        );

        String caughtUpTimeStatus = CameraController.formatPlaybackClockStatus(
                CameraPlaybackMode.TIME,
                16_000_000L,
                16_000_000L,
                0L
        );
        assertEquals(
                " | clock time | raw frame 16.000ms | camera advance 16.000ms",
                caughtUpTimeStatus
        );
    }

    @Test
    void seekingActivePlaybackToTheEndpointCompletesImmediately() {
        assertEquals(
                CameraController.State.PLAYING,
                CameraController.stateAfterSeek(
                        CameraController.State.PLAYING,
                        999L,
                        1_000L
                )
        );
        assertEquals(
                CameraController.State.IDLE,
                CameraController.stateAfterSeek(
                        CameraController.State.PLAYING,
                        1_000L,
                        1_000L
                )
        );
        assertEquals(
                CameraController.State.IDLE,
                CameraController.stateAfterSeek(
                        CameraController.State.PAUSED,
                        2_000L,
                        1_000L
                )
        );
        assertEquals(
                CameraController.State.IDLE,
                CameraController.stateAfterSeek(
                        CameraController.State.IDLE,
                        1_000L,
                        1_000L
                )
        );
    }

    @Test
    void recordingCoalescesCatchUpTicksWithoutMovingTimeBackwards() {
        List<CameraPath.Waypoint> waypoints = new ArrayList<>();
        CameraController.storeRecordingWaypoint(waypoints, waypoint(0, 0));

        // Preserve the exact time-zero pose instead of creating a tiny first
        // segment when the first client tick follows the hotkey immediately.
        CameraController.storeRecordingWaypoint(waypoints, waypoint(2, 1));
        assertEquals(List.of(waypoint(0, 0)), waypoints);

        CameraController.storeRecordingWaypoint(waypoints, waypoint(50, 1));
        CameraController.storeRecordingWaypoint(waypoints, waypoint(52, 2));
        assertEquals(List.of(waypoint(0, 0), waypoint(52, 2)), waypoints);

        CameraController.storeRecordingWaypoint(waypoints, waypoint(102, 3));
        assertEquals(List.of(waypoint(0, 0), waypoint(52, 2), waypoint(102, 3)), waypoints);

        // An exact final sample replaces the tick sample at the same timestamp.
        CameraController.storeRecordingWaypoint(waypoints, waypoint(102, 4));
        assertEquals(List.of(waypoint(0, 0), waypoint(52, 2), waypoint(102, 4)), waypoints);

        assertThrows(
                IllegalArgumentException.class,
                () -> CameraController.storeRecordingWaypoint(waypoints, waypoint(101, 5))
        );
    }

    @Test
    void pausingScreenIsDetectedBeforeMinecraftRefreshesItsCachedPauseFlag() {
        assertTrue(CameraController.isPausedOrWillPause(true, false, false, false, false));
        assertTrue(CameraController.isPausedOrWillPause(false, true, false, true, false));
        assertTrue(CameraController.isPausedOrWillPause(false, true, false, false, true));

        // Multiplayer pause menus do not pause the world.
        assertFalse(CameraController.isPausedOrWillPause(false, false, false, true, false));
        assertFalse(CameraController.isPausedOrWillPause(false, true, true, true, false));
    }

    private static CameraPath.Waypoint waypoint(long timeMs, double x) {
        return new CameraPath.Waypoint(timeMs, x, 0, 0, 0, 0, null);
    }
}

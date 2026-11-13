/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraPathTest {

    private static final double EPSILON = 1.0e-6;

    @Test
    void sampleAtClampsToEndpointsAndInterpolatesInterior() {
        CameraPath path = new CameraPath(List.of(
                waypoint(0, 0, 0, 0, 0, 0, null),
                waypoint(1_000, 10, 20, 30, 40, 5, null),
                waypoint(2_000, 20, 40, 60, 80, 10, null),
                waypoint(3_000, 30, 60, 90, 120, 15, null)
        ));

        assertEquals(new CameraPath.Sample(0, 0, 0, 0, 0, null), path.sampleAt(-100));
        assertEquals(new CameraPath.Sample(30, 60, 90, 120, 15, null), path.sampleAt(4_000));
        assertEquals(3_000, path.durationMs());

        CameraPath.Sample interior = path.sampleAt(1_500);
        assertEquals(15.0, interior.x(), EPSILON);
        assertEquals(30.0, interior.y(), EPSILON);
        assertEquals(45.0, interior.z(), EPSILON);
        assertEquals(60.0f, interior.yaw(), EPSILON);
        assertEquals(7.5f, interior.pitch(), EPSILON);
        assertNull(interior.timeOfDay());
    }

    @Test
    void rejectsDurationsThatCannotFitTheNanosecondPlaybackClock() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new CameraPath(List.of(
                        waypoint(0, 0, 0, 0, 0, 0, null),
                        waypoint(CameraPath.MAX_DURATION_MS + 1L, 1, 1, 1, 1, 1, null)
                ))
        );
        assertTrue(error.getMessage().contains("maximum supported duration"));
    }

    @Test
    void sampleAtPreservesConstantSpeedAndVelocityAcrossNonuniformWaypoints() {
        CameraPath path = new CameraPath(List.of(
                waypoint(0, 0, 0, 0, 0, 0, null),
                waypoint(100, 10, 20, 30, 5, 2, null),
                waypoint(350, 35, 70, 105, 17.5f, 7, null),
                waypoint(1_000, 100, 200, 300, 50, 20, null)
        ));

        assertEquals(20.0, path.sampleAt(200).x(), EPSILON);
        assertEquals(60.0, path.sampleAt(600).x(), EPSILON);

        double leftVelocity = path.sampleAt(350).x() - path.sampleAt(349).x();
        double rightVelocity = path.sampleAt(351).x() - path.sampleAt(350).x();
        assertEquals(0.1, leftVelocity, EPSILON);
        assertEquals(leftVelocity, rightVelocity, EPSILON);
    }

    @Test
    void sampleAtUnwrapsYawAcrossSignedBoundary() {
        CameraPath path = new CameraPath(List.of(
                waypoint(0, 0, 0, 0, 179, 0, null),
                waypoint(1_000, 0, 0, 0, -179, 0, null)
        ));

        assertEquals(179.0f, path.sampleAt(0).yaw(), EPSILON);
        assertEquals(180.0f, path.sampleAt(500).yaw(), EPSILON);
        assertEquals(181.0f, path.sampleAt(1_000).yaw(), EPSILON);
    }

    @Test
    void sampleAtDoesNotOvershootMonotoneOrStationaryComponents() {
        CameraPath path = new CameraPath(List.of(
                waypoint(0, 0, 0, 0, 0, 0, null),
                waypoint(100, 10, 0, 0, 10, 5, null),
                waypoint(600, 10, 0, 0, 10, 5, null),
                waypoint(1_000, 20, 0, 0, 20, 10, null)
        ));

        for (long timeMs = 0; timeMs <= path.durationMs(); timeMs++) {
            CameraPath.Sample sample = path.sampleAt(timeMs);
            assertTrue(Double.isFinite(sample.x()));
            assertTrue(Float.isFinite(sample.yaw()));
            assertTrue(Float.isFinite(sample.pitch()));
            assertTrue(sample.x() >= -EPSILON && sample.x() <= 20.0 + EPSILON);
            assertTrue(sample.yaw() >= -EPSILON && sample.yaw() <= 20.0f + EPSILON);
            assertTrue(sample.pitch() >= -EPSILON && sample.pitch() <= 10.0f + EPSILON);
            if (timeMs >= 100 && timeMs <= 600) {
                assertEquals(10.0, sample.x(), EPSILON);
                assertEquals(10.0f, sample.yaw(), EPSILON);
                assertEquals(5.0f, sample.pitch(), EPSILON);
            }
        }
    }

    @Test
    void sampleAtUsesShortestMinecraftTimeOfDayWrap() {
        CameraPath path = new CameraPath(List.of(
                waypoint(0, 0, 0, 0, 0, 0, 23_000L),
                waypoint(1_000, 1, 1, 1, 1, 1, 1_000L)
        ));

        assertEquals(23_000L, path.sampleAt(0).timeOfDay());
        assertEquals(0L, path.sampleAt(500).timeOfDay());
        assertEquals(1_000L, path.sampleAt(1_000).timeOfDay());
    }

    @Test
    void sampleAtPreservesAvailableTimeOfDayWhenOtherEndpointIsMissing() {
        CameraPath missingStart = new CameraPath(List.of(
                waypoint(0, 0, 0, 0, 0, 0, null),
                waypoint(1_000, 1, 1, 1, 1, 1, 6_000L)
        ));
        CameraPath missingEnd = new CameraPath(List.of(
                waypoint(0, 0, 0, 0, 0, 0, 18_000L),
                waypoint(1_000, 1, 1, 1, 1, 1, null)
        ));
        CameraPath bothMissing = new CameraPath(List.of(
                waypoint(0, 0, 0, 0, 0, 0, null),
                waypoint(1_000, 1, 1, 1, 1, 1, null)
        ));

        assertEquals(6_000L, missingStart.sampleAt(500).timeOfDay());
        assertEquals(18_000L, missingEnd.sampleAt(500).timeOfDay());
        assertNull(bothMissing.sampleAt(500).timeOfDay());
    }

    @Test
    void constructorRejectsInvalidWaypointTimelines() {
        assertThrows(IllegalArgumentException.class, () -> new CameraPath(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CameraPath(List.of(
                waypoint(1, 0, 0, 0, 0, 0, null),
                waypoint(2, 0, 0, 0, 0, 0, null)
        )));
        assertThrows(IllegalArgumentException.class, () -> new CameraPath(List.of(
                waypoint(0, 0, 0, 0, 0, 0, null),
                waypoint(1_000, 0, 0, 0, 0, 0, null),
                waypoint(1_000, 0, 0, 0, 0, 0, null)
        )));
        assertThrows(IllegalArgumentException.class, () -> new CameraPath(List.of(
                waypoint(0, 0, 0, 0, 0, 0, null),
                waypoint(2_000, 0, 0, 0, 0, 0, null),
                waypoint(1_000, 0, 0, 0, 0, 0, null)
        )));
    }

    @Test
    void cameraPathDefensivelyCopiesWaypointList() {
        List<CameraPath.Waypoint> source = new ArrayList<>(List.of(
                waypoint(0, 0, 0, 0, 0, 0, null),
                waypoint(1_000, 1, 1, 1, 1, 1, null)
        ));
        CameraPath path = new CameraPath(source);

        source.clear();

        assertEquals(2, path.waypoints().size());
        assertThrows(UnsupportedOperationException.class, () -> path.waypoints().clear());
    }

    private static CameraPath.Waypoint waypoint(
            long timeMs,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            Long timeOfDay
    ) {
        return new CameraPath.Waypoint(timeMs, x, y, z, yaw, pitch, timeOfDay);
    }
}

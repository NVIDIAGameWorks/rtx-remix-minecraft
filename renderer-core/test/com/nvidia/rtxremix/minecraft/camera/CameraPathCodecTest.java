/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraPathCodecTest {

    @Test
    void prettyJsonRoundTripsVersionOnePath() {
        CameraPath original = new CameraPath(List.of(
                new CameraPath.Waypoint(0, 1.25, 2.5, 3.75, 45.5f, -10.25f, null),
                new CameraPath.Waypoint(2_500, 4.25, 5.5, 6.75, 90.5f, 15.25f, 25_000L)
        ));

        String json = CameraPathCodec.toJson(original);
        CameraPath decoded = CameraPathCodec.parse(json);

        assertEquals(original, decoded);
        assertTrue(json.contains("\n"));
        assertTrue(json.contains("\"version\": 1"));
        assertTrue(json.contains("\"interpolation\": \"catmull-rom\""));
        assertFalse(json.contains("\"frames\""));
        assertFalse(json.substring(0, json.indexOf("\"timeMs\": 2500")).contains("\"timeOfDay\""));
        assertEquals(1_000L, decoded.waypoints().get(1).timeOfDay());
    }

    @Test
    void rejectsLegacyFramesOnlyWaypoints() {
        String json = """
                {
                  "version": 1,
                  "interpolation": "catmull-rom",
                  "waypoints": [
                    {"x": 0, "y": 0, "z": 0, "yaw": 0, "pitch": 0, "frames": 60},
                    {"x": 1, "y": 1, "z": 1, "yaw": 1, "pitch": 1, "frames": 60}
                  ]
                }
                """;

        assertThrows(JsonParseException.class, () -> CameraPathCodec.parse(json));
    }

    @Test
    void rejectsUnsupportedAndMalformedSchemas() {
        assertThrows(JsonParseException.class, () -> CameraPathCodec.parse("""
                {"version": 2, "interpolation": "catmull-rom", "waypoints": []}
                """));
        assertThrows(JsonParseException.class, () -> CameraPathCodec.parse("""
                {"version": 1, "interpolation": "linear", "waypoints": []}
                """));
        assertThrows(JsonParseException.class, () -> CameraPathCodec.parse("""
                {
                  "version": 1,
                  "interpolation": "catmull-rom",
                  "waypoints": [
                    {"timeMs": 0, "x": 0, "y": 0, "z": 0, "yaw": 0, "pitch": 0},
                    {"timeMs": 0, "x": 1, "y": 1, "z": 1, "yaw": 1, "pitch": 1}
                  ]
                }
                """));
        assertThrows(JsonParseException.class, () -> CameraPathCodec.parse("""
                {
                  "version": 1,
                  "interpolation": "catmull-rom",
                  "waypoints": [
                    {"timeMs": 0, "x": "zero", "y": 0, "z": 0, "yaw": 0, "pitch": 0},
                    {"timeMs": 1000, "x": 1, "y": 1, "z": 1, "yaw": 1, "pitch": 1}
                  ]
                }
                """));
        assertThrows(JsonParseException.class, () -> CameraPathCodec.parse("""
                {"version": 1, "interpolation": "catmull-rom", "waypoints": [], "extra": true}
                """));
        assertThrows(JsonParseException.class, () -> CameraPathCodec.parse("""
                {
                  "version": 1,
                  "interpolation": "catmull-rom",
                  "waypoints": [
                    {"timeMs": 0, "x": 0, "y": 0, "z": 0, "yaw": 0, "pitch": 0},
                    {"timeMs": 9223372036855, "x": 1, "y": 1, "z": 1, "yaw": 1, "pitch": 1}
                  ]
                }
                """));
    }
}

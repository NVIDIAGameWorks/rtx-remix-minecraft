/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StillShotCodecTest {

    private static StillShot shot(String name, long timeOfDay, StillShot.Weather weather) {
        return new StillShot(name, 10.5, 64.0, -3.25, 90.0f, -12.5f, timeOfDay, weather, true, false);
    }

    @Test
    void roundTripsEveryField() {
        List<StillShot> shots = List.of(
                shot("village-night", 18000, StillShot.Weather.CLEAR),
                new StillShot("lighthouse", 1.0, 2.0, 3.0, 0.0f, 0.0f, 13000,
                        StillShot.Weather.THUNDER, false, true));

        assertEquals(shots, StillShotCodec.parse(StillShotCodec.toJson(shots)));
    }

    @Test
    void rejectsUnknownFieldsAndOtherVersions() {
        String json = StillShotCodec.toJson(List.of(shot("a", 0, StillShot.Weather.RAIN)));

        assertThrows(JsonParseException.class,
                () -> StillShotCodec.parse(json.replace("\"rtx\"", "\"rtxOn\"")));
        assertThrows(JsonParseException.class,
                () -> StillShotCodec.parse(json.replace("\"version\": 1", "\"version\": 2")));
    }

    @Test
    void rejectsNamesRepeatedInAnyCaseAndBadWeather() {
        String twice = StillShotCodec.toJson(List.of(
                shot("same", 0, StillShot.Weather.CLEAR), shot("Same", 6000, StillShot.Weather.CLEAR)));
        assertThrows(JsonParseException.class, () -> StillShotCodec.parse(twice));

        String badWeather = StillShotCodec.toJson(List.of(shot("a", 0, StillShot.Weather.CLEAR)))
                .replace("\"clear\"", "\"drizzle\"");
        assertThrows(JsonParseException.class, () -> StillShotCodec.parse(badWeather));
    }

    @Test
    void aShotNameIsASingleFileNameEvenWhenReadFromAFile() {
        for (String name : List.of("../outside", "a/b", "C:\\x", "CON", "con.png", ".", "..", "", " spaced")) {
            assertThrows(IllegalArgumentException.class,
                    () -> shot(name, 0, StillShot.Weather.CLEAR), name);
            String json = StillShotCodec.toJson(List.of(shot("ok", 0, StillShot.Weather.CLEAR)))
                    .replace("\"ok\"", "\"" + name.replace("\\", "\\\\") + "\"");
            assertThrows(JsonParseException.class, () -> StillShotCodec.parse(json), name);
        }
        assertEquals("under_score.v2-x", shot("under_score.v2-x", 0, StillShot.Weather.CLEAR).name());
    }

    @Test
    void aShotKeepsItsTimeWithinOneDay() {
        assertThrows(IllegalArgumentException.class, () -> shot("late", 24000, StillShot.Weather.CLEAR));
        assertThrows(IllegalArgumentException.class, () -> shot("early", -1, StillShot.Weather.CLEAR));
        assertEquals(23999, shot("edge", 23999, StillShot.Weather.CLEAR).timeOfDay());
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.nvidia.rtxremix.minecraft.camera.CameraPathCodec.asJsonParseException;
import static com.nvidia.rtxremix.minecraft.camera.CameraPathCodec.immutableSet;
import static com.nvidia.rtxremix.minecraft.camera.CameraPathCodec.rejectUnknownFields;
import static com.nvidia.rtxremix.minecraft.camera.CameraPathCodec.requireDouble;
import static com.nvidia.rtxremix.minecraft.camera.CameraPathCodec.requireField;
import static com.nvidia.rtxremix.minecraft.camera.CameraPathCodec.requireFloat;
import static com.nvidia.rtxremix.minecraft.camera.CameraPathCodec.requireLong;
import static com.nvidia.rtxremix.minecraft.camera.CameraPathCodec.requireString;

/**
 * Strict JSON codec for a shot set, the {@code <set>.shots.json} file beside the
 * camera paths. Unknown fields and other versions are rejected, as for camera
 * paths, so a hand edit that misspells a key fails loudly instead of silently
 * dropping a shot's state.
 */
final class StillShotCodec {

    static final int VERSION = 1;

    private static final Set<String> ROOT_FIELDS = immutableSet("version", "shots");
    private static final Set<String> SHOT_FIELDS = immutableSet(
            "name", "x", "y", "z", "yaw", "pitch", "timeOfDay", "weather", "rtx", "proceduralClouds");
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    private StillShotCodec() {}

    /**
     * Parses a version 1 shot set. Names must be unique within the set
     * regardless of letter case, since each one becomes an image file name.
     *
     * @throws JsonParseException if the JSON is malformed, uses another version,
     *                            carries an unknown field, or repeats a name
     */
    static List<StillShot> parse(String json) {
        if (json == null) {
            throw new JsonParseException("shot set JSON must not be null");
        }
        final JsonElement parsed;
        try {
            parsed = new JsonParser().parse(json);
        } catch (RuntimeException e) {
            throw asJsonParseException("invalid shot set JSON", e);
        }
        if (!parsed.isJsonObject()) {
            throw new JsonParseException("shot set root must be an object");
        }
        JsonObject root = parsed.getAsJsonObject();
        rejectUnknownFields(root, ROOT_FIELDS, "shot set root");
        long version = requireLong(root, "version");
        if (version != VERSION) {
            throw new JsonParseException("unsupported shot set version: " + version);
        }
        JsonElement shotsElement = requireField(root, "shots");
        if (!shotsElement.isJsonArray()) {
            throw new JsonParseException("shots must be an array");
        }

        JsonArray array = shotsElement.getAsJsonArray();
        List<StillShot> shots = new ArrayList<>(array.size());
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) {
                throw new JsonParseException("shot " + i + " must be an object");
            }
            JsonObject shot = element.getAsJsonObject();
            rejectUnknownFields(shot, SHOT_FIELDS, "shot " + i);
            try {
                StillShot parsedShot = new StillShot(
                        requireString(shot, "name"),
                        requireDouble(shot, "x"),
                        requireDouble(shot, "y"),
                        requireDouble(shot, "z"),
                        requireFloat(shot, "yaw"),
                        requireFloat(shot, "pitch"),
                        requireLong(shot, "timeOfDay"),
                        StillShot.Weather.fromWireName(requireString(shot, "weather")),
                        requireBoolean(shot, "rtx"),
                        requireBoolean(shot, "proceduralClouds"));
                if (!names.add(parsedShot.name().toLowerCase(Locale.ROOT))) {
                    throw new JsonParseException("shot name repeated: " + parsedShot.name());
                }
                shots.add(parsedShot);
            } catch (IllegalArgumentException e) {
                throw asJsonParseException("invalid shot " + i, e);
            }
        }
        return shots;
    }

    /** Serializes a shot set as pretty-printed version 1 JSON. */
    static String toJson(List<StillShot> shots) {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonArray array = new JsonArray();
        for (StillShot shot : shots) {
            JsonObject object = new JsonObject();
            object.addProperty("name", shot.name());
            object.addProperty("x", shot.x());
            object.addProperty("y", shot.y());
            object.addProperty("z", shot.z());
            object.addProperty("yaw", shot.yaw());
            object.addProperty("pitch", shot.pitch());
            object.addProperty("timeOfDay", shot.timeOfDay());
            object.addProperty("weather", shot.weather().wireName());
            object.addProperty("rtx", shot.rtxEnabled());
            object.addProperty("proceduralClouds", shot.proceduralClouds());
            array.add(object);
        }
        root.add("shots", array);
        return PRETTY_GSON.toJson(root);
    }

    private static boolean requireBoolean(JsonObject object, String name) {
        JsonElement element = requireField(object, name);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new JsonParseException(name + " must be a boolean");
        }
        return element.getAsBoolean();
    }
}

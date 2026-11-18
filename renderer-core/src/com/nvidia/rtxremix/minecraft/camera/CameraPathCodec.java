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
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Strict JSON codec for time-based camera paths.
 *
 * <p>Every waypoint carries an absolute {@code timeMs}, and any unknown field
 * is rejected. {@link #INTERPOLATION} is the wire discriminator: the on-disk
 * name stays {@code "catmull-rom"} while the model interpolates with
 * shape-preserving cubic Hermite.</p>
 */
final class CameraPathCodec {

    static final int VERSION = 1;
    static final String INTERPOLATION = "catmull-rom";

    private static final Set<String> ROOT_FIELDS = immutableSet(
            "version", "interpolation", "waypoints");
    private static final Set<String> WAYPOINT_FIELDS =
            immutableSet("timeMs", "x", "y", "z", "yaw", "pitch", "timeOfDay");
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    private CameraPathCodec() {}

    static Set<String> immutableSet(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    /**
     * Parses and validates a version 1 camera path.
     *
     * @throws JsonParseException if the JSON or camera path is malformed or
     *                            uses an unsupported schema
     */
    static CameraPath parse(String json) {
        if (json == null) {
            throw new JsonParseException("camera path JSON must not be null");
        }

        final JsonElement parsed;
        try {
            parsed = new JsonParser().parse(json);
        } catch (RuntimeException e) {
            throw asJsonParseException("invalid camera path JSON", e);
        }

        if (!parsed.isJsonObject()) {
            throw new JsonParseException("camera path root must be an object");
        }
        JsonObject root = parsed.getAsJsonObject();
        rejectUnknownFields(root, ROOT_FIELDS, "camera path root");

        long version = requireLong(root, "version");
        if (version != VERSION) {
            throw new JsonParseException("unsupported camera path version: " + version);
        }

        String interpolation = requireString(root, "interpolation");
        if (!INTERPOLATION.equals(interpolation)) {
            throw new JsonParseException("unsupported camera path interpolation: " + interpolation);
        }

        JsonElement waypointElement = requireField(root, "waypoints");
        if (!waypointElement.isJsonArray()) {
            throw new JsonParseException("waypoints must be an array");
        }

        JsonArray waypointArray = waypointElement.getAsJsonArray();
        List<CameraPath.Waypoint> waypoints = new ArrayList<>(waypointArray.size());
        for (int i = 0; i < waypointArray.size(); i++) {
            JsonElement element = waypointArray.get(i);
            if (!element.isJsonObject()) {
                throw new JsonParseException("waypoint " + i + " must be an object");
            }

            JsonObject waypoint = element.getAsJsonObject();
            rejectUnknownFields(waypoint, WAYPOINT_FIELDS, "waypoint " + i);
            Long timeOfDay = waypoint.has("timeOfDay")
                    ? requireLong(waypoint, "timeOfDay")
                    : null;

            try {
                waypoints.add(new CameraPath.Waypoint(
                        requireLong(waypoint, "timeMs"),
                        requireDouble(waypoint, "x"),
                        requireDouble(waypoint, "y"),
                        requireDouble(waypoint, "z"),
                        requireFloat(waypoint, "yaw"),
                        requireFloat(waypoint, "pitch"),
                        timeOfDay
                ));
            } catch (IllegalArgumentException e) {
                throw asJsonParseException("invalid waypoint " + i, e);
            }
        }

        try {
            return new CameraPath(waypoints);
        } catch (IllegalArgumentException e) {
            throw asJsonParseException("invalid camera path", e);
        }
    }

    /**
     * Serializes a camera path as pretty-printed version 1 JSON.
     */
    static String toJson(CameraPath path) {
        Objects.requireNonNull(path, "path");

        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.addProperty("interpolation", INTERPOLATION);

        JsonArray waypoints = new JsonArray();
        for (CameraPath.Waypoint waypoint : path.waypoints()) {
            JsonObject object = new JsonObject();
            object.addProperty("timeMs", waypoint.timeMs());
            object.addProperty("x", waypoint.x());
            object.addProperty("y", waypoint.y());
            object.addProperty("z", waypoint.z());
            object.addProperty("yaw", waypoint.yaw());
            object.addProperty("pitch", waypoint.pitch());
            if (waypoint.timeOfDay() != null) {
                object.addProperty("timeOfDay", waypoint.timeOfDay());
            }
            waypoints.add(object);
        }
        root.add("waypoints", waypoints);
        return PRETTY_GSON.toJson(root);
    }

    static JsonElement requireField(JsonObject object, String name) {
        if (!object.has(name) || object.get(name).isJsonNull()) {
            throw new JsonParseException("missing required field: " + name);
        }
        return object.get(name);
    }

    static long requireLong(JsonObject object, String name) {
        JsonPrimitive primitive = requireNumber(object, name);
        try {
            return new BigDecimal(primitive.getAsString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw asJsonParseException(name + " must be an integer", e);
        }
    }

    static double requireDouble(JsonObject object, String name) {
        JsonPrimitive primitive = requireNumber(object, name);
        final double value;
        try {
            value = primitive.getAsDouble();
        } catch (NumberFormatException e) {
            throw asJsonParseException(name + " must be a number", e);
        }
        if (!Double.isFinite(value)) {
            throw new JsonParseException(name + " must be finite");
        }
        return value;
    }

    static float requireFloat(JsonObject object, String name) {
        double value = requireDouble(object, name);
        if (value < -Float.MAX_VALUE || value > Float.MAX_VALUE) {
            throw new JsonParseException(name + " is outside the float range");
        }
        return (float) value;
    }

    static JsonPrimitive requireNumber(JsonObject object, String name) {
        JsonElement element = requireField(object, name);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JsonParseException(name + " must be a number");
        }
        return element.getAsJsonPrimitive();
    }

    static String requireString(JsonObject object, String name) {
        JsonElement element = requireField(object, name);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(name + " must be a string");
        }
        return element.getAsString();
    }

    static void rejectUnknownFields(JsonObject object, Set<String> allowed, String context) {
        for (String field : object.keySet()) {
            if (!allowed.contains(field)) {
                throw new JsonParseException(context + " contains unsupported field: " + field);
            }
        }
    }

    static JsonParseException asJsonParseException(String message, RuntimeException cause) {
        if (cause instanceof JsonParseException) {
            return (JsonParseException) cause;
        }
        return new JsonParseException(message + ": " + cause.getMessage(), cause);
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconPose;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.core.Direction;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopperBeaconBlockEntityRendererTest {

    private static final float EPSILON = 1.0e-5f;

    @Test
    void everyPlacementRotatesStaticBaseAndDynamicHeadOntoTheSameMountingBasis() {
        Map<Direction, Direction> baseDirections = readBaseDirections();
        assertEquals(Direction.values().length, baseDirections.size());

        for (Direction mount : Direction.values()) {
            Direction baseDirection = baseDirections.get(mount);
            assertNotNull(baseDirection, "Missing base model variant for mount=" + mount);
            assertEquals(mount, baseDirection, "base for mount=" + mount);

            for (Direction forward : Direction.values()) {
                if (mount.getAxis() == forward.getAxis()) {
                    continue;
                }

                Quaternionf headRotation =
                        CopperBeaconBlockEntityRenderer.mountingRotation(mount, forward);
                Vector3f headUp = headRotation.transform(new Vector3f(0.0f, 1.0f, 0.0f));
                Vector3f headForward =
                        headRotation.transform(new Vector3f(0.0f, 0.0f, -1.0f));
                String placement = "mount=" + mount + ", facing=" + forward;

                assertDirection(mount, headUp, "head up for " + placement);
                assertDirection(forward, headForward, "head forward for " + placement);
                assertDirection(baseDirection, headUp, "base/head mount mismatch for " + placement);
            }
        }
    }

    @Test
    void animatedHeadAndEmitterShareOnePoseForEveryPlacementAndFullTurnAngle() {
        float[] angles = {-360.0f, -180.0f, -45.0f, 0.0f, 90.0f, 360.0f};
        ModelPart yoke = CopperBeaconBlockEntityRenderer
                .getBodyTexturedModelData()
                .bakeRoot()
                .getChild("yoke");
        ModelPart head = yoke.getChild("head");
        ModelPart lens = CopperBeaconBlockEntityRenderer
                .getLensTexturedModelData()
                .bakeRoot()
                .getChild("lens");
        PoseStack matrices = new PoseStack();
        Vector3f headDirection = new Vector3f();
        Vector3f lensDirection = new Vector3f();
        CopperBeaconPose.Pose pose = new CopperBeaconPose.Pose();
        for (Direction mount : Direction.values()) {
            for (Direction forward : Direction.values()) {
                if (mount.getAxis() == forward.getAxis()) {
                    continue;
                }
                matrices.pushPose();
                matrices.mulPose(
                        CopperBeaconBlockEntityRenderer.mountingRotation(mount, forward));
                for (float yaw : angles) {
                    for (float pitch : angles) {
                        CopperBeaconPose.evaluate(mount, forward, yaw, pitch, pose);
                        CopperBeaconBlockEntityRenderer.applyArticulation(yoke, head, lens, pose);

                        matrices.pushPose();
                        yoke.translateAndRotate(matrices);
                        head.translateAndRotate(matrices);
                        matrices.last().pose()
                                .transformDirection(headDirection.set(0.0f, 0.0f, -1.0f))
                                .normalize();
                        matrices.popPose();

                        matrices.pushPose();
                        lens.translateAndRotate(matrices);
                        matrices.last().pose()
                                .transformDirection(lensDirection.set(0.0f, 0.0f, -1.0f))
                                .normalize();
                        matrices.popPose();
                        String sample = "mount=" + mount
                                + ", forward=" + forward
                                + ", yaw=" + yaw
                                + ", pitch=" + pitch;

                        assertVector(pose.direction(), headDirection, "head " + sample);
                        assertVector(pose.direction(), lensDirection, "lens " + sample);
                    }
                }
                matrices.popPose();
            }
        }

        CopperBeaconPose.evaluate(Direction.UP, Direction.NORTH, 0.0f, 0.0f, pose);
        assertVector(new Vector3f(0.5f, 0.625f, 0.1875f), pose.emitter(),
                "emitter above the floor mount facing north");
    }

    @Test
    void forkUsesYawWhileHeadAndLensUseYawAndPitch() {
        CopperBeaconPose.Pose pose = new CopperBeaconPose.Pose();
        CopperBeaconPose.evaluate(
                Direction.UP,
                Direction.NORTH,
                75.0f,
                -35.0f,
                pose);
        ModelPart yoke = new ModelPart(List.of(), Map.of());
        ModelPart head = new ModelPart(List.of(), Map.of());
        ModelPart lens = new ModelPart(List.of(), Map.of());

        CopperBeaconBlockEntityRenderer.applyArticulation(yoke, head, lens, pose);

        assertEquals(pose.modelYawRadians(), yoke.yRot, EPSILON);
        assertEquals(0.0f, yoke.xRot, EPSILON);
        assertEquals(0.0f, head.yRot, EPSILON);
        assertEquals(pose.modelPitchRadians(), head.xRot, EPSILON);
        assertEquals(pose.modelYawRadians(), lens.yRot, EPSILON);
        assertEquals(head.xRot, lens.xRot, EPSILON);
    }

    @Test
    void headIsAChildOfTheYawFork() {
        ModelPart root = CopperBeaconBlockEntityRenderer
                .getBodyTexturedModelData()
                .bakeRoot();
        assertTrue(root.hasChild("yoke"));
        assertTrue(root.getChild("yoke").hasChild("head"));
        assertFalse(root.hasChild("head"));
    }

    @Test
    void lensMaterialIsEmissiveOnlyWhileTheAnalyticLightIsActive() {
        assertEquals(
                "rtx-remix-renderer:block/copper_beacon_lens",
                CopperBeaconBlockEntityRenderer.lensTexture(true, 1.0f)
                        .texture()
                        .toString());
        assertEquals(
                "rtx-remix-renderer:block/copper_beacon_lens_unlit",
                CopperBeaconBlockEntityRenderer.lensTexture(false, 1.0f)
                        .texture()
                        .toString());
        assertEquals(
                "rtx-remix-renderer:block/copper_beacon_lens_unlit",
                CopperBeaconBlockEntityRenderer.lensTexture(true, 0.0f)
                        .texture()
                        .toString());
    }

    @Test
    void activeLensHasDimLabPbrEmissionAndUnlitLensHasNoEmissionSidecar()
            throws Exception {
        String textureRoot = "assets/rtx-remix-renderer/textures/block/";
        var classLoader = CopperBeaconBlockEntityRendererTest.class.getClassLoader();
        var activeSidecar = classLoader.getResourceAsStream(
                textureRoot + "copper_beacon_lens_s.png");
        assertNotNull(activeSidecar);
        var image = ImageIO.read(activeSidecar);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                assertEquals(32, image.getRGB(x, y) >>> 24);
            }
        }

        assertNotNull(classLoader.getResource(
                textureRoot + "copper_beacon_lens_unlit.png"));
        assertNull(classLoader.getResource(
                textureRoot + "copper_beacon_lens_unlit_s.png"));
    }

    @Test
    void completeModelForkSurroundsRatherThanIntersectsTheLens() {
        String path = "assets/rtx-remix-renderer/models/block/copper_beacon_complete.json";
        var stream = CopperBeaconBlockEntityRendererTest.class
                .getClassLoader()
                .getResourceAsStream(path);
        assertNotNull(stream, path);
        var elements = JsonParser.parseReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8))
                .getAsJsonObject()
                .getAsJsonArray("elements");
        assertEquals(9, elements.size());

        JsonObject lens = elements.get(8).getAsJsonObject();
        for (int index = 2; index <= 4; index++) {
            assertFalse(
                    intersects(elements.get(index).getAsJsonObject(), lens),
                    "Fork element " + index + " intersects the lens");
        }

        JsonObject leftFork = elements.get(3).getAsJsonObject();
        JsonObject rightFork = elements.get(4).getAsJsonObject();
        JsonObject leftBearing = elements.get(6).getAsJsonObject();
        JsonObject rightBearing = elements.get(7).getAsJsonObject();
        assertEquals(
                1.0f,
                coordinate(leftFork, "from", 0) - coordinate(leftBearing, "from", 0),
                EPSILON);
        assertEquals(
                1.0f,
                coordinate(rightBearing, "to", 0) - coordinate(rightFork, "to", 0),
                EPSILON);
    }

    @Test
    void copperUvsUseAnEdgeToEdgeStripThatExcludesTextureCorners() {
        assertEquals(0.25f, CopperBeaconBlockEntityRenderer.copperTextureV(0.0f), EPSILON);
        assertEquals(0.5f, CopperBeaconBlockEntityRenderer.copperTextureV(0.5f), EPSILON);
        assertEquals(0.75f, CopperBeaconBlockEntityRenderer.copperTextureV(1.0f), EPSILON);

        String[] models = {
            "copper_beacon.json",
            "copper_beacon_wall.json",
            "copper_beacon_complete.json"
        };
        for (String model : models) {
            String path = "assets/rtx-remix-renderer/models/block/" + model;
            var stream = CopperBeaconBlockEntityRendererTest.class
                    .getClassLoader()
                    .getResourceAsStream(path);
            assertNotNull(stream, path);
            var elements = JsonParser.parseReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject()
                    .getAsJsonArray("elements");
            elements.forEach(element -> element.getAsJsonObject()
                    .getAsJsonObject("faces")
                    .entrySet()
                    .stream()
                    .map(Map.Entry::getValue)
                    .map(face -> face.getAsJsonObject())
                    .filter(face -> "#copper".equals(face.get("texture").getAsString()))
                    .forEach(face -> {
                        var uv = face.getAsJsonArray("uv");
                        assertEquals(0.0f, uv.get(0).getAsFloat(), EPSILON, path);
                        assertEquals(4.0f, uv.get(1).getAsFloat(), EPSILON, path);
                        assertEquals(16.0f, uv.get(2).getAsFloat(), EPSILON, path);
                        assertEquals(12.0f, uv.get(3).getAsFloat(), EPSILON, path);
                    }));
        }
    }

    private static Map<Direction, Direction> readBaseDirections() {
        String path = "assets/rtx-remix-renderer/blockstates/copper_beacon.json";
        var stream = CopperBeaconBlockEntityRendererTest.class
                .getClassLoader()
                .getResourceAsStream(path);
        assertNotNull(stream, path);
        JsonObject variants = JsonParser.parseReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8))
                .getAsJsonObject()
                .getAsJsonObject("variants");

        Map<Direction, Direction> directions = new EnumMap<>(Direction.class);
        for (Direction mount : Direction.values()) {
            JsonObject variant = variants
                    .getAsJsonObject("mount=" + mount.name().toLowerCase(Locale.ROOT));
            assertNotNull(variant, "Missing base model variant for mount=" + mount);
            int x = variant.has("x") ? variant.get("x").getAsInt() : 0;
            int y = variant.has("y") ? variant.get("y").getAsInt() : 0;
            Direction unrotatedDirection =
                    readBaseModelDirection(variant.get("model").getAsString());
            directions.put(
                    mount,
                    BlockModelRotation.by(x, y)
                            .actualRotation()
                            .rotate(unrotatedDirection));
        }
        return directions;
    }

    private static Direction readBaseModelDirection(String modelId) {
        String[] id = modelId.split(":", 2);
        String path = "assets/" + id[0] + "/models/" + id[1] + ".json";
        var stream = CopperBeaconBlockEntityRendererTest.class
                .getClassLoader()
                .getResourceAsStream(path);
        assertNotNull(stream, path);
        var elements = JsonParser.parseReader(
                        new InputStreamReader(stream, StandardCharsets.UTF_8))
                .getAsJsonObject()
                .getAsJsonArray("elements");
        assertEquals(2, elements.size(), "Base model must contain a plate followed by its stem");

        JsonObject plate = elements.get(0).getAsJsonObject();
        JsonObject stem = elements.get(1).getAsJsonObject();
        float x = center(stem, 0) - center(plate, 0);
        float y = center(stem, 1) - center(plate, 1);
        float z = center(stem, 2) - center(plate, 2);
        if (Math.abs(x) > Math.abs(y) && Math.abs(x) > Math.abs(z)) {
            return x > 0.0f ? Direction.EAST : Direction.WEST;
        }
        if (Math.abs(y) > Math.abs(z)) {
            return y > 0.0f ? Direction.UP : Direction.DOWN;
        }
        return z > 0.0f ? Direction.SOUTH : Direction.NORTH;
    }

    private static float center(JsonObject element, int axis) {
        return (element.getAsJsonArray("from").get(axis).getAsFloat()
                        + element.getAsJsonArray("to").get(axis).getAsFloat())
                * 0.5f;
    }

    private static boolean intersects(JsonObject first, JsonObject second) {
        for (int axis = 0; axis < 3; axis++) {
            float firstMin = coordinate(first, "from", axis);
            float firstMax = coordinate(first, "to", axis);
            float secondMin = coordinate(second, "from", axis);
            float secondMax = coordinate(second, "to", axis);
            if (firstMax <= secondMin || secondMax <= firstMin) {
                return false;
            }
        }
        return true;
    }

    private static float coordinate(JsonObject element, String corner, int axis) {
        return element.getAsJsonArray(corner).get(axis).getAsFloat();
    }

    private static void assertDirection(Direction expected, Vector3f actual, String message) {
        assertEquals(expected.getStepX(), actual.x, EPSILON, message);
        assertEquals(expected.getStepY(), actual.y, EPSILON, message);
        assertEquals(expected.getStepZ(), actual.z, EPSILON, message);
    }

    private static void assertVector(Vector3f expected, Vector3f actual, String message) {
        assertEquals(expected.x, actual.x, EPSILON, message);
        assertEquals(expected.y, actual.y, EPSILON, message);
        assertEquals(expected.z, actual.z, EPSILON, message);
    }

}

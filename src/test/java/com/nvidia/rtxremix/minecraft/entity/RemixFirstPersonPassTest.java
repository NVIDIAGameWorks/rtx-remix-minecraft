/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.Transform;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.SpriteTexturedVertexConsumer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexConsumers;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.resource.metadata.ResourceMetadata;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises real Minecraft unions and cuboids with the production capture finalization. */
@Tag("fabric")
class RemixFirstPersonPassTest {
    private static final float EPSILON = 1.0e-5f;
    private static final float[][] CORNERS = {{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}};

    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @BeforeEach
    @AfterEach
    void resetContext() {
        EntityRenderContext.get().reset();
    }

    @ParameterizedTest
    @CsvSource({"0, 0, 1, 0.5", "45, 0, 1, 0.5", "90, 0, 1, 0.5",
            "-135, 60, 1, 0.5", "45, -70, 1.3, 0.5", "90, 35, 0.8, -0.5", "-45, -60, 1.3, -0.5"})
    void ordinaryAndEnchantedItemsKeepTheirCameraPoseAndHeldLight(
            float yaw, float pitch, float fovScale, float handX) {
        Camera camera = new TestCamera(yaw, pitch);
        Matrix4f modelView = new Matrix4f().rotation(camera.getRotation()).invert();
        Matrix4f handPose = new Matrix4f().translation(handX, -0.5f, -1.5f).rotateXYZ(0.2f, 0.3f, 0.1f).scale(0.8f);
        Matrix4f expectedPose = new Matrix4f().scaling(
                fovScale * ViewModelSpace.SCALE,
                fovScale * ViewModelSpace.SCALE,
                ViewModelSpace.SCALE).mul(handPose);
        Hand hand = handX > 0 ? Hand.MAIN_HAND : Hand.OFF_HAND;
        EquipmentSlot slot = handX > 0 ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
        try (RemixFirstPersonPass pass = new RemixFirstPersonPass()) {
            for (boolean enchanted : new boolean[] {false, true}) {
                resetContext();
                VertexConsumerProvider provider = pass.beginCapture(modelView);
                EntityRenderContext.get().setActiveHand(hand);
                MatrixStack matrices = handMatrices(camera, handX);
                VertexConsumer consumer = itemConsumer(provider, enchanted);
                consumer.quad(matrices.peek(), quad(null), 1, 1, 1, 1, 0, OverlayTexture.DEFAULT_UV);
                consumer.quad(matrices.peek(), quad(null), 1, 1, 1, 1, 0, OverlayTexture.DEFAULT_UV);
                var result = pass.finishCapture(this, camera, fovScale);
                assertNotNull(result);
                assertEquals(enchanted ? 2 : 1, result.layers().size());
                Transform root = captureRoot(camera, modelView, fovScale);
                for (var layer : result.layers()) {
                    RemixCapturingConsumer captured = layer.consumer();
                    assertEquals(8, captured.getVertexCount());
                    assertEquals(1, captured.getPartCount(), "interleaved layers keep their own pivot");
                    int bone = captured.getPartBone(0);
                    assertTrue(bone >= 0, "glint union must preserve a rigid pose");
                    assertEquals(hand, EntityRenderContext.get().handForTransform(bone));
                    Transform partToWorld = new Transform();
                    RemixEntitySubmitter.composeRigidTransform(root, result.boneData(), result.boneCount(), bone, partToWorld);
                    for (int v = 0; v < captured.getVertexCount(); v++) {
                        Vector3f expected = toWorld(camera, expectedPose.transformPosition(new Vector3f(CORNERS[v % 4])));
                        Vector3f actual = new Vector3f();
                        transformPoint(partToWorld,
                                captured.getPx()[v], captured.getPy()[v], captured.getPz()[v], actual);
                        assertVector(expected, actual);
                        assertEquals(CORNERS[v % 4][0], captured.getPx()[v]);
                        assertEquals(CORNERS[v % 4][1], captured.getPy()[v]);
                    }
                }
                Vec3d anchor = pass.getPendingHeldLightPosition(slot);
                assertNotNull(anchor, "enchanted block retains its view-model light anchor");
                assertVector(toWorld(camera, expectedPose.transformPosition(new Vector3f(0.5f, 0.5f, 0))), anchor.toVector3f());
                assertNull(pass.getPendingHeldLightPosition(slot == EquipmentSlot.MAINHAND
                        ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND));
                pass.clearPending();
                assertNull(pass.getPendingHeldLightPosition(slot));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(floats = {7, 30, 70, 110, 140})
    void fovCorrectionPreservesProjectedSideFaceSpacing(float worldFov) {
        Camera camera = new TestCamera(45, 35, 0, 0, 0);
        Matrix4f modelView = new Matrix4f().rotation(camera.getRotation()).invert();
        float ratio = (float) (Math.tan(Math.toRadians(worldFov / 2)) / Math.tan(Math.toRadians(35)));
        Vector3f[] points = new Vector3f[2];
        for (int sample = 0; sample < 2; sample++) {
            float fov = sample == 0 ? 70 : worldFov;
            float scale = sample == 0 ? 1 : ratio;
            try (RemixFirstPersonPass pass = new RemixFirstPersonPass()) {
                VertexConsumerProvider provider = pass.beginCapture(modelView);
                itemConsumer(provider, true).quad(handMatrices(camera, 0.5f).peek(), quad(null),
                        1, 1, 1, 1, 0, OverlayTexture.DEFAULT_UV);
                var result = pass.finishCapture(this, camera, scale);
                RemixCapturingConsumer consumer = result.layers().getFirst().consumer();
                Transform transform = new Transform();
                RemixEntitySubmitter.composeRigidTransform(captureRoot(camera, modelView, scale),
                        result.boneData(), result.boneCount(), consumer.getPartBone(0), transform);
                Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(fov), 16f / 9, 0.01f, 1000)
                        .mul(modelView);
                Vector3f base = new Vector3f(), offset = new Vector3f();
                transformPoint(transform, 0, 0, 0, base);
                transformPoint(transform, 0.005f, 0, 0, offset);
                points[sample] = projection.transformProject(offset).sub(projection.transformProject(base));
            }
        }
        assertEquals(points[0].x, points[1].x, 1e-6f);
        assertEquals(points[0].y, points[1].y, 1e-6f);
    }

    @ParameterizedTest
    @CsvSource({"-170, 6, 1", "45, 35, 0.8", "0, -70, 1.3"})
    void firstPersonGlintStaysOutsideTheSubstrateAfterRayContinuation(float yaw, float pitch, float fovScale) {
        Camera camera = new TestCamera(yaw, pitch, 211, 64, 51);
        Matrix4f modelView = new Matrix4f().rotation(camera.getRotation()).invert();
        try (RemixFirstPersonPass pass = new RemixFirstPersonPass()) {
            VertexConsumerProvider provider = pass.beginCapture(modelView);
            itemConsumer(provider, true).quad(handMatrices(camera, 0.5f).peek(), quad(null),
                    1, 1, 1, 1, 0, OverlayTexture.DEFAULT_UV);
            var result = pass.finishCapture(this, camera, fovScale);
            assertNotNull(result);
            var layer = result.layers().stream().filter(l -> l.surfaceMode() == RenderLayerUtils.SurfaceMode.GLINT).findFirst().orElseThrow();
            var item = result.layers().stream().filter(l -> l != layer).findFirst().orElseThrow();
            Transform transform = new Transform(), itemTransform = new Transform();
            RemixEntitySubmitter.composeRigidTransform(captureRoot(camera, modelView, fovScale),
                    result.boneData(), result.boneCount(), layer.consumer().getPartBone(0), transform);
            RemixEntitySubmitter.composeRigidTransform(captureRoot(camera, modelView, fovScale),
                    result.boneData(), result.boneCount(), item.consumer().getPartBone(0), itemTransform);
            Vector3f origin = new Vector3f(), alongX = new Vector3f(), alongY = new Vector3f();
            transformPoint(transform, 0, 0, 0, origin);
            transformPoint(transform, 1, 0, 0, alongX);
            transformPoint(transform, 0, 1, 0, alongY);
            Vector3f worldNormal = alongX.sub(origin).cross(alongY.sub(origin)).normalize();
            RemixEntitySubmitter submitter = new RemixEntitySubmitter(null, null);
            submitter.prepareLayerShells(result);
            int glintShell = submitter.shellLayer(layer), itemShell = submitter.shellLayer(item);
            submitter.close();
            Vector3f glint = new Vector3f(), surface = new Vector3f();
            transformPoint(transform, 0, 0,
                    RemixEntitySubmitter.meshLayerDepth(layer.text(), glintShell, InstanceCategoryBit.VIEW_MODEL), glint);
            transformPoint(itemTransform, 0, 0,
                    RemixEntitySubmitter.meshLayerDepth(item.text(), itemShell, InstanceCategoryBit.VIEW_MODEL), surface);

            // The runtime advances a transparent ray by four float ULPs along its normal.
            // This coordinate range reproduces the stationary pickaxe artifact.
            float continuation = Math.max(Math.abs(glint.x), Math.max(Math.abs(glint.y), Math.abs(glint.z)))
                    * 4f * Math.ulp(1f);
            assertTrue(new Vector3f(glint).sub(surface).dot(worldNormal) > continuation,
                    "continuing through glint must still hit the gold surface");
            Vector3f uncorrectedGlint = new Vector3f(), uncorrectedSurface = new Vector3f();
            transformPoint(transform, 0, 0,
                    RemixEntitySubmitter.surfaceDepth(false, glintShell, 0), uncorrectedGlint);
            transformPoint(itemTransform, 0, 0,
                    RemixEntitySubmitter.surfaceDepth(false, itemShell, 0), uncorrectedSurface);
            assertTrue(uncorrectedGlint.sub(uncorrectedSurface).dot(worldNormal) < continuation,
                    "the fixture must reproduce the old layer gap being skipped");
        }
    }

    @Test
    void cameraTurnsKeepTheSameLocalMeshAndPreviousFramePose() {
        try (RemixFirstPersonPass pass = new RemixFirstPersonPass()) {
            long[] previousHashes = null;
            Vector3f previousCameraPoint = null;
            for (float yaw : new float[] {0, 45, -120}) {
                resetContext();
                Camera camera = new TestCamera(yaw, 35);
                VertexConsumerProvider provider = pass.beginCapture(new Matrix4f().rotation(camera.getRotation()).invert());
                EntityRenderContext.get().setActiveHand(Hand.MAIN_HAND);
                itemConsumer(provider, true).quad(handMatrices(camera, 0.5f).peek(), quad(null),
                        1, 1, 1, 1, 0, OverlayTexture.DEFAULT_UV);
                var result = pass.finishCapture(this, camera, 1);
                long[] hashes = result.layers().stream().mapToLong(layer -> layer.consumer().getPartHash(0)).toArray();
                if (previousHashes != null) {
                    assertArrayEquals(previousHashes, hashes, "camera motion must not rebuild local geometry");
                }
                var layer = result.layers().getFirst();
                Transform partToWorld = new Transform();
                RemixEntitySubmitter.composeRigidTransform(captureRoot(camera,
                                new Matrix4f().rotation(camera.getRotation()).invert(), 1),
                        result.boneData(), result.boneCount(), layer.consumer().getPartBone(0), partToWorld);
                Vector3f cameraPoint = new Vector3f();
                transformPoint(partToWorld, 0, 0, 0, cameraPoint);
                cameraPoint.sub(12, 64, -8);
                camera.getRotation().transformInverse(cameraPoint);
                if (previousCameraPoint != null) assertVector(previousCameraPoint, cameraPoint);
                previousHashes = hashes;
                previousCameraPoint = cameraPoint;
            }
        }
    }

    @Test
    void bothEnchantedHandsKeepSeparateLightAnchors() {
        Camera camera = new TestCamera(50, -35);
        try (RemixFirstPersonPass pass = new RemixFirstPersonPass()) {
            VertexConsumerProvider provider = pass.beginCapture(new Matrix4f().rotation(camera.getRotation()).invert());
            for (Hand hand : Hand.values()) {
                EntityRenderContext.get().setActiveHand(hand);
                itemConsumer(provider, true).quad(handMatrices(camera, hand == Hand.MAIN_HAND ? 0.5f : -0.5f).peek(),
                        quad(null), 1, 1, 1, 1, 0, OverlayTexture.DEFAULT_UV);
            }
            pass.finishCapture(this, camera, 1);
            for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND}) {
                float handX = slot == EquipmentSlot.MAINHAND ? 0.5f : -0.5f;
                Vector3f expected = new Matrix4f().translation(handX, -0.5f, -1.5f)
                        .rotateXYZ(0.2f, 0.3f, 0.1f).scale(0.8f)
                        .transformPosition(new Vector3f(0.5f, 0.5f, 0)).mul(ViewModelSpace.SCALE);
                Vec3d anchor = pass.getPendingHeldLightPosition(slot);
                assertNotNull(anchor);
                assertVector(toWorld(camera, expected), anchor.toVector3f());
            }
        }
    }

    @Test
    void anUnposedCaptureKeepsTheBoneBufferAndClearsHandAnchors() {
        Camera camera = new TestCamera(0, 0);
        Matrix4f modelView = new Matrix4f().rotation(camera.getRotation()).invert();
        try (RemixFirstPersonPass pass = new RemixFirstPersonPass()) {
            VertexConsumerProvider provider = pass.beginCapture(modelView);
            EntityRenderContext.get().setActiveHand(Hand.MAIN_HAND);
            itemConsumer(provider, false).quad(handMatrices(camera, 0.5f).peek(), quad(null),
                    1, 1, 1, 1, 0, OverlayTexture.DEFAULT_UV);
            float[] bones = pass.finishCapture(this, camera, 1).boneData();
            resetContext();
            provider = pass.beginCapture(modelView);
            VertexConsumer consumer = itemConsumer(provider, false);
            for (float[] corner : CORNERS) consumer.vertex(corner[0], corner[1], corner[2]).normal(0, 0, 1);
            var result = pass.finishCapture(this, camera, 1);
            assertEquals(0, result.boneCount());
            assertSame(bones, result.boneData());
            assertNull(pass.getPendingHeldLightPosition(EquipmentSlot.MAINHAND));
        }
    }

    @Test
    void nestedMultiUnionsPreserveTintedQuadsAndMatrixVertices() {
        EntityRenderContext.get().begin(true);
        RemixCapturingConsumer a = new RemixCapturingConsumer(null, true);
        RemixCapturingConsumer b = new RemixCapturingConsumer(null, true);
        RemixCapturingConsumer c = new RemixCapturingConsumer(null, true);
        VertexConsumer union = VertexConsumers.union(new VertexConsumer[] {VertexConsumers.union(a, b), c});
        MatrixStack matrices = new MatrixStack();
        matrices.translate(3, 4, 5);
        union.quad(matrices.peek(), quad(null), new float[] {1, 1, 1, 1}, 1, 0.5f, 0.25f, 1,
                new int[4], OverlayTexture.DEFAULT_UV, false);
        union.vertex(matrices.peek(), 1, 2, 3).normal(matrices.peek(), 0, 0, 1);
        union.vertex(matrices.peek().getPositionMatrix(), 4, 5, 6).normal(matrices.peek(), 0, 0, 1);
        for (RemixCapturingConsumer capture : new RemixCapturingConsumer[] {a, b, c}) {
            capture.finishOpenVertex();
            assertEquals(6, capture.getVertexCount());
            assertEquals(1, capture.getPx()[4]);
            assertEquals(4, capture.getPx()[5]);
            assertEquals(1, capture.getNz()[4]);
            assertEquals(0xFFFF7F3F, capture.getColors()[0]);
            for (int i = 0; i < 6; i++) assertEquals(capture.getBoneIndices()[0], capture.getBoneIndices()[i]);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void glintedSpriteCuboidsRetainIdentityAndPose(boolean outerSprite) {
        EntityRenderContext.get().begin(true);
        RemixCapturingConsumer base = new RemixCapturingConsumer(null, true);
        RemixCapturingConsumer glint = new RemixCapturingConsumer(null, true);
        try (SpriteContents contents = spriteContents()) {
            Sprite sprite = new Sprite(BlockTextureManager.BLOCK_ATLAS_ID, contents, 32, 32, 8, 16) {};
            VertexConsumer consumer = outerSprite
                    ? new SpriteTexturedVertexConsumer(VertexConsumers.union(glint, base), sprite)
                    : VertexConsumers.union(new SpriteTexturedVertexConsumer(glint, sprite),
                            new SpriteTexturedVertexConsumer(base, sprite));
            ModelPart.Cuboid cuboid = new ModelPart.Cuboid(0, 0, 0, 0, 0, 1, 1, 1,
                    0, 0, 0, false, 16, 16, EnumSet.allOf(Direction.class));
            MatrixStack matrices = new MatrixStack();
            matrices.translate(3, 4, 5);
            cuboid.renderCuboid(matrices.peek(), consumer, 0, OverlayTexture.DEFAULT_UV, -1);
            for (RemixCapturingConsumer capture : new RemixCapturingConsumer[] {base, glint}) {
                capture.finishOpenVertex();
                assertEquals(0, capture.getVertexCount());
                assertEquals(1, capture.getPartCount());
                assertSame(cuboid.sides, capture.getPartSides(0));
                assertSame(sprite, capture.getPartSprite(0));
                assertEquals(matrices.peek().getPositionMatrix(), EntityRenderContext.get().getBoneTransforms().get(capture.getPartBone(0)));
            }
        }
    }

    @Test
    void glintUnionRebuildsTheCardFromOpaqueTexels() {
        EntityRenderContext.get().begin(true);
        try (SpriteContents contents = spriteContents()) {
            Sprite sprite = new Sprite(BlockTextureManager.BLOCK_ATLAS_ID, contents, 32, 32, 8, 16) {};
            RemixCapturingConsumer a = new RemixCapturingConsumer(null, true);
            RemixCapturingConsumer b = new RemixCapturingConsumer(null, true);
            VertexConsumers.union(a, b).quad(new MatrixStack().peek(), quad(sprite), 1, 1, 1, 1, 0, OverlayTexture.DEFAULT_UV);
            for (RemixCapturingConsumer capture : new RemixCapturingConsumer[] {a, b}) {
                capture.finishOpenVertex();
                assertEquals(4, capture.getVertexCount());
                for (int v = 0; v < 4; v++) assertTrue(capture.getPx()[v] == 0.25f || capture.getPx()[v] == 0.75f);
            }
        }
    }

    @Test
    void rasterPassKeepsVanillasPosedVertexDispatch() {
        EntityRenderContext.get().begin(false);
        RemixCapturingConsumer a = new RemixCapturingConsumer(null, true);
        RemixCapturingConsumer b = new RemixCapturingConsumer(null, true);
        MatrixStack matrices = new MatrixStack();
        matrices.translate(3, 4, 5);
        VertexConsumers.union(a, b).quad(matrices.peek(), quad(null), 1, 1, 1, 1, 0, OverlayTexture.DEFAULT_UV);
        a.finishOpenVertex();
        assertEquals(3, a.getPx()[0]);
        assertEquals(-1, a.getBoneIndices()[0]);
        assertEquals(0, EntityRenderContext.get().getBoneCount());
    }

    private static VertexConsumer itemConsumer(VertexConsumerProvider provider, boolean glint) {
        return ItemRenderer.getDirectItemGlintConsumer(provider,
                RenderLayer.getEntityCutout(BlockTextureManager.BLOCK_ATLAS_ID), true, glint);
    }

    private static Transform captureRoot(Camera camera, Matrix4f modelView, float fovScale) {
        Matrix4f correction = new Matrix4f();
        RemixFirstPersonPass.setViewCorrection(correction, modelView, fovScale);
        Transform root = new Transform();
        RemixFirstPersonPass.setCaptureToWorld(camera, correction, new Matrix4f(), root);
        return root;
    }

    private static MatrixStack handMatrices(Camera camera, float handX) {
        MatrixStack matrices = new MatrixStack();
        matrices.multiply(camera.getRotation());
        matrices.translate(handX, -0.5f, -1.5f);
        matrices.multiply(new Quaternionf().rotationXYZ(0.2f, 0.3f, 0.1f));
        matrices.scale(0.8f, 0.8f, 0.8f);
        return matrices;
    }

    private static Vector3f toWorld(Camera camera, Vector3f point) {
        return camera.getRotation().transform(point).add((float) camera.getPos().x,
                (float) camera.getPos().y, (float) camera.getPos().z);
    }

    private static void transformPoint(Transform transform, float x, float y, float z, Vector3f destination) {
        destination.set(RemixFirstPersonPass.transformPoint(transform, x, y, z).toVector3f());
    }

    private static void assertVector(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, EPSILON, "world x");
        assertEquals(expected.y, actual.y, EPSILON, "world y");
        assertEquals(expected.z, actual.z, EPSILON, "world z");
    }

    private static BakedQuad quad(Sprite sprite) {
        int[] data = new int[32];
        for (int v = 0; v < 4; v++) {
            for (int axis = 0; axis < 3; axis++) data[v * 8 + axis] = Float.floatToRawIntBits(CORNERS[v][axis]);
            data[v * 8 + 2] = Float.floatToRawIntBits(sprite == null ? 0 : 8.5f / 16);
            data[v * 8 + 3] = 0xFFFFFFFF;
            data[v * 8 + 4] = Float.floatToRawIntBits(sprite == null ? CORNERS[v][0] : sprite.getFrameU(CORNERS[v][0]));
            data[v * 8 + 5] = Float.floatToRawIntBits(sprite == null ? CORNERS[v][1] : sprite.getFrameV(1 - CORNERS[v][1]));
            data[v * 8 + 7] = 0x007F0000;
        }
        return new BakedQuad(data, -1, Direction.SOUTH, sprite, true);
    }

    private static SpriteContents spriteContents() {
        NativeImage image = new NativeImage(8, 8, true);
        for (int y = 0; y < 8; y++) for (int x = 2; x < 6; x++) image.setColor(x, y, -1);
        return new SpriteContents(Identifier.of("minecraft", "item/test"), new SpriteDimensions(8, 8), image, ResourceMetadata.NONE);
    }

    private static final class TestCamera extends Camera {
        TestCamera(float yaw, float pitch) {
            this(yaw, pitch, 12, 64, -8);
        }

        TestCamera(float yaw, float pitch, double x, double y, double z) {
            setRotation(yaw, pitch);
            setPos(x, y, z);
        }
    }
}

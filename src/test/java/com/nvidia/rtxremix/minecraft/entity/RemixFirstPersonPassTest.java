/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.Transform;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumers;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RemixFirstPersonPassTest {

    private static final float EPSILON = 1.0e-5f;
    private static final float[][] CORNERS = {{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}};

    @AfterEach
    void clearRenderContext() {
        EntityRenderContext.get().reset();
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0, 1, 0.5",
            "45, 0, 1, 0.5",
            "90, 0, 1, 0.5",
            "-135, 60, 1, 0.5",
            "45, -70, 1.3, 0.5",
            "90, 35, 0.8, -0.5",
            "-45, -60, 1.3, -0.5"
    })
    void glintAndBaseStayWithTheHandAcrossCameraAngles(
            float yaw, float pitch, float fovScale, float handX) {
        EntityRenderContext context = EntityRenderContext.get();
        context.begin(true);
        Quaternionf cameraRotation = new Quaternionf().rotationYXZ(
                (float) Math.toRadians(yaw), (float) Math.toRadians(pitch), 0);
        Matrix4f modelView = new Matrix4f().rotation(cameraRotation).invert();
        Matrix4f handPose = new Matrix4f().translation(handX, -0.5f, -1.5f)
                .rotateXYZ(0.2f, 0.3f, 0.1f).scale(0.8f);
        MatrixStack matrices = new MatrixStack();
        matrices.multiply(cameraRotation);
        matrices.translate(handX, -0.5f, -1.5f);
        matrices.multiply(new Quaternionf().rotationXYZ(0.2f, 0.3f, 0.1f));
        matrices.scale(0.8f, 0.8f, 0.8f);

        RemixCapturingConsumer ordinary = new RemixCapturingConsumer(null, true);
        RemixCapturingConsumer enchantedBase = new RemixCapturingConsumer(null, true);
        RemixCapturingConsumer glint = new RemixCapturingConsumer(null, true);
        BakedQuad quad = quad();
        ordinary.quad(matrices.peek(), quad, 1, 1, 1, 1, 0, OverlayTexture.DEFAULT_UV);
        VertexConsumer enchanted = VertexConsumers.union(glint, enchantedBase);
        enchanted.quad(matrices.peek(), quad, 1, 1, 1, 1, 0, OverlayTexture.DEFAULT_UV);
        ordinary.finishOpenVertex();
        enchantedBase.finishOpenVertex();
        glint.finishOpenVertex();

        // The real vanilla wrapper forwards posed vertices, while the ordinary
        // consumer keeps local vertices and a pivot. Both share the same capture root.
        assertEquals(1, context.getBoneCount());
        assertEquals(0, ordinary.getBoneIndices()[0]);
        assertEquals(-1, enchantedBase.getBoneIndices()[0]);
        assertEquals(-1, glint.getBoneIndices()[0]);
        float[] bones = context.snapshotBoneTransforms(new float[0]);
        Transform captureToWorld = Transform.fromTRS(12, 64, -8, cameraRotation, 1);
        Matrix4f worldToCamera = new Matrix4f().translation(12, 64, -8)
                .rotate(cameraRotation).invert();
        Matrix4f correction = new Matrix4f();
        RemixFirstPersonPass.setViewCorrection(correction, modelView, fovScale);
        RemixFirstPersonPass.applyViewCorrection(captureToWorld, correction, new float[12]);

        Matrix4f expectedPose = new Matrix4f().scaling(
                fovScale * RemixFirstPersonPass.VIEW_MODEL_SCALE,
                fovScale * RemixFirstPersonPass.VIEW_MODEL_SCALE,
                RemixFirstPersonPass.VIEW_MODEL_SCALE).mul(handPose);
        for (RemixCapturingConsumer consumer : new RemixCapturingConsumer[] {ordinary, enchantedBase, glint}) {
            assertEquals(4, consumer.getVertexCount());
            assertEquals(1, consumer.getPartCount());
            Transform partToWorld = new Transform();
            RemixEntitySubmitter.composeRigidTransform(captureToWorld, bones, context.getBoneCount(),
                    consumer.getPartBone(0), partToWorld);
            for (int v = 0; v < CORNERS.length; v++) {
                Vector3f expected = expectedPose.transformPosition(new Vector3f(CORNERS[v]));
                var worldPoint = RemixFirstPersonPass.transformPoint(partToWorld,
                        consumer.getPx()[v], consumer.getPy()[v], consumer.getPz()[v]);
                Vector3f actual = worldToCamera.transformPosition(new Vector3f(
                        (float) worldPoint.x, (float) worldPoint.y, (float) worldPoint.z));
                assertEquals(expected.x, actual.x, EPSILON, "camera-space x");
                assertEquals(expected.y, actual.y, EPSILON, "camera-space y");
                assertEquals(expected.z, actual.z, EPSILON, "camera-space z");
            }
        }
    }

    private static BakedQuad quad() {
        int[] data = new int[32];
        for (int v = 0; v < CORNERS.length; v++) {
            for (int axis = 0; axis < 3; axis++) {
                data[v * 8 + axis] = Float.floatToRawIntBits(CORNERS[v][axis]);
            }
            data[v * 8 + 3] = 0xFFFFFFFF;
            data[v * 8 + 7] = 0x007F0000;
        }
        return new BakedQuad(data, -1, Direction.SOUTH, null, true);
    }
}

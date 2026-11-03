/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.Transform;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.OverlayVertexConsumer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MatrixUtil;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses the projected-glint factory shared by lodestone compasses and enchanted clocks. */
@Tag("fabric")
class ProjectedGlintCaptureTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @BeforeEach
    @AfterEach
    void reset() {
        EntityRenderContext.get().reset();
    }

    @ParameterizedTest
    @ValueSource(strings = {"capture", "replay", "expand"})
    void projectedQuadsFinishBeforeTheFollowingCuboid(String boundary) {
        EntityRenderContext.get().begin(true);
        RemixCapturingConsumer captured = new RemixCapturingConsumer(null, true);
        MatrixStack matrices = new MatrixStack();
        matrices.translate(2, 3, 4);
        VertexProbe reference = new VertexProbe();
        VertexConsumer expected = new OverlayVertexConsumer(reference, matrices.peek(), 1f / 128);
        VertexConsumer actual = new OverlayVertexConsumer(captured, matrices.peek(), 1f / 128);
        for (int i = 0; i < 2; i++) {
            expected.quad(matrices.peek(), quad(), 1, 1, 1, 1, 0, OverlayTexture.DEFAULT_UV);
            actual.quad(matrices.peek(), quad(), 1, 1, 1, 1, 0, OverlayTexture.DEFAULT_UV);
        }
        assertEquals(7, captured.getVertexCount(), "the final projected vertex is still waiting for its UV");
        ModelPart.Cuboid cuboid = new ModelPart.Cuboid(0, 0, 0, 0, 0, 1, 1, 1,
                0, 0, 0, false, 16, 16, EnumSet.allOf(Direction.class));
        switch (boundary) {
            case "capture" -> cuboid.renderCuboid(matrices.peek(), captured, 0, OverlayTexture.DEFAULT_UV, -1);
            case "replay" -> captured.replayCuboidPart(cuboid.sides, -1, 0, null);
            case "expand" -> captured.emitCuboidGeometry(cuboid.sides, -1, 0, null);
            default -> throw new AssertionError(boundary);
        }
        captured.finishOpenVertex();
        assertEquals(2, captured.getPartCount(), "the projected quad run must not be split around the cuboid");
        assertEquals(0, captured.getPartStart(0));
        assertEquals(8, captured.getPartVertexCount(0));
        assertEquals(24, captured.getPartVertexCount(1));
        for (int i = 0; i < 8; i++) {
            assertEquals(reference.x[i], captured.getPx()[i]);
            assertEquals(reference.y[i], captured.getPy()[i]);
            assertEquals(reference.z[i], captured.getPz()[i]);
            assertEquals(reference.u[i], captured.getTu()[i]);
            assertEquals(reference.v[i], captured.getTv()[i]);
        }
    }

    @ParameterizedTest
    @CsvSource({"0, 0, 1", "45, 30, 1", "90, -60, 1.3", "-120, 70, 0.8", "180, -20, 1.3"})
    void projectedGlintKeepsTheItemPositionAndVanillaUVs(float yaw, float pitch, float fovScale) {
        Camera camera = new TestCamera(yaw, pitch);
        Matrix4f modelView = new Matrix4f().rotation(camera.getRotation()).invert();
        MatrixStack matrices = new MatrixStack();
        matrices.multiply(camera.getRotation());
        matrices.translate(0.45f, -0.3f, -1.2f);
        matrices.scale(0.6f, 0.6f, 0.6f);
        MatrixStack.Entry texturePose = matrices.peek().copy();
        MatrixUtil.scale(texturePose.getPositionMatrix(), 0.75f);
        BakedQuad quad = quad();
        VertexProbe reference = new VertexProbe();
        new OverlayVertexConsumer(reference, texturePose, 1f / 128).quad(matrices.peek(), quad,
                1, 1, 1, 1, 0, OverlayTexture.DEFAULT_UV);
        Matrix4f expectedPose = new Matrix4f().scaling(fovScale * 0.1f, fovScale * 0.1f, 0.1f)
                .translate(0.45f, -0.3f, -1.2f).scale(0.6f);

        try (RemixFirstPersonPass pass = new RemixFirstPersonPass()) {
            VertexConsumerProvider provider = pass.beginCapture(modelView);
            ItemRenderer.getDynamicDisplayGlintConsumer(provider,
                    RenderLayer.getEntityCutout(BlockTextureManager.BLOCK_ATLAS_ID), texturePose)
                    .quad(matrices.peek(), quad, 1, 1, 1, 1, 0, OverlayTexture.DEFAULT_UV);
            var result = pass.finishCapture(this, camera, fovScale);
            Matrix4f correction = new Matrix4f();
            RemixFirstPersonPass.setViewCorrection(correction, modelView, fovScale);
            Transform root = new Transform();
            RemixFirstPersonPass.setCaptureToWorld(camera, correction, new Matrix4f(), root);
            assertEquals(2, result.layers().size());
            RemixEntitySubmitter submitter = new RemixEntitySubmitter(null, null);
            submitter.prepareLayerShells(result);
            int glintOrder = -1, baseOrder = -1;
            for (var layer : result.layers()) {
                RemixCapturingConsumer captured = layer.consumer();
                boolean glint = layer.surfaceMode() == RenderLayerUtils.SurfaceMode.GLINT;
                if (glint) glintOrder = submitter.shellLayer(layer);
                else baseOrder = submitter.shellLayer(layer);
                assertEquals(glint, layer.transientGeometry(0), "posed projection is streamed instead of filling the mesh cache");
                assertEquals(4, captured.getVertexCount());
                Transform part = new Transform();
                RemixEntitySubmitter.composeRigidTransform(root, result.boneData(), result.boneCount(),
                        captured.getPartBone(0), part);
                boolean nonzeroUV = false;
                for (int v = 0; v < 4; v++) {
                    float x = v == 1 || v == 2 ? 1 : 0;
                    float y = v >= 2 ? 1 : 0;
                    Vector3f expected = expectedPose.transformPosition(new Vector3f(x, y, 0));
                    camera.getRotation().transform(expected).add(12, 64, -8);
                    Vector3f actual = RemixFirstPersonPass.transformPoint(
                            part, captured.getPx()[v], captured.getPy()[v], captured.getPz()[v]).toVector3f();
                    assertEquals(expected.x, actual.x, 1e-5, "world x");
                    assertEquals(expected.y, actual.y, 1e-5, "world y");
                    assertEquals(expected.z, actual.z, 1e-5, "world z");
                    if (glint) {
                        assertEquals(reference.u[v], captured.getTu()[v]);
                        assertEquals(reference.v[v], captured.getTv()[v]);
                        nonzeroUV |= captured.getTu()[v] != 0 || captured.getTv()[v] != 0;
                    }
                }
                if (glint) assertTrue(nonzeroUV, "UVs written after normal() must survive capture");
                else assertFalse(captured.getBoneIndices()[0] < 0);
            }
            submitter.close();
            assertTrue(baseOrder < glintOrder, "glint shell is outside the item, independent of buffer request order");
        }
    }

    private static BakedQuad quad() {
        int[] data = new int[32];
        for (int i = 0; i < 4; i++) {
            data[i * 8] = Float.floatToRawIntBits(i == 1 || i == 2 ? 1 : 0);
            data[i * 8 + 1] = Float.floatToRawIntBits(i >= 2 ? 1 : 0);
            data[i * 8 + 3] = -1;
            data[i * 8 + 7] = 0x007F0000;
        }
        return new BakedQuad(data, -1, Direction.SOUTH, null, true);
    }

    private static final class TestCamera extends Camera {
        TestCamera(float yaw, float pitch) {
            setRotation(yaw, pitch);
            setPos(12, 64, -8);
        }
    }

    static final class VertexProbe implements VertexConsumer {
        final float[] x = new float[64], y = new float[64], z = new float[64];
        final float[] u = new float[64], v = new float[64];
        int count;
        @Override public VertexConsumer vertex(float px, float py, float pz) {
            x[count] = px; y[count] = py; z[count] = pz; count++; return this;
        }
        @Override public VertexConsumer texture(float tu, float tv) { u[count - 1] = tu; v[count - 1] = tv; return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer overlay(int u, int v) { return this; }
        @Override public VertexConsumer light(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises capture ownership and cache eligibility without native handles. */
@Tag("fabric")
class CaptureReuseRegressionTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @BeforeEach
    @AfterEach
    void reset() { EntityRenderContext.get().reset(); }

    @Test
    void repeatedBlockDamageCaptureRetainsLocalGeometryAndOwner() {
        RemixBlockDamagePass pass = new RemixBlockDamagePass();
        RecordingSubmitter submitter = new RecordingSubmitter();
        try {
            Object previousOwner = null;
            RemixEntitySubmitter.HashedMeshKey previousKey = null;
            for (int frame = 0; frame < 3; frame++) {
                pass.capture(new BlockPos(211, 64, 51), 3, (matrices, consumer) -> quad(consumer, null), submitter);
                assertEquals(frame + 1, submitter.submissions, "the production damage pass must submit the model");
                var result = submitter.result;
                var layer = result.layers().getFirst();
                assertFalse(layer.transientGeometry(0), "block-local crack vertices must use the rigid mesh pool");
                assertEquals(1, result.boneCount());
                assertTrue(layer.consumer().getPartBone(0) >= 0);
                assertEquals(1f, result.boneData()[0]);
                assertEquals(1f, result.boneData()[5]);
                assertEquals(1f, result.boneData()[10]);
                var key = new RemixEntitySubmitter.HashedMeshKey(layer.textureId(),
                        RenderLayerUtils.surfaceMode(layer.renderLayer()), layer.topology(), layer.layerIndex(),
                        layer.consumer().getPartVertexCount(0), layer.consumer().getPartHash(0));
                if (frame > 0) {
                    assertSame(previousOwner, result.owner());
                    assertEquals(previousKey, key);
                }
                previousOwner = result.owner();
                previousKey = key;
                pass.finishFrame();
            }
        } finally {
            submitter.close();
        }
    }

    @Test
    void posedVerticesDoNotDemoteLocalQuadsOrCuboidsInTheSameLayer() {
        EntityRenderContext.get().begin(true);
        RemixVertexConsumerProvider provider = new RemixVertexConsumerProvider();
        provider.beginEntity();
        VertexConsumer consumer = provider.getBuffer(RenderLayer.getGlint());
        quad(consumer, null);
        MatrixStack matrices = new MatrixStack();
        matrices.translate(2, 3, 4);
        quad(consumer, matrices);
        ModelPart.Cuboid cuboid = cuboid();
        cuboid.renderCuboid(matrices.peek(), consumer, 0, OverlayTexture.DEFAULT_UV, -1);
        var layer = provider.endEntity().getFirst();
        assertEquals(3, layer.consumer().getPartCount());
        assertFalse(layer.transientGeometry(0), "raw positions alone do not imply changing geometry");
        assertFalse(layer.transientGeometry(1), "a neighboring fully posed part must not invalidate this local mesh");
        assertFalse(layer.transientGeometry(2));
        assertSame(cuboid.sides, layer.consumer().getPartSides(2));
    }

    @Test
    void projectedCoordinatesDoNotDemoteStaticUnposedParts() {
        EntityRenderContext.get().begin(true);
        RemixVertexConsumerProvider provider = new RemixVertexConsumerProvider();
        long previousHash = 0;
        for (int frame = 0; frame < 3; frame++) {
            provider.beginEntity();
            RemixCapturingConsumer consumer = (RemixCapturingConsumer) provider.getBuffer(RenderLayer.getGlint());
            quad(consumer, null);
            for (int i = 0; i < 4; i++) {
                consumer.vertex(i & 1, i >> 1, frame).texture(frame, 0);
                consumer.projectedNormal(0, 0, 1);
            }
            var layer = provider.endEntity().getFirst();
            assertEquals(2, consumer.getPartCount(), "static and projected parts need separate cache policies");
            assertFalse(layer.transientGeometry(0));
            assertTrue(layer.transientGeometry(1));
            if (frame > 0) assertEquals(previousHash, consumer.getPartHash(0));
            previousHash = consumer.getPartHash(0);
        }
    }

    private static void quad(VertexConsumer target, MatrixStack matrices) {
        for (int i = 0; i < 4; i++) {
            float x = i == 1 || i == 2 ? 1 : 0;
            float y = i >= 2 ? 1 : 0;
            if (matrices == null) target.vertex(x, y, 0);
            else target.vertex(matrices.peek(), x, y, 0);
            target.color(-1).texture(x, y).overlay(OverlayTexture.DEFAULT_UV).light(0).normal(0, 0, 1);
        }
    }

    private static ModelPart.Cuboid cuboid() {
        return new ModelPart.Cuboid(0, 0, 0, 0, 0, 1, 1, 1, 0, 0, 0,
                false, 16, 16, EnumSet.allOf(Direction.class));
    }

    private static final class RecordingSubmitter extends RemixEntitySubmitter {
        private RemixEntityPass.EntityCaptureResult result;
        private int submissions;
        RecordingSubmitter() { super(null, null); }
        @Override void submitEntity(RemixEntityPass.EntityCaptureResult result, PartCategoryPolicy policy) {
            this.result = result;
            submissions++;
        }
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises capture ownership, cache eligibility and pose replay without native handles. */
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
                var key = new RemixEntitySubmitter.HashedMeshKey(layer.textureId(), layer.surfaceMode(),
                        layer.topology(), RemixEntitySubmitter.meshLayerDepth(layer.text(), layer.layerIndex(), 0),
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

    @Test
    void capturedTextAndMapQuadsUseViewModelSpacingEvenOnTheBaseLayer() {
        EntityRenderContext.get().begin(true);
        RemixVertexConsumerProvider provider = new RemixVertexConsumerProvider();
        RemixEntitySubmitter submitter = new RemixEntitySubmitter(null, null);
        try {
            provider.beginEntity();
            VertexConsumer text = provider.getBuffer(RenderLayer.getText(Identifier.of("test", "font")));
            MatrixStack matrices = new MatrixStack();
            quad(text, matrices);
            quad(text, matrices);
            var layers = provider.endEntity();
            submitter.prepareSurfaceLayers(result(layers));
            var layer = layers.getFirst();
            var world = (RemixEntitySubmitter.HashedMeshKey) submitter.prepareRigidMeshKey(layer, 0,
                    layer.surfaceMode(), 0).immutableCopy();
            var held = (RemixEntitySubmitter.HashedMeshKey) submitter.prepareRigidMeshKey(layer, 0,
                    layer.surfaceMode(), InstanceCategoryBit.VIEW_MODEL).immutableCopy();
            assertEquals(0, world.shellDepth());
            assertEquals(0, held.shellDepth());
            assertTrue(world.quadStep() > 0);
            assertEquals(world.quadStep(), held.quadStep() * ViewModelSpace.SCALE, 1e-9f);
            org.junit.jupiter.api.Assertions.assertNotEquals(world, held);
        } finally {
            submitter.close();
        }
    }

    @Test
    void unrelatedPatternsAndBaseLayersCannotMoveOrRenameSwordGlint() {
        RemixVertexConsumerProvider provider = new RemixVertexConsumerProvider();
        RemixEntitySubmitter submitter = new RemixEntitySubmitter(null, null);
        int previousIdentity = -1;
        ModelPart.Cuboid sword = cuboid(), shield = cuboid();
        try {
            for (int patternCount : new int[] {0, 5, 0}) {
                EntityRenderContext.get().reset();
                EntityRenderContext.get().begin(true);
                provider.beginEntity();
                MatrixStack matrices = new MatrixStack();
                sword.renderCuboid(matrices.peek(), provider.getBuffer(RenderLayer.getGlint()), 0, OverlayTexture.DEFAULT_UV, -1);
                if (patternCount > 0) {
                    provider.getBuffer(RenderLayer.getEyes(Identifier.of("test", "eyes.png")));
                    VertexConsumer patterns = provider.getBuffer(RenderLayer.getEntityTranslucent(
                            Identifier.of("minecraft", "textures/atlas/shield_patterns.png")));
                    for (int p = 0; p < patternCount; p++) shield.renderCuboid(matrices.peek(), patterns, 0, OverlayTexture.DEFAULT_UV, -1);
                }
                sword.renderCuboid(matrices.peek(), provider.getBuffer(RenderLayer.getEntityCutout(
                        BlockTextureManager.BLOCK_ATLAS_ID)), 0, OverlayTexture.DEFAULT_UV, -1);
                var layers = provider.endEntity();
                submitter.prepareSurfaceLayers(result(layers));
                var glint = layers.getFirst();
                assertEquals(1, glint.consumer().getPartShellLayer(0), "only the sword's own surfaces set its shell depth");
                if (previousIdentity >= 0) assertEquals(previousIdentity, glint.layerIndex());
                previousIdentity = glint.layerIndex();
            }
        } finally {
            submitter.close();
        }
    }

    @Test
    void snapshotReplayKeepsRanksReservedByEmptyBaseAndGlintLayers() {
        EntityRenderContext context = EntityRenderContext.get();
        context.begin(true);
        RemixVertexConsumerProvider provider = new RemixVertexConsumerProvider();
        provider.beginEntity();
        provider.getBuffer(RenderLayer.getEntityCutout(Identifier.of("test", "empty.png")));
        provider.getBuffer(RenderLayer.getArmorEntityGlint());
        ModelPart.Cuboid cuboid = cuboid();
        MatrixStack matrices = new MatrixStack();
        cuboid.renderCuboid(matrices.peek(), provider.getBuffer(RenderLayer.getGlint()), 0, OverlayTexture.DEFAULT_UV, -1);
        cuboid.renderCuboid(matrices.peek(), provider.getBuffer(RenderLayer.getEntitySolid(BlockTextureManager.BLOCK_ATLAS_ID)),
                0, OverlayTexture.DEFAULT_UV, -1);
        var captured = provider.endEntity();
        assertEquals(2, captured.size());
        int[] ranks = captured.stream().mapToInt(RemixVertexConsumerProvider.CapturedLayer::layerIndex).toArray();
        assertTrue(ranks[0] != ranks[1], "different layers must have distinct identities");
        var snapshot = new RemixEntityPass.PoseSnapshot();
        snapshot.record(result(captured));
        context.reset();
        RemixVertexConsumerProvider replay = new RemixVertexConsumerProvider();
        for (int frame = 0; frame < 3; frame++) {
            var layers = RemixEntityPass.replayLayers(snapshot, replay);
            assertArrayEquals(ranks, layers.stream().mapToInt(RemixVertexConsumerProvider.CapturedLayer::layerIndex).toArray());
            for (int i = 0; i < layers.size(); i++) {
                var original = captured.get(i);
                var restored = layers.get(i);
                assertEquals(cuboidKey(original), cuboidKey(restored), "replay must reuse the captured shell mesh");
            }
        }
    }

    @Test
    void glintSitsOutsideEveryPatternOfAnEnchantedShield() {
        EntityRenderContext.get().begin(true);
        RemixVertexConsumerProvider provider = new RemixVertexConsumerProvider();
        provider.beginEntity();
        MatrixStack matrices = new MatrixStack();
        ModelPart.Cuboid plate = cuboid();
        plate.renderCuboid(matrices.peek(), provider.getBuffer(RenderLayer.getGlint()), 0, OverlayTexture.DEFAULT_UV, -1);
        VertexConsumer canvas = provider.getBuffer(RenderLayer.getEntityTranslucent(
                Identifier.of("minecraft", "textures/atlas/shield_patterns.png")));
        for (int i = 0; i < 5; i++) plate.renderCuboid(matrices.peek(), canvas, 0, OverlayTexture.DEFAULT_UV, -1);
        var layers = provider.endEntity();
        var glint = layers.stream().filter(l -> l.surfaceMode() == RenderLayerUtils.SurfaceMode.GLINT).findFirst().orElseThrow();
        var patterns = layers.stream().filter(l -> l != glint).findFirst().orElseThrow();
        RemixEntitySubmitter submitter = new RemixEntitySubmitter(null, null);
        try {
            submitter.prepareSurfaceLayers(result(layers));
            float glintDepth = RemixEntitySubmitter.meshLayerDepth(false,
                    glint.consumer().getPartShellLayer(0), InstanceCategoryBit.VIEW_MODEL);
            RemixCapturingConsumer expanded = new RemixCapturingConsumer(null, false);
            RemixEntitySubmitter.emitCanvasPatterns(expanded, patterns.consumer(), 1, 4,
                    patterns.consumer().getPartShellLayer(1) - 1, ViewModelSpace.layerSpacingScale(InstanceCategoryBit.VIEW_MODEL));
            expanded.finishOpenVertex();
            int v = 0;
            for (int pattern = 0; pattern < 4; pattern++) {
                for (ModelPart.Quad face : plate.sides) {
                    for (ModelPart.Vertex vertex : face.vertices) {
                        float distance = (expanded.getPx()[v] - vertex.pos.x / 16f) * face.direction.x
                                + (expanded.getPy()[v] - vertex.pos.y / 16f) * face.direction.y
                                + (expanded.getPz()[v] - vertex.pos.z / 16f) * face.direction.z;
                        assertTrue(glintDepth > distance, "glint must sit above the complete pattern stack");
                        v++;
                    }
                }
            }
            var snapshot = new RemixEntityPass.PoseSnapshot();
            snapshot.record(result(layers));
            var replay = RemixEntityPass.replayLayers(snapshot, new RemixVertexConsumerProvider());
            submitter.prepareSurfaceLayers(result(replay, snapshot.boneData, snapshot.boneCount));
            assertEquals(glintDepth, RemixEntitySubmitter.meshLayerDepth(false,
                    replay.getFirst().consumer().getPartShellLayer(0), InstanceCategoryBit.VIEW_MODEL));
        } finally {
            submitter.close();
        }
    }

    private static RemixEntitySubmitter.CuboidMeshKey cuboidKey(RemixVertexConsumerProvider.CapturedLayer layer) {
        return new RemixEntitySubmitter.CuboidMeshKey(layer.textureId(), layer.surfaceMode(),
                RemixEntitySubmitter.meshLayerDepth(layer.text(), layer.layerIndex(), 0),
                layer.consumer().getPartSides(0), layer.consumer().getPartSprite(0));
    }

    private static RemixEntityPass.EntityCaptureResult result(List<RemixVertexConsumerProvider.CapturedLayer> layers) {
        var context = EntityRenderContext.get();
        return result(layers, context.snapshotBoneTransforms(new float[0]), context.getBoneCount());
    }

    private static RemixEntityPass.EntityCaptureResult result(List<RemixVertexConsumerProvider.CapturedLayer> layers,
                                                              float[] bones, int boneCount) {
        return new RemixEntityPass.EntityCaptureResult(new Object(), Vec3d.ZERO, 0, layers, bones, boneCount, "test", false);
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

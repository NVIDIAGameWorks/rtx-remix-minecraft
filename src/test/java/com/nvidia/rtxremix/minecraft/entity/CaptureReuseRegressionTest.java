/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MeshInfo;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.Transform;
import com.nvidia.rtxremix.minecraft.asset.RemixInstanceStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterialStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshStore;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import org.joml.Matrix4f;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothHandsReuseNativeMeshesAndInstancesAcrossRepeatedFramesAndCameraTurns(boolean enchanted) throws Exception {
        try (SceneFixture scene = new SceneFixture(); RemixFirstPersonPass pass = new RemixFirstPersonPass()) {
            for (float yaw : new float[] {0, 0, 45}) {
                EntityRenderContext.get().reset();
                TestCamera camera = new TestCamera(yaw);
                var provider = pass.beginCapture(new Matrix4f().rotation(camera.getRotation()).invert());
                for (Hand hand : Hand.values()) {
                    EntityRenderContext.get().setActiveHand(hand);
                    MatrixStack matrices = new MatrixStack();
                    matrices.multiply(camera.getRotation());
                    matrices.translate(hand == Hand.MAIN_HAND ? 0.5 : -0.5, -0.5, -1.5);
                    VertexConsumer item = itemConsumer(provider, enchanted);
                    quad(item, matrices);
                }
                assertNotNull(pass.finishCapture(this, camera, 1));
                scene.submitter.beginFrame();
                pass.submitPending(scene.submitter);
                scene.submitter.endFrame();
                assertEquals(enchanted ? 2 : 1, scene.api.meshCreates);
                assertEquals(enchanted ? 4 : 2, scene.api.instanceCreates);
                assertEquals(0, scene.api.instanceRemoves);
                if (enchanted) {
                    var transforms = new ArrayList<>(scene.api.transforms.values());
                    assertArrayEquals(transforms.get(0), transforms.get(2));
                    assertArrayEquals(transforms.get(1), transforms.get(3));
                }
            }
            assertEquals(enchanted ? 4 : 2, scene.api.instanceUpdates,
                    "the camera turn updates existing native instances; the identical frame updates none");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void productionPoseReplayKeepsNativeMeshAndInstanceKeys(boolean enchanted) throws Exception {
        try (SceneFixture scene = new SceneFixture()) {
            EntityRenderContext context = EntityRenderContext.get();
            context.begin(true);
            RemixVertexConsumerProvider provider = new RemixVertexConsumerProvider();
            provider.beginEntity();
            provider.getBuffer(RenderLayer.getEntityCutout(Identifier.of("test", "empty.png")));
            ModelPart.Cuboid cuboid = cuboid();
            cuboid.renderCuboid(new MatrixStack().peek(), itemConsumer(provider, enchanted), 0, OverlayTexture.DEFAULT_UV, -1);
            var layers = provider.endEntity();
            Entity owner = new ArmorStandEntity(EntityType.ARMOR_STAND, null);
            var result = new RemixEntityPass.EntityCaptureResult(owner, Vec3d.ZERO, 0, layers,
                    context.snapshotBoneTransforms(new float[0]), context.getBoneCount(), "test", false);
            RemixEntityPass pass = new RemixEntityPass(id -> false);
            var snapshot = RemixEntityPass.class.getDeclaredMethod("snapshotPose", Entity.class, RemixEntityPass.EntityCaptureResult.class);
            snapshot.setAccessible(true);
            snapshot.invoke(pass, owner, result);
            var field = RemixEntityPass.class.getDeclaredField("poseSnapshots");
            field.setAccessible(true);
            var snapshots = (it.unimi.dsi.fastutil.ints.Int2ObjectMap<?>) field.get(pass);
            Object pose = snapshots.get(owner.getId());
            assertNotNull(pose);
            var replay = RemixEntityPass.class.getDeclaredMethod("replayPose", Entity.class, pose.getClass(), float.class, RemixEntitySubmitter.class);
            replay.setAccessible(true);
            scene.submitter.beginFrame();
            scene.submitter.submitEntity(result, Vec3d.ZERO);
            scene.submitter.endFrame();
            Map<Long, Long> original = new LinkedHashMap<>(scene.api.instanceMeshes);
            int meshes = scene.api.meshCreates;
            for (int frame = 0; frame < 2; frame++) {
                scene.submitter.beginFrame();
                assertEquals(true, replay.invoke(pass, owner, pose, 1f, scene.submitter));
                scene.submitter.endFrame();
                assertEquals(original, scene.api.instanceMeshes);
                assertEquals(meshes, scene.api.meshCreates);
                assertEquals(enchanted ? 2 : 1, scene.api.instanceCreates);
                assertEquals(0, scene.api.instanceRemoves);
            }
        }
    }

    @Test
    void unchangedCracksReuseNativeGeometryAndInstances() throws Exception {
        try (SceneFixture scene = new SceneFixture()) {
            RemixBlockDamagePass pass = new RemixBlockDamagePass();
            for (int frame = 0; frame < 3; frame++) {
                scene.submitter.beginFrame();
                pass.capture(new BlockPos(211, 64, 51), 3, (matrices, consumer) -> quad(consumer, null), scene.submitter);
                scene.submitter.endFrame();
                pass.finishFrame();
                assertEquals(1, scene.api.meshCreates);
                assertEquals(1, scene.api.instanceCreates);
                assertEquals(0, scene.api.instanceRemoves);
            }
        }
    }

    private static VertexConsumer itemConsumer(VertexConsumerProvider provider, boolean enchanted) {
        return ItemRenderer.getDirectItemGlintConsumer(provider,
                RenderLayer.getEntityCutout(Identifier.of("test", "capture_reuse.png")), true, enchanted);
    }

    private static final class TestCamera extends Camera {
        TestCamera(float yaw) { setRotation(yaw, 20); setPos(211, 64, 51); }
    }

    /** Counts native ownership calls; texture loading and unchanged blend marshalling are stand-ins. */
    private static final class SceneFixture implements AutoCloseable {
        final CountingApi api = new CountingApi();
        final RemixEntitySubmitter submitter;

        @SuppressWarnings("unchecked")
        SceneFixture() throws Exception {
            RemixMeshStore.init(api);
            RemixMaterialStore.init(api);
            RemixInstanceStore.init(api);
            submitter = new RemixEntitySubmitter(api, null) {
                @Override void submitEntity(RemixEntityPass.EntityCaptureResult result, Transform root,
                                            int flags, PartCategoryPolicy policy) {
                    for (var layer : result.layers()) {
                        try {
                            var field = RemixEntitySubmitter.class.getDeclaredField("materialCache");
                            field.setAccessible(true);
                            var cache = (Map<EntityMaterialKey, RemixMaterial>) field.get(this);
                            for (var mode : RenderLayerUtils.SurfaceMode.values()) {
                                var key = new EntityMaterialKey(layer.textureId(), mode);
                                if (!cache.containsKey(key)) {
                                    var material = new RemixMaterial(new MaterialInfo(), List.of(), null);
                                    RemixMaterialStore.get().create(material);
                                    cache.put(key, material);
                                }
                            }
                        } catch (ReflectiveOperationException e) {
                            throw new AssertionError(e);
                        }
                    }
                    super.submitEntity(result, root, flags, policy);
                }
                @Override InstanceInfoBlendEXT blendExtFor(Identifier texture, RenderLayerUtils.SurfaceMode mode,
                                                           boolean cuboid, int tint, PatternCanvasRole role) {
                    return null;
                }
            };
        }

        @Override public void close() {
            submitter.close();
            RemixInstanceStore.shutdown();
            RemixMeshStore.shutdown();
            RemixMaterialStore.shutdown();
        }
    }

    private static final class CountingApi extends RemixAPI {
        long next = 1;
        int meshCreates, instanceCreates, instanceUpdates, instanceRemoves;
        final Map<Long, Long> instanceMeshes = new LinkedHashMap<>();
        final Map<Long, float[]> transforms = new LinkedHashMap<>();
        @Override public long createMaterial(MaterialInfo info) { return next++; }
        @Override public void destroyMaterial(long handle) { }
        @Override public long createMesh(MeshInfo info) { meshCreates++; return next++; }
        @Override public void destroyMesh(long handle) { }
        @Override public long sceneInstanceAdd(long mesh, float[] transform, int flags, int doubleSided, long pNext) {
            instanceCreates++;
            long handle = next++;
            instanceMeshes.put(handle, mesh);
            transforms.put(handle, transform.clone());
            return handle;
        }
        @Override public void sceneInstanceUpdate(long handle, long mesh, float[] transform, int flags, int doubleSided, long pNext) {
            instanceUpdates++;
            assertEquals(instanceMeshes.get(handle), mesh);
            transforms.put(handle, transform.clone());
        }
        @Override public void sceneInstanceRemove(long handle) {
            instanceRemoves++;
            instanceMeshes.remove(handle);
            transforms.remove(handle);
        }
    }

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

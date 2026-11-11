/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.InstanceCategoryBit;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Captures the block-breaking crack where vanilla draws it and submits it through the
 * entity submitter as a decal over the terrain.
 *
 * <p>{@code WorldRendererMixin} wraps vanilla's {@code BlockRenderManager.renderDamage}
 * call and routes it to {@code RemixRenderer.renderBlockDamageInPlace}, which calls
 * {@link #capture}. The block model is rendered on this pass's identity
 * {@link RecyclingMatrixStack} rather than vanilla's camera-relative one, so the captured
 * quads are block-local and the crack projection runs in block space: one pooled mesh per
 * block shape and stage, and an instance root that is a translation by the block position.
 *
 * <p>Vanilla allocates a fresh {@code BlockPos} for every broken block every frame, and the
 * submitter's retained registry keys parts by owner identity, so each broken position is
 * lent one owner token for as long as it keeps being rendered. A position vanilla stops
 * rendering, because the block broke, the player let go, or it left the 32-block range,
 * loses its token at {@link #finishFrame}, and the submitter's {@code endFrame} retires
 * its parts the same frame. The pass holds no native state of its own.
 */
public final class RemixBlockDamagePass {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemixBlockDamagePass.class);

    private static final String TYPE_KEY = "minecraft:block_breaking";

    /**
     * The crack is coplanar with the terrain face it is drawn on, which is the case
     * terrain's own overlay category already solves with {@code DECAL_STATIC}: the
     * runtime offsets decal quads off the surface beneath and blends them over it. The
     * runtime handles {@code DECAL_DYNAMIC} identically and has deprecated its option,
     * so the static bit is used for both.
     */
    static final RemixEntitySubmitter.PartCategoryPolicy TERRAIN_DECAL =
            role -> InstanceCategoryBit.DECAL_STATIC;

    /**
     * Vanilla's damage render with the matrix stack and consumer left open, so the pass
     * can supply its own. The mixin's {@code Operation} handle stays in the mixin.
     */
    @FunctionalInterface
    public interface BlockDamageRenderCall {
        void render(PoseStack matrices, VertexConsumer consumer);
    }

    private final RecyclingMatrixStack matrices = new RecyclingMatrixStack();
    private final RemixVertexConsumerProvider provider = new RemixVertexConsumerProvider();
    private final CrackProjector projector = new CrackProjector();
    private final PositionOwners owners = new PositionOwners();
    private final FailureLogThrottle failures = new FailureLogThrottle(LOGGER, "Block damage");
    private float[] reusableBoneData = new float[0];

    /**
     * Renders one broken block's damage model through vanilla's renderer on the identity
     * stack and submits the capture inside the submitter's open frame.
     *
     * @param stage the destroy stage vanilla selected for the block, 0 to 9
     */
    public void capture(BlockPos pos, int stage, BlockDamageRenderCall call,
                        RemixEntitySubmitter submitter) {
        EntityRenderContext ctx = EntityRenderContext.get();
        provider.beginEntity();
        matrices.pushPose();
        ctx.begin(true);
        List<RemixVertexConsumerProvider.CapturedLayer> layers;
        try {
            VertexConsumer crack = provider.getBuffer(
                    ModelBakery.DESTROY_TYPES.get(stage));
            call.render(matrices, projector.target(crack));
            layers = provider.endEntity();
        } catch (Exception e) {
            failures.noteFailure("capturing", "block damage", pos, e);
            return;
        } finally {
            // Both restore even if the renderer threw: the model renderer translates the
            // root in place, and a capture left open would make the next one look nested.
            matrices.reset();
            ctx.reset();
        }
        if (layers.isEmpty()) {
            return;
        }

        reusableBoneData = ctx.snapshotBoneTransforms(reusableBoneData);
        RemixEntityPass.EntityCaptureResult result = new RemixEntityPass.EntityCaptureResult(
                owners.ownerFor(pos.asLong()),
                Vec3.atLowerCornerOf(pos),
                0.0f,
                layers,
                reusableBoneData,
                ctx.getBoneCount(),
                TYPE_KEY,
                false);
        try {
            submitter.submitEntity(result, TERRAIN_DECAL);
        } catch (Exception e) {
            failures.noteFailure("submitting", "block damage", pos, e);
        }
    }

    /**
     * Drops the owner tokens of positions not captured this frame. Called once per frame
     * from {@code renderAndSubmitFrame}; retiring their scene instances belongs to the
     * submitter's {@code endFrame}.
     */
    public void finishFrame() {
        owners.endFrame();
        failures.endFrame();
    }

    /**
     * One identity token per broken block position, kept while the position is rendered.
     * Tokens are handed out during a frame and the ones not handed out are dropped at
     * {@link #endFrame}, so a position rendered every frame keeps one token and a
     * position that stops being rendered starts over with a new one if it returns.
     */
    static final class PositionOwners {
        private static final class Owner {
            long lastSeenFrame;
        }

        private final Long2ObjectOpenHashMap<Owner> owners = new Long2ObjectOpenHashMap<>();
        private long frame;

        Object ownerFor(long packedPos) {
            Owner owner = owners.get(packedPos);
            if (owner == null) {
                owner = new Owner();
                owners.put(packedPos, owner);
            }
            owner.lastSeenFrame = frame;
            return owner;
        }

        void endFrame() {
            if (!owners.isEmpty()) {
                owners.values().removeIf(owner -> owner.lastSeenFrame != frame);
            }
            frame++;
        }

        int size() {
            return owners.size();
        }
    }

    /**
     * Projects the crack texture onto a block's faces the way vanilla's
     * {@code OverlayVertexConsumer} does for an identity pose: the block-local position,
     * rotated into the frame of the face its normal selects, is the texture coordinate,
     * and the model's own atlas coordinates and shaded colour are discarded.
     *
     * <p>Vanilla's consumer is not reused because it hands its delegate the normal before
     * the texture coordinate, and {@link RemixCapturingConsumer} closes a vertex at its
     * normal. This one delivers the coordinate first.
     */
    static final class CrackProjector implements VertexConsumer {
        private final Quaternionf[] faceRotations = new Quaternionf[Direction.values().length];
        private final Vector3f projected = new Vector3f();
        private VertexConsumer target;
        private float x, y, z;

        CrackProjector() {
            for (Direction face : Direction.values()) {
                faceRotations[face.ordinal()] = face.getRotation();
            }
        }

        /** Directs the next render's vertices at {@code target}. */
        CrackProjector target(VertexConsumer target) {
            this.target = target;
            return this;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
            target.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            target.setColor(0xFFFFFFFF);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            target.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            target.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            Direction face = Direction.getNearest(x, y, z);
            projected.set(this.x, this.y, this.z)
                    .rotateY(Mth.PI)
                    .rotateX(-Mth.HALF_PI)
                    .rotate(faceRotations[face.ordinal()]);
            target.setUv(-projected.x, -projected.y);
            target.setNormal(x, y, z);
            return this;
        }
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.minecraft.mixin.EntityRenderDispatcherAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.Vec3;

/**
 * Captures block-entity geometry where vanilla dispatches it and submits it through
 * the entity submitter with the {@link BlockEntity} as owner.
 *
 * <p>{@code BlockEntityRenderDispatcherMixin} routes each vanilla dispatch to
 * {@code RemixRenderer.renderBlockEntityInPlace}, which calls {@link #capture}. The
 * block entity is rendered on this pass's own identity {@link RecyclingMatrixStack}
 * instead of vanilla's camera-relative one, so the captured pivots hold only the
 * renderer's block rotation and animation, and the instance root is a translation by
 * the block position, an integer that is exact in float. Vanilla still decides what
 * is rendered: {@code WorldRenderer} dispatches only the block entities of
 * frustum-visible sections, and the dispatcher's render-distance check runs inside
 * the wrapped call, so an out-of-range block entity enters capture, produces no
 * layers and is skipped.
 *
 * <p>Block entities share the submitter's retained registry, mesh pool and material
 * cache with entities, so this pass holds no native state. A block entity vanilla does
 * not dispatch in a frame, because it is culled, out of range or unloaded, is simply
 * absent, and the submitter's {@code endFrame} retires its parts. There is no frame
 * bracket of its own: capture runs inside the submitter's open frame, and
 * {@link #finishFrame} only reports the frame's counters.
 */
public final class RemixBlockEntityPass {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemixBlockEntityPass.class);

    /**
     * Placed banners and shields are static world geometry, so their patterns are
     * static decals over the dyed base. The same cuboids carried in a hand stay plain
     * geometry, which is why the category is the capturing pass's choice rather than
     * the submitter's.
     */
    static final RemixEntitySubmitter.PartCategoryPolicy STATIC_WORLD_CANVASES =
            role -> role == RemixEntitySubmitter.PatternCanvasRole.PATTERN
                    ? InstanceCategoryBit.DECAL_STATIC
                    : 0;

    /**
     * Vanilla's dispatcher render with the matrix stack and provider left open, so the
     * pass can supply its own. The mixin's {@code Operation} handle stays in the mixin.
     */
    @FunctionalInterface
    public interface BlockEntityRenderCall {
        void render(PoseStack matrices, MultiBufferSource provider);
    }

    /**
     * Totals for one frame's block-entity captures. Part and mesh-create counts are
     * this pass's own submits, taken as deltas of the submitter's running totals,
     * which also carry entities. Times are microseconds, sized for the counters they
     * feed.
     */
    public record FrameStats(int count, int parts, int meshCreates,
                             int captureMicros, int submitMicros) {}

    private final RecyclingMatrixStack matrices = new RecyclingMatrixStack();
    private final RemixVertexConsumerProvider provider = new RemixVertexConsumerProvider();
    private final IdentityHashMap<BlockEntityType<?>, String> typeKeyCache = new IdentityHashMap<>();
    private final FailureLogThrottle failures = new FailureLogThrottle(LOGGER, "Block entity");
    // One buffer suffices: the submit consumes the bones before the next capture
    // overwrites them.
    private float[] reusableBoneData = new float[0];

    private int frameCount, frameParts, frameMeshCreates;
    private long frameCaptureNanos, frameSubmitNanos;

    /**
     * Renders one block entity through vanilla's renderer on the identity stack and
     * submits the capture inside the submitter's open frame.
     *
     * <p>Submitting before the next block entity renders is what lets the provider's
     * consumers recycle: a layer's data is consumed before anything can overwrite it.
     * Blob shadows are suppressed for the duration, as the entity pass does, so a
     * spawner's display entity casts only the shadow Remix traces.
     */
    public void capture(BlockEntity be, BlockEntityRenderCall call,
                        EntityRenderDispatcher dispatcher, RemixEntitySubmitter submitter) {
        long captureStart = System.nanoTime();
        EntityRenderContext ctx = EntityRenderContext.get();
        boolean hadShadows = ((EntityRenderDispatcherAccessor) dispatcher).rtxremix$getRenderShadows();
        dispatcher.setRenderShadow(false);
        provider.beginEntity();
        matrices.pushPose();
        ctx.begin(true);
        List<RemixVertexConsumerProvider.CapturedLayer> layers;
        try {
            call.render(matrices, provider);
            layers = provider.endEntity();
        } catch (Exception e) {
            failures.noteFailure("capturing", "block entity " + typeKey(be), be.getBlockPos(), e);
            return;
        } finally {
            // All three restore even if the renderer threw mid-traversal: the identity
            // root returns every pooled entry, the player's shadow setting comes back,
            // and a capture left open would make the next one look nested. The bone
            // count stays readable for the snapshot below.
            matrices.reset();
            dispatcher.setRenderShadow(hadShadows);
            ctx.reset();
        }
        frameCaptureNanos += System.nanoTime() - captureStart;
        if (layers.isEmpty()) {
            // The dispatcher's own render-distance check ends here too. Snapshotting
            // the zero bones it left would swap the reusable buffer for the shared
            // empty array and force the next capture to allocate.
            return;
        }

        reusableBoneData = ctx.snapshotBoneTransforms(reusableBoneData);
        RemixEntityPass.EntityCaptureResult result = new RemixEntityPass.EntityCaptureResult(
                be,
                Vec3.atLowerCornerOf(be.getBlockPos()),
                0.0f,
                layers,
                reusableBoneData,
                ctx.getBoneCount(),
                typeKey(be),
                false);
        submit(be, result, submitter);
    }

    /**
     * Hands one block entity to the submitter. The submitter retires the owner's own
     * parts before a failure reaches here, so the block entity is absent this frame
     * and rebuilt on the next; the failure must not escape into vanilla's world render.
     */
    private void submit(BlockEntity be, RemixEntityPass.EntityCaptureResult result,
                        RemixEntitySubmitter submitter) {
        long submitStart = System.nanoTime();
        int partsBefore = submitter.getLastDrawCount();
        int meshCreatesBefore = submitter.getLastMeshCreateCount();
        try {
            submitter.submitEntity(result, STATIC_WORLD_CANVASES);
            frameCount++;
        } catch (Exception e) {
            failures.noteFailure("submitting", "block entity " + typeKey(be), be.getBlockPos(), e);
        } finally {
            frameSubmitNanos += System.nanoTime() - submitStart;
            frameParts += submitter.getLastDrawCount() - partsBefore;
            frameMeshCreates += submitter.getLastMeshCreateCount() - meshCreatesBefore;
        }
    }

    /**
     * Reports and zeroes the frame's totals. Called once per frame from
     * {@code renderAndSubmitFrame}; retiring the block entities vanilla did not
     * dispatch belongs to the submitter's {@code endFrame}.
     */
    public FrameStats finishFrame() {
        failures.endFrame();
        FrameStats stats = new FrameStats(frameCount, frameParts, frameMeshCreates,
                (int) (frameCaptureNanos / 1000L), (int) (frameSubmitNanos / 1000L));
        frameCount = 0;
        frameParts = 0;
        frameMeshCreates = 0;
        frameCaptureNanos = 0L;
        frameSubmitNanos = 0L;
        return stats;
    }

    private String typeKey(BlockEntity be) {
        return typeKeyCache.computeIfAbsent(be.getType(),
                type -> BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).toString());
    }
}

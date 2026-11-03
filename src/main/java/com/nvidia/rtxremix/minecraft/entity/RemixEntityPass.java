/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.util.ClosestKHeap;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.mixin.EntityRenderDispatcherAccessor;
import com.nvidia.rtxremix.minecraft.ClientViewDistance;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Captures entity geometry for Remix and hands it to the submission layer.
 *
 * <p>Entities reach this class two ways. Those vanilla already draws are captured in place:
 * {@code WorldRendererMixin} routes each one to {@code RemixRenderer.renderEntityInPlace},
 * which calls {@link #captureAndSubmit}, so the model is evaluated once rather than once for
 * OpenGL and again here. A path tracer also needs entities the camera cannot see, for shadows
 * and reflections, so {@link #finishFrame} then sweeps the world for entities vanilla skipped
 * and submits them closest-first under a {@code MAX_ENTITIES} budget.
 *
 * <p>Both paths render through the vanilla {@link EntityRenderDispatcher} with a
 * {@link RemixVertexConsumerProvider}, so Minecraft keeps ownership of animation, feature
 * renderers, multi-layer models and modded entities. Distant off-screen entities may replay a
 * cached pose instead of re-running the dispatcher; the world transform is still recomputed
 * every frame, so a reused pose lags an animation but never misplaces an entity.
 *
 * <p>Native Remix calls belong to the submitter, not to this class.
 */
public class RemixEntityPass {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemixEntityPass.class);

    /**
     * Maximum entity candidates considered per frame, to avoid stalling.
     *
     * <p>The bound is on candidates rather than successful captures because the
     * cost being limited is the vanilla {@code EntityRenderDispatcher.render()}
     * call, which a candidate incurs whether or not it emits any vertices.
     * Bounding successes instead would let a crowd of geometry-less entities
     * drive an unbounded number of render calls per frame.
     *
     * <p>Consequently {@code Ent.Count} can sit below this cap in a saturated
     * frame, and {@code Ent.Count} plus {@code Ent.Skipped} need not equal the
     * in-range entity total: {@code Ent.Skipped} counts only in-range candidates
     * that lost the closest-first selection.
     */
    private static final int MAX_ENTITIES = 256;
    private static final int DEFAULT_ENTITY_CULL_CHUNKS = 7;
    private static final int DEFAULT_ENTITY_CULL_BLOCKS = DEFAULT_ENTITY_CULL_CHUNKS * 16;

    /**
     * Off-screen entities closer than this always get a freshly evaluated pose.
     * Near enough that their limbs can be a large part of a reflection, so a few
     * frames of stale animation would be visible.
     */
    private static final double POSE_REUSE_MIN_BLOCKS = 64.0;
    private static final double POSE_REUSE_MIN_SQ = POSE_REUSE_MIN_BLOCKS * POSE_REUSE_MIN_BLOCKS;

    private static final int POSE_REFRESH_FRAMES = 4;

    private static final int TYPE_LOG_INTERVAL = 300;

    private final RemixVertexConsumerProvider provider = new RemixVertexConsumerProvider();
    /**
     * The per-type breakdown costs a HashMap probe per entity per frame and a
     * stream sort plus a dozen String.formats on the render thread every summary,
     * a periodic hitch by construction. Diagnostics, so off unless asked for.
     */
    private static final boolean TYPE_SUMMARY_ENABLED =
            Boolean.getBoolean("rtxremix.debug.entityTypeSummary");
    private final Map<String, int[]> typeCounts = new HashMap<>();
    private final RecyclingMatrixStack sharedMatrices = new RecyclingMatrixStack();
    private final IdentityHashMap<EntityType<?>, String> entityTypeKeyCache = new IdentityHashMap<>();
    private final ClosestKHeap<Entity> entityHeap = new ClosestKHeap<>(MAX_ENTITIES);
    private final IntOpenHashSet capturedIds = new IntOpenHashSet(MAX_ENTITIES);
    private boolean frameActive = false;
    private int frameEntityCount, frameLayerCount, frameBoneCount, frameCapSkipped;
    // Split so the half we own in Java can be judged against the half that is
    // native, and so the off-frustum sweep can be judged against the entities
    // the camera can actually see.
    private long frameCaptureNanos, frameSubmitNanos, frameOffSweepNanos;
    private int frameOffCount, frameOffDraws, frameOffReused;
    private Vec3d firstPersonShadowModelOffset = Vec3d.ZERO;
    // Deltas of the submitter's running totals taken around this pass's own submits,
    // so what the submitter's other owners draw in the same frame stays out of them.
    private int frameDraws, frameMeshCreates;

    /** One captured cuboid part, kept so a pose can be replayed without the model. */
    private static final class SnapshotPart {
        ModelPart.Quad[] sides;
        Sprite sprite;
        int tint;
        int bone;
    }

    private static final class SnapshotLayer {
        RenderLayer renderLayer;
        int layerIndex;
        SnapshotPart[] parts = new SnapshotPart[0];
        int partCount;

        /** Room for {@code count} parts, keeping the part objects already made. */
        void resize(int count) {
            if (parts.length < count) {
                parts = Arrays.copyOf(parts, count);
            }
            for (int i = 0; i < count; i++) {
                if (parts[i] == null) {
                    parts[i] = new SnapshotPart();
                }
            }
            partCount = count;
        }
    }

    /**
     * A captured animation pose, held so a distant off-screen entity can be
     * resubmitted without re-evaluating its model. The world transform is NOT part
     * of it: that is recomputed from the entity every frame, so a reused pose never
     * puts the entity in the wrong place, it only lets its limbs lag.
     *
     * <p>Rewritten in place on each refresh: an entity's snapshot is read only on the
     * frames between its own refreshes, so the storage can be reused instead of
     * allocated for every distant entity every refresh.
     */
    private static final class PoseSnapshot {
        SnapshotLayer[] layers = new SnapshotLayer[0];
        int layerCount;
        float[] boneData = new float[0];
        int boneCount;
        String typeKey;
        long frame;

        void resizeLayers(int count) {
            if (layers.length < count) {
                layers = Arrays.copyOf(layers, count);
            }
            for (int i = 0; i < count; i++) {
                if (layers[i] == null) {
                    layers[i] = new SnapshotLayer();
                }
            }
            layerCount = count;
        }
    }

    private final Int2ObjectOpenHashMap<PoseSnapshot> poseSnapshots = new Int2ObjectOpenHashMap<>();
    private final RemixVertexConsumerProvider replayProvider = new RemixVertexConsumerProvider();
    private ClientWorld snapshotWorld;
    private long sweepFrame;
    private float[] reusableBoneData = new float[0];
    private int typeLogFrames = 0;
    private long capSkippedSinceSummary = 0;
    private boolean capWarnActive = false;
    private long submitFailuresSinceSummary = 0;
    private boolean submitErrorActive = false;
    private volatile int entityCullBlocks = DEFAULT_ENTITY_CULL_BLOCKS;
    /**
     * Answers whether an entity id claimed a Remix light this frame. Read during the
     * closing sweep, which runs after the frame's light pass.
     */
    private final IntPredicate lightOwners;

    public RemixEntityPass(IntPredicate lightOwners) {
        this.lightOwners = lightOwners;
    }

    /** Drops poses for entities that have not been submitted for a while. */
    private void evictStalePoseSnapshots() {
        if (poseSnapshots.size() <= MAX_ENTITIES) {
            return;
        }
        long oldest = sweepFrame - (long) POSE_REFRESH_FRAMES * 4L;
        poseSnapshots.values().removeIf(snapshot -> snapshot.frame < oldest);
    }

    /**
     * Drops every recorded pose. A snapshot names the cuboids and sprites a model was
     * baked with, which a resource reload replaces, so a replay afterwards would rebuild
     * its meshes from the previous stitch's UV rectangles.
     */
    public void discardPoseSnapshots() {
        poseSnapshots.clear();
    }

    /** Abandons a capture window left open by a frame whose submit never ran. */
    public void resetFrame() {
        frameActive = false;
    }

    /**
     * Opens the per-frame capture window. Called from the start of vanilla's world
     * render, and again by {@link #finishFrame} so a frame that reaches no entity
     * still produces a well-formed scene.
     */
    public void beginFrame(RemixEntitySubmitter submitter) {
        if (frameActive) {
            return;
        }
        frameActive = true;
        capturedIds.clear();
        frameEntityCount = 0;
        frameLayerCount = 0;
        frameBoneCount = 0;
        frameCapSkipped = 0;
        frameCaptureNanos = 0L;
        frameSubmitNanos = 0L;
        frameOffSweepNanos = 0L;
        frameOffCount = 0;
        frameOffDraws = 0;
        frameOffReused = 0;
        frameDraws = 0;
        frameMeshCreates = 0;
        submitter.beginFrame();
    }

    /**
     * Captures one entity through vanilla's dispatcher and submits it immediately.
     *
     * <p>Submitting before the next entity renders is what lets the consumer pool
     * recycle: a layer's data is consumed before anything can overwrite it.
     *
     * @return true if this frame claimed the entity, false if it was already captured
     */
    public boolean captureAndSubmit(Entity entity, EntityRenderDispatcher dispatcher,
                                    float tickDelta, RemixEntitySubmitter submitter) {
        return captureAndSubmit(entity, dispatcher, tickDelta, submitter, false);
    }

    private boolean captureAndSubmit(Entity entity, EntityRenderDispatcher dispatcher,
                                     float tickDelta, RemixEntitySubmitter submitter,
                                     boolean snapshotPose) {
        if (!capturedIds.add(entity.getId())) {
            return false;
        }
        long captureStart = System.nanoTime();
        // Remix traces shadows from the scene lights, so vanilla's blob shadow
        // quads have no consumer. Suppress them rather than capture and drop them.
        boolean hadShadows =
                ((EntityRenderDispatcherAccessor) dispatcher)
                        .rtxremix$getRenderShadows();
        dispatcher.setRenderShadows(false);
        EntityCaptureResult result;
        try {
            result = renderOneEntity(entity, dispatcher, tickDelta,
                    isFirstPersonLocalPlayer(MinecraftClient.getInstance(), entity));
        } catch (Exception e) {
            LOGGER.error("Error capturing entity {}: {}", entity.getType(), e.getMessage());
            return true;
        } finally {
            dispatcher.setRenderShadows(hadShadows);
        }
        long captured = System.nanoTime();
        frameCaptureNanos += captured - captureStart;
        if (result == null) {
            return true;
        }
        if (!submitToScene(entity, result, submitter)) {
            return true;
        }
        if (snapshotPose) {
            snapshotPose(entity, result);
        }
        frameEntityCount++;
        frameLayerCount += result.layers().size();
        frameBoneCount += result.boneCount();
        if (TYPE_SUMMARY_ENABLED) {
            int[] typeStats = typeCounts.computeIfAbsent(result.entityTypeKey(), k -> new int[3]);
            typeStats[0]++;
            typeStats[1] += result.layers().size();
            typeStats[2] += result.boneCount();
        }
        return true;
    }

    /**
     * Hands one entity to the submitter and keeps a failure to that entity. The
     * in-place path runs inside vanilla's WorldRenderer.render, where an escaping
     * native failure would end the frame and crash the client; on the off-frustum
     * sweep it would abandon every entity behind this one and retire their instances
     * at endFrame. The submitter retires the entity's own retained parts before the
     * failure reaches here, so the entity is absent this frame and rebuilt on the next.
     *
     * @return true if the entity reached the scene
     */
    private boolean submitToScene(Entity entity, EntityCaptureResult result,
                                  RemixEntitySubmitter submitter) {
        long submitStart = System.nanoTime();
        int drawsBefore = submitter.getLastDrawCount();
        int meshCreatesBefore = submitter.getLastMeshCreateCount();
        try {
            Vec3d renderOffset = result.firstPersonLocalPlayer()
                    ? firstPersonShadowModelOffset
                    : Vec3d.ZERO;
            submitter.submitEntity(result, renderOffset);
            return true;
        } catch (Exception e) {
            noteSubmitFailure(entity, e);
            return false;
        } finally {
            frameSubmitNanos += System.nanoTime() - submitStart;
            frameDraws += submitter.getLastDrawCount() - drawsBefore;
            frameMeshCreates += submitter.getLastMeshCreateCount() - meshCreatesBefore;
        }
    }

    /**
     * Logs the first submit failure of an episode. The pass continues past a
     * failure, so a persistent one would otherwise print a line per entity per
     * frame; later failures are counted and the log re-arms after a clean
     * {@link #TYPE_LOG_INTERVAL} window.
     */
    private void noteSubmitFailure(Entity entity, Exception e) {
        submitFailuresSinceSummary++;
        if (submitErrorActive) {
            return;
        }
        submitErrorActive = true;
        LOGGER.error("Error submitting entity {}: {} (further submit failures are counted, "
                + "not logged, until a {}-frame window passes without one)",
                entity.getType(), e.getMessage(), TYPE_LOG_INTERVAL);
    }

    /**
     * Totals for one frame's entity pass. Counts cover both in-place and off-frustum
     * captures; the off-frustum fields are the subset from the closing sweep. Draw and
     * mesh-create counts are this pass's own submits, not the submitter's frame totals,
     * which also carry its other owners. Times are microseconds, sized for the
     * counters they feed.
     */
    public record FrameStats(int entityCount, int layerCount, int boneCount,
                             int drawCount, int meshCreates, int capSkipped,
                             int captureMicros, int submitMicros,
                             int offFrustumCount, int offSweepMicros,
                             int offFrustumDraws, int offFrustumReused,
                             int poolSize) {}

    /**
     * Closes the frame: sweeps up the nearby entities vanilla's frustum skipped,
     * then reports the frame totals. Vanilla omits the local player from its
     * first-person traversal, so the closing sweep captures that hidden shadow
     * model after light placement has resolved its current render-only offset.
     */
    public FrameStats finishFrame(ClientWorld world, Vec3d cameraPos, float tickDelta,
                                  Vec3d firstPersonShadowModelOffset,
                                  RemixEntitySubmitter submitter) {
        this.firstPersonShadowModelOffset = firstPersonShadowModelOffset;
        beginFrame(submitter);
        MinecraftClient client = MinecraftClient.getInstance();
        EntityRenderDispatcher dispatcher = client == null ? null : client.getEntityRenderDispatcher();
        // Entity ids are per world, so a stale snapshot would alias a different
        // entity after a world change.
        if (world != snapshotWorld) {
            snapshotWorld = world;
            poseSnapshots.clear();
        }
        sweepFrame++;
        if (dispatcher != null) {
            try (ProfileScope offScreenScope = ProfileScope.begin("Entities offscreen")) {
                captureOffFrustum(world, cameraPos, tickDelta, dispatcher, submitter);
            }
        }
        evictStalePoseSnapshots();

        if (++typeLogFrames >= TYPE_LOG_INTERVAL) {
            // The saturation warn and the submit error re-arm on a clean window whether
            // or not the breakdown itself is enabled; they are the operational signals.
            if (capSkippedSinceSummary == 0) {
                capWarnActive = false;
            }
            if (submitFailuresSinceSummary == 0) {
                submitErrorActive = false;
            }
            if (TYPE_SUMMARY_ENABLED) {
                logEntityTypeSummary();
                typeCounts.clear();
            }
            capSkippedSinceSummary = 0;
            submitFailuresSinceSummary = 0;
            typeLogFrames = 0;
        }

        frameActive = false;
        return new FrameStats(frameEntityCount, frameLayerCount, frameBoneCount,
                frameDraws, frameMeshCreates,
                frameCapSkipped,
                (int) (frameCaptureNanos / 1000L), (int) (frameSubmitNanos / 1000L),
                frameOffCount, (int) (frameOffSweepNanos / 1000L), frameOffDraws,
                frameOffReused,
                submitter.getPoolSize());
    }

    /**
     * Captures in-range entities that vanilla's frustum-culled traversal skipped.
     * They are invisible to the raster pass but still cast shadows and appear in
     * reflections, so a path tracer needs them.
     *
     * <p>Selection is closest-first, so a newly spawned nearby entity is never
     * starved by older distant ones that happen to iterate first. The budget is
     * whatever remains of {@link #MAX_ENTITIES} after the in-place captures.
     *
     * <p>An entity that owns a Remix light this frame is admitted at any distance
     * and never evicted: its light already reaches the scene from wherever it
     * stands, and a beam without its housing looks broken.
     */
    private void captureOffFrustum(ClientWorld world, Vec3d cameraPos, float tickDelta,
                                   EntityRenderDispatcher dispatcher, RemixEntitySubmitter submitter) {
        int budget = MAX_ENTITIES - capturedIds.size();
        if (budget <= 0) {
            return;
        }
        // Bounded to the budget, not to MAX_ENTITIES. Heap order is not distance order, so
        // keeping the first `budget` slots of a larger heap would keep the farthest entity
        // (slot 0 is the max) and drop near ones from the leaves. Letting the heap do the
        // eviction is what actually makes the selection closest-first.
        entityHeap.reset(budget);
        // Timed from here so the world walk and heap build below fall inside the section
        // that reports the off-frustum sweep.
        long sweepStart = System.nanoTime();
        long captureNanosBefore = frameCaptureNanos;
        long submitNanosBefore = frameSubmitNanos;
        double maxDistSq = getEffectiveEntityRenderDistance();
        maxDistSq *= maxDistSq;
        for (Entity entity : world.getEntities()) {
            if (capturedIds.contains(entity.getId())) {
                continue;
            }
            double distSq = entity.squaredDistanceTo(cameraPos);
            boolean ownsLight = lightOwners.test(entity.getId());
            if (!sweepAdmits(distSq, maxDistSq, ownsLight)) {
                continue;
            }
            entityHeap.offer(entity, sweepPriority(distSq, ownsLight));
        }
        int drawsBefore = frameDraws;
        try {
            int skipped = entityHeap.overflowCount();
            if (skipped > 0) {
                frameCapSkipped = skipped;
                capSkippedSinceSummary += skipped;
                if (!capWarnActive) {
                    capWarnActive = true;
                    LOGGER.warn("Entity capture cap ({}) reached: {} in-range entities not captured "
                            + "this frame (capturing closest first; see Ent.Skipped counter)",
                            MAX_ENTITIES, skipped);
                }
            }
            for (int i = 0, n = entityHeap.size(); i < n; i++) {
                Entity entity = entityHeap.get(i);
                if (submitOffFrustum(entity, cameraPos, tickDelta, dispatcher, submitter)) {
                    frameOffCount++;
                }
            }
            frameOffDraws = frameDraws - drawsBefore;
            // Capture and submit inject as flat sibling sections, so the sweep excludes the
            // capture and submit time it contains and the three sum to the pass.
            frameOffSweepNanos = (System.nanoTime() - sweepStart)
                    - (frameCaptureNanos - captureNanosBefore)
                    - (frameSubmitNanos - submitNanosBefore);
        } finally {
            // Release the references immediately: a world switch between frames
            // would otherwise keep the unloaded ClientWorld reachable through them.
            entityHeap.clear();
        }
    }

    /**
     * Whether the closing sweep considers an entity at all. Distance culls everything
     * except an entity that owns a Remix light this frame.
     */
    static boolean sweepAdmits(double distSq, double maxDistSq, boolean ownsLight) {
        return ownsLight || distSq <= maxDistSq;
    }

    /**
     * Heap priority of an admitted entity; lower is kept first. A non-owner ranks by
     * squared distance. A light owner ranks below every non-owner so a saturated
     * {@link #MAX_ENTITIES} budget cannot evict it, and owners keep distance order among
     * themselves so the nearer one wins when two compete for the last slot.
     */
    static double sweepPriority(double distSq, boolean ownsLight) {
        return ownsLight ? -1.0 / (1.0 + distSq) : distSq;
    }

    private void logEntityTypeSummary() {
        if (typeCounts.isEmpty() && capSkippedSinceSummary == 0 && submitFailuresSinceSummary == 0) return;
        int frames = typeLogFrames > 0 ? typeLogFrames : 1;
        StringBuilder sb = new StringBuilder("\n=== Entity Type Breakdown (")
                .append(frames).append(" frames) ===\n");
        sb.append(String.format("  %-35s %8s %8s %8s\n", "Type", "Ent/frm", "Lay/frm", "Bone/frm"));
        typeCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .forEach(e -> {
                    int[] s = e.getValue();
                    sb.append(String.format("  %-35s %8.1f %8.1f %8.1f\n",
                            e.getKey(),
                            s[0] / (double) frames,
                            s[1] / (double) frames,
                            s[2] / (double) frames));
                });
        if (capSkippedSinceSummary > 0) {
            sb.append(String.format("  capture cap (%d) skipped: %.1f entities/frame\n",
                    MAX_ENTITIES, capSkippedSinceSummary / (double) frames));
        }
        if (submitFailuresSinceSummary > 0) {
            sb.append(String.format("  submit failures: %.1f entities/frame\n",
                    submitFailuresSinceSummary / (double) frames));
        }
        sb.append("==========================================");
        LOGGER.info(sb.toString());
    }

    /**
     * Sets the maximum entity capture distance in chunks. 0 = use Minecraft's
     * view distance.
     *
     * <p>Chunks at this surface so it reads the same as every other distance
     * setting and as Minecraft's own render distance. The comparison itself is
     * genuinely per-entity and continuous, so blocks stay the internal unit.
     */
    public void setEntityCullDistance(int chunks) {
        entityCullBlocks = Math.max(0, chunks) * 16;
    }

    /** Returns the entity capture distance in chunks. */
    public int getEntityCullDistance() {
        return entityCullBlocks / 16;
    }

    /**
     * Submits one off-frustum entity, reusing its recorded pose when it is far
     * enough away and not due a refresh this frame.
     *
     * <p>Close entities are always evaluated: their limbs can be a large part of a
     * reflection. Beyond that, a pose is reused for at most
     * {@link #POSE_REFRESH_FRAMES} frames, and the entity's position is recomputed
     * every frame regardless, so what lags is the animation and never the place.
     */
    private boolean submitOffFrustum(Entity entity, Vec3d cameraPos, float tickDelta,
                                     EntityRenderDispatcher dispatcher,
                                     RemixEntitySubmitter submitter) {
        boolean far = entity.squaredDistanceTo(cameraPos) > POSE_REUSE_MIN_SQ;
        if (far) {
            PoseSnapshot snapshot = poseSnapshots.get(entity.getId());
            // The stagger spreads refreshes across frames; the age test is what bounds
            // the lag. In-place captures record no pose, so an entity that has been on
            // screen for a while still holds the snapshot from before it entered the
            // frustum, and the stagger alone would replay that for up to three frames.
            boolean dueRefresh = snapshot == null
                    || sweepFrame - snapshot.frame >= POSE_REFRESH_FRAMES
                    || Math.floorMod(entity.getId() + sweepFrame, POSE_REFRESH_FRAMES) == 0;
            if (!dueRefresh && capturedIds.add(entity.getId())) {
                if (replayPose(entity, snapshot, tickDelta, submitter)) {
                    frameOffReused++;
                    return true;
                }
                // Replay failed, so fall through to a full capture. The id is already
                // claimed, so clear it or captureAndSubmit will treat it as a duplicate.
                capturedIds.remove(entity.getId());
            }
        }
        return captureAndSubmit(entity, dispatcher, tickDelta, submitter, far);
    }

    /**
     * Records a pose so later frames can resubmit this entity without evaluating
     * its model again.
     *
     * <p>Only cuboid-backed geometry can be replayed: a cuboid part names the
     * immutable {@code ModelPart.Cuboid} it came from, so it can be rebuilt at any
     * time, while a baked item or map part exists only as captured vertices in a
     * pooled consumer that is recycled the moment the next entity renders. An
     * entity carrying any of the latter is skipped rather than half-snapshotted.
     */
    private void snapshotPose(Entity entity, EntityCaptureResult result) {
        List<RemixVertexConsumerProvider.CapturedLayer> layers = result.layers();
        // Check replayability before touching the stored pose, so a failed refresh leaves
        // no half-written snapshot behind.
        for (RemixVertexConsumerProvider.CapturedLayer layer : layers) {
            RemixCapturingConsumer consumer = layer.consumer();
            for (int p = 0, n = consumer.getPartCount(); p < n; p++) {
                if (consumer.getPartSides(p) == null) {
                    // An entity can stop being snapshottable, by picking up an item for
                    // instance. Any pose recorded before that is now unreachable geometry, and
                    // leaving it would have refresh frames capture the new pose while the
                    // frames between them replayed the old one.
                    poseSnapshots.remove(entity.getId());
                    return;
                }
            }
        }

        PoseSnapshot snapshot = poseSnapshots.get(entity.getId());
        if (snapshot == null) {
            snapshot = new PoseSnapshot();
            poseSnapshots.put(entity.getId(), snapshot);
        }
        snapshot.resizeLayers(layers.size());
        for (int i = 0; i < layers.size(); i++) {
            RemixVertexConsumerProvider.CapturedLayer layer = layers.get(i);
            RemixCapturingConsumer consumer = layer.consumer();
            SnapshotLayer snapLayer = snapshot.layers[i];
            snapLayer.renderLayer = layer.renderLayer();
            snapLayer.layerIndex = layer.layerIndex();
            int partCount = consumer.getPartCount();
            snapLayer.resize(partCount);
            for (int p = 0; p < partCount; p++) {
                SnapshotPart part = snapLayer.parts[p];
                part.sides = consumer.getPartSides(p);
                part.sprite = consumer.getPartSprite(p);
                part.tint = consumer.getPartTint(p);
                part.bone = consumer.getPartBone(p);
            }
        }
        // The pass reuses one bone array across entities, so this has to be a copy.
        int boneFloats = result.boneCount() * 12;
        if (snapshot.boneData.length < boneFloats) {
            snapshot.boneData = new float[boneFloats];
        }
        System.arraycopy(result.boneData(), 0, snapshot.boneData, 0, boneFloats);
        snapshot.boneCount = result.boneCount();
        snapshot.typeKey = result.entityTypeKey();
        snapshot.frame = sweepFrame;
    }

    /**
     * Resubmits an entity from a recorded pose, with its world transform taken
     * fresh so it stays exactly where it is.
     *
     * @return false if the pose could not be replayed, in which case the caller
     *         must fall back to a full capture; true once the entity is handled,
     *         whether or not its submit reached the scene
     */
    private boolean replayPose(Entity entity, PoseSnapshot snapshot, float tickDelta,
                               RemixEntitySubmitter submitter) {
        replayProvider.beginEntity();
        for (int i = 0; i < snapshot.layerCount; i++) {
            SnapshotLayer layer = snapshot.layers[i];
            VertexConsumer buffer = replayProvider.getBuffer(layer.renderLayer);
            if (!(buffer instanceof RemixCapturingConsumer consumer)) {
                replayProvider.endEntity();
                return false;
            }
            // Keep the ordinal the layer was first given: it scales the shell offset
            // baked into the mesh, so replaying under a different one would key a
            // different mesh and separate the layer from the body it belongs to.
            consumer.setLayerIndex(layer.layerIndex);
            for (int p = 0; p < layer.partCount; p++) {
                SnapshotPart part = layer.parts[p];
                consumer.replayCuboidPart(part.sides, part.tint, part.bone, part.sprite);
            }
        }

        List<RemixVertexConsumerProvider.CapturedLayer> layers = replayProvider.endEntity();
        if (layers.isEmpty()) {
            return false;
        }
        EntityCaptureResult result = new EntityCaptureResult(
                entity,
                entity.getLerpedPos(tickDelta),
                MathHelper.lerp(tickDelta, entity.prevYaw, entity.getYaw()),
                layers,
                snapshot.boneData,
                snapshot.boneCount,
                snapshot.typeKey,
                false);
        if (!submitToScene(entity, result, submitter)) {
            // The pose replayed and the scene rejected it. A full capture would only
            // resubmit the same geometry, so the entity stays claimed and absent.
            return true;
        }

        // A replayed entity is in the scene exactly as a captured one is, so it has
        // to count the same. Only the capture time differs, which is the point.
        frameEntityCount++;
        frameLayerCount += layers.size();
        frameBoneCount += snapshot.boneCount;
        if (TYPE_SUMMARY_ENABLED) {
            int[] typeStats = typeCounts.computeIfAbsent(snapshot.typeKey, k -> new int[3]);
            typeStats[0]++;
            typeStats[1] += layers.size();
            typeStats[2] += snapshot.boneCount;
        }
        return true;
    }

    private double getEffectiveEntityRenderDistance() {
        double minecraftDistance = ClientViewDistance.blocks();
        int cap = entityCullBlocks;
        return cap > 0 ? Math.min(minecraftDistance, cap) : minecraftDistance;
    }

    /**
     * Renders a single entity through vanilla's pipeline and captures the result.
     *
     * <p>The entity is dispatched at the origin so the captured rigid transforms
     * carry only model and pivot rotations. The world position rides on the Remix
     * instance transform instead, so it is applied exactly once.
     */
    private EntityCaptureResult renderOneEntity(Entity entity,
                                                 EntityRenderDispatcher dispatcher,
                                                 float tickDelta,
                                                 boolean firstPersonLocalPlayer) {
        Vec3d entityPos = entity.getLerpedPos(tickDelta);
        float yaw = MathHelper.lerp(tickDelta, entity.prevYaw, entity.getYaw());

        // Light is discarded by the capture; any value reaches the same result.
        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;

        provider.beginEntity();
        EntityRenderContext ctx = EntityRenderContext.get();

        sharedMatrices.push();
        try {
            dispatcher.render(entity, 0.0, 0.0, 0.0, yaw, tickDelta, sharedMatrices, provider, light);
        } finally {
            // Both reset even if a renderer threw mid-traversal, which skips the
            // dispatcher's RETURN hook: the identity root returns every pooled
            // entry, and a capture left open would make the next one look nested
            // and keep its bone numbering. The bone count stays readable for the
            // snapshot below.
            sharedMatrices.reset();
            ctx.reset();
        }

        List<RemixVertexConsumerProvider.CapturedLayer> layers = provider.endEntity();

        if (layers.isEmpty()) {
            return null;
        }

        // One reusable buffer suffices: submission consumes the bone data before
        // the next entity renders over it.
        reusableBoneData = ctx.snapshotBoneTransforms(reusableBoneData);
        float[] boneData = reusableBoneData;
        int boneCount = ctx.getBoneCount();

        String typeKey = entityTypeKeyCache.computeIfAbsent(entity.getType(),
                t -> Registries.ENTITY_TYPE.getId(t).toString());

        return new EntityCaptureResult(
            entity,
            entityPos,
            yaw,
            layers,
            boneData,
            boneCount,
            typeKey,
            firstPersonLocalPlayer
        );
    }

    private static boolean isFirstPersonLocalPlayer(MinecraftClient client, Entity entity) {
        return entity == client.player && client.options.getPerspective().isFirstPerson();
    }

    // ---- Result container ----

    /**
     * The captured result of rendering one owner's model through vanilla's pipeline:
     * everything the submitter needs to build rigid meshes and retained instances.
     *
     * @param owner     the game object whose parts these are. Retained instances are
     *                  keyed on it by identity, so it is the object itself rather than
     *                  a numeric ID the game may recycle.
     * @param worldPos  the owner's world position, from which the world-position
     *                  submit overloads build the root the captured pivot transforms
     *                  compose onto. A caller that supplies its own root passes
     *                  {@link Vec3d#ZERO}.
     * @param yaw       the owner's interpolated yaw at capture, in degrees
     * @param layers    captured vertices grouped by RenderLayer and texture
     * @param boneData  flat float array: 12 floats per bone (3 rows x 4 cols).
     *                  Layout per bone matches Transform's set(row, col) order.
     * @param boneCount number of bones (boneData.length / 12)
     * @param entityTypeKey the owner's registry id, or a label the capturing pass
     *                  chooses, under which per-type statistics are grouped
     * @param firstPersonLocalPlayer whether these are the local player's own body
     *                  parts while the camera is in first person; they carry the
     *                  third-person player categories so Remix can treat the body
     *                  the camera sits inside differently from other players
     */
    public record EntityCaptureResult(
        Object owner,
        Vec3d worldPos,
        float yaw,
        List<RemixVertexConsumerProvider.CapturedLayer> layers,
        float[] boneData,
        int boneCount,
        String entityTypeKey,
        boolean firstPersonLocalPlayer
    ) { }
}

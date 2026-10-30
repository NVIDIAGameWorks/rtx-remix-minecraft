/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderStateOwner;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.BlockBreakingRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.cloud.WorldRendererCloudAccess;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.profiling.WorldRendererProfilingAccess;
import com.nvidia.rtxremix.minecraft.sky.SkyStarCapture;
import com.nvidia.rtxremix.minecraft.sky.WorldRendererStarAccess;
import com.nvidia.rtxremix.minecraft.terrain.WorldRendererTerrainAccess;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionBuilderRelease;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The level renderer's side of Remix capture: per-frame entity bookkeeping,
 * terrain profiling, and the terrain queries and rebuild requests behind
 * {@link WorldRendererTerrainAccess}.
 *
 * <p>Since 26.x a section's dirty state lives in the level extractor's
 * {@link SectionUpdateTracker}, region snapshots are taken during extraction,
 * and this renderer compiles the sections the extraction queued. Entities, block
 * entities and the breaking crack are captured where this renderer submits them;
 * sky, clouds, weather and particles each have their own renderer and mixin.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin
        implements WorldRendererCloudAccess, WorldRendererProfilingAccess,
        WorldRendererStarAccess, WorldRendererTerrainAccess {

    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$renderScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$compileScope = new ThreadLocal<>();

    @Shadow
    private @Nullable ViewArea viewArea;

    @Shadow
    private @Nullable SectionRenderDispatcher sectionRenderDispatcher;

    private ProfileScope rtxremix$profilerPhaseScope;

    @Override
    public int rtxremix$getCloudTicks() {
        // 26.x drives cloud drift from game time; LevelRenderer no longer counts ticks.
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? 0 : (int) level.getGameTime();
    }

    @Override
    public float[] rtxremix$copyStarVertices() {
        return SkyStarCapture.copyStarVertices();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void rtxremix$renderHead(
            GraphicsResourceAllocator resourceAllocator,
            boolean renderOutline,
            CameraRenderState cameraState,
            GpuBufferSlice terrainFog,
            Vector4f fogColor,
            boolean shouldRenderSky,
            boolean consistentDepthRequired,
            CallbackInfo ci) {
        rtxremix$openJfrOnly(rtxremix$renderScope, "WorldRenderer.render", "Java.WorldRenderer.render.ms");
        RtxRemixMod mod = RtxRemixMod.getInstance();
        if (mod != null && mod.getRenderer() != null) {
            mod.getRenderer().beginEntityFrame();
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void rtxremix$renderReturn(
            GraphicsResourceAllocator resourceAllocator,
            boolean renderOutline,
            CameraRenderState cameraState,
            GpuBufferSlice terrainFog,
            Vector4f fogColor,
            boolean shouldRenderSky,
            boolean consistentDepthRequired,
            CallbackInfo ci) {
        rtxremix$closeProfilerPhase();
        rtxremix$close(rtxremix$renderScope);
    }

    /**
     * Captures entities where vanilla submits them, instead of rendering them twice.
     *
     * <p>Replacing this submit keeps Minecraft in charge of extraction, animation,
     * feature layers and modded entity support, but evaluates the submits into the
     * Remix capture instead of vanilla's feature renderers, so the model is
     * evaluated once. Extraction is frustum culled, so entities the camera cannot
     * see are picked up afterwards by the Remix pass, which needs them for shadows
     * and reflections. A captured entity's glowing outline is not drawn.
     */
    @WrapOperation(
            method = "submitEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V"
            ))
    private void rtxremix$captureEntityInPlace(
            EntityRenderDispatcher dispatcher, EntityRenderState state, CameraRenderState camera,
            double x, double y, double z, PoseStack poseStack, SubmitNodeCollector collector,
            Operation<Void> original) {
        Entity entity = ((EntityRenderStateOwner) state).rtxremix$entity();
        RtxRemixMod mod = RtxRemixMod.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (entity != null && mod != null && mod.getRenderer() != null && client.level != null) {
            // The partial tick vanilla extracted this entity at.
            float tickDelta = client.getDeltaTracker().getGameTimeDeltaPartialTick(
                    !client.level.tickRateManager().isEntityFrozen(entity));
            if (mod.getRenderer().renderEntityInPlace(entity, state, tickDelta)) {
                return;
            }
        }
        original.call(dispatcher, state, camera, x, y, z, poseStack, collector);
    }

    /**
     * Captures block entities where vanilla submits them. The submit is replayed
     * on the capture's own block-local stack and collector.
     */
    @WrapOperation(
            method = "submitBlockEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"
            ))
    private void rtxremix$captureBlockEntityInPlace(
            BlockEntityRenderDispatcher dispatcher, BlockEntityRenderState state, PoseStack poseStack,
            SubmitNodeCollector collector, CameraRenderState camera, Operation<Void> original) {
        RtxRemixMod mod = RtxRemixMod.getInstance();
        RemixRenderer renderer = mod == null ? null : mod.getRenderer();
        ClientLevel level = Minecraft.getInstance().level;
        BlockEntity be = level == null ? null : level.getBlockEntity(state.blockPos);
        boolean captured = renderer != null
                && be != null
                && renderer.renderBlockEntityInPlace(be,
                        (captureMatrices, captureCollector) ->
                                original.call(dispatcher, state, captureMatrices, captureCollector, camera));
        if (!captured) {
            original.call(dispatcher, state, poseStack, collector, camera);
        }
    }

    /**
     * Captures the block-breaking crack where vanilla submits it, instead of letting it
     * fall into the OpenGL frame that Remix's image replaces.
     *
     * <p>Vanilla's camera-relative stack is not forwarded: the capture supplies an
     * identity stack and its own crack projection so the geometry stays block-local.
     * When Remix declines, vanilla submits as usual.
     */
    @WrapOperation(
            method = "submitBlockDestroyAnimation",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitBreakingBlockModel(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/util/List;IZ)V"
            ))
    private void rtxremix$captureBlockDamageInPlace(
            SubmitNodeCollector collector, PoseStack poseStack, List<BlockStateModelPart> parts,
            int progress, boolean translucent, Operation<Void> original,
            @Local BlockBreakingRenderState state) {
        RtxRemixMod mod = RtxRemixMod.getInstance();
        RemixRenderer renderer = mod == null ? null : mod.getRenderer();
        BlockPos pos = state.blockPos();
        boolean captured = renderer != null
                && renderer.renderBlockDamageInPlace(pos, progress, (captureMatrices, captureConsumer) -> {
                    captureMatrices.translate(state.blockState().getOffset(pos));
                    rtxremix$emitParts(captureMatrices.last(), parts, captureConsumer);
                });
        if (!captured) {
            original.call(collector, poseStack, parts, progress, translucent);
        }
    }

    private static final Direction[] rtxremix$DIRECTIONS = Direction.values();
    private final QuadInstance rtxremix$crackQuad = new QuadInstance();

    private void rtxremix$emitParts(PoseStack.Pose pose, List<BlockStateModelPart> parts, VertexConsumer consumer) {
        for (BlockStateModelPart part : parts) {
            for (Direction direction : rtxremix$DIRECTIONS) {
                for (BakedQuad quad : part.getQuads(direction)) {
                    consumer.putBakedQuad(pose, quad, rtxremix$crackQuad);
                }
            }
            for (BakedQuad quad : part.getQuads(null)) {
                consumer.putBakedQuad(pose, quad, rtxremix$crackQuad);
            }
        }
    }

    @Inject(method = "compileSections", at = @At("HEAD"))
    private void rtxremix$compileSectionsHead(CameraRenderState camera, CallbackInfo ci) {
        int queued = sectionRenderDispatcher == null ? -1 : sectionRenderDispatcher.getCompileQueueSize();
        if (queued >= 0) {
            ProfileScope.plot("Java.Chunk.toBatchCount", queued);
        }
        if (ProfileScope.isDeepProfilingEnabled()) {
            ProfileScope scope = ProfileScope.begin("WorldRenderer.updateChunks");
            if (queued >= 0) {
                scope.dirtyLeaves(queued);
            }
            rtxremix$push(rtxremix$compileScope, scope);
        }
    }

    @Inject(method = "compileSections", at = @At("RETURN"))
    private void rtxremix$compileSectionsReturn(CameraRenderState camera, CallbackInfo ci) {
        rtxremix$close(rtxremix$compileScope);
    }

    @Override
    public void rtxremix$closeProfilerPhaseForFabricEvent() {
        rtxremix$closeProfilerPhase();
    }

    private void rtxremix$closeProfilerPhase() {
        ProfileScope scope = rtxremix$profilerPhaseScope;
        if (scope == null) {
            return;
        }
        rtxremix$profilerPhaseScope = null;
        scope.close();
    }

    // ---- WorldRendererTerrainAccess ----

    @Override
    public void rtxremix$onNeighbourColumnInvalidated(int cx, int cz) {
        // The neighbour re-flag filter has no hook in 26.x: a chunk load re-flags
        // its neighbourhood through setSectionRangeDirty, which vanilla keeps.
    }

    @Override
    public LongSet rtxremix$builtSectionPositions() {
        LongOpenHashSet built = new LongOpenHashSet();
        if (viewArea == null) {
            return built;
        }
        ((ViewAreaAccessor) viewArea).rtxremix$sections().forEach(section -> {
            if (section.sectionMesh.get() != CompiledSectionMesh.UNCOMPILED) {
                built.add(section.getSectionNode());
            }
        });
        return built;
    }

    @Override
    public LongSet rtxremix$queueSectionsForRemix(LongSet sections) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (viewArea == null || sectionRenderDispatcher == null || level == null || sections.isEmpty()) {
            return LongSets.emptySet();
        }

        // Same gate as vanilla extraction: a section built before its light
        // is ready would bake bad light, and clearing its dirty flag below means
        // nothing re-flags it afterwards.
        LevelLightEngine lightingProvider = level.getLightEngine();

        ArrayList<SectionRenderDispatcher.RenderSection> loadedSections = new ArrayList<>();
        ((ViewAreaAccessor) viewArea).rtxremix$sections().forEach(section -> {
            long node = section.getSectionNode();
            if (!sections.contains(node)) {
                return;
            }
            int cx = SectionPos.x(node);
            int sy = SectionPos.y(node);
            int cz = SectionPos.z(node);
            LevelChunk worldChunk = level.getChunkSource().getChunk(cx, cz, false);
            if (worldChunk != null
                    && !worldChunk.getSection(worldChunk.getSectionIndexFromSectionY(sy)).hasOnlyAir()
                    && lightingProvider.lightOnInColumn(SectionPos.getZeroNode(cx, cz))) {
                loadedSections.add(section);
            }
        });

        // The compile queue is FIFO. Queue nearby sections first so visible
        // holes close promptly while the Remix scene repopulates.
        Camera camera = client.gameRenderer.mainCamera();
        double cameraX = camera.position().x;
        double cameraY = camera.position().y;
        double cameraZ = camera.position().z;
        loadedSections.sort(Comparator.comparingDouble(section -> {
            BlockPos origin = section.getRenderOrigin();
            double dx = origin.getX() + 8.0 - cameraX;
            double dy = origin.getY() + 8.0 - cameraY;
            double dz = origin.getZ() + 8.0 - cameraZ;
            return dx * dx + dy * dy + dz * dz;
        }));

        SectionUpdateTracker tracker = ((LevelExtractorAccessor) client.levelExtractor).rtxremix$sectionUpdateTracker();
        RenderRegionCache regionBuilder = new RenderRegionCache();
        LongOpenHashSet queued = new LongOpenHashSet(loadedSections.size());
        try {
            for (SectionRenderDispatcher.RenderSection section : loadedSections) {
                long node = section.getSectionNode();
                section.compileAsync(regionBuilder.createRegion(level, node));
                // Keep the next extraction from queueing this same section again.
                SectionUpdateTracker.SectionDirtyState dirty = tracker == null ? null : tracker.getDirtyState(node);
                if (dirty != null) {
                    dirty.setNotDirty();
                }
                queued.add(node);
            }
        } finally {
            ((RegionBuilderRelease) regionBuilder).rtxremix$releaseSnapshots();
        }
        return queued;
    }

    @Override
    public void rtxremix$scheduleResidentSectionRebuild(int cx, int sy, int cz) {
        if (viewArea == null
                || ((ViewAreaAccessor) viewArea).rtxremix$sections().getValue(cx, sy, cz) == null) {
            // A section that has left the window is rebuilt when it re-enters.
            return;
        }
        Minecraft.getInstance().levelExtractor.setSectionDirty(cx, sy, cz);
    }

    // ---- profiling helpers ----

    private static void rtxremix$openJfrOnly(
            ThreadLocal<ArrayDeque<ProfileScope>> holder,
            String phaseName,
            String plotName) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        rtxremix$push(holder, ProfileScope.beginJfrOnly(phaseName).plotOnClose(plotName));
    }

    private static void rtxremix$push(
            ThreadLocal<ArrayDeque<ProfileScope>> holder,
            ProfileScope scope) {
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            holder.set(stack);
        }
        stack.push(scope);
    }

    private static void rtxremix$close(ThreadLocal<ArrayDeque<ProfileScope>> holder) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            ProfileScope scope = stack.pop();
            if (stack.isEmpty()) {
                holder.remove();
            }
            scope.close();
        } else if (stack != null) {
            holder.remove();
        }
    }
}

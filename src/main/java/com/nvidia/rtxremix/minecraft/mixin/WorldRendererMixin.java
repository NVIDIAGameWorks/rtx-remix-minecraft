/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.weather.RemixWeatherRenderer;
import com.nvidia.rtxremix.minecraft.terrain.visibility.BuiltChunkStorageSectionAccess;
import com.nvidia.rtxremix.minecraft.terrain.visibility.BuiltChunkArrayIterator;
import com.nvidia.rtxremix.minecraft.terrain.capture.RegionBuilderRelease;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.terrain.WorldRendererTerrainAccess;
import com.nvidia.rtxremix.minecraft.terrain.NeighbourReflagFilter;
import com.nvidia.rtxremix.minecraft.terrain.ChunkBuilderBlockEntityAccess;
import com.nvidia.rtxremix.minecraft.sky.WorldRendererStarAccess;
import com.nvidia.rtxremix.minecraft.profiling.WorldRendererProfilingAccess;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.cloud.WorldRendererCloudAccess;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import java.util.SortedSet;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

/**
 * Reroutes vanilla's world render into the Remix capture while a Remix world is
 * active.
 *
 * <ul>
 *   <li>Entities, block entities and the block-breaking crack are captured where
 *       vanilla draws them, so each model is evaluated once rather than once per
 *       renderer.</li>
 *   <li>Vanilla's terrain layers, particle draw and cloud draw are cancelled,
 *       because Remix owns that geometry.</li>
 *   <li>Weather and the star buffer are handed to the Remix passes.</li>
 *   <li>Section rebuild scheduling is exposed to the terrain pipeline: the
 *       water-occlusion margin on block-update rebuilds and the chunk-load
 *       neighbour re-flag filter.</li>
 *   <li>The block-entity walk is narrowed to chunks that hold one.</li>
 *   <li>Every vanilla render phase, {@code setupTerrain} and
 *       {@code updateChunks} are labelled for Tracy and JFR.</li>
 * </ul>
 */
@Mixin(LevelRenderer.class)
public abstract class WorldRendererMixin
        implements WorldRendererCloudAccess, WorldRendererProfilingAccess,
        WorldRendererStarAccess, WorldRendererTerrainAccess {
    private static final int rtxremix$BLOCK_ENTITY_CHUNK_LIST_INITIAL_CAPACITY = 32_768;

    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$renderScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$setupTerrainScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$updateChunksScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$uploadScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$syncRebuildScope = new ThreadLocal<>();

    @Shadow
    private SectionRenderDispatcher sectionRenderDispatcher;

    @Shadow
    @Nullable
    private ViewArea viewArea;

    @Shadow
    @Nullable
    public ClientLevel level;

    @Shadow
    private int renderedEntities;

    @Shadow
    private int culledEntities;

    @Shadow
    private int ticks;

    @Shadow
    @Final
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    @Shadow
    @Final
    private Set<BlockEntity> globalBlockEntities;

    @Shadow
    @Final
    private Long2ObjectMap<SortedSet<BlockDestructionProgress>> destructionProgress;

    @Shadow
    protected abstract boolean shouldShowEntityOutlines();

    private ProfileScope rtxremix$profilerPhaseScope;
    private String rtxremix$profilerPhaseName;
    private int rtxremix$renderLayerCalls;
    private int rtxremix$renderLayerSkips;
    private int rtxremix$phaseRenderLayerCallsStart;
    private int rtxremix$phaseRenderLayerSkipsStart;
    private int rtxremix$blockEntityChunkScanCount = -1;
    private int rtxremix$blockEntityChunkCount = -1;
    private SectionRenderDispatcher.RenderSection[] rtxremix$blockEntityChunks =
            new SectionRenderDispatcher.RenderSection[rtxremix$BLOCK_ENTITY_CHUNK_LIST_INITIAL_CAPACITY];
    private int rtxremix$blockEntityChunkBufferUsed;
    private final BuiltChunkArrayIterator rtxremix$blockEntityChunkIterator = new BuiltChunkArrayIterator();
    private RemixWeatherRenderer rtxremix$weatherRenderer;
    private ResourceLocation rtxremix$weatherTexture;
    private float[] rtxremix$starVertices = new float[0];

    @Override
    public int rtxremix$getCloudTicks() {
        return ticks;
    }

    @Inject(method = "drawStars", at = @At("RETURN"))
    private void rtxremix$captureStarVertices(
            Tesselator tessellator,
            CallbackInfoReturnable<MeshData> cir) {
        MeshData builtBuffer = cir.getReturnValue();
        MeshData.DrawState parameters = builtBuffer.drawState();
        int positionOffset = parameters.format().getOffset(VertexFormatElement.POSITION);
        if (positionOffset < 0) {
            rtxremix$starVertices = new float[0];
            return;
        }

        int vertexCount = parameters.vertexCount();
        int stride = parameters.format().getVertexSize();
        ByteBuffer vertices = builtBuffer.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
        float[] positions = new float[vertexCount * 3];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int source = vertex * stride + positionOffset;
            int destination = vertex * 3;
            positions[destination] = vertices.getFloat(source);
            positions[destination + 1] = vertices.getFloat(source + Float.BYTES);
            positions[destination + 2] = vertices.getFloat(source + 2 * Float.BYTES);
        }
        rtxremix$starVertices = positions;
    }

    @Override
    public float[] rtxremix$copyStarVertices() {
        return rtxremix$starVertices.clone();
    }

    /**
     * Hidden water-top culling reads farther than vanilla's one-block rebuild
     * margin. Expand horizontal invalidation so a ceiling or fluid update
     * cannot leave a neighboring section's occlusion decision stale.
     */
    @Inject(method = "setBlocksDirty(IIIIII)V", at = @At("HEAD"), cancellable = true)
    private void rtxremix$expandWaterOcclusionRebuildMargin(
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
        if (!RtxRemixMod.isRtxEnabled()) {
            return;
        }

        int radius = Math.max(1, BlockQuadEmitter.getWaterTopOcclusionRadius());
        LevelRenderer renderer = (LevelRenderer) (Object) this;
        int minSectionX = SectionPos.blockToSectionCoord(minX - radius);
        int minSectionY = SectionPos.blockToSectionCoord(minY - 1);
        int minSectionZ = SectionPos.blockToSectionCoord(minZ - radius);
        int maxSectionX = SectionPos.blockToSectionCoord(maxX + radius);
        int maxSectionY = SectionPos.blockToSectionCoord(maxY + 1);
        int maxSectionZ = SectionPos.blockToSectionCoord(maxZ + radius);
        for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
            for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    renderer.setSectionDirty(sectionX, sectionY, sectionZ);
                }
            }
        }
        ci.cancel();
    }

    /**
     * Chunk-load and light-data re-flags enter here and nowhere else: ClientWorld's
     * scheduleBlockRenders(III) is a pure forwarder whose only callers are
     * ClientPlayNetworkHandler.scheduleRenderChunk and updateLighting. Block updates and
     * biome data use the other overloads and stay vanilla. Vanilla's 3x3x3 loop is replaced
     * wholesale because the rule must tell the loading column from its neighbours, which a
     * redirect of the inner per-section call cannot.
     */
    @Inject(method = "setSectionDirtyWithNeighbors(III)V", at = @At("HEAD"), cancellable = true)
    private void rtxremix$filterChunkLoadReflags(int cx, int sy, int cz, CallbackInfo ci) {
        if (!RtxRemixMod.isRtxEnabled() || !NeighbourReflagFilter.ENABLED || viewArea == null) {
            return;
        }
        ((BuiltChunkStorageSectionAccess) viewArea).rtxremix$flagLoadedSection(cx, sy, cz);
        ci.cancel();
    }

    @Override
    public void rtxremix$onNeighbourColumnInvalidated(int cx, int cz) {
        if (viewArea != null) {
            ((BuiltChunkStorageSectionAccess) viewArea).rtxremix$clearNeighbourSnapshotBits(cx, cz);
        }
    }

    @Override
    public LongSet rtxremix$builtSectionPositions() {
        LongOpenHashSet built = new LongOpenHashSet();
        if (viewArea == null) {
            return built;
        }
        for (SectionRenderDispatcher.RenderSection builtChunk : viewArea.sections) {
            if (builtChunk.getCompiled() == SectionRenderDispatcher.CompiledSection.UNCOMPILED) {
                continue;
            }
            BlockPos origin = builtChunk.getOrigin();
            built.add(SectionPos.asLong(
                    SectionPos.blockToSectionCoord(origin.getX()),
                    SectionPos.blockToSectionCoord(origin.getY()),
                    SectionPos.blockToSectionCoord(origin.getZ())));
        }
        return built;
    }

    @Override
    public LongSet rtxremix$queueSectionsForRemix(LongSet sections) {
        if (viewArea == null || sectionRenderDispatcher == null || level == null || sections.isEmpty()) {
            return LongSets.emptySet();
        }

        // Same gate as vanilla updateChunks: a section built before its light
        // is ready would bake bad light, and cancelRebuild below means nothing
        // re-flags it afterwards.
        LevelLightEngine lightingProvider = level.getLightEngine();

        ArrayList<SectionRenderDispatcher.RenderSection> loadedSections = new ArrayList<>();
        for (SectionRenderDispatcher.RenderSection builtChunk : viewArea.sections) {
            BlockPos origin = builtChunk.getOrigin();
            int cx = SectionPos.blockToSectionCoord(origin.getX());
            int sy = SectionPos.blockToSectionCoord(origin.getY());
            int cz = SectionPos.blockToSectionCoord(origin.getZ());
            if (!sections.contains(SectionPos.asLong(cx, sy, cz))) {
                continue;
            }
            LevelChunk worldChunk = level.getChunkSource().getChunk(cx, cz, false);
            if (worldChunk != null && !worldChunk.isSectionEmpty(sy)
                    && lightingProvider.lightOnInSection(SectionPos.of(cx, sy, cz))) {
                loadedSections.add(builtChunk);
            }
        }

        // ChunkBuilder's normal task queue is FIFO. Queue nearby sections first
        // so visible holes close promptly while the Remix scene repopulates.
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        double cameraX = camera.getPosition().x;
        double cameraY = camera.getPosition().y;
        double cameraZ = camera.getPosition().z;
        loadedSections.sort(Comparator.comparingDouble(builtChunk -> {
            BlockPos origin = builtChunk.getOrigin();
            double dx = origin.getX() + 8.0 - cameraX;
            double dy = origin.getY() + 8.0 - cameraY;
            double dz = origin.getZ() + 8.0 - cameraZ;
            return dx * dx + dy * dy + dz * dz;
        }));

        RenderRegionCache regionBuilder = new RenderRegionCache();
        LongOpenHashSet queued = new LongOpenHashSet(loadedSections.size());
        try {
            for (SectionRenderDispatcher.RenderSection builtChunk : loadedSections) {
                builtChunk.rebuildSectionAsync(sectionRenderDispatcher, regionBuilder);
                // Prevent WorldRenderer.updateChunks from queueing this same slot a
                // second time when it next walks vanilla's visible section list.
                builtChunk.setNotDirty();
                BlockPos origin = builtChunk.getOrigin();
                queued.add(SectionPos.asLong(
                        SectionPos.blockToSectionCoord(origin.getX()),
                        SectionPos.blockToSectionCoord(origin.getY()),
                        SectionPos.blockToSectionCoord(origin.getZ())));
            }
        } finally {
            ((RegionBuilderRelease) regionBuilder).rtxremix$releaseSnapshots();
        }
        return queued;
    }

    @Override
    public void rtxremix$scheduleResidentSectionRebuild(int cx, int sy, int cz) {
        ((BuiltChunkStorageSectionAccess) viewArea).rtxremix$scheduleRebuildIfResident(cx, sy, cz);
    }

    /**
     * Captures entities where vanilla renders them, instead of rendering them twice.
     *
     * <p>Vanilla evaluates the entity model here and writes it to OpenGL. Replacing
     * this call keeps Minecraft in charge of animation, feature layers and modded
     * entity support, but sends the geometry to the Remix capture provider instead,
     * so the model is evaluated once for both renderers.
     *
     * <p>This traversal is frustum culled, so entities the camera cannot see are
     * picked up afterwards by the Remix pass, which needs them for shadows and
     * reflections.
     */
    @WrapOperation(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderEntity(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V"
            ))
    private void rtxremix$captureEntityInPlace(
            LevelRenderer worldRenderer,
            Entity entity,
            double cameraX,
            double cameraY,
            double cameraZ,
            float tickDelta,
            PoseStack matrices,
            MultiBufferSource vertexConsumers,
            Operation<Void> original) {
        RtxRemixMod mod = RtxRemixMod.getInstance();
        boolean captured = mod != null
                && mod.getRenderer() != null
                && mod.getRenderer().renderEntityInPlace(entity, tickDelta);
        if (!captured) {
            // Remix is not drawing this entity, so vanilla's frame is what the user
            // sees and it keeps its blob shadow.
            original.call(worldRenderer, entity, cameraX, cameraY, cameraZ,
                    tickDelta, matrices, vertexConsumers);
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (!shouldShowEntityOutlines() || !client.shouldEntityAppearGlowing(entity)) {
            return;
        }
        // Vanilla hands an entity the outline provider only when the outline post-processor
        // is usable and the entity is outlined (glowing, or a player under the spectator
        // outlines key). That pass writes the framebuffer the outline shader reads, which
        // capturing for Remix does not feed, so vanilla still has to run for it. Its blob
        // shadow must not: the GL frame is composited wholesale over the traced scene, and
        // Remix already traces the real shadow, so the blob would double it.
        EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
        boolean hadShadows = ((EntityRenderDispatcherAccessor) dispatcher).rtxremix$getRenderShadows();
        dispatcher.setRenderShadow(false);
        try {
            original.call(worldRenderer, entity, cameraX, cameraY, cameraZ,
                    tickDelta, matrices, vertexConsumers);
        } finally {
            dispatcher.setRenderShadow(hadShadows);
        }
    }

    /**
     * Captures the block-breaking crack where vanilla renders it, instead of letting it
     * fall into the OpenGL frame that Remix's image replaces.
     *
     * <p>Vanilla's matrix stack and consumer are deliberately not forwarded: the stack
     * is camera-relative and the consumer writes into the GL effect buffer, while the
     * capture supplies an identity stack and its own crack projection so the geometry
     * stays block-local. When Remix declines, vanilla renders as usual.
     */
    @WrapOperation(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderBreakingTexture(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V"
            ))
    private void rtxremix$captureBlockDamageInPlace(
            BlockRenderDispatcher blockRenderManager,
            BlockState state,
            BlockPos pos,
            BlockAndTintGetter world,
            PoseStack matrices,
            VertexConsumer vertexConsumer,
            Operation<Void> original) {
        RtxRemixMod mod = RtxRemixMod.getInstance();
        RemixRenderer renderer = mod == null ? null : mod.getRenderer();
        boolean captured = renderer != null
                && renderer.renderBlockDamageInPlace(pos, rtxremix$blockBreakingStage(pos),
                        (captureMatrices, captureConsumer) -> original.call(
                                blockRenderManager, state, pos, world, captureMatrices, captureConsumer));
        if (!captured) {
            original.call(blockRenderManager, state, pos, world, matrices, vertexConsumer);
        }
    }

    /**
     * The highest destroy stage among the actors breaking this block, read back from the
     * progress map because the wrapped call receives only the consumer vanilla built for
     * that stage. Vanilla reaches the wrapped call only after finding this position's set
     * non-empty, on the same thread, so the entry is present here.
     */
    private int rtxremix$blockBreakingStage(BlockPos pos) {
        SortedSet<BlockDestructionProgress> progressions = destructionProgress.get(pos.asLong());
        return progressions.last().getProgress();
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void rtxremix$renderHead(
            DeltaTracker tickCounter,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightmapTextureManager,
            Matrix4f positionMatrix,
            Matrix4f projectionMatrix,
            CallbackInfo ci) {
        rtxremix$openJfrOnly(
                rtxremix$renderScope,
                "WorldRenderer.render",
                "Java.WorldRenderer.render.ms");
        rtxremix$renderLayerCalls = 0;
        rtxremix$renderLayerSkips = 0;
        rtxremix$blockEntityChunkScanCount = -1;
        rtxremix$blockEntityChunkCount = -1;
        rtxremix$transitionProfilerPhase("preamble");
        RtxRemixMod mod = RtxRemixMod.getInstance();
        if (mod != null && mod.getRenderer() != null) {
            mod.getRenderer().beginEntityFrame();
        }
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void rtxremix$renderReturn(
            DeltaTracker tickCounter,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightmapTextureManager,
            Matrix4f positionMatrix,
            Matrix4f projectionMatrix,
            CallbackInfo ci) {
        rtxremix$closeProfilerPhase();
        rtxremix$close(rtxremix$renderScope);
    }

    @WrapOperation(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V"
            ))
    private void rtxremix$mirrorVanillaProfilerPhase(
            ProfilerFiller profiler,
            String name,
            Operation<Void> original) {
        original.call(profiler, name);
        rtxremix$transitionProfilerPhase(name);
    }

    @Inject(method = "renderSectionLayer", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipVanillaTerrainLayer(
            RenderType renderLayer,
            double x,
            double y,
            double z,
            Matrix4f matrix4f,
            Matrix4f positionMatrix,
            CallbackInfo ci) {
        rtxremix$renderLayerCalls++;
        if (RtxRemixMod.isRemixWorldRendering() && rtxremix$isVanillaTerrainLayer(renderLayer)) {
            rtxremix$renderLayerSkips++;
            ci.cancel();
        }
    }

    @Redirect(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/particle/ParticleEngine;render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;F)V"
            ))
    private void rtxremix$skipVanillaParticleDraw(
            ParticleEngine particleManager,
            LightTexture lightmapTextureManager,
            Camera camera,
            float tickDelta) {
        if (!RtxRemixMod.isRemixWorldRendering()) {
            particleManager.render(lightmapTextureManager, camera, tickDelta);
        }
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipVanillaCloudDraw(
            PoseStack matrices,
            Matrix4f positionMatrix,
            Matrix4f projectionMatrix,
            float tickDelta,
            double cameraX,
            double cameraY,
            double cameraZ,
            CallbackInfo ci) {
        if (RtxRemixMod.isRemixWorldRendering()
                && RtxRemixMod.getInstance()
                        .getRenderer()
                        .getCloudRenderer()
                        .shouldSuppressVanillaClouds()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSnowAndRain", at = @At("HEAD"))
    private void rtxremix$beginWeatherCapture(
            LightTexture lightmapTextureManager,
            float tickDelta,
            double cameraX,
            double cameraY,
            double cameraZ,
            CallbackInfo ci) {
        rtxremix$weatherRenderer = rtxremix$getWeatherRenderer();
        rtxremix$weatherTexture = null;
        if (rtxremix$weatherRenderer != null) {
            rtxremix$weatherRenderer.beginCaptureFrame(lightmapTextureManager);
        }
    }

    @WrapOperation(
            method = "renderSnowAndRain",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V"
            ))
    private void rtxremix$captureWeatherTexture(
            int textureUnit,
            ResourceLocation texture,
            Operation<Void> original) {
        if (rtxremix$weatherRenderer != null && textureUnit == 0) {
            rtxremix$weatherTexture = texture;
            return;
        }
        original.call(textureUnit, texture);
    }

    @WrapOperation(
            method = "renderSnowAndRain",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/BufferUploader;drawWithShader(Lcom/mojang/blaze3d/vertex/MeshData;)V"
            ))
    private void rtxremix$captureWeatherDraw(
            MeshData buffer,
            Operation<Void> original) {
        if (rtxremix$weatherRenderer == null) {
            original.call(buffer);
            return;
        }

        rtxremix$weatherRenderer.capture(
                buffer,
                rtxremix$weatherTexture);
    }

    @Inject(method = "renderSnowAndRain", at = @At("RETURN"))
    private void rtxremix$endWeatherCapture(
            LightTexture lightmapTextureManager,
            float tickDelta,
            double cameraX,
            double cameraY,
            double cameraZ,
            CallbackInfo ci) {
        rtxremix$weatherRenderer = null;
        rtxremix$weatherTexture = null;
    }

    @Redirect(
            method = "renderLevel",
            slice = @Slice(
                    from = @At(value = "CONSTANT", args = "stringValue=blockentities"),
                    to = @At(value = "CONSTANT", args = "stringValue=destroyProgress")),
            at = @At(
                    value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/objects/ObjectArrayList;iterator()Lit/unimi/dsi/fastutil/objects/ObjectListIterator;"
            ),
            require = 0)
    private ObjectListIterator<SectionRenderDispatcher.RenderSection> rtxremix$iterateBlockEntityChunks(
            ObjectArrayList<SectionRenderDispatcher.RenderSection> chunks) {
        if (!RtxRemixMod.isRemixWorldRendering()) {
            return chunks.iterator();
        }

        int visibleChunks = chunks.size();
        int blockEntityChunks = 0;
        for (int i = 0; i < visibleChunks; i++) {
            SectionRenderDispatcher.RenderSection chunk = chunks.get(i);
            if (chunk instanceof ChunkBuilderBlockEntityAccess access
                    && access.rtxremix$hasBlockEntities()) {
                if (blockEntityChunks == rtxremix$blockEntityChunks.length) {
                    rtxremix$blockEntityChunks = Arrays.copyOf(
                            rtxremix$blockEntityChunks,
                            rtxremix$blockEntityChunks.length + (rtxremix$blockEntityChunks.length >> 1));
                }
                rtxremix$blockEntityChunks[blockEntityChunks++] = chunk;
            }
        }
        if (blockEntityChunks < rtxremix$blockEntityChunkBufferUsed) {
            Arrays.fill(rtxremix$blockEntityChunks, blockEntityChunks, rtxremix$blockEntityChunkBufferUsed, null);
        }
        rtxremix$blockEntityChunkBufferUsed = blockEntityChunks;
        ProfileScope.plot("Java.WorldRenderer.blockEntityChunks", blockEntityChunks);
        ProfileScope.plot("Java.WorldRenderer.blockEntityChunkScan", visibleChunks);
        rtxremix$blockEntityChunkScanCount = visibleChunks;
        rtxremix$blockEntityChunkCount = blockEntityChunks;
        return rtxremix$blockEntityChunkIterator.reset(rtxremix$blockEntityChunks, blockEntityChunks);
    }

    @Inject(method = "setupRender", at = @At("HEAD"))
    private void rtxremix$setupTerrainHead(
            Camera camera,
            Frustum frustum,
            boolean hasForcedFrustum,
            boolean spectator,
            CallbackInfo ci) {
        rtxremix$openJfrOnly(
                rtxremix$setupTerrainScope,
                "WorldRenderer.setupTerrain",
                "Java.WorldRenderer.setupTerrain.ms");
    }

    @Inject(method = "setupRender", at = @At("RETURN"))
    private void rtxremix$setupTerrainReturn(
            Camera camera,
            Frustum frustum,
            boolean hasForcedFrustum,
            boolean spectator,
            CallbackInfo ci) {
        rtxremix$close(rtxremix$setupTerrainScope);
    }

    @Inject(method = "compileSections", at = @At("HEAD"))
    private void rtxremix$updateChunksHead(Camera camera, CallbackInfo ci) {
        int installCount = rtxremix$chunksToUpload();
        int dirtyLeaves = rtxremix$chunksToBatch();
        if (installCount >= 0) {
            ProfileScope.plot("Java.Chunk.toUploadCount", installCount);
        }
        if (dirtyLeaves >= 0) {
            ProfileScope.plot("Java.Chunk.toBatchCount", dirtyLeaves);
        }
        rtxremix$open(
                rtxremix$updateChunksScope,
                "WorldRenderer.updateChunks",
                installCount,
                dirtyLeaves);
    }

    /** Vanilla's builder is a local of updateChunks; captured at construction, released at return. */
    private RenderRegionCache rtxremix$frameRegionBuilder;

    @WrapOperation(
            method = "compileSections",
            at = @At(value = "NEW", target = "()Lnet/minecraft/client/renderer/chunk/RenderRegionCache;"))
    private RenderRegionCache rtxremix$captureFrameRegionBuilder(Operation<RenderRegionCache> original) {
        RenderRegionCache builder = original.call();
        rtxremix$frameRegionBuilder = builder;
        return builder;
    }

    /**
     * Drops the builder's references so the snapshots it cached can be recycled once their
     * rebuild tasks have run. See {@link ChunkRendererRegionBuilderMixin}.
     */
    @Inject(method = "compileSections", at = @At("RETURN"))
    private void rtxremix$updateChunksReturn(Camera camera, CallbackInfo ci) {
        RenderRegionCache builder = rtxremix$frameRegionBuilder;
        rtxremix$frameRegionBuilder = null;
        if (builder != null) {
            ((RegionBuilderRelease) builder).rtxremix$releaseSnapshots();
        }
        rtxremix$close(rtxremix$updateChunksScope);
    }

    @Inject(
            method = "compileSections",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;rebuildSectionSync(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;Lnet/minecraft/client/renderer/chunk/RenderRegionCache;)V"
            ),
            require = 0)
    private void rtxremix$beforeSyncRebuild(Camera camera, CallbackInfo ci) {
        rtxremix$open(
                rtxremix$syncRebuildScope,
                "WorldRenderer.updateChunks.build_near_sync",
                -1,
                rtxremix$chunksToBatch());
    }

    @Inject(
            method = "compileSections",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;rebuildSectionSync(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;Lnet/minecraft/client/renderer/chunk/RenderRegionCache;)V",
                    shift = At.Shift.AFTER
            ),
            require = 0)
    private void rtxremix$afterSyncRebuild(Camera camera, CallbackInfo ci) {
        rtxremix$close(rtxremix$syncRebuildScope);
    }

    @Inject(
            method = "compileSections",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;uploadAllPendingUploads()V"
            ),
            require = 0)
    private void rtxremix$beforeChunkUpload(Camera camera, CallbackInfo ci) {
        rtxremix$open(
                rtxremix$uploadScope,
                "WorldRenderer.updateChunks.upload",
                rtxremix$chunksToUpload(),
                -1);
    }

    @Inject(
            method = "compileSections",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;uploadAllPendingUploads()V",
                    shift = At.Shift.AFTER
            ),
            require = 0)
    private void rtxremix$afterChunkUpload(Camera camera, CallbackInfo ci) {
        rtxremix$close(rtxremix$uploadScope);
    }

    private int rtxremix$chunksToUpload() {
        return sectionRenderDispatcher == null ? -1 : sectionRenderDispatcher.getToUpload();
    }

    private int rtxremix$chunksToBatch() {
        return sectionRenderDispatcher == null ? -1 : sectionRenderDispatcher.getToBatchCount();
    }

    private void rtxremix$transitionProfilerPhase(String vanillaPhaseName) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            rtxremix$closeProfilerPhase();
            return;
        }
        rtxremix$closeProfilerPhase();
        rtxremix$profilerPhaseName = "WorldRenderer.phase." + rtxremix$sanitizePhase(vanillaPhaseName);
        rtxremix$phaseRenderLayerCallsStart = rtxremix$renderLayerCalls;
        rtxremix$phaseRenderLayerSkipsStart = rtxremix$renderLayerSkips;
        rtxremix$profilerPhaseScope = ProfileScope.begin(rtxremix$profilerPhaseName)
                .plotOnClose("Java." + rtxremix$profilerPhaseName + ".ms");
    }

    private void rtxremix$closeProfilerPhase() {
        ProfileScope scope = rtxremix$profilerPhaseScope;
        if (scope == null) {
            return;
        }
        rtxremix$profilerPhaseScope = null;
        rtxremix$annotateProfilerPhase(scope);
        scope.close();
    }

    @Override
    public void rtxremix$closeProfilerPhaseForFabricEvent() {
        rtxremix$closeProfilerPhase();
    }

    private void rtxremix$annotateProfilerPhase(ProfileScope scope) {
        if (visibleSections != null) {
            int visibleChunks = visibleSections.size();
            scope.visibleChunkCount(visibleChunks);
            scope.captureCount(visibleChunks);
        }
        scope.entityCount(renderedEntities);
        scope.blockEntityCount(culledEntities);
        scope.blockCount(culledEntities);
        scope.noCullBlockEntityCount(rtxremix$noCullBlockEntityCount());
        scope.blockBreakingCount(destructionProgress == null ? -1 : destructionProgress.size());
        scope.renderLayerCalls(rtxremix$renderLayerCalls - rtxremix$phaseRenderLayerCallsStart);
        scope.renderLayerSkips(rtxremix$renderLayerSkips - rtxremix$phaseRenderLayerSkipsStart);
        if ("WorldRenderer.phase.blockentities".equals(rtxremix$profilerPhaseName)) {
            scope.scannedChunkCount(rtxremix$blockEntityChunkScanCount);
            scope.blockEntityChunkCount(rtxremix$blockEntityChunkCount);
        }
    }

    private int rtxremix$noCullBlockEntityCount() {
        if (globalBlockEntities == null) {
            return -1;
        }
        synchronized (globalBlockEntities) {
            return globalBlockEntities.size();
        }
    }

    private static String rtxremix$sanitizePhase(String phase) {
        if (phase == null || phase.isBlank()) {
            return "unknown";
        }
        return phase.replace(' ', '_');
    }

    private static void rtxremix$openJfrOnly(
            ThreadLocal<ArrayDeque<ProfileScope>> holder,
            String phaseName,
            String plotName) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        rtxremix$push(holder, ProfileScope.beginJfrOnly(phaseName).plotOnClose(plotName));
    }

    private static void rtxremix$open(
            ThreadLocal<ArrayDeque<ProfileScope>> holder,
            String phaseName,
            int installCount,
            int dirtyLeaves) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        ProfileScope scope = ProfileScope.begin(phaseName);
        if (installCount >= 0) {
            scope.installCount(installCount);
        }
        if (dirtyLeaves >= 0) {
            scope.dirtyLeaves(dirtyLeaves);
        }
        rtxremix$push(holder, scope);
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

    private static boolean rtxremix$isVanillaTerrainLayer(RenderType renderLayer) {
        return renderLayer == RenderType.solid()
                || renderLayer == RenderType.cutoutMipped()
                || renderLayer == RenderType.cutout()
                || renderLayer == RenderType.translucent()
                || renderLayer == RenderType.tripwire();
    }

    private static RemixWeatherRenderer rtxremix$getWeatherRenderer() {
        if (!RtxRemixMod.isRemixWorldRendering()) {
            return null;
        }
        return RtxRemixMod.getInstance().getRenderer().getWeatherRenderer();
    }
}

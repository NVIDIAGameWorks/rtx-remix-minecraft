/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionBlockData;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockDataQueue;
import com.nvidia.rtxremix.minecraft.terrain.VanillaSectionBuilds;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.chunk.ChunkRendererRegion;
import net.minecraft.client.render.chunk.BlockBufferAllocatorStorage;
import net.minecraft.client.render.chunk.SectionBuilder;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.BlockRenderView;
import com.mojang.blaze3d.systems.VertexSorter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts chunk section builds to capture block/fluid mesh data for
 * RTX Remix <b>inline</b>, piggybacking on MC's own block loop instead
 * of running a separate pass.
 *
 * <ul>
 *   <li>{@code getBlockState} redirect: captures opacity bitmap,
 *       face masks, and light entries for every block, and emits the
 *       block's model quads directly to the Remix interleaved buffer.</li>
 *   <li>{@code getRenderType} redirect: reports every block invisible while
 *       capturing, so vanilla skips its layer buffers, matrix stack, and
 *       {@code renderBlock} for geometry that is never drawn.</li>
 *   <li>{@code renderFluid} redirect: emits fluid quads directly,
 *       skipping GL vertex generation.</li>
 *   <li>RETURN inject: finalises the section mesh data and enqueues it
 *       for the render thread.</li>
 * </ul>
 */
@Mixin(SectionBuilder.class)
public class SectionBuilderMixin {

    private static final ThreadLocal<ProfileScope> rtxremix$tracyOuter = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> rtxremix$tracyBlockLoop = new ThreadLocal<>();
    private static final ThreadLocal<BlockDataQueue.CaptureToken> rtxremix$blockDataCaptureToken = new ThreadLocal<>();

    /**
     * Whether this build() is capturing for Remix, latched once at HEAD.
     *
     * <p>{@code rtxEnabled} is volatile so worker threads see a toggle promptly,
     * which means every redirect below would otherwise read it independently and
     * a toggle could land <em>between</em> two of them inside one build, routing
     * some blocks of a section into the Remix emitter and the rest into vanilla's
     * GL path. Reading one latched value keeps a build wholly in one mode;
     * {@code setRtxEnabled} forces a WorldRenderer.reload(), so sections built
     * in the losing mode are rebuilt immediately afterwards.
     *
     * <p>The latch is taken through {@link VanillaSectionBuilds}: a build that
     * latched capture off reads client light on this worker, and the client light
     * storage may publish its live map only while no such build is running.
     */
    private static final ThreadLocal<Boolean> rtxremix$captureForBuild =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Latched RTX capture state for the build in progress on this thread. */
    private static boolean rtxremix$capturing() {
        return rtxremix$captureForBuild.get();
    }

    @Shadow
    private BlockRenderManager blockRenderManager;

    // HEAD: init per-section emission state
    @Inject(method = "build", at = @At("HEAD"))
    private void rtxremix$onBuildHead(
            ChunkSectionPos sectionPos,
            ChunkRendererRegion renderRegion,
            VertexSorter vertexSorter,
            BlockBufferAllocatorStorage allocatorStorage,
            CallbackInfoReturnable<SectionBuilder.RenderData> cir) {

        // Latched before any early return below, and unconditionally, so a value
        // left over from this thread's previous build cannot decide this one.
        boolean capturing = VanillaSectionBuilds.beginBuild(RtxRemixMod::isRtxEnabled);
        rtxremix$captureForBuild.set(capturing);
        if (!capturing) return;

        rtxremix$blockDataCaptureToken.set(BlockDataQueue.beginCapture(sectionPos.getX(), sectionPos.getZ()));
        rtxremix$tracyOuter.set(ProfileScope.begin("SectionBuilder"));
        rtxremix$tracyBlockLoop.set(null);

        if (renderRegion == null) return;

        BlockTextureManager tex = null;
        RtxRemixMod mod = RtxRemixMod.getInstance();
        if (mod != null && mod.getRenderer() != null) {
            tex = mod.getRenderer().getTextureManager();
        }

        BlockQuadEmitter.threadLocal().beginSection(sectionPos, tex, this.blockRenderManager);
        rtxremix$tracyBlockLoop.set(ProfileScope.begin("block loop"));
    }

    // Capture each block before vanilla checks opacity or selects a render path.
    @Redirect(method = "build",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/render/chunk/ChunkRendererRegion;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"))
    private BlockState rtxremix$captureBlock(ChunkRendererRegion world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        BlockQuadEmitter emitter = BlockQuadEmitter.current();
        if (emitter != null && emitter.isActive()) {
            emitter.processBlock(state, world, pos, state.isOpaqueFullCube());
            // Vanilla hands only MODEL blocks to renderBlock; ENTITYBLOCK_ANIMATED blocks
            // belong to their block-entity renderer.
            if (BlockQuadEmitter.getRenderTypeCached(state) == BlockRenderType.MODEL) {
                emitter.emitBlock(state, pos, world);
            }
        }
        return state;
    }

    // While capturing, the block's quads were already emitted above, so vanilla's raster
    // path for it (layer buffer, matrix stack, renderBlock) is dead work. Reporting the
    // block invisible makes vanilla skip all of it.
    @Redirect(method = "build",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/block/BlockState;getRenderType()Lnet/minecraft/block/BlockRenderType;"))
    private BlockRenderType rtxremix$renderTypeForVanilla(BlockState state) {
        return rtxremix$capturing()
                ? BlockRenderType.INVISIBLE
                : BlockQuadEmitter.getRenderTypeCached(state);
    }

    // renderFluid: emit fluid quads for Remix, skip GL
    @Redirect(method = "build",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/render/block/BlockRenderManager;renderFluid(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/world/BlockRenderView;Lnet/minecraft/client/render/VertexConsumer;Lnet/minecraft/block/BlockState;Lnet/minecraft/fluid/FluidState;)V"))
    private void rtxremix$redirectFluid(BlockRenderManager brm,
            BlockPos pos, BlockRenderView world, VertexConsumer consumer,
            BlockState blockState, FluidState fluidState) {
        if (rtxremix$capturing()) {
            BlockQuadEmitter emitter = BlockQuadEmitter.threadLocal();
            if (!emitter.isActive()) return;
            emitter.emitFluid(pos, world, fluidState);
        } else {
            brm.renderFluid(pos, world, consumer, blockState, fluidState);
        }
    }

    // RETURN: finalise mesh data and enqueue
    @Inject(method = "build", at = @At("RETURN"))
    private void rtxremix$onBuildReturn(
            ChunkSectionPos sectionPos,
            ChunkRendererRegion renderRegion,
            VertexSorter vertexSorter,
            BlockBufferAllocatorStorage allocatorStorage,
            CallbackInfoReturnable<SectionBuilder.RenderData> cir) {

        try {
            BlockQuadEmitter emitter = BlockQuadEmitter.threadLocal();
            if (!emitter.isActive()) return;

            ProfileScope blockScope = rtxremix$tracyBlockLoop.get();
            if (blockScope != null) blockScope.close();

            // The token is set at build HEAD before beginSection, so an active emitter
            // implies one exists.
            SectionBlockData blockData = emitter.finishSection(sectionPos);
            BlockDataQueue.enqueue(blockData, rtxremix$blockDataCaptureToken.get());
        } finally {
            ProfileScope outerScope = rtxremix$tracyOuter.get();
            if (outerScope != null) outerScope.close();
            rtxremix$tracyOuter.remove();
            rtxremix$tracyBlockLoop.remove();
            rtxremix$blockDataCaptureToken.remove();
            // A build that throws skips this inject and stays counted; vanilla turns a
            // section build exception into a client crash, so the stale count cannot
            // outlive the session.
            VanillaSectionBuilds.endBuild(rtxremix$capturing());
            rtxremix$captureForBuild.remove();
        }
    }
}

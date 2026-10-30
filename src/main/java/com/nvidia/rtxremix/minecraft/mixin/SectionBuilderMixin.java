/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionBlockData;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockDataQueue;
import com.nvidia.rtxremix.minecraft.terrain.VanillaSectionBuilds;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import org.spongepowered.asm.mixin.Final;
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
 *   <li>{@code getBlockState} wrap: captures opacity bitmap, face masks, and
 *       light entries for every cell, air included, and emits the block's
 *       model quads directly to the Remix interleaved buffer. Since 26.x the
 *       loop's only per-cell call that still sees air is this read.</li>
 *   <li>{@code getRenderShape} redirect: reports every block invisible while
 *       capturing, so vanilla skips its layer buffers and
 *       {@code tesselateBlock} for geometry that is never drawn. Fabric API
 *       redirects {@code tesselateBlock} itself, so it is left alone.</li>
 *   <li>{@code FluidRenderer.tesselate} wrap: emits fluid quads directly,
 *       skipping GL vertex generation.</li>
 *   <li>RETURN inject: finalises the section mesh data and enqueues it
 *       for the render thread.</li>
 * </ul>
 */
@Mixin(SectionCompiler.class)
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
     * {@code setRtxEnabled} forces a renderer reload, so sections built
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

    @Shadow @Final
    private BlockStateModelSet blockModelSet;

    @Shadow @Final
    private FluidStateModelSet fluidModelSet;

    // HEAD: init per-section emission state
    @Inject(method = "compile", at = @At("HEAD"))
    private void rtxremix$onBuildHead(
            SectionPos sectionPos,
            RenderSectionRegion renderRegion,
            VertexSorting vertexSorter,
            SectionBufferBuilderPack allocatorStorage,
            CallbackInfoReturnable<SectionCompiler.Results> cir) {

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

        boolean cutoutLeaves = Minecraft.getInstance().options.cutoutLeaves().get();
        BlockQuadEmitter.threadLocal().beginSection(
                sectionPos, tex, this.blockModelSet, this.fluidModelSet, cutoutLeaves);
        rtxremix$tracyBlockLoop.set(ProfileScope.begin("block loop"));
    }

    // The block loop's read of each cell: capture opacity / face masks / lights / quads
    @WrapOperation(method = "compile",
                   at = @At(value = "INVOKE",
                            target = "Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState rtxremix$captureBlock(RenderSectionRegion region, BlockPos pos, Operation<BlockState> original) {
        BlockState state = original.call(region, pos);
        BlockQuadEmitter emitter = BlockQuadEmitter.current();
        if (emitter != null && emitter.isActive()) {
            emitter.processBlock(state, region, pos, state.isSolidRender());
            // Vanilla tessellates only MODEL blocks; ENTITYBLOCK_ANIMATED blocks
            // belong to their block-entity renderer.
            if (BlockQuadEmitter.getRenderTypeCached(state) == RenderShape.MODEL) {
                emitter.emitBlock(state, pos, region);
            }
        }
        return state;
    }

    // While capturing, the block's quads were already emitted above, so vanilla's raster
    // path for it (layer buffer, tesselateBlock) is dead work. Reporting the
    // block invisible makes vanilla skip all of it.
    @Redirect(method = "compile",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;"))
    private RenderShape rtxremix$renderTypeForVanilla(BlockState state) {
        return rtxremix$capturing()
                ? RenderShape.INVISIBLE
                : BlockQuadEmitter.getRenderTypeCached(state);
    }

    // Fluid tessellation: emit fluid quads for Remix, skip GL
    @WrapOperation(method = "compile",
                   at = @At(value = "INVOKE",
                            target = "Lnet/minecraft/client/renderer/block/FluidRenderer;tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"))
    private void rtxremix$redirectFluid(FluidRenderer renderer,
            BlockAndTintGetter world, BlockPos pos, FluidRenderer.Output output,
            BlockState blockState, FluidState fluidState, Operation<Void> original) {
        if (rtxremix$capturing()) {
            BlockQuadEmitter emitter = BlockQuadEmitter.threadLocal();
            if (!emitter.isActive()) return;
            emitter.emitFluid(pos, world, fluidState);
        } else {
            original.call(renderer, world, pos, output, blockState, fluidState);
        }
    }

    // RETURN: finalise mesh data and enqueue
    @Inject(method = "compile", at = @At("RETURN"))
    private void rtxremix$onBuildReturn(
            SectionPos sectionPos,
            RenderSectionRegion renderRegion,
            VertexSorting vertexSorter,
            SectionBufferBuilderPack allocatorStorage,
            CallbackInfoReturnable<SectionCompiler.Results> cir) {

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

/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionBuildCapture;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
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
 *   <li>{@code isOpaqueFullCube} redirect: captures opacity bitmap,
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
@Mixin(SectionCompiler.class)
public class SectionBuilderMixin {
    private static final String rtxremix$COMPILE_METHOD =
            "compile(Lnet/minecraft/core/SectionPos;"
                    + "Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;"
                    + "Lcom/mojang/blaze3d/vertex/VertexSorting;"
                    + "Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)"
                    + "Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;";

    @Shadow
    private BlockRenderDispatcher blockRenderer;

    // HEAD: init per-section emission state
    @Inject(method = rtxremix$COMPILE_METHOD, at = @At("HEAD"))
    private void rtxremix$onBuildHead(
            SectionPos sectionPos,
            RenderChunkRegion renderRegion,
            VertexSorting vertexSorter,
            SectionBufferBuilderPack allocatorStorage,
            CallbackInfoReturnable<SectionCompiler.Results> cir) {
        SectionBuildCapture.begin(sectionPos, renderRegion, this.blockRenderer);
    }

    // isOpaqueFullCube: capture opacity / face masks / lights / quads
    @Redirect(method = rtxremix$COMPILE_METHOD,
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/world/level/block/state/BlockState;isSolidRender(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean rtxremix$captureBlock(BlockState state, BlockGetter world, BlockPos pos) {
        return SectionBuildCapture.captureBlock(state, world, pos);
    }

    // While capturing, the block's quads were already emitted above, so vanilla's raster
    // path for it (layer buffer, matrix stack, renderBlock) is dead work. Reporting the
    // block invisible makes vanilla skip all of it.
    @Redirect(method = rtxremix$COMPILE_METHOD,
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;"))
    private RenderShape rtxremix$renderTypeForVanilla(BlockState state) {
        return SectionBuildCapture.renderShapeForVanilla(state);
    }

    // renderFluid: emit fluid quads for Remix, skip GL
    @Redirect(method = rtxremix$COMPILE_METHOD,
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderLiquid(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"))
    private void rtxremix$redirectFluid(BlockRenderDispatcher brm,
            BlockPos pos, BlockAndTintGetter world, VertexConsumer consumer,
            BlockState blockState, FluidState fluidState) {
        SectionBuildCapture.renderFluid(
                brm, pos, world, consumer, blockState, fluidState);
    }

    // RETURN: finalise mesh data and enqueue
    @Inject(method = rtxremix$COMPILE_METHOD, at = @At("RETURN"))
    private void rtxremix$onBuildReturn(
            SectionPos sectionPos,
            RenderChunkRegion renderRegion,
            VertexSorting vertexSorter,
            SectionBufferBuilderPack allocatorStorage,
            CallbackInfoReturnable<SectionCompiler.Results> cir) {
        SectionBuildCapture.end(sectionPos);
    }
}

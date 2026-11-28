/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionBuildCapture;
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
        SectionBuildCapture.begin(sectionPos, renderRegion, this.blockModelSet, this.fluidModelSet);
    }

    // The block loop's read of each cell: capture opacity / face masks / lights / quads
    @WrapOperation(method = "compile",
                   at = @At(value = "INVOKE",
                            target = "Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState rtxremix$captureBlock(RenderSectionRegion region, BlockPos pos, Operation<BlockState> original) {
        BlockState state = original.call(region, pos);
        SectionBuildCapture.captureBlock(state, region, pos);
        return state;
    }

    // While capturing, the block's quads were already emitted above, so vanilla's raster
    // path for it (layer buffer, tesselateBlock) is dead work. Reporting the
    // block invisible makes vanilla skip all of it.
    @Redirect(method = "compile",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;"))
    private RenderShape rtxremix$renderTypeForVanilla(BlockState state) {
        return SectionBuildCapture.renderShapeForVanilla(state);
    }

    // Fluid tessellation: emit fluid quads for Remix, skip GL
    @WrapOperation(method = "compile",
                   at = @At(value = "INVOKE",
                            target = "Lnet/minecraft/client/renderer/block/FluidRenderer;tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"))
    private void rtxremix$redirectFluid(FluidRenderer renderer,
            BlockAndTintGetter world, BlockPos pos, FluidRenderer.Output output,
            BlockState blockState, FluidState fluidState, Operation<Void> original) {
        SectionBuildCapture.renderFluid(
                renderer, world, pos, output, blockState, fluidState, original);
    }

    // RETURN: finalise mesh data and enqueue
    @Inject(method = "compile", at = @At("RETURN"))
    private void rtxremix$onBuildReturn(
            SectionPos sectionPos,
            RenderSectionRegion renderRegion,
            VertexSorting vertexSorter,
            SectionBufferBuilderPack allocatorStorage,
            CallbackInfoReturnable<SectionCompiler.Results> cir) {
        SectionBuildCapture.end(sectionPos);
    }
}

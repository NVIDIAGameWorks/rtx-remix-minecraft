/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.neoforge.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionBuildCapture;
import java.util.List;
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
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adapts NeoForge's extended section-compiler entry point to the canonical
 * section-capture lifecycle.
 */
@Mixin(SectionCompiler.class)
public class SectionBuilderNeoForgeMixin {
    private static final String rtxremix$COMPILE_METHOD =
            "compile(Lnet/minecraft/core/SectionPos;"
                    + "Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;"
                    + "Lcom/mojang/blaze3d/vertex/VertexSorting;"
                    + "Lnet/minecraft/client/renderer/SectionBufferBuilderPack;"
                    + "Ljava/util/List;)"
                    + "Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;";

    @Shadow
    private BlockRenderDispatcher blockRenderer;

    @Inject(method = rtxremix$COMPILE_METHOD, at = @At("HEAD"))
    private void rtxremix$onBuildHead(
            SectionPos sectionPos,
            RenderChunkRegion renderRegion,
            VertexSorting vertexSorter,
            SectionBufferBuilderPack allocatorStorage,
            List<AddSectionGeometryEvent.AdditionalSectionRenderer> additionalRenderers,
            CallbackInfoReturnable<SectionCompiler.Results> cir) {
        SectionBuildCapture.begin(sectionPos, renderRegion, this.blockRenderer);
    }

    @Redirect(
            method = rtxremix$COMPILE_METHOD,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;"
                            + "isSolidRender(Lnet/minecraft/world/level/BlockGetter;"
                            + "Lnet/minecraft/core/BlockPos;)Z"))
    private boolean rtxremix$captureBlock(
            BlockState state, BlockGetter world, BlockPos pos) {
        return SectionBuildCapture.captureBlock(state, world, pos);
    }

    @Redirect(
            method = rtxremix$COMPILE_METHOD,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;"
                            + "getRenderShape()Lnet/minecraft/world/level/block/RenderShape;"))
    private RenderShape rtxremix$renderTypeForVanilla(BlockState state) {
        return SectionBuildCapture.renderShapeForVanilla(state);
    }

    @Redirect(
            method = rtxremix$COMPILE_METHOD,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;"
                            + "renderLiquid(Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/BlockAndTintGetter;"
                            + "Lcom/mojang/blaze3d/vertex/VertexConsumer;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;"
                            + "Lnet/minecraft/world/level/material/FluidState;)V"))
    private void rtxremix$redirectFluid(
            BlockRenderDispatcher blockRenderer,
            BlockPos pos,
            BlockAndTintGetter world,
            VertexConsumer consumer,
            BlockState blockState,
            FluidState fluidState) {
        SectionBuildCapture.renderFluid(
                blockRenderer, pos, world, consumer, blockState, fluidState);
    }

    @Inject(method = rtxremix$COMPILE_METHOD, at = @At("RETURN"))
    private void rtxremix$onBuildReturn(
            SectionPos sectionPos,
            RenderChunkRegion renderRegion,
            VertexSorting vertexSorter,
            SectionBufferBuilderPack allocatorStorage,
            List<AddSectionGeometryEvent.AdditionalSectionRenderer> additionalRenderers,
            CallbackInfoReturnable<SectionCompiler.Results> cir) {
        SectionBuildCapture.end(sectionPos);
    }
}

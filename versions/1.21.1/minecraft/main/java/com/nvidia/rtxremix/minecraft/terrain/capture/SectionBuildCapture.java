/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.minecraft.MinecraftRuntime;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.terrain.VanillaSectionBuilds;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Canonical section-capture behavior invoked by each loader's compatible
 * {@code SectionCompiler} mixin.
 */
public final class SectionBuildCapture {
    private static final ThreadLocal<ProfileScope> TRACY_OUTER = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> TRACY_BLOCK_LOOP = new ThreadLocal<>();
    private static final ThreadLocal<BlockDataQueue.CaptureToken> CAPTURE_TOKEN =
            new ThreadLocal<>();

    /**
     * Whether this compile is capturing for Remix, latched once at HEAD.
     *
     * <p>{@code rtxEnabled} is volatile so worker threads see a toggle promptly.
     * Latching keeps a section wholly in either the Remix or vanilla path.
     */
    private static final ThreadLocal<Boolean> CAPTURE_FOR_BUILD =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private SectionBuildCapture() {
    }

    /** Begins one section compilation on the current worker thread. */
    public static void begin(
            SectionPos sectionPos,
            RenderChunkRegion renderRegion,
            BlockRenderDispatcher blockRenderer) {
        boolean capturing = VanillaSectionBuilds.beginBuild(MinecraftRuntime::isRtxEnabled);
        CAPTURE_FOR_BUILD.set(capturing);
        if (!capturing) {
            return;
        }

        CAPTURE_TOKEN.set(BlockDataQueue.beginCapture(sectionPos.getX(), sectionPos.getZ()));
        TRACY_OUTER.set(ProfileScope.begin("SectionBuilder"));
        TRACY_BLOCK_LOOP.set(null);

        if (renderRegion == null) {
            return;
        }

        BlockTextureManager textures = null;
        RemixRenderer renderer = MinecraftRuntime.renderer();
        if (renderer != null) {
            textures = renderer.getTextureManager();
        }

        BlockQuadEmitter.threadLocal().beginSection(sectionPos, textures, blockRenderer);
        TRACY_BLOCK_LOOP.set(ProfileScope.begin("block loop"));
    }

    /** Returns the capture decision latched for this section compilation. */
    public static boolean isCapturing() {
        return CAPTURE_FOR_BUILD.get();
    }

    /** Captures one block while preserving vanilla's opacity result. */
    public static boolean captureBlock(BlockState state, BlockGetter world, BlockPos pos) {
        boolean opaque = state.isSolidRender(world, pos);
        BlockQuadEmitter emitter = BlockQuadEmitter.current();
        if (emitter != null && emitter.isActive()) {
            emitter.processBlock(state, world, pos, opaque);
            if (BlockQuadEmitter.getRenderTypeCached(state) == RenderShape.MODEL) {
                emitter.emitBlock(state, pos, (BlockAndTintGetter) world);
            }
        }
        return opaque;
    }

    /** Suppresses vanilla block geometry only for a Remix capture build. */
    public static RenderShape renderShapeForVanilla(BlockState state) {
        return isCapturing()
                ? RenderShape.INVISIBLE
                : BlockQuadEmitter.getRenderTypeCached(state);
    }

    /** Captures a fluid or delegates to vanilla when capture is disabled. */
    public static void renderFluid(
            BlockRenderDispatcher blockRenderer,
            BlockPos pos,
            BlockAndTintGetter world,
            VertexConsumer consumer,
            BlockState blockState,
            FluidState fluidState) {
        if (isCapturing()) {
            BlockQuadEmitter emitter = BlockQuadEmitter.threadLocal();
            if (emitter.isActive()) {
                emitter.emitFluid(pos, world, fluidState);
            }
        } else {
            blockRenderer.renderLiquid(pos, world, consumer, blockState, fluidState);
        }
    }

    /** Completes one section compilation and publishes its captured data. */
    public static void end(SectionPos sectionPos) {
        try {
            BlockQuadEmitter emitter = BlockQuadEmitter.threadLocal();
            if (!emitter.isActive()) {
                return;
            }

            ProfileScope blockScope = TRACY_BLOCK_LOOP.get();
            if (blockScope != null) {
                blockScope.close();
            }

            SectionBlockData blockData = emitter.finishSection(sectionPos);
            BlockDataQueue.enqueue(blockData, CAPTURE_TOKEN.get());
        } finally {
            ProfileScope outerScope = TRACY_OUTER.get();
            if (outerScope != null) {
                outerScope.close();
            }
            TRACY_OUTER.remove();
            TRACY_BLOCK_LOOP.remove();
            CAPTURE_TOKEN.remove();
            VanillaSectionBuilds.endBuild(isCapturing());
            CAPTURE_FOR_BUILD.remove();
        }
    }
}

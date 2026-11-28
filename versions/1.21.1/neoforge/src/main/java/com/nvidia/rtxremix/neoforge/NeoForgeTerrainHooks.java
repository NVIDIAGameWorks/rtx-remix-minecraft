/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.neoforge;

import com.nvidia.rtxremix.minecraft.MinecraftTerrainHooks;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.model.data.ModelData;

/** NeoForge rendering extensions for the canonical terrain emitter. */
public final class NeoForgeTerrainHooks implements MinecraftTerrainHooks.Hooks {
    private static final Direction[] DIRECTIONS = Direction.values();

    @Override
    public boolean isCustomBlockModel(BakedModel model) {
        // NeoForge's canonical chunk renderer always uses ModelData,
        // render-type iteration, and the five-argument getQuads overload.
        // IDynamicBakedModel's legacy three-argument bridge may return no
        // quads, so no NeoForge block model is safe on the vanilla fast path.
        return true;
    }

    @Override
    public boolean emitCustomBlockDirect(
            BakedModel model,
            BlockAndTintGetter world,
            BlockState state,
            BlockPos pos,
            Supplier<RandomSource> random,
            Predicate<Direction> cullTest,
            MinecraftTerrainHooks.BlockQuadOutput output) {
        ModelData initialModelData = world instanceof RenderChunkRegion region
                ? region.getModelData(pos)
                : ModelData.EMPTY;
        ModelData modelData =
                model.getModelData(world, pos, state, initialModelData);
        RandomSource source = random.get();
        Iterable<RenderType> renderTypes =
                model.getRenderTypes(state, source, modelData);
        for (RenderType renderType : renderTypes) {
            String layer = BlockQuadEmitter.classifyRenderLayer(renderType);
            for (Direction direction : DIRECTIONS) {
                if (cullTest.test(direction)) {
                    continue;
                }
                source = random.get();
                for (var quad : model.getQuads(
                        state, direction, source, modelData, renderType)) {
                    output.emit(quad, layer);
                }
            }
            source = random.get();
            for (var quad : model.getQuads(
                    state, null, source, modelData, renderType)) {
                output.emit(quad, layer);
            }
        }
        return true;
    }

    @Override
    public boolean fluidVisuals(
            BlockAndTintGetter world,
            BlockPos pos,
            FluidState state,
            boolean resolveColor,
            MinecraftTerrainHooks.FluidVisuals output) {
        IClientFluidTypeExtensions extensions =
                IClientFluidTypeExtensions.of(state);
        var stillId = extensions.getStillTexture(state, world, pos);
        var flowingId = extensions.getFlowingTexture(state, world, pos);
        if (stillId == null || flowingId == null) {
            return false;
        }
        var atlas = Minecraft.getInstance()
                .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS);
        output.set(
                atlas.apply(stillId),
                atlas.apply(flowingId),
                resolveColor ? extensions.getTintColor(state, world, pos) : 0);
        return true;
    }

    @Override
    public int fluidColor(
            BlockAndTintGetter world, BlockPos pos, FluidState state) {
        return IClientFluidTypeExtensions.of(state)
                .getTintColor(state, world, pos);
    }

}

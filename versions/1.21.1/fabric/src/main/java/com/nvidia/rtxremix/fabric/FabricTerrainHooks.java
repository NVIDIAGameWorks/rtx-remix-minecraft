/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.fabric;

import com.nvidia.rtxremix.minecraft.MinecraftTerrainHooks;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionBlockData;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** Fabric Renderer API adaptation for the canonical terrain emitter. */
public final class FabricTerrainHooks implements MinecraftTerrainHooks.Hooks {
    private static final ThreadLocal<FabricRenderContext> CONTEXT =
            ThreadLocal.withInitial(FabricRenderContext::new);

    @Override
    public boolean isCustomBlockModel(BakedModel model) {
        return model instanceof FabricBakedModel fabricModel
                && !fabricModel.isVanillaAdapter();
    }

    @Override
    public List<SectionBlockData.BlockQuad> emitCustomBlock(
            BakedModel model,
            BlockAndTintGetter world,
            BlockState state,
            BlockPos pos,
            Supplier<RandomSource> random,
            Predicate<Direction> cullTest) {
        if (!(model instanceof FabricBakedModel fabricModel)) {
            return null;
        }
        FabricRenderContext context = CONTEXT.get();
        context.prepare(world, state, pos, cullTest);
        fabricModel.emitBlockQuads(world, state, pos, random, context);
        return context.harvest();
    }

    @Override
    public boolean fluidVisuals(
            BlockAndTintGetter world,
            BlockPos pos,
            FluidState state,
            boolean resolveColor,
            MinecraftTerrainHooks.FluidVisuals output) {
        FluidRenderHandler handler =
                FluidRenderHandlerRegistry.INSTANCE.get(state.getType());
        if (handler == null) {
            return false;
        }
        var sprites = handler.getFluidSprites(world, pos, state);
        if (sprites == null || sprites.length < 2) {
            return false;
        }
        output.set(
                sprites[0],
                sprites[1],
                resolveColor ? handler.getFluidColor(world, pos, state) : 0);
        return true;
    }

    @Override
    public int fluidColor(BlockAndTintGetter world, BlockPos pos, FluidState state) {
        FluidRenderHandler handler =
                FluidRenderHandlerRegistry.INSTANCE.get(state.getType());
        return handler != null ? handler.getFluidColor(world, pos, state) : 0x3F76E4;
    }
}

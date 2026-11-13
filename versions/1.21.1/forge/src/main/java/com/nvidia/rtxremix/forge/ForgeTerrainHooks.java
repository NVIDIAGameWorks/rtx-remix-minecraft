/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge;

import com.nvidia.rtxremix.minecraft.MinecraftTerrainHooks;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionBlockData;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.client.model.data.ModelData;

/** Forge rendering extensions for the canonical terrain emitter. */
public final class ForgeTerrainHooks implements MinecraftTerrainHooks.Hooks {
    private static final String FORGE_BAKED_MODEL_INTERFACE =
            "net.minecraftforge.client.extensions.IForgeBakedModel";
    private static final Direction[] DIRECTIONS = Direction.values();

    private static final ClassValue<Boolean> CUSTOM_MODEL_CLASSES = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return overridesForgeMethod(
                            type,
                            "getModelData",
                            BlockAndTintGetter.class,
                            BlockPos.class,
                            BlockState.class,
                            ModelData.class)
                    || overridesForgeMethod(
                            type,
                            "getRenderTypes",
                            BlockState.class,
                            RandomSource.class,
                            ModelData.class)
                    || overridesForgeMethod(
                            type,
                            "getQuads",
                            BlockState.class,
                            Direction.class,
                            RandomSource.class,
                            ModelData.class,
                            RenderType.class);
        }
    };

    @Override
    public boolean isCustomBlockModel(BakedModel model) {
        return requiresExtendedModel(model);
    }

    public static boolean requiresExtendedModel(BakedModel model) {
        // Forge adds a render-type override to its ordinary SimpleBakedModel,
        // but that model still uses vanilla quads and belongs on the canonical
        // fast path. Subclasses remain eligible for extension detection.
        if (model.getClass() == SimpleBakedModel.class) {
            return false;
        }
        if (model instanceof ForgeModelComposition composition) {
            return composition.rtxremix$hasCustomChildren();
        }
        return CUSTOM_MODEL_CLASSES.get(model.getClass());
    }

    @Override
    public List<SectionBlockData.BlockQuad> emitCustomBlock(
            BakedModel model,
            BlockAndTintGetter world,
            BlockState state,
            BlockPos pos,
            Supplier<RandomSource> random,
            Predicate<Direction> cullTest) {
        ModelData modelData = model.getModelData(world, pos, state, ModelData.EMPTY);
        RandomSource source = random.get();
        Iterable<RenderType> renderTypes = model.getRenderTypes(state, source, modelData);
        List<SectionBlockData.BlockQuad> captured = new ArrayList<>();
        for (RenderType renderType : renderTypes) {
            String layer = BlockQuadEmitter.classifyRenderLayer(renderType);
            for (Direction direction : DIRECTIONS) {
                if (cullTest.test(direction)) {
                    continue;
                }
                source = random.get();
                capture(
                        captured,
                        model.getQuads(state, direction, source, modelData, renderType),
                        state,
                        world,
                        pos,
                        layer);
            }
            source = random.get();
            capture(
                    captured,
                    model.getQuads(state, null, source, modelData, renderType),
                    state,
                    world,
                    pos,
                    layer);
        }
        return captured;
    }

    @Override
    public boolean fluidVisuals(
            BlockAndTintGetter world,
            BlockPos pos,
            FluidState state,
            boolean resolveColor,
            MinecraftTerrainHooks.FluidVisuals output) {
        IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(state);
        var stillId = extensions.getStillTexture(state, world, pos);
        var flowingId = extensions.getFlowingTexture(state, world, pos);
        if (stillId == null || flowingId == null) {
            return false;
        }
        var atlas = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS);
        output.set(
                atlas.apply(stillId),
                atlas.apply(flowingId),
                resolveColor ? extensions.getTintColor(state, world, pos) : 0);
        return true;
    }

    @Override
    public int fluidColor(BlockAndTintGetter world, BlockPos pos, FluidState state) {
        return IClientFluidTypeExtensions.of(state).getTintColor(state, world, pos);
    }

    private static boolean overridesForgeMethod(
            Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            Method method = type.getMethod(name, parameterTypes);
            return !FORGE_BAKED_MODEL_INTERFACE.equals(
                    method.getDeclaringClass().getName());
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private static void capture(
            List<SectionBlockData.BlockQuad> output,
            List<BakedQuad> quads,
            BlockState state,
            BlockAndTintGetter world,
            BlockPos pos,
            String layer) {
        for (BakedQuad quad : quads) {
            int[] vertices = quad.getVertices();
            float[] positions = new float[12];
            float[] uvs = new float[8];
            for (int vertex = 0; vertex < 4; vertex++) {
                int source = vertex * 8;
                int position = vertex * 3;
                int uv = vertex * 2;
                positions[position] = Float.intBitsToFloat(vertices[source]);
                positions[position + 1] = Float.intBitsToFloat(vertices[source + 1]);
                positions[position + 2] = Float.intBitsToFloat(vertices[source + 2]);
                uvs[uv] = Float.intBitsToFloat(vertices[source + 4]);
                uvs[uv + 1] = Float.intBitsToFloat(vertices[source + 5]);
            }

            int packedNormal = vertices[7];
            float nx = (byte) (packedNormal & 0xFF) / 127.0f;
            float ny = (byte) ((packedNormal >>> 8) & 0xFF) / 127.0f;
            float nz = (byte) ((packedNormal >>> 16) & 0xFF) / 127.0f;
            if (nx * nx + ny * ny + nz * nz < 0.01f) {
                Direction direction = quad.getDirection();
                nx = direction.getStepX();
                ny = direction.getStepY();
                nz = direction.getStepZ();
            }

            int color = 0xFFFFFFFF;
            if (quad.isTinted()) {
                int tint = Minecraft.getInstance().getBlockColors()
                        .getColor(state, world, pos, quad.getTintIndex());
                color = 0xFF000000 | (tint & 0x00FFFFFF);
            }

            TextureAtlasSprite sprite = quad.getSprite();
            ResourceLocation spriteId =
                    sprite != null ? sprite.contents().name() : null;
            output.add(new SectionBlockData.BlockQuad(
                    positions,
                    uvs,
                    nx,
                    ny,
                    nz,
                    color,
                    -1,
                    spriteId,
                    sprite != null ? sprite.getU0() : 0.0f,
                    sprite != null ? sprite.getV0() : 0.0f,
                    sprite != null ? sprite.getU1() : 1.0f,
                    sprite != null ? sprite.getV1() : 1.0f,
                    layer,
                    false,
                    false));
        }
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.nvidia.rtxremix.minecraft.terrain.capture.SectionBlockData;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** Loader rendering extensions used by the canonical terrain capture path. */
public final class MinecraftTerrainHooks {
    @FunctionalInterface
    public interface BlockQuadOutput {
        void emit(BakedQuad quad, String renderLayer);
    }

    /** Reusable fluid-rendering result owned by the caller. */
    public static final class FluidVisuals {
        private TextureAtlasSprite still;
        private TextureAtlasSprite flowing;
        private int color;

        public TextureAtlasSprite still() {
            return still;
        }

        public TextureAtlasSprite flowing() {
            return flowing;
        }

        public int color() {
            return color;
        }

        public void set(TextureAtlasSprite still, TextureAtlasSprite flowing, int color) {
            this.still = still;
            this.flowing = flowing;
            this.color = color;
        }
    }

    public interface Hooks {
        /** Returns whether this model needs the loader's extended model path. */
        boolean isCustomBlockModel(BakedModel model);

        /**
         * Returns custom-model quads, or {@code null} when the model should use
         * vanilla's baked-quad path.
         */
        default List<SectionBlockData.BlockQuad> emitCustomBlock(
                BakedModel model,
                BlockAndTintGetter world,
                BlockState state,
                BlockPos pos,
                Supplier<RandomSource> random,
                Predicate<Direction> cullTest) {
            return null;
        }

        /**
         * Streams loader-model quads without intermediate per-quad objects.
         * Returns true when the model was handled.
         */
        default boolean emitCustomBlockDirect(
                BakedModel model,
                BlockAndTintGetter world,
                BlockState state,
                BlockPos pos,
                Supplier<RandomSource> random,
                Predicate<Direction> cullTest,
                BlockQuadOutput output) {
            return false;
        }

        /**
         * Populates caller-owned fluid visuals. Implementations may skip tint
         * resolution when {@code resolveColor} is false.
         */
        boolean fluidVisuals(
                BlockAndTintGetter world,
                BlockPos pos,
                FluidState state,
                boolean resolveColor,
                FluidVisuals output);

        int fluidColor(BlockAndTintGetter world, BlockPos pos, FluidState state);
    }

    private static volatile Hooks hooks;

    private MinecraftTerrainHooks() {
    }

    public static void install(Hooks value) {
        hooks = Objects.requireNonNull(value, "value");
    }

    public static boolean isCustomBlockModel(BakedModel model) {
        Hooks value = hooks;
        return value != null && value.isCustomBlockModel(model);
    }

    public static List<SectionBlockData.BlockQuad> emitCustomBlock(
            BakedModel model,
            BlockAndTintGetter world,
            BlockState state,
            BlockPos pos,
            Supplier<RandomSource> random,
            Predicate<Direction> cullTest) {
        Hooks value = hooks;
        return value == null
                ? null
                : value.emitCustomBlock(model, world, state, pos, random, cullTest);
    }

    public static boolean emitCustomBlockDirect(
            BakedModel model,
            BlockAndTintGetter world,
            BlockState state,
            BlockPos pos,
            Supplier<RandomSource> random,
            Predicate<Direction> cullTest,
            BlockQuadOutput output) {
        Hooks value = hooks;
        return value != null
                && value.emitCustomBlockDirect(
                        model, world, state, pos, random, cullTest, output);
    }

    public static boolean fluidVisuals(
            BlockAndTintGetter world,
            BlockPos pos,
            FluidState state,
            boolean resolveColor,
            FluidVisuals output) {
        Hooks value = hooks;
        return value != null
                && value.fluidVisuals(world, pos, state, resolveColor, output);
    }

    public static int fluidColor(
            BlockAndTintGetter world, BlockPos pos, FluidState state, int fallback) {
        Hooks value = hooks;
        return value == null ? fallback : value.fluidColor(world, pos, state);
    }
}

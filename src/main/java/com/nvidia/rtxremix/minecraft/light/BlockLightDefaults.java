/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import com.nvidia.rtxremix.minecraft.block.SpotLightBlock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Built-in block light rules. Resource packs can override these by using the same rule path.
 */
final class BlockLightDefaults {
    static final float DEFAULT_AUTHORED_INTENSITY_MULTIPLIER = 5.0f;
    static final float TORCH_AUTHORED_INTENSITY_MULTIPLIER = 7.5f;
    static final float CAMPFIRE_AUTHORED_INTENSITY_MULTIPLIER = 7.5f;
    static final float THIN_SPHERE_RADIUS_SCALE = 0.4f;
    static final float THIN_SPHERE_INTENSITY_SCALE = 3.0f;
    /** Pre-scale emitter radius of a torch's flame, shared with the light reach calibration. */
    static final float TORCH_RADIUS = 0.2f;
    private static final float AMETHYST_INTENSITY_SCALE = 2.0f;
    static final LightEntry.ColorOverride TORCH_COLOR = LightEntry.ColorOverride.of(1.0f, 0.35f, 0.1f);
    private static final LightEntry.ColorOverride CAMPFIRE_COLOR = LightEntry.ColorOverride.of(1.0f, 0.1f, 0.05f);
    private static final LightEntry.ColorOverride SOUL_COLOR = LightEntry.ColorOverride.of(0.05f, 0.2f, 1.0f);
    private static final LightEntry.ColorOverride REDSTONE_COLOR = LightEntry.ColorOverride.of(1.0f, 0.15f, 0.02f);
    private static final LightEntry.ColorOverride CONDUIT_COLOR = LightEntry.ColorOverride.of(0.45f, 0.9f, 1.0f);
    private static final LightEntry.ColorOverride AMETHYST_COLOR = LightEntry.ColorOverride.of(0.65f, 0.35f, 1.0f);

    // Shared beacon geometry and path-specific radiance tuning live in
    // BeaconBeam; the block_display lighthouse remains independent.
    // Beam colour is per-variant and lives on the block (SpotLightBlock.beamColor).

    private BlockLightDefaults() {}

    static Map<ResourceLocation, BlockLightRuleManager.Rule> create() {
        LinkedHashMap<ResourceLocation, BlockLightRuleManager.Rule> rules = new LinkedHashMap<>();
        // Built as locals, not static fields: a static field touching Blocks
        // would force the block registry to bootstrap just to load this class,
        // even for a caller that only wants a constant such as TORCH_COLOR.
        Set<Block> candles = candleBlocks();
        Set<Block> candleCakes = candleCakeBlocks();
        add(rules, "torch", exact(Blocks.TORCH),
                (id, state, pos, centroid) -> List.of(thinSphere(id, 0, state, pos, 0.5f, 0.7f, 0.5f, TORCH_RADIUS, TORCH_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "soul_torch", exact(Blocks.SOUL_TORCH),
                (id, state, pos, centroid) -> List.of(thinSphere(id, 0, state, pos, 0.5f, 0.7f, 0.5f, TORCH_RADIUS, SOUL_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "wall_torch", exact(Blocks.WALL_TORCH),
                (id, state, pos, centroid) -> List.of(wallTorch(id, state, pos, TORCH_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "soul_wall_torch", exact(Blocks.SOUL_WALL_TORCH),
                (id, state, pos, centroid) -> List.of(wallTorch(id, state, pos, SOUL_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "redstone_torch", state -> state.is(Blocks.REDSTONE_TORCH) && state.getValue(RedstoneTorchBlock.LIT),
                (id, state, pos, centroid) -> List.of(thinSphere(id, 0, state, pos, 0.5f, 0.7f, 0.5f, 0.16f, REDSTONE_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "redstone_wall_torch", state -> state.is(Blocks.REDSTONE_WALL_TORCH) && state.getValue(RedstoneWallTorchBlock.LIT),
                (id, state, pos, centroid) -> List.of(wallTorch(id, state, pos, REDSTONE_COLOR)),
                TORCH_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "small_amethyst_bud", exact(Blocks.SMALL_AMETHYST_BUD),
                (id, state, pos, centroid) -> List.of(amethystCrystal(id, state, pos, 3.0f, 0.08f, 1)));
        add(rules, "medium_amethyst_bud", exact(Blocks.MEDIUM_AMETHYST_BUD),
                (id, state, pos, centroid) -> List.of(amethystCrystal(id, state, pos, 4.0f, 0.10f, 2)));
        add(rules, "large_amethyst_bud", exact(Blocks.LARGE_AMETHYST_BUD),
                (id, state, pos, centroid) -> List.of(amethystCrystal(id, state, pos, 5.0f, 0.13f, 4)));
        add(rules, "amethyst_cluster", exact(Blocks.AMETHYST_CLUSTER),
                (id, state, pos, centroid) -> List.of(amethystCrystal(id, state, pos, 7.0f, 0.18f, 5)));
        add(rules, "campfire", state -> state.is(Blocks.CAMPFIRE) && state.getValue(BlockStateProperties.LIT),
                (id, state, pos, centroid) -> List.of(sphere(id, 0, state, pos, 0.5f, 0.5f, 0.5f, 0.25f, CAMPFIRE_COLOR, null)),
                CAMPFIRE_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "soul_campfire", state -> state.is(Blocks.SOUL_CAMPFIRE) && state.getValue(BlockStateProperties.LIT),
                (id, state, pos, centroid) -> List.of(sphere(id, 0, state, pos, 0.5f, 0.5f, 0.5f, 0.25f, SOUL_COLOR, null)),
                CAMPFIRE_AUTHORED_INTENSITY_MULTIPLIER);
        add(rules, "conduit", exact(Blocks.CONDUIT),
                (id, state, pos, centroid) -> List.of(sphere(id, 0, state, pos, 0.5f, 0.5f, 0.5f, 0.1875f, CONDUIT_COLOR, null)));
        add(rules, "light", state -> state.is(Blocks.LIGHT) && state.getValue(BlockStateProperties.LEVEL) > 0,
                (id, state, pos, centroid) -> List.of(sphere(id, 0, state, pos, 0.5f, 0.5f, 0.5f, 0.2f, LightEntry.ColorOverride.of(1, 1, 1), null)));
        add(rules, "candles", state -> candles.contains(state.getBlock()) && state.getValue(CandleBlock.LIT),
                BlockLightDefaults::candles);
        add(rules, "candle_cakes", state -> candleCakes.contains(state.getBlock()) && state.getValue(BlockStateProperties.LIT),
                (id, state, pos, centroid) -> List.of(thinSphere(id, 0, state, pos, 0.5f, 1.0f, 0.5f, 0.08f, null)));
        BlockLightIntensityControls.validateBuiltInRules(rules.keySet());
        return Collections.unmodifiableMap(new LinkedHashMap<>(rules));
    }

    private static void add(Map<ResourceLocation, BlockLightRuleManager.Rule> rules,
                            String path,
                            Predicate<BlockState> matcher,
                            BlockLightRuleManager.Resolver resolver) {
        add(rules, path, matcher, resolver, DEFAULT_AUTHORED_INTENSITY_MULTIPLIER);
    }

    private static void add(Map<ResourceLocation, BlockLightRuleManager.Rule> rules,
                            String path,
                            Predicate<BlockState> matcher,
                            BlockLightRuleManager.Resolver resolver,
                            float authoredIntensityMultiplier) {
        ResourceLocation id = BlockLightRuleIds.builtIn(path);
        add(rules, id, matcher, resolver, authoredIntensityMultiplier);
    }

    private static void add(Map<ResourceLocation, BlockLightRuleManager.Rule> rules,
                            ResourceLocation id,
                            Predicate<BlockState> matcher,
                            BlockLightRuleManager.Resolver resolver,
                            float authoredIntensityMultiplier) {
        rules.put(id, BlockLightRuleManager.rule(
                id,
                matcher,
                (ruleId, state, pos, sampler) -> resolver
                        .resolve(ruleId, state, pos, sampler)
                        .stream()
                        .map(entry -> entry.withAuthoredIntensityMultiplier(
                                authoredIntensityMultiplier))
                        .toList()));
    }

    private static Predicate<BlockState> exact(Block block) {
        return state -> state.is(block);
    }

    private static LightEntry sphere(ResourceLocation id, int index, BlockState state, BlockPos pos,
                                     float x, float y, float z, float radius,
                                     LightEntry.ColorOverride color, Float intensity) {
        return BlockLightRuleManager.sphere(id, index, state, pos, x, y, z, radius, color, intensity);
    }

    private static LightEntry thinSphere(ResourceLocation id, int index, BlockState state, BlockPos pos,
                                         float x, float y, float z, float radius,
                                         LightEntry.ColorOverride color) {
        float intensity = state.getLightEmission()
                * BlockLightRuleManager.RADIANCE_PER_LIGHT_LEVEL
                * THIN_SPHERE_INTENSITY_SCALE;
        return sphere(id, index, state, pos, x, y, z, radius * THIN_SPHERE_RADIUS_SCALE, color, intensity);
    }

    private static LightEntry wallTorch(ResourceLocation id, BlockState state, BlockPos pos,
                                        LightEntry.ColorOverride color) {
        Direction facing = state.getValue(WallTorchBlock.FACING).getOpposite();
        return thinSphere(id, 0, state, pos,
                0.5f + 0.27f * facing.getStepX(),
                0.92f,
                0.5f + 0.27f * facing.getStepZ(),
                0.2f, color);
    }

    private static LightEntry endRod(ResourceLocation id, BlockState state, BlockPos pos) {
        Direction facing = state.getValue(BlockStateProperties.FACING);
        BlockLightRuleManager.Vec3 axis = BlockLightRuleManager.Vec3.fromAxis(facing.getAxis());
        return BlockLightRuleManager.cylinder(id, 0, state, pos,
                0.5f, 0.5f, 0.5f, 0.125f, axis, 1.0f, null, null);
    }

    private static LightEntry amethystCrystal(ResourceLocation id, BlockState state, BlockPos pos,
                                              float heightPixels, float radius, int lightLevel) {
        Direction facing = state.getValue(BlockStateProperties.FACING);
        float halfHeight = heightPixels / 32.0f;
        float offsetFromCenter = 0.5f - halfHeight;
        float intensity = lightLevel
                * BlockLightRuleManager.RADIANCE_PER_LIGHT_LEVEL
                * AMETHYST_INTENSITY_SCALE;
        return sphere(id, 0, state, pos,
                0.5f - facing.getStepX() * offsetFromCenter,
                0.5f - facing.getStepY() * offsetFromCenter,
                0.5f - facing.getStepZ() * offsetFromCenter,
                radius, AMETHYST_COLOR, intensity);
    }

    private static List<LightEntry> candles(ResourceLocation id, BlockState state, BlockPos pos,
                                            BlockLightRuleManager.ModelSampler centroid) {
        List<LightEntry> entries = new ArrayList<>(state.getValue(CandleBlock.CANDLES));
        float[][] offsets = candleOffsets(state.getValue(CandleBlock.CANDLES));
        for (int i = 0; i < offsets.length; i++) {
            float[] o = offsets[i];
            entries.add(thinSphere(id, i, state, pos, o[0], o[1], o[2], 0.06f, null));
        }
        return entries;
    }

    private static Set<Block> candleBlocks() {
        return Set.of(
                Blocks.CANDLE, Blocks.WHITE_CANDLE, Blocks.ORANGE_CANDLE, Blocks.MAGENTA_CANDLE,
                Blocks.LIGHT_BLUE_CANDLE, Blocks.YELLOW_CANDLE, Blocks.LIME_CANDLE, Blocks.PINK_CANDLE,
                Blocks.GRAY_CANDLE, Blocks.LIGHT_GRAY_CANDLE, Blocks.CYAN_CANDLE, Blocks.PURPLE_CANDLE,
                Blocks.BLUE_CANDLE, Blocks.BROWN_CANDLE, Blocks.GREEN_CANDLE, Blocks.RED_CANDLE,
                Blocks.BLACK_CANDLE);
    }

    private static Set<Block> candleCakeBlocks() {
        return Set.of(
                Blocks.CANDLE_CAKE, Blocks.WHITE_CANDLE_CAKE, Blocks.ORANGE_CANDLE_CAKE,
                Blocks.MAGENTA_CANDLE_CAKE, Blocks.LIGHT_BLUE_CANDLE_CAKE, Blocks.YELLOW_CANDLE_CAKE,
                Blocks.LIME_CANDLE_CAKE, Blocks.PINK_CANDLE_CAKE, Blocks.GRAY_CANDLE_CAKE,
                Blocks.LIGHT_GRAY_CANDLE_CAKE, Blocks.CYAN_CANDLE_CAKE, Blocks.PURPLE_CANDLE_CAKE,
                Blocks.BLUE_CANDLE_CAKE, Blocks.BROWN_CANDLE_CAKE, Blocks.GREEN_CANDLE_CAKE,
                Blocks.RED_CANDLE_CAKE, Blocks.BLACK_CANDLE_CAKE);
    }

    private static float[][] candleOffsets(int candles) {
        return switch (candles) {
            case 1 -> new float[][]{{0.5f, 0.5f, 0.5f}};
            case 2 -> new float[][]{{0.375f, 0.44f, 0.5f}, {0.625f, 0.5f, 0.44f}};
            case 3 -> new float[][]{{0.5f, 0.313f, 0.625f}, {0.375f, 0.44f, 0.5f}, {0.56f, 0.5f, 0.44f}};
            default -> new float[][]{{0.44f, 0.313f, 0.56f}, {0.625f, 0.44f, 0.56f}, {0.375f, 0.44f, 0.375f}, {0.56f, 0.5f, 0.375f}};
        };
    }
}

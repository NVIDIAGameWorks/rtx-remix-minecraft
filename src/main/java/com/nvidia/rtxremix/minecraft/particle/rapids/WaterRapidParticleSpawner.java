/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle.rapids;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

public final class WaterRapidParticleSpawner {
    private static final int[] CARDINAL_X = {1, -1, 0, 0};
    private static final int[] CARDINAL_Z = {0, 0, 1, -1};
    private static final int NEAR_ATTEMPTS = 192;
    private static final int FAR_ATTEMPTS = 96;
    private static final int SPAWN_BUDGET_PER_TICK = Math.max(0,
            Integer.getInteger("rtxremix.waterRapids.spawnBudgetPerTick", 64));
    private static final int NEAR_RADIUS = 16;
    private static final int FAR_RADIUS = 32;
    private static final double MIN_FLOW_SPEED_SQUARED = 1.0E-4;
    private static final double BASE_SPEED = 0.17;
    private static final double VERTICAL_BASE_SPEED = 0.28;
    private static final double VERTICAL_DOWNWARD_SPEED = 0.24;
    private static final double JITTER_SPEED = 0.04;
    private static final double MIN_HORIZONTAL_FLOW_SQUARED = 0.02;
    private static final double MIN_VERTICAL_COLUMN_HORIZONTAL_SQUARED = 0.01;
    private static final double VERTICAL_COLUMN_SURFACE_RADIUS = 0.42;
    private static final double VERTICAL_COLUMN_OUTWARD_SPEED = 0.055;
    private static ClientLevel budgetWorld;
    private static long budgetWorldTime = Long.MIN_VALUE;
    private static SpawnBudget tickBudget = new SpawnBudget(0);

    private WaterRapidParticleSpawner() {}

    public static void spawnRandomDisplayParticles(ClientLevel world, int centerX, int centerY, int centerZ) {
        if (!RtxRemixMod.isRtxEnabled() || !RtxRemixMod.isWorldEnabled() || !RtxRemixMod.isParticlesEnabled()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.particleEngine == null) {
            return;
        }

        RandomSource random = world.random;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        SpawnBudget budget = currentTickBudget(world);
        if (!budget.hasRemaining()) {
            return;
        }
        for (int i = 0; i < NEAR_ATTEMPTS && budget.hasRemaining(); i++) {
            sample(world, client, centerX, centerY, centerZ, NEAR_RADIUS, random, pos, budget);
        }
        for (int i = 0; i < FAR_ATTEMPTS && budget.hasRemaining(); i++) {
            sample(world, client, centerX, centerY, centerZ, FAR_RADIUS, random, pos, budget);
        }
    }

    private static void sample(ClientLevel world, Minecraft client,
                               int centerX, int centerY, int centerZ, int radius,
                               RandomSource random, BlockPos.MutableBlockPos pos, SpawnBudget budget) {
        pos.set(
                centerX + random.nextInt(radius) - random.nextInt(radius),
                centerY + random.nextInt(radius) - random.nextInt(radius),
                centerZ + random.nextInt(radius) - random.nextInt(radius));

        FluidState fluidState = world.getFluidState(pos);
        if (!fluidState.is(FluidTags.WATER) || fluidState.isSource()) {
            return;
        }

        Vec3 flow = fluidState.getFlow(world, pos);
        boolean verticalColumn = isVerticalColumn(world, pos, fluidState, flow);
        if (!verticalColumn && flow.lengthSqr() < MIN_FLOW_SPEED_SQUARED) {
            return;
        }

        Vec3 direction = verticalColumn ? new Vec3(0.0, -1.0, 0.0) : flow.normalize();
        boolean falling = verticalColumn || direction.y < -0.35;
        float[] color = foamColor(world, pos, fluidState);
        int particleCount = verticalColumn ? 6 : falling ? 4 : 2 + random.nextInt(2);
        for (int i = 0; i < particleCount && budget.hasRemaining(); i++) {
            emitParticle(world, client, pos, fluidState, direction, falling, verticalColumn, color, random, budget);
        }

        if (!budget.hasRemaining()) {
            return;
        }

        if (verticalColumn) {
            emitVerticalColumnImpactMist(client, world, pos, random, budget);
        } else {
            BlockPos impactPos = downstreamImpactWaterPos(world, pos, flow);
            if (impactPos != null) {
                int mistCount = falling ? 12 + random.nextInt(9) : 6 + random.nextInt(7);
                for (int i = 0; i < mistCount && budget.hasRemaining(); i++) {
                    emitMist(client, world, pos, impactPos, flow, random, budget);
                }
            }
        }
    }

    private static boolean isVerticalColumn(ClientLevel world, BlockPos pos, FluidState state, Vec3 flow) {
        if (!isFallingWater(state)) {
            return false;
        }

        FluidState below = world.getFluidState(pos.below());
        if (!below.is(FluidTags.WATER)) {
            return false;
        }

        return flow.x * flow.x + flow.z * flow.z < MIN_VERTICAL_COLUMN_HORIZONTAL_SQUARED;
    }

    private static void emitParticle(ClientLevel world, Minecraft client, BlockPos pos, FluidState fluidState,
                                     Vec3 direction, boolean falling, boolean verticalColumn,
                                     float[] color, RandomSource random, SpawnBudget budget) {
        double radialX = 0.0;
        double radialZ = 0.0;
        double spawnX;
        double spawnZ;
        if (verticalColumn) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            radialX = Math.cos(angle);
            radialZ = Math.sin(angle);
            double radius = VERTICAL_COLUMN_SURFACE_RADIUS + random.nextDouble() * 0.16;
            spawnX = pos.getX() + 0.5 + radialX * radius;
            spawnZ = pos.getZ() + 0.5 + radialZ * radius;
        } else {
            spawnX = spawnCoord(pos.getX(), direction.x, random);
            spawnZ = spawnCoord(pos.getZ(), direction.z, random);
        }
        double spawnY = spawnY(world, pos, fluidState, random);

        double sideX = -direction.z;
        double sideZ = direction.x;
        double sideJitter = (random.nextDouble() - 0.5) * JITTER_SPEED;
        double verticalJitter = (random.nextDouble() - 0.25) * JITTER_SPEED;
        double speed = falling ? VERTICAL_BASE_SPEED : BASE_SPEED;
        double vx = direction.x * speed + sideX * sideJitter + radialX * VERTICAL_COLUMN_OUTWARD_SPEED;
        double vy = direction.y * speed + verticalJitter;
        double vz = direction.z * speed + sideZ * sideJitter + radialZ * VERTICAL_COLUMN_OUTWARD_SPEED;
        if (falling) {
            vy = Math.min(vy, -VERTICAL_DOWNWARD_SPEED - random.nextDouble() * 0.12);
        }

        Particle foam = addParticle(client, budget, RapidFoamParticles.RAPID_FOAM, spawnX, spawnY, spawnZ, vx, vy, vz);
        tint(foam, color);

        int dotCount = falling ? 3 : 2;
        for (int i = 0; i < dotCount && budget.hasRemaining(); i++) {
            double dotX = spawnX + (random.nextDouble() - 0.5) * 0.18;
            double dotY = spawnY - 0.05 + random.nextDouble() * 0.16;
            double dotZ = spawnZ + (random.nextDouble() - 0.5) * 0.18;
            Particle dot = addParticle(client, budget, RapidFoamParticles.RAPID_FOAM_DOT,
                    dotX, dotY, dotZ, vx * 0.85, vy * 0.9, vz * 0.85);
            tint(dot, color);
        }
    }

    private static BlockPos downstreamImpactWaterPos(ClientLevel world, BlockPos pos, Vec3 flow) {
        double horizontalLengthSquared = flow.x * flow.x + flow.z * flow.z;
        if (horizontalLengthSquared < MIN_HORIZONTAL_FLOW_SQUARED) {
            return null;
        }

        int dx = Mth.floor(flow.x / Math.sqrt(horizontalLengthSquared) + 0.5);
        int dz = Mth.floor(flow.z / Math.sqrt(horizontalLengthSquared) + 0.5);
        BlockPos impactPos = pos.below().offset(dx, 0, dz);
        return world.getFluidState(impactPos).is(FluidTags.WATER) ? impactPos : null;
    }

    private static void emitVerticalColumnImpactMist(Minecraft client, ClientLevel world,
                                                     BlockPos pos, RandomSource random, SpawnBudget budget) {
        FluidState below = world.getFluidState(pos.below());
        if (!below.is(FluidTags.WATER) || isFallingWater(below)) {
            return;
        }

        for (int i = 0; i < CARDINAL_X.length; i++) {
            if (!budget.hasRemaining()) {
                return;
            }

            int dx = CARDINAL_X[i];
            int dz = CARDINAL_Z[i];
            BlockPos impactPos = pos.offset(dx, 0, dz);
            FluidState impactState = world.getFluidState(impactPos);
            if (!impactState.is(FluidTags.WATER) || isFallingWater(impactState)) {
                continue;
            }

            int mistCount = 6 + random.nextInt(7);
            for (int j = 0; j < mistCount && budget.hasRemaining(); j++) {
                emitMist(client, world, pos, impactPos, dx, dz, random, budget);
            }
        }
    }

    private static boolean isFallingWater(FluidState state) {
        return state.is(FluidTags.WATER) && state.hasProperty(FlowingFluid.FALLING) && state.getValue(FlowingFluid.FALLING);
    }

    private static void emitMist(Minecraft client, ClientLevel world, BlockPos pos, BlockPos impactPos,
                                 Vec3 flow, RandomSource random, SpawnBudget budget) {
        double horizontalLength = Math.sqrt(flow.x * flow.x + flow.z * flow.z);
        emitMist(client, world, pos, impactPos, flow.x / horizontalLength, flow.z / horizontalLength, random, budget);
    }

    private static void emitMist(Minecraft client, ClientLevel world, BlockPos pos, BlockPos impactPos,
                                 double dirX, double dirZ, RandomSource random, SpawnBudget budget) {
        double sideX = -dirZ;
        double sideZ = dirX;
        double downstream = 0.62 + random.nextDouble() * 0.28;
        double side = (random.nextDouble() - 0.5) * 0.95;
        double x = pos.getX() + 0.5 + dirX * downstream + sideX * side;
        FluidState impactFluidState = world.getFluidState(impactPos);
        double y = impactPos.getY() + impactFluidState.getHeight(world, impactPos) + 0.05 + random.nextDouble() * 0.35;
        double z = pos.getZ() + 0.5 + dirZ * downstream + sideZ * side;
        double speed = 0.012 + random.nextDouble() * 0.018;
        double vx = dirX * speed + sideX * (random.nextDouble() - 0.5) * 0.012;
        double vy = 0.018 + random.nextDouble() * 0.025;
        double vz = dirZ * speed + sideZ * (random.nextDouble() - 0.5) * 0.012;
        addParticle(client, budget, RapidFoamParticles.RAPID_MIST, x, y, z, vx, vy, vz);
    }

    private static double spawnCoord(int blockCoord, double flowComponent, RandomSource random) {
        double center = 0.5 + flowComponent * 0.32 + (random.nextDouble() - 0.5) * 0.55;
        return blockCoord + Mth.clamp(center, 0.08, 0.92);
    }

    private static double spawnY(ClientLevel world, BlockPos pos, FluidState fluidState, RandomSource random) {
        BlockPos above = pos.above();
        if (!world.getFluidState(above).is(FluidTags.WATER)) {
            return pos.getY() + fluidState.getHeight(world, pos) + 0.025;
        }
        return pos.getY() + 0.15 + random.nextDouble() * 0.7;
    }

    private static float[] foamColor(ClientLevel world, BlockPos pos, FluidState fluidState) {
        FluidRenderHandler handler = FluidRenderHandlerRegistry.INSTANCE.get(fluidState.getType());
        int color = handler != null ? handler.getFluidColor(world, pos, fluidState) : 0x3F76E4;
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        return new float[]{
                brightenTowardFoam(r),
                brightenTowardFoam(g),
                brightenTowardFoam(b)
        };
    }

    private static float brightenTowardFoam(float channel) {
        return Mth.clamp(channel * 1.25f + 0.28f, 0.0f, 1.0f);
    }

    private static void tint(Particle particle, float[] color) {
        if (particle != null) {
            particle.setColor(color[0], color[1], color[2]);
        }
    }

    private static SpawnBudget currentTickBudget(ClientLevel world) {
        long worldTime = world.getGameTime();
        if (world != budgetWorld || worldTime != budgetWorldTime) {
            budgetWorld = world;
            budgetWorldTime = worldTime;
            tickBudget = new SpawnBudget(SPAWN_BUDGET_PER_TICK);
        }
        return tickBudget;
    }

    private static Particle addParticle(Minecraft client, SpawnBudget budget,
                                        SimpleParticleType type,
                                        double x, double y, double z,
                                        double vx, double vy, double vz) {
        if (!budget.hasRemaining()) {
            return null;
        }
        Particle particle = client.particleEngine.createParticle(type, x, y, z, vx, vy, vz);
        if (particle != null) {
            budget.consume();
        }
        return particle;
    }

    private static final class SpawnBudget {
        private int remaining;

        private SpawnBudget(int limit) {
            remaining = limit;
        }

        private boolean hasRemaining() {
            return remaining > 0;
        }

        private void consume() {
            remaining--;
        }
    }
}

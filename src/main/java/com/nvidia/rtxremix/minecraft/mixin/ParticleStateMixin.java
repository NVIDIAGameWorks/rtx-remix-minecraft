/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.profiling.ParticleTickProfiler;
import com.nvidia.rtxremix.minecraft.particle.RemixParticleStateAccess;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

@Mixin(Particle.class)
public abstract class ParticleStateMixin implements RemixParticleStateAccess {
    private static final boolean rtxremix$FAST_AIR_COLLISION =
            Boolean.parseBoolean(System.getProperty("rtxremix.particles.fastAirCollision", "true"));
    private static final int rtxremix$FAST_AIR_COLLISION_MAX_BLOCKS = Math.max(1,
            Integer.getInteger("rtxremix.particles.fastAirCollisionMaxBlocks", 8));
    private static final double rtxremix$COLLISION_EPSILON = 1.0E-7D;
    private static final ThreadLocal<BlockPos.MutableBlockPos> rtxremix$COLLISION_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private Object rtxremix$particleBucket;
    private int rtxremix$particleSlot = -1;

    @Redirect(
        method = "move(DDD)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 rtxremix$adjustMovementForParticleCollision(
            Entity entity,
            Vec3 movement,
            AABB box,
            Level world,
            List<VoxelShape> collisions) {
        if (rtxremix$canFastPathAirCollision(world, box, movement, collisions)) {
            ParticleTickProfiler.recordSkippedCollisionCheck();
            return movement;
        }

        ParticleTickProfiler.beginCollisionCheck();
        try {
            return Entity.collideBoundingBox(entity, movement, box, world, collisions);
        } finally {
            ParticleTickProfiler.endCollisionCheck();
        }
    }

    @Override
    public Object rtxremix$getParticleBucket() {
        return rtxremix$particleBucket;
    }

    @Override
    public void rtxremix$setParticleBucket(Object bucket) {
        rtxremix$particleBucket = bucket;
    }

    @Override
    public int rtxremix$getParticleSlot() {
        return rtxremix$particleSlot;
    }

    @Override
    public void rtxremix$setParticleSlot(int slot) {
        rtxremix$particleSlot = slot;
    }

    private static boolean rtxremix$canFastPathAirCollision(
            Level world,
            AABB box,
            Vec3 movement,
            List<VoxelShape> collisions) {
        if (!rtxremix$FAST_AIR_COLLISION
                || !rtxremix$isRtxParticleOptimizationActive()
                || !collisions.isEmpty()) {
            return false;
        }

        double minX = movement.x < 0.0D ? box.minX + movement.x : box.minX;
        double maxX = movement.x > 0.0D ? box.maxX + movement.x : box.maxX;
        double minY = movement.y < 0.0D ? box.minY + movement.y : box.minY;
        double maxY = movement.y > 0.0D ? box.maxY + movement.y : box.maxY;
        double minZ = movement.z < 0.0D ? box.minZ + movement.z : box.minZ;
        double maxZ = movement.z > 0.0D ? box.maxZ + movement.z : box.maxZ;

        int blockMinX = Mth.floor(minX - rtxremix$COLLISION_EPSILON);
        int blockMaxX = Mth.floor(maxX + rtxremix$COLLISION_EPSILON);
        int blockMinY = Mth.floor(minY - rtxremix$COLLISION_EPSILON);
        int blockMaxY = Mth.floor(maxY + rtxremix$COLLISION_EPSILON);
        int blockMinZ = Mth.floor(minZ - rtxremix$COLLISION_EPSILON);
        int blockMaxZ = Mth.floor(maxZ + rtxremix$COLLISION_EPSILON);

        int checked = 0;
        BlockPos.MutableBlockPos pos = rtxremix$COLLISION_POS.get();
        for (int x = blockMinX; x <= blockMaxX; x++) {
            for (int y = blockMinY; y <= blockMaxY; y++) {
                for (int z = blockMinZ; z <= blockMaxZ; z++) {
                    if (++checked > rtxremix$FAST_AIR_COLLISION_MAX_BLOCKS) {
                        return false;
                    }
                    pos.set(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (!state.getCollisionShape(world, pos).isEmpty()) {
                        return false;
                    }
                }
            }
        }

        return checked > 0;
    }

    private static boolean rtxremix$isRtxParticleOptimizationActive() {
        if (!RtxRemixMod.isRtxEnabled()
                || !RtxRemixMod.isWorldEnabled()
                || !RtxRemixMod.isParticlesEnabled()) {
            return false;
        }
        RtxRemixMod mod = RtxRemixMod.getInstance();
        return mod != null
                && mod.getRenderer() != null
                && mod.getRenderer().isInitialized();
    }
}

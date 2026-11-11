/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

/**
 * Vanilla's line-of-sight probe for sections far from the camera, without its per-step
 * allocations.
 *
 * <p>Vanilla's visibility walk admits a neighbour more than 60 blocks from the camera only if
 * a ray from the neighbour's near corner toward the camera passes exclusively through
 * sections that exist and are already in the visible graph. It advances the ray in fixed
 * steps and, per step, allocates a Vec3d for the remaining offset, a Vec3d for the advanced
 * point and a BlockPos for the lookup. This reproduces the same arithmetic in the same order
 * on doubles, so the probed positions and the verdict are bit-identical to vanilla's.
 */
public final class FarChunkProbe {

    /** Answers whether the section holding a block position exists and is in the graph. */
    @FunctionalInterface
    public interface GraphLookup {
        boolean contains(int blockX, int blockY, int blockZ);
    }

    /** Squared distance at which the ray is close enough to the camera to stop. */
    private static final double NEAR_CAMERA_DISTANCE_SQUARED = 3600.0;
    /** Below this length vanilla's {@code Vec3d.normalize} yields the zero vector. */
    private static final double NORMALIZE_EPSILON = 1.0E-4;

    private FarChunkProbe() {}

    /**
     * Runs the probe from the neighbour section at {@code origin} entered through a face on
     * {@code axis}, toward the camera at ({@code cameraX}, {@code cameraY}, {@code cameraZ}).
     *
     * @param cameraSectionCenterX camera section origin plus eight, per axis; picks the corner
     *                             of the neighbour the ray starts from
     * @param step                 ray advance per iteration in blocks
     * @param worldTopYExclusive   one past the highest block, as {@code HeightLimitView.getTopY()}
     *                             returns it (320 for the overworld); a point above it or below
     *                             {@code worldBottomY} leaves the world, which passes
     * @param worldBottomY         inclusive bottom of the world
     * @return true when every probed section is in the graph or the ray left the world
     */
    public static boolean reachesCamera(
            int originX, int originY, int originZ,
            Direction.Axis axis,
            int cameraSectionCenterX, int cameraSectionCenterY, int cameraSectionCenterZ,
            double cameraX, double cameraY, double cameraZ,
            double step,
            int worldTopYExclusive, int worldBottomY,
            GraphLookup graph) {
        double pointX = originX + (axis == Direction.Axis.X
                ? (cameraSectionCenterX > originX ? 16 : 0)
                : (cameraSectionCenterX < originX ? 16 : 0));
        double pointY = originY + (axis == Direction.Axis.Y
                ? (cameraSectionCenterY > originY ? 16 : 0)
                : (cameraSectionCenterY < originY ? 16 : 0));
        double pointZ = originZ + (axis == Direction.Axis.Z
                ? (cameraSectionCenterZ > originZ ? 16 : 0)
                : (cameraSectionCenterZ < originZ ? 16 : 0));

        double toCameraX = cameraX - pointX;
        double toCameraY = cameraY - pointY;
        double toCameraZ = cameraZ - pointZ;
        double length = Math.sqrt(toCameraX * toCameraX + toCameraY * toCameraY + toCameraZ * toCameraZ);
        double stepX = 0.0;
        double stepY = 0.0;
        double stepZ = 0.0;
        if (!(length < NORMALIZE_EPSILON)) {
            stepX = toCameraX / length * step;
            stepY = toCameraY / length * step;
            stepZ = toCameraZ / length * step;
        }

        while (true) {
            double remainingX = cameraX - pointX;
            double remainingY = cameraY - pointY;
            double remainingZ = cameraZ - pointZ;
            if (!(remainingX * remainingX + remainingY * remainingY + remainingZ * remainingZ
                    > NEAR_CAMERA_DISTANCE_SQUARED)) {
                return true;
            }
            pointX += stepX;
            pointY += stepY;
            pointZ += stepZ;
            if (pointY > (double) worldTopYExclusive || pointY < (double) worldBottomY) {
                return true;
            }
            if (!graph.contains(
                    Mth.floor(pointX), Mth.floor(pointY), Mth.floor(pointZ))) {
                return false;
            }
        }
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarChunkProbeTest {

    /** {@code ceil(sqrt(3) * 16)}, vanilla's step. */
    private static final double STEP = Math.ceil(Math.sqrt(3.0) * 16.0);
    /** The overworld's range as the mixin passes it: getBottomY() and getTopY(), the latter one past the top block. */
    private static final int WORLD_BOTTOM = -64;
    private static final int WORLD_TOP = 320;

    /**
     * Vanilla's probe, transcribed with its Vec3d chain intact. The port must probe the same
     * block positions in the same order and reach the same verdict.
     */
    private enum Verdict { REACHED, BLOCKED, LEFT_WORLD }

    private static Verdict vanillaReachesCamera(
            BlockPos origin, Direction direction, BlockPos cameraSectionCenter, Vec3 cameraPos,
            int worldTop, int worldBottom, FarChunkProbe.GraphLookup graph) {
        BlockPos corner = origin.offset(
                direction.getAxis() == Direction.Axis.X
                        ? (cameraSectionCenter.getX() > origin.getX() ? 16 : 0)
                        : (cameraSectionCenter.getX() < origin.getX() ? 16 : 0),
                direction.getAxis() == Direction.Axis.Y
                        ? (cameraSectionCenter.getY() > origin.getY() ? 16 : 0)
                        : (cameraSectionCenter.getY() < origin.getY() ? 16 : 0),
                direction.getAxis() == Direction.Axis.Z
                        ? (cameraSectionCenter.getZ() > origin.getZ() ? 16 : 0)
                        : (cameraSectionCenter.getZ() < origin.getZ() ? 16 : 0));
        Vec3 point = new Vec3(corner.getX(), corner.getY(), corner.getZ());
        Vec3 step = cameraPos.subtract(point).normalize().scale(STEP);
        while (cameraPos.subtract(point).lengthSqr() > 3600.0) {
            point = point.add(step);
            if (point.y > worldTop || point.y < worldBottom) {
                return Verdict.LEFT_WORLD;
            }
            BlockPos probe = BlockPos.containing(point.x, point.y, point.z);
            if (!graph.contains(probe.getX(), probe.getY(), probe.getZ())) {
                return Verdict.BLOCKED;
            }
        }
        return Verdict.REACHED;
    }

    /** Deterministic sparse graph: one section in twelve is missing. */
    private static final class RecordingGraph implements FarChunkProbe.GraphLookup {
        private final long salt;
        final List<Long> probed = new ArrayList<>();

        RecordingGraph(long salt) {
            this.salt = salt;
        }

        @Override
        public boolean contains(int blockX, int blockY, int blockZ) {
            probed.add(BlockPos.asLong(blockX, blockY, blockZ));
            long section = BlockPos.asLong(blockX >> 4, blockY >> 4, blockZ >> 4) ^ salt;
            return Long.hashCode(section * 0x9E3779B97F4A7C15L) % 12 != 0;
        }
    }

    @Test
    void probesTheSamePositionsAndVerdictAsVanillaInTheOverworld() {
        probesTheSamePositionsAndVerdictAsVanilla(WORLD_TOP, WORLD_BOTTOM, 0x5EED);
    }

    /** The Nether's 0..128 range: a shorter world, so more rays cross out of it. */
    @Test
    void probesTheSamePositionsAndVerdictAsVanillaInTheNether() {
        probesTheSamePositionsAndVerdictAsVanilla(128, 0, 0x7E7);
    }

    private static void probesTheSamePositionsAndVerdictAsVanilla(int worldTop, int worldBottom, long seed) {
        Random random = new Random(seed);
        int passes = 0;
        int leftTheWorld = 0;
        for (int trial = 0; trial < 20_000; trial++) {
            // The camera ranges well outside the world: a ray between two points inside
            // the height range never leaves it, so only a camera above build height or
            // below bedrock (both reachable in spectator) exercises the early exit.
            Vec3 cameraPos = new Vec3(
                    (random.nextDouble() - 0.5) * 200.0,
                    worldBottom - 128 + random.nextDouble() * (worldTop - worldBottom + 256),
                    (random.nextDouble() - 0.5) * 200.0);
            BlockPos cameraSectionOrigin = new BlockPos(
                    Mth.floor(cameraPos.x / 16.0) * 16,
                    Mth.floor(cameraPos.y / 16.0) * 16,
                    Mth.floor(cameraPos.z / 16.0) * 16);
            BlockPos cameraSectionCenter = cameraSectionOrigin.offset(8, 8, 8);
            BlockPos origin = new BlockPos(
                    (random.nextInt(80) - 40) * 16,
                    Mth.clamp((random.nextInt(30) - 5) * 16, worldBottom, worldTop - 16),
                    (random.nextInt(80) - 40) * 16);
            Direction direction = Direction.values()[random.nextInt(6)];
            long salt = random.nextLong();

            RecordingGraph vanillaGraph = new RecordingGraph(salt);
            Verdict verdict = vanillaReachesCamera(
                    origin, direction, cameraSectionCenter, cameraPos, worldTop, worldBottom, vanillaGraph);
            boolean expected = verdict != Verdict.BLOCKED;

            RecordingGraph portedGraph = new RecordingGraph(salt);
            boolean actual = FarChunkProbe.reachesCamera(
                    origin.getX(), origin.getY(), origin.getZ(),
                    direction.getAxis(),
                    cameraSectionCenter.getX(), cameraSectionCenter.getY(), cameraSectionCenter.getZ(),
                    cameraPos.x, cameraPos.y, cameraPos.z,
                    STEP,
                    worldTop, worldBottom,
                    portedGraph);

            assertEquals(expected, actual, "verdict, trial " + trial);
            assertEquals(vanillaGraph.probed, portedGraph.probed, "probed positions, trial " + trial);
            if (expected) {
                passes++;
            }
            if (verdict == Verdict.LEFT_WORLD) {
                leftTheWorld++;
            }
        }
        assertTrue(passes > 2_000 && passes < 18_000, "both verdicts exercised: " + passes);
        assertTrue(leftTheWorld > 200, "the early exit on leaving the world is exercised: " + leftTheWorld);
    }

    @Test
    void cornerCoincidingWithCameraPassesWithoutProbing() {
        RecordingGraph graph = new RecordingGraph(1L);
        boolean reaches = FarChunkProbe.reachesCamera(
                0, 0, 0, Direction.Axis.X,
                8, 8, 8,
                16.0, 0.0, 0.0,
                STEP, WORLD_TOP, WORLD_BOTTOM, graph);

        assertTrue(reaches);
        assertTrue(graph.probed.isEmpty());
    }
}

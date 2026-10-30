/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.nvidia.rtxremix.minecraft.light.BlockLightRuleManager;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;

/**
 * Samples the baked model of the block currently being meshed, so a block light
 * rule can be positioned against the geometry that actually rendered.
 *
 * <p>Rules see only a {@link BlockState} and a {@link net.minecraft.core.BlockPos},
 * which is enough for a block whose model is fixed. It is not enough for one
 * whose blockstate picks among rotated variants: the rotation lives in the
 * blockstate JSON, not in a property, so no offset table written against the
 * unrotated model can follow it. Only the mesh emitter knows which variant a
 * position drew, so it hands that knowledge over through this.
 *
 * <p>One instance per {@link BlockQuadEmitter}, therefore per worker thread.
 * {@link #begin} resets it for the next block; everything is computed lazily and
 * memoised, so a block whose rules never ask pays only the reset.
 */
public final class ModelSampling implements BlockLightRuleManager.ModelSampler {

    /** Matches the emission loop, so a sampled variant is the drawn variant. */
    private static final Direction[] DIRECTIONS = {
        null, Direction.UP, Direction.DOWN, Direction.NORTH,
        Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    /** Own random, so sampling cannot perturb the emitter's model randomisation. */
    private final SingleThreadedRandomSource random = new SingleThreadedRandomSource(0L);

    private BlockRenderDispatcher blockRenderManager;
    private BlockState state;
    private long seed;

    private BakedModel model;
    private boolean modelResolved;

    private float[] vertices = new float[3 * 256];
    private int vertexCount;
    private boolean verticesCollected;

    private final float[] centroid = new float[3];
    private boolean centroidComputed;

    /** Rebinds this sampler to a new block and drops everything memoised. */
    public void begin(BlockRenderDispatcher blockRenderManager, BlockState state, long seed) {
        this.blockRenderManager = blockRenderManager;
        this.state = state;
        this.seed = seed;
        this.model = null;
        this.modelResolved = false;
        this.vertexCount = 0;
        this.verticesCollected = false;
        this.centroidComputed = false;
    }

    @Override
    public float[] centroid() {
        if (centroidComputed) return centroid;
        centroidComputed = true;

        collect();
        if (vertexCount == 0) {
            centroid[0] = 0.5f;
            centroid[1] = 0.5f;
            centroid[2] = 0.5f;
            return centroid;
        }

        double sx = 0, sy = 0, sz = 0;
        for (int i = 0; i < vertexCount * 3; i += 3) {
            sx += vertices[i];
            sy += vertices[i + 1];
            sz += vertices[i + 2];
        }
        centroid[0] = (float) (sx / vertexCount);
        centroid[1] = (float) (sy / vertexCount);
        centroid[2] = (float) (sz / vertexCount);
        return centroid;
    }

    private void collect() {
        if (verticesCollected) return;
        verticesCollected = true;

        if (!modelResolved) {
            modelResolved = true;
            model = (blockRenderManager != null && state != null)
                    ? blockRenderManager.getBlockModel(state)
                    : null;
        }
        if (model == null) return;

        for (Direction dir : DIRECTIONS) {
            // Re-seed per direction exactly as the emission loop does, so a
            // multi-variant model resolves to the same variant here as on screen.
            random.setSeed(seed);
            for (BakedQuad quad : model.getQuads(state, dir, random)) {
                int[] vd = quad.getVertices();
                ensureCapacity(vertexCount + 4);
                for (int v = 0; v < 4; v++) {
                    int off = v * 8;
                    int w = vertexCount * 3;
                    vertices[w] = Float.intBitsToFloat(vd[off]);
                    vertices[w + 1] = Float.intBitsToFloat(vd[off + 1]);
                    vertices[w + 2] = Float.intBitsToFloat(vd[off + 2]);
                    vertexCount++;
                }
            }
        }
    }

    private void ensureCapacity(int wantedVertices) {
        if (wantedVertices * 3 <= vertices.length) return;
        int cap = vertices.length;
        while (cap < wantedVertices * 3) cap <<= 1;
        float[] grown = new float[cap];
        System.arraycopy(vertices, 0, grown, 0, vertexCount * 3);
        vertices = grown;
    }
}

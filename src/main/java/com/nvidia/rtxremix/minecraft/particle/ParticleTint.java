/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SmokeParticle;

/**
 * Vertex tint a particle is drawn with when vanilla's colour is the wrong one
 * for a path tracer.
 *
 * <p>A vanilla particle colour is a tint for a quad shaded by the lightmap, which
 * clamps at "fully lit". Remix instead lights the quad from its real surroundings,
 * and a torch's smoke sits a fraction of a block from a small, bright light, so the
 * 0 to 0.3 grey vanilla gives soot comes back near white and vanishes over a
 * torch-lit floor while reading as a bright puff over water. Drawn nearly black,
 * a puff stays a dark speck that darkens whatever is behind it, which is the look
 * vanilla's tint was drawn to give.
 *
 * <p>These are display-space values, like every other vertex colour the mod
 * writes: the runtime multiplies the sprite by the vertex colour and decodes the
 * product with a 2.2 gamma, so a grey of 0.04 reaches the path tracer as an
 * albedo near 0.001, not as soot's physical few percent.
 */
public final class ParticleTint {

    /** Vertex grey of a fire's smoke, tuned to read as soot beside a torch. */
    public static final float SOOT_GREY = 0.04f;

    private ParticleTint() {}

    /**
     * Whether the particle is one vanilla tints as a dark speck: a
     * {@link SmokeParticle} ({@code smoke} and {@code large_smoke}), which
     * {@code AscendingParticle} colours random * 0.3 grey.
     * {@code CampfireSmokeParticle} is deliberately outside: vanilla draws it
     * white over mid-grey sprites, so it has no dark tint to reproduce and
     * keeps its sprite colour.
     */
    public static boolean isSoot(Particle particle) {
        return particle instanceof SmokeParticle;
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

/**
 * Per-particle Remix bookkeeping supplied by the host adapter: the bucket a
 * particle was assigned to and its slot within that bucket, so a live particle
 * keeps the same Remix mesh slot between frames.
 *
 * <p>Written and read by the particle worker during preparation and by the
 * render thread at publication; a particle is only ever touched by one of them
 * at a time.
 */
public interface RemixParticleStateAccess {
    Object rtxremix$getParticleBucket();
    void rtxremix$setParticleBucket(Object bucket);
    int rtxremix$getParticleSlot();
    void rtxremix$setParticleSlot(int slot);
}

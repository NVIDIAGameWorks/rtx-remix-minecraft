/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

/**
 * Generation of the grass and foliage colormaps. A biome's constant grass and foliage
 * colours depend on those maps, and a resource reload can replace the maps while the same
 * {@code Biome} instances stay loaded, so a colour cached on the biome is valid only for the
 * generation it was computed under. Bumped from the render thread when either map is set;
 * read on the section-build workers, so the field is volatile and a write before the bump
 * is visible to any reader that sees the new generation.
 */
public final class BiomeColorGeneration {
    private static volatile int generation = 1;

    private BiomeColorGeneration() {
    }

    public static int current() {
        return generation;
    }

    /** Invalidates every cached biome colour. Called when a colormap is replaced. */
    public static void invalidate() {
        generation++;
    }

    /**
     * A colour cached on a biome, as the boxed {@link Integer} vanilla's own path produced,
     * so that handing it back costs no allocation: the colours are far outside the small
     * values the JDK's Integer cache covers, and boxing one per lookup was 0.67 GB over a
     * benchmark run, the largest allocation the mod made. Immutable, so a reader that sees
     * the holder sees both fields.
     */
    public static final class Cached {
        private final int generation;
        private final Integer color;

        public Cached(int generation, Integer color) {
            this.generation = generation;
            this.color = color;
        }

        public int generation() {
            return generation;
        }

        public Integer color() {
            return color;
        }

        public boolean isCurrent() {
            return generation == current();
        }
    }
}

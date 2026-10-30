/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelHeightAccessor;

/**
 * Vanilla's {@code ChunkSkyLight.refreshSurfaceY} walk with each column started at its
 * {@code WORLD_SURFACE} height instead of the top of the chunk's highest non-empty section.
 *
 * <p>The walk is vanilla's, step for step: the same block pairs are tested with the same
 * predicate in the same order, only the leading air-over-air pairs are not visited. A pair
 * of air blocks never blocks sky light (air has zero opacity and no opaque shape), so the
 * result is identical as long as no block sits at or above the seed; that is the heightmap's
 * contract, and {@link SkyLightSurfaceScanPolicy} states what it assumes of the sender. A
 * seed above vanilla's own start is clamped to it, so a heightmap value beyond the world
 * top only makes the walk vanilla's.
 *
 * <p>Generic over the block-state type and independent of the chunk classes: the mixin binds
 * vanilla's private predicate and palette writer, a unit test binds synthetic columns, and
 * the walk itself never inspects a state.
 */
public final class SeededSurfaceScan {

    /** One column's {@code WORLD_SURFACE} height. */
    public interface SurfaceHeight {
        int get(int localX, int localZ);
    }

    /** The chunk's sections, indexed from the world bottom as {@code Chunk.getSection} indexes them. */
    public interface Sections<S> {
        boolean isEmpty(int index);

        /** The state at local coordinates within section {@code index}; asked only of non-empty sections. */
        S state(int index, int localX, int localY, int localZ);
    }

    /** Vanilla's {@code ChunkSkyLight.faceBlocksLight}: whether the lower block of a vertical pair stops sky light. */
    public interface FaceBlocksLight<S> {
        boolean test(BlockPos upperPos, S upperState, BlockPos lowerPos, S lowerState);
    }

    /** Receives one column's surface Y, already raised to the palette's minimum. */
    public interface SurfaceSink {
        void set(int localX, int localZ, int surfaceY);
    }

    private SeededSurfaceScan() {
    }

    /**
     * Computes every column's sky-light surface.
     *
     * @param world                  the chunk's height limits
     * @param highestNonEmptySection {@code Chunk.getHighestNonEmptySection()}, -1 for a chunk without blocks
     * @param surface                the chunk's {@code WORLD_SURFACE} heightmap as received in the packet
     * @param air                    the air state the walk carries above the seed and across empty sections
     * @param sections               the chunk's sections
     * @param faceBlocksLight        vanilla's pair predicate
     * @param sink                   receives the 256 surfaces
     */
    public static <S> void refreshSurfaceY(LevelHeightAccessor world, int highestNonEmptySection, SurfaceHeight surface,
                                           S air, Sections<S> sections, FaceBlocksLight<S> faceBlocksLight,
                                           SurfaceSink sink) {
        int minY = world.getMinY() - 1;
        if (highestNonEmptySection == -1) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    sink.set(x, z, minY);
                }
            }
            return;
        }
        int vanillaStart = SectionPos.sectionToBlockCoord(world.getSectionYFromSectionIndex(highestNonEmptySection) + 1);
        BlockPos.MutableBlockPos upper = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos lower = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int seed = Math.min(surface.get(x, z), vanillaStart);
                int found = walkDownFrom(world, seed, x, z, upper, lower, air, sections, faceBlocksLight, minY);
                sink.set(x, z, Math.max(found, minY));
            }
        }
    }

    /**
     * Vanilla's {@code calculateSurfaceY} from the pair ({@code upperY}, {@code upperY - 1})
     * downward. The upper state is read the way vanilla's walk would have carried it into
     * that pair: through the section when the section is non-empty, air otherwise. Local
     * coordinates go into the positions as vanilla passes them. A seed at or below the world
     * bottom visits no pair and yields the minimum, as vanilla reports for an empty column.
     */
    private static <S> int walkDownFrom(LevelHeightAccessor world, int upperY, int localX, int localZ,
                                        BlockPos.MutableBlockPos upper, BlockPos.MutableBlockPos lower, S air, Sections<S> sections,
                                        FaceBlocksLight<S> faceBlocksLight, int minY) {
        upper.set(localX, upperY, localZ);
        lower.set(localX, upperY - 1, localZ);
        S upperState = stateAt(world, upperY, localX, localZ, air, sections);
        int startIndex = world.getSectionIndex(upperY - 1);
        int startK = (upperY - 1) & 15;
        for (int j = startIndex; j >= 0; --j) {
            if (sections.isEmpty(j)) {
                upperState = air;
                upper.setY(SectionPos.sectionToBlockCoord(world.getSectionYFromSectionIndex(j)));
                lower.setY(upper.getY() - 1);
                continue;
            }
            for (int k = j == startIndex ? startK : 15; k >= 0; --k) {
                S lowerState = sections.state(j, localX, k, localZ);
                if (faceBlocksLight.test(upper, upperState, lower, lowerState)) {
                    return upper.getY();
                }
                upperState = lowerState;
                upper.set(lower);
                lower.move(Direction.DOWN);
            }
        }
        return minY;
    }

    private static <S> S stateAt(LevelHeightAccessor world, int y, int localX, int localZ, S air, Sections<S> sections) {
        int index = world.getSectionIndex(y);
        if (index < 0 || index >= world.getSectionsCount() || sections.isEmpty(index)) {
            return air;
        }
        return sections.state(index, localX, y & 15, localZ);
    }
}

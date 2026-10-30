/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelHeightAccessor;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Drives the seeded walk over synthetic columns and compares it with a naive oracle that
 * walks every pair from the world top: the seeded walk must visit exactly the oracle's
 * pairs from the seed pair downward, with the same states, and report the same surface.
 */
class SeededSurfaceScanTest {

    // Synthetic block states. The predicate depends on the upper state in one rule, so a
    // wrong upper-state carry across a pair or an empty section is observable.
    private static final int AIR = 0;
    private static final int SOLID = 1;
    private static final int TOP_SLAB = 2;
    private static final int BOTTOM_SLAB = 3;

    private record Pair(int upperY, int upperState, int lowerY, int lowerState) {
    }

    /** A synthetic chunk: {@code blocks[x][z][y - bottomY]}. */
    private static final class Column {
        final LevelHeightAccessor world;
        final int[][][] blocks;

        Column(int bottomY, int height) {
            world = LevelHeightAccessor.create(bottomY, height);
            blocks = new int[16][16][height];
        }

        int state(int x, int y, int z) {
            int i = y - world.getMinBuildHeight();
            return i < 0 || i >= world.getHeight() ? AIR : blocks[x][z][i];
        }

        void set(int x, int y, int z, int state) {
            blocks[x][z][y - world.getMinBuildHeight()] = state;
        }

        boolean sectionEmpty(int index) {
            int bottom = SectionPos.sectionToBlockCoord(world.getSectionYFromSectionIndex(index));
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = bottom; y < bottom + 16; y++) {
                        if (state(x, y, z) != AIR) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        int highestNonEmptySection() {
            for (int i = world.getSectionsCount() - 1; i >= 0; i--) {
                if (!sectionEmpty(i)) {
                    return i;
                }
            }
            return -1;
        }

        /** {@code WORLD_SURFACE}: one above the highest non-air block, the bottom if there is none. */
        int worldSurface(int x, int z) {
            for (int y = world.getMaxBuildHeight() - 1; y >= world.getMinBuildHeight(); y--) {
                if (state(x, y, z) != AIR) {
                    return y + 1;
                }
            }
            return world.getMinBuildHeight();
        }

        /** Sections as the walk sees them; an index outside the array throws, as {@code Chunk.getSection} does. */
        SeededSurfaceScan.Sections<Integer> sections() {
            return new SeededSurfaceScan.Sections<>() {
                @Override
                public boolean isEmpty(int index) {
                    return sectionEmpty(Objects.checkIndex(index, world.getSectionsCount()));
                }

                @Override
                public Integer state(int index, int localX, int localY, int localZ) {
                    Objects.checkIndex(index, world.getSectionsCount());
                    return Column.this.state(localX, SectionPos.sectionToBlockCoord(world.getSectionYFromSectionIndex(index)) + localY, localZ);
                }
            };
        }
    }

    private static boolean blocks(int upperState, int lowerState) {
        return lowerState == SOLID || (upperState == BOTTOM_SLAB && lowerState == TOP_SLAB);
    }

    /** Vanilla's walk from the world top, written plainly: every pair whose lower block is in a non-empty section. */
    private static int oracle(Column c, int x, int z, List<Pair> visited) {
        LevelHeightAccessor w = c.world;
        for (int lowerY = w.getMaxBuildHeight() - 1; lowerY >= w.getMinBuildHeight(); lowerY--) {
            if (c.sectionEmpty(w.getSectionIndex(lowerY))) {
                continue;
            }
            int upperY = lowerY + 1;
            boolean upperInEmptySection = upperY >= w.getMaxBuildHeight() || c.sectionEmpty(w.getSectionIndex(upperY));
            int upperState = upperInEmptySection ? AIR : c.state(x, upperY, z);
            int lowerState = c.state(x, lowerY, z);
            visited.add(new Pair(upperY, upperState, lowerY, lowerState));
            if (blocks(upperState, lowerState)) {
                return upperY;
            }
        }
        return w.getMinBuildHeight() - 1;
    }

    private static int[][] seeded(Column c, SeededSurfaceScan.SurfaceHeight surface, List<Pair>[][] visited) {
        int[][] result = new int[16][16];
        int[] column = new int[2];
        SeededSurfaceScan.FaceBlocksLight<Integer> predicate = (upperPos, upperState, lowerPos, lowerState) -> {
            visited[column[0]][column[1]].add(new Pair(upperPos.getY(), upperState, lowerPos.getY(), lowerState));
            assertEquals(column[0], upperPos.getX());
            assertEquals(column[1], lowerPos.getZ());
            assertEquals(upperPos.getY() - 1, lowerPos.getY());
            return blocks(upperState, lowerState);
        };
        SeededSurfaceScan.SurfaceHeight tracking = (x, z) -> {
            column[0] = x;
            column[1] = z;
            return surface.get(x, z);
        };
        SeededSurfaceScan.refreshSurfaceY(c.world, c.highestNonEmptySection(), tracking, AIR, c.sections(), predicate,
                (x, z, y) -> result[x][z] = y);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Pair>[][] pairLists() {
        List<Pair>[][] lists = new List[16][16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                lists[x][z] = new ArrayList<>();
            }
        }
        return lists;
    }

    /** Asserts that every column's seeded walk is the oracle's walk from the seed pair down, and reports the oracle's surface. */
    private static void assertMatchesOracle(Column c) {
        assertMatchesOracle(c, c::worldSurface);
    }

    private static void assertMatchesOracle(Column c, SeededSurfaceScan.SurfaceHeight surface) {
        List<Pair>[][] visited = pairLists();
        int[][] surfaces = seeded(c, surface, visited);
        int minY = c.world.getMinBuildHeight() - 1;
        int vanillaStart = c.highestNonEmptySection() == -1
                ? minY
                : SectionPos.sectionToBlockCoord(c.world.getSectionYFromSectionIndex(c.highestNonEmptySection()) + 1);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                List<Pair> expectedAll = new ArrayList<>();
                int expected = Math.max(oracle(c, x, z, expectedAll), minY);
                int seed = Math.min(surface.get(x, z), vanillaStart);
                List<Pair> expectedFromSeed = expectedAll.stream().filter(p -> p.upperY() <= seed).toList();
                String where = "column " + x + "," + z + " seed " + seed;
                assertEquals(expected, surfaces[x][z], where);
                assertEquals(expectedFromSeed, visited[x][z], where);
            }
        }
    }

    private static Column flat(int bottomY, int height, int groundY, int state) {
        Column c = new Column(bottomY, height);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = bottomY; y <= groundY; y++) {
                    c.set(x, y, z, state);
                }
            }
        }
        return c;
    }

    @Test
    void seedInTheMiddleOfASectionVisitsTheOraclePairsFromTheSeed() {
        Column c = flat(-64, 384, 70, SOLID);
        c.set(3, 71, 4, SOLID);
        c.set(3, 72, 4, TOP_SLAB);
        assertMatchesOracle(c);
        List<Pair>[][] visited = pairLists();
        seeded(c, c::worldSurface, visited);
        // Column 3,4 has its surface at 73, so the walk starts at the pair (73, 72) and stops
        // at the first blocking pair, the solid block at 71 under the slab.
        assertEquals(List.of(new Pair(73, AIR, 72, TOP_SLAB), new Pair(72, TOP_SLAB, 71, SOLID)), visited[3][4]);
    }

    @Test
    void seedAtASectionBoundaryStartsInTheSectionBelow() {
        // Ground at 63 puts the surface at 64, a section bottom: the first lower block is 63,
        // the top block (k == 15) of the section below.
        assertMatchesOracle(flat(-64, 384, 63, SOLID));
        // Ground at 48 puts the surface at 49: the first lower block is 48, k == 0 of its section.
        assertMatchesOracle(flat(-64, 384, 48, SOLID));
    }

    @Test
    void emptySectionsBelowTheSeedAreSkippedWithTheAirReset() {
        Column c = new Column(-64, 384);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                c.set(x, -60, z, SOLID);
            }
        }
        // A slab floating high above leaves several empty sections between it and the ground.
        c.set(5, 100, 5, TOP_SLAB);
        c.set(9, 100, 9, BOTTOM_SLAB);
        c.set(9, 99, 9, TOP_SLAB);
        assertMatchesOracle(c);
    }

    @Test
    void aSingleBlockAtTheBottomAndAnAllAirColumn() {
        Column c = new Column(-64, 384);
        c.set(0, -64, 0, SOLID);
        assertMatchesOracle(c);
        List<Pair>[][] visited = pairLists();
        int[][] surfaces = seeded(c, c::worldSurface, visited);
        assertEquals(-63, surfaces[0][0]);
        // An all-air column of a chunk that has blocks elsewhere reads the palette minimum.
        assertEquals(-65, surfaces[1][1]);
    }

    @Test
    void aColumnWithoutABlockingPairReportsTheMinimum() {
        Column c = new Column(-64, 384);
        c.set(2, 10, 2, TOP_SLAB);
        c.set(2, 9, 2, TOP_SLAB);
        assertMatchesOracle(c);
        List<Pair>[][] visited = pairLists();
        assertEquals(-65, seeded(c, c::worldSurface, visited)[2][2]);
    }

    @Test
    void seedAtTheWorldTopReadsAirAbove() {
        Column c = flat(0, 256, 254, SOLID);
        c.set(7, 255, 7, SOLID);
        assertMatchesOracle(c);
    }

    @Test
    void aWhollyEmptyChunkFillsTheMinimum() {
        Column c = new Column(-64, 384);
        int[][] surfaces = seeded(c, c::worldSurface, pairLists());
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                assertEquals(-65, surfaces[x][z]);
            }
        }
    }

    @Test
    void aHeightAboveVanillaStartIsClampedToIt() {
        // A heightmap value above the world top must neither throw nor change the result;
        // the seed is at most vanilla's own start, the bottom of the section above the
        // highest non-empty one, where the walk is vanilla's exactly.
        Column c = flat(-64, 384, 70, SOLID);
        assertMatchesOracle(c, (x, z) -> c.world.getMaxBuildHeight() + 200);
        assertMatchesOracle(c, (x, z) -> c.world.getMaxBuildHeight());
    }

    @Test
    void randomColumnsMatchTheOracle() {
        Random random = new Random(20260918L);
        for (int trial = 0; trial < 40; trial++) {
            int bottomY = random.nextBoolean() ? -64 : 0;
            Column c = new Column(bottomY, random.nextBoolean() ? 384 : 256);
            int sections = c.world.getSectionsCount();
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int ground = bottomY + random.nextInt(c.world.getHeight() / 2);
                    for (int y = bottomY; y <= ground; y++) {
                        c.set(x, y, z, random.nextInt(10) == 0 ? AIR : SOLID);
                    }
                    for (int i = random.nextInt(4); i > 0; i--) {
                        int y = bottomY + random.nextInt(c.world.getHeight());
                        c.set(x, y, z, 1 + random.nextInt(3));
                    }
                }
            }
            // Blank out a few whole sections so empty-section skips occur below seeds.
            for (int i = random.nextInt(3); i > 0; i--) {
                int index = random.nextInt(sections);
                int bottom = SectionPos.sectionToBlockCoord(c.world.getSectionYFromSectionIndex(index));
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = bottom; y < bottom + 16; y++) {
                            c.set(x, y, z, AIR);
                        }
                    }
                }
            }
            assertMatchesOracle(c);
        }
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.visibility;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HierarchicalFrustumCullerTest {

    @Test
    void hierarchicalResultMatchesBruteForceAndPreservesOrder() {
        Random random = new Random(0x5EED);
        List<BoxEntry> entries = new ArrayList<>();
        HierarchicalFrustumCuller<BoxEntry> culler = new HierarchicalFrustumCuller<>();

        for (int i = 0; i < 10_000; i++) {
            int x = (random.nextInt(129) - 64) * 16;
            int y = (random.nextInt(24) - 8) * 16;
            int z = (random.nextInt(129) - 64) * 16;
            BoxEntry entry = new BoxEntry(i, x, y, z, x + 16, y + 16, z + 16);
            entries.add(entry);
            add(culler, entry);
        }

        for (int sample = 0; sample < 100; sample++) {
            double minX = random.nextInt(800) - 400;
            double minY = random.nextInt(160) - 80;
            double minZ = random.nextInt(800) - 400;
            AxisAlignedView view = new AxisAlignedView(
                    minX, minY, minZ,
                    minX + 128 + random.nextInt(512),
                    minY + 64 + random.nextInt(256),
                    minZ + 128 + random.nextInt(512));

            List<BoxEntry> expected = entries.stream().filter(view::intersects).toList();
            List<BoxEntry> actual = new ArrayList<>();
            culler.forEachVisible(view::classify, view::intersects, actual::add);
            assertEquals(expected, actual);
        }
    }

    @Test
    void aClearedCullerForgetsItsPreviousFillAndMatchesBruteForceAgain() {
        Random random = new Random(0xC1EA2);
        HierarchicalFrustumCuller<BoxEntry> culler = new HierarchicalFrustumCuller<>();
        for (int i = 0; i < 5_000; i++) {
            int x = (random.nextInt(65) - 32) * 16;
            int z = (random.nextInt(65) - 32) * 16;
            add(culler, new BoxEntry(i, x, 0, z, x + 16, 16, z + 16));
        }

        culler.clear();
        assertEquals(0, culler.size());

        // Slots from the first fill are reused for different entries at different places, which
        // a culler that kept stale slot ownership would reject or mis-bucket.
        List<BoxEntry> entries = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            int x = (random.nextInt(65) - 32) * 16;
            int y = (random.nextInt(24) - 8) * 16;
            int z = (random.nextInt(65) - 32) * 16;
            BoxEntry entry = new BoxEntry(i, x, y, z, x + 16, y + 16, z + 16);
            entries.add(entry);
            add(culler, entry);
        }
        assertEquals(entries.size(), culler.size());
        for (int sample = 0; sample < 50; sample++) {
            double minX = random.nextInt(600) - 300;
            double minY = random.nextInt(160) - 80;
            double minZ = random.nextInt(600) - 300;
            AxisAlignedView view = new AxisAlignedView(
                    minX, minY, minZ, minX + 96 + random.nextInt(400), minY + 48 + random.nextInt(200), minZ + 96 + random.nextInt(400));
            List<BoxEntry> expected = entries.stream().filter(view::intersects).toList();
            List<BoxEntry> actual = new ArrayList<>();
            culler.forEachVisible(view::classify, view::intersects, actual::add);
            assertEquals(expected, actual);
        }
    }

    @Test
    void entriesAddedAfterInitialFilteringJoinTheSameOrderedIndex() {
        HierarchicalFrustumCuller<BoxEntry> culler = new HierarchicalFrustumCuller<>();
        BoxEntry first = new BoxEntry(1, 0, 0, 0, 16, 16, 16);
        BoxEntry second = new BoxEntry(2, 16, 0, 0, 32, 16, 16);
        add(culler, first);

        AxisAlignedView view = new AxisAlignedView(-1, -1, -1, 33, 17, 17);
        List<BoxEntry> initial = new ArrayList<>();
        culler.forEachVisible(view::classify, view::intersects, initial::add);
        assertEquals(List.of(first), initial);

        add(culler, second);
        List<BoxEntry> updated = new ArrayList<>();
        culler.forEachVisible(view::classify, view::intersects, updated::add);
        assertEquals(List.of(first, second), updated);
    }

    @Test
    void reAddingAnIndexedEntryMovesItInsteadOfDuplicatingIt() {
        // Vanilla's reached-section set can hand the same re-originned section to the index
        // again, because the section's ChunkInfo hashes by its now-changed origin.
        HierarchicalFrustumCuller<MovableEntry> culler = new HierarchicalFrustumCuller<>();
        MovableEntry mover = new MovableEntry(0, 0, 0, 0);
        add(culler, mover);

        mover.moveTo(1024, 0, 0);
        add(culler, mover);

        AxisAlignedView everywhere = new AxisAlignedView(-100, -100, -100, 1200, 100, 100);
        List<MovableEntry> visible = new ArrayList<>();
        culler.forEachVisible(everywhere::classify, everywhere::intersects, visible::add);
        assertEquals(List.of(mover), visible, "one entry per slot, at its current position");

        AxisAlignedView newPosition = new AxisAlignedView(1000, -16, -16, 1100, 32, 32);
        visible.clear();
        culler.forEachVisible(newPosition::classify, newPosition::intersects, visible::add);
        assertEquals(List.of(mover), visible);
        assertEquals(1, culler.size());
    }

    @Test
    void aSlotCannotBeIndexedForTwoDifferentEntries() {
        HierarchicalFrustumCuller<BoxEntry> culler = new HierarchicalFrustumCuller<>();
        add(culler, new BoxEntry(5, 0, 0, 0, 16, 16, 16));
        assertThrows(IllegalStateException.class,
                () -> add(culler, new BoxEntry(5, 64, 0, 0, 80, 16, 16)));
    }

    @Test
    void jomlGroupClassificationMatchesIndividualFrustumTests() {
        Random random = new Random(0xF12A57L);
        HierarchicalFrustumCuller<BoxEntry> culler = new HierarchicalFrustumCuller<>();
        List<BoxEntry> entries = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            int x = (random.nextInt(129) - 64) * 16;
            int y = (random.nextInt(32) - 16) * 16;
            int z = (random.nextInt(129) - 64) * 16;
            BoxEntry entry = new BoxEntry(i, x, y, z, x + 16, y + 16, z + 16);
            entries.add(entry);
            add(culler, entry);
        }

        FrustumIntersection intersection = new FrustumIntersection(
                new Matrix4f().ortho(-320.0f, 320.0f, -192.0f, 192.0f, -512.0f, 512.0f));
        double frustumX = 37.25;
        double frustumY = -19.5;
        double frustumZ = 81.75;
        Predicate<BoxEntry> leafTest = entry -> intersection.testAab(
                (float) (entry.minX - frustumX),
                (float) (entry.minY - frustumY),
                (float) (entry.minZ - frustumZ),
                (float) (entry.maxX - frustumX),
                (float) (entry.maxY - frustumY),
                (float) (entry.maxZ - frustumZ));

        List<BoxEntry> expected = entries.stream().filter(leafTest).toList();
        List<BoxEntry> actual = new ArrayList<>();
        culler.forEachVisible(
                (minX, minY, minZ, maxX, maxY, maxZ) -> {
                    int classification = intersection.intersectAab(
                            (float) (minX - frustumX),
                            (float) (minY - frustumY),
                            (float) (minZ - frustumZ),
                            (float) (maxX - frustumX),
                            (float) (maxY - frustumY),
                            (float) (maxZ - frustumZ));
                    if (classification == FrustumIntersection.INSIDE) {
                        return HierarchicalFrustumCuller.INSIDE;
                    }
                    if (classification == FrustumIntersection.INTERSECT) {
                        return HierarchicalFrustumCuller.INTERSECT;
                    }
                    return HierarchicalFrustumCuller.OUTSIDE;
                },
                leafTest,
                actual::add);

        assertEquals(expected, actual);
    }

    @Test
    void anEntryThatLeavesItsCellIsCulledUntilItsSlotIsRebucketed() {
        HierarchicalFrustumCuller<MovableEntry> culler = new HierarchicalFrustumCuller<>();
        MovableEntry mover = new MovableEntry(0, 0, 0, 0);
        add(culler, mover);

        // The torus re-origins the slot far away, into the view. Cached bounds still say 0,0,0.
        mover.moveTo(1024, 0, 0);
        AxisAlignedView view = new AxisAlignedView(1000, -16, -16, 1100, 32, 32);

        List<MovableEntry> beforeRebucket = new ArrayList<>();
        culler.forEachVisible(view::classify, view::intersects, beforeRebucket::add);
        assertEquals(List.of(), beforeRebucket, "stale cached bounds should cull the moved entry");

        culler.rebucket(mover.slot, MovableEntry::readBounds);

        List<MovableEntry> afterRebucket = new ArrayList<>();
        culler.forEachVisible(view::classify, view::intersects, afterRebucket::add);
        assertEquals(List.of(mover), afterRebucket, "re-bucketing should restore the entry");
    }

    @Test
    void aMovedEntryLeavesItsOldCellExact() {
        HierarchicalFrustumCuller<MovableEntry> culler = new HierarchicalFrustumCuller<>();
        MovableEntry stationary = new MovableEntry(0, 0, 0, 0);
        MovableEntry mover = new MovableEntry(1, 16, 0, 0);
        add(culler, stationary);
        add(culler, mover);

        mover.moveTo(1024, 0, 0);
        culler.rebucket(mover.slot, MovableEntry::readBounds);

        // The old cell must not have been widened toward the new position: a view between the
        // two positions misses both cells outright and must never reach the per-entry test.
        AxisAlignedView between = new AxisAlignedView(200, -16, -16, 800, 32, 32);
        List<MovableEntry> visible = new ArrayList<>();
        culler.forEachVisible(between::classify, entry -> {
            throw new AssertionError("a view that misses every cell must not reach the per-entry test");
        }, visible::add);
        assertEquals(List.of(), visible);
    }

    @Test
    void rebucketingAnUnmovedOrUnindexedSlotChangesNothing() {
        HierarchicalFrustumCuller<MovableEntry> culler = new HierarchicalFrustumCuller<>();
        MovableEntry stationary = new MovableEntry(0, 0, 0, 0);
        add(culler, stationary);

        culler.rebucket(stationary.slot, MovableEntry::readBounds);
        culler.rebucket(4_096, MovableEntry::readBounds);
        culler.rebucket(-1, MovableEntry::readBounds);

        // Nothing moved, so the cell keeps exact classification: a view that misses the cell
        // must still emit nothing rather than falling back to per-entry tests.
        AxisAlignedView elsewhere = new AxisAlignedView(1000, 1000, 1000, 1100, 1100, 1100);
        List<MovableEntry> visible = new ArrayList<>();
        culler.forEachVisible(elsewhere::classify, entry -> {
            throw new AssertionError("a clean cell must not reach the per-entry test");
        }, visible::add);
        assertEquals(List.of(), visible);
    }

    @Test
    void rebucketedResultMatchesBruteForceAfterASlabMoves() {
        Random random = new Random(0xC0FFEE);
        List<MovableEntry> entries = new ArrayList<>();
        HierarchicalFrustumCuller<MovableEntry> culler = new HierarchicalFrustumCuller<>();

        for (int i = 0; i < 4_000; i++) {
            MovableEntry entry = new MovableEntry(i,
                    (random.nextInt(129) - 64) * 16,
                    (random.nextInt(24) - 8) * 16,
                    (random.nextInt(129) - 64) * 16);
            entries.add(entry);
            add(culler, entry);
        }

        // Re-origin a slab, the way a chunk-boundary crossing moves a plane of torus slots, and
        // report each moved slot the way the move log replays them.
        for (MovableEntry entry : entries) {
            if (entry.originX < -512) {
                entry.moveTo(entry.originX + 2048, entry.originY, entry.originZ);
                culler.rebucket(entry.slot, MovableEntry::readBounds);
            }
        }

        assertMatchesBruteForce(culler, entries, random, 100);
    }

    @Test
    void replayingTheMoveLogRecoversEntriesMovedDuringTheBuild() {
        // A published index whose entries were re-originned partway through the build. Entries
        // added before the move hold stale bounds; entries added after hold current ones. The
        // first reconcile replays the moves logged since the build started and has to catch the
        // former without disturbing the latter.
        ChunkSlotMoveLog log = new ChunkSlotMoveLog(1024);
        HierarchicalFrustumCuller<MovableEntry> culler = new HierarchicalFrustumCuller<>();
        List<MovableEntry> all = new ArrayList<>();
        long buildStart = log.sequence();

        List<MovableEntry> indexedBeforeMove = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            MovableEntry entry = new MovableEntry(i, i * 16, 0, 0);
            add(culler, entry);
            indexedBeforeMove.add(entry);
            all.add(entry);
        }

        // The torus re-origins a whole plane mid-walk, including slots the walk has not indexed
        // yet and slots it never will.
        for (MovableEntry entry : indexedBeforeMove) {
            entry.moveTo(entry.originX + 4096, entry.originY, entry.originZ);
            log.record(entry.slot);
        }
        for (int slot = 200; slot < 600; slot++) {
            log.record(slot);
        }

        // Incremental adds continue against the still-unpublished index, carrying live bounds.
        for (int i = 200; i < 400; i++) {
            MovableEntry entry = new MovableEntry(i, -2048 + (i - 200) * 16, 0, 0);
            add(culler, entry);
            all.add(entry);
        }

        long published = log.sequence();
        assertTrue(log.replay(buildStart, published,
                slot -> culler.rebucket(slot, MovableEntry::readBounds)));
        // A second replay of the same window must be idempotent.
        assertTrue(log.replay(buildStart, published,
                slot -> culler.rebucket(slot, MovableEntry::readBounds)));

        Random random = new Random(0xBEEF);
        for (int sample = 0; sample < 60; sample++) {
            double minX = random.nextInt(7000) - 2500;
            AxisAlignedView view = new AxisAlignedView(
                    minX, -64, -64, minX + 256 + random.nextInt(768), 64, 64);
            List<MovableEntry> expected = all.stream().filter(view::intersects).toList();
            List<MovableEntry> actual = new ArrayList<>();
            culler.forEachVisible(view::classify, view::intersects, actual::add);
            assertEquals(expected, actual, "sample " + sample);
        }
    }

    @Test
    void rebucketAllRecoversEveryMovedEntryWithoutTheLog() {
        Random random = new Random(0x0FF5E7);
        List<MovableEntry> entries = new ArrayList<>();
        HierarchicalFrustumCuller<MovableEntry> culler = new HierarchicalFrustumCuller<>();
        for (int i = 0; i < 3_000; i++) {
            MovableEntry entry = new MovableEntry(i,
                    (random.nextInt(65) - 32) * 16,
                    (random.nextInt(24) - 8) * 16,
                    (random.nextInt(65) - 32) * 16);
            entries.add(entry);
            add(culler, entry);
        }

        // A teleport re-origins every slot, more moves than any log window would hold.
        for (MovableEntry entry : entries) {
            entry.moveTo(entry.originX + 8192, entry.originY, entry.originZ - 4096);
        }
        culler.rebucketAll(MovableEntry::readBounds);

        assertMatchesBruteForce(culler, entries, random, 60);
    }

    private static void assertMatchesBruteForce(HierarchicalFrustumCuller<MovableEntry> culler,
                                                List<MovableEntry> entries,
                                                Random random,
                                                int samples) {
        double lo = entries.stream().mapToDouble(MovableEntry::minX).min().orElse(0) - 200;
        double hi = entries.stream().mapToDouble(MovableEntry::maxX).max().orElse(0) + 200;
        double loZ = entries.stream().mapToDouble(MovableEntry::minZ).min().orElse(0) - 200;
        double hiZ = entries.stream().mapToDouble(MovableEntry::maxZ).max().orElse(0) + 200;
        for (int sample = 0; sample < samples; sample++) {
            double minX = lo + random.nextDouble() * (hi - lo);
            double minY = random.nextInt(160) - 80;
            double minZ = loZ + random.nextDouble() * (hiZ - loZ);
            AxisAlignedView view = new AxisAlignedView(
                    minX, minY, minZ,
                    minX + 128 + random.nextInt(512),
                    minY + 64 + random.nextInt(256),
                    minZ + 128 + random.nextInt(512));

            List<MovableEntry> expected = entries.stream().filter(view::intersects).toList();
            List<MovableEntry> actual = new ArrayList<>();
            culler.forEachVisible(view::classify, view::intersects, actual::add);
            assertEquals(expected, actual, "sample " + sample);
        }
    }

    private static void add(HierarchicalFrustumCuller<BoxEntry> culler, BoxEntry entry) {
        culler.add(entry, entry.id, entry.minX, entry.minY, entry.minZ,
                entry.maxX, entry.maxY, entry.maxZ);
    }

    private static void add(HierarchicalFrustumCuller<MovableEntry> culler, MovableEntry entry) {
        culler.add(entry, entry.slot, entry.minX(), entry.minY(), entry.minZ(),
                entry.maxX(), entry.maxY(), entry.maxZ());
    }

    /** A 16-block entry whose origin can change after indexing, like a re-originned BuiltChunk. */
    private static final class MovableEntry {
        private final int slot;
        private int originX;
        private int originY;
        private int originZ;

        private MovableEntry(int slot, int originX, int originY, int originZ) {
            this.slot = slot;
            moveTo(originX, originY, originZ);
        }

        private void moveTo(int x, int y, int z) {
            originX = x;
            originY = y;
            originZ = z;
        }

        private double minX() { return originX; }
        private double minY() { return originY; }
        private double minZ() { return originZ; }
        private double maxX() { return originX + 16.0; }
        private double maxY() { return originY + 16.0; }
        private double maxZ() { return originZ + 16.0; }

        private void readBounds(double[] out) {
            out[0] = minX();
            out[1] = minY();
            out[2] = minZ();
            out[3] = maxX();
            out[4] = maxY();
            out[5] = maxZ();
        }
    }

    private record BoxEntry(int id, double minX, double minY, double minZ,
                            double maxX, double maxY, double maxZ) {
    }

    private record AxisAlignedView(double minX, double minY, double minZ,
                                   double maxX, double maxY, double maxZ) {
        private int classify(double candidateMinX, double candidateMinY, double candidateMinZ,
                             double candidateMaxX, double candidateMaxY, double candidateMaxZ) {
            if (!intersects(candidateMinX, candidateMinY, candidateMinZ,
                    candidateMaxX, candidateMaxY, candidateMaxZ)) {
                return HierarchicalFrustumCuller.OUTSIDE;
            }
            if (candidateMinX >= minX && candidateMinY >= minY && candidateMinZ >= minZ
                    && candidateMaxX <= maxX && candidateMaxY <= maxY && candidateMaxZ <= maxZ) {
                return HierarchicalFrustumCuller.INSIDE;
            }
            return HierarchicalFrustumCuller.INTERSECT;
        }

        private boolean intersects(BoxEntry candidate) {
            return intersects(candidate.minX, candidate.minY, candidate.minZ,
                    candidate.maxX, candidate.maxY, candidate.maxZ);
        }

        private boolean intersects(MovableEntry candidate) {
            return intersects(candidate.minX(), candidate.minY(), candidate.minZ(),
                    candidate.maxX(), candidate.maxY(), candidate.maxZ());
        }

        private boolean intersects(double candidateMinX, double candidateMinY, double candidateMinZ,
                                   double candidateMaxX, double candidateMaxY, double candidateMaxZ) {
            return candidateMaxX >= minX && candidateMinX <= maxX
                    && candidateMaxY >= minY && candidateMinY <= maxY
                    && candidateMaxZ >= minZ && candidateMinZ <= maxZ;
        }
    }
}

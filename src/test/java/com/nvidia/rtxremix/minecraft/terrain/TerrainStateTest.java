/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import com.nvidia.rtxremix.minecraft.terrain.capture.SectionMeshData;
import com.nvidia.rtxremix.minecraft.light.BlockLightKey;
import com.nvidia.rtxremix.minecraft.asset.RemixLight;
import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.minecraft.texture.RedstoneEmission;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainStateTest {

    private static TerrainState state() {
        return new TerrainState(64);
    }

    @Test
    void sectionCrudIsColumnKeyedAndHashDeduplicated() {
        TerrainState terrain = state();
        Object firstData = new Object();
        TerrainState.SectionEntry first = terrain.addSection(3, 4, -2, 11, firstData);

        assertNotNull(first);
        assertEquals(1, terrain.columnCount());
        assertEquals(1, terrain.sectionCount());
        assertEquals(3, terrain.findColumn(3, -2).cx);
        assertEquals(-2, terrain.findColumn(3, -2).cz);
        assertSame(firstData, terrain.findSection(3, 4, -2).data);

        assertNull(terrain.addSection(3, 4, -2, 11, new Object()));
        assertSame(first, terrain.findSection(3, 4, -2));

        TerrainState.SectionEntry replacement =
                terrain.addSection(3, 4, -2, 12, new Object());
        assertNotNull(replacement);
        assertSame(first, terrain.getReplacedEntry());
        assertSame(replacement, terrain.findSection(3, 4, -2));
        assertEquals(1, terrain.sectionCount());
    }

    @Test
    void ownsMeshPayloadsUntilRejectedReplacedOrRemoved() {
        TerrainState terrain = state();
        SectionMeshData first = meshPayload();
        terrain.addSection(0, 0, 0, 1, first);

        SectionMeshData duplicate = meshPayload();
        assertNull(terrain.addSection(0, 0, 0, 1, duplicate));
        assertThrows(IllegalStateException.class, duplicate::surfaces, "rejected duplicate is released");
        assertEquals(1, first.surfaces().size(), "the kept entry stays live");

        SectionMeshData replacement = meshPayload();
        replacement.retain();
        assertNotNull(terrain.addSection(0, 0, 0, 2, replacement));
        assertThrows(IllegalStateException.class, first::surfaces, "replaced payload is released");

        terrain.removeSectionForRetirement(0, 0, 0);
        assertEquals(1, replacement.surfaces().size(), "a column build's reference outlives removal");
        replacement.release();
        assertThrows(IllegalStateException.class, replacement::surfaces);
    }

    @Test
    void clearingReleasesEveryRetainedPayload() {
        TerrainState terrain = state();
        SectionMeshData near = meshPayload();
        SectionMeshData far = meshPayload();
        terrain.addSection(0, 0, 0, 1, near);
        terrain.addSection(9, 3, -4, 2, far);

        terrain.clearForRetirement();

        assertThrows(IllegalStateException.class, near::surfaces);
        assertThrows(IllegalStateException.class, far::surfaces);
        assertEquals(0, terrain.sectionCount());
    }

    private static SectionMeshData meshPayload() {
        ByteBuffer vertices = ByteBuffer.allocate(4 * HardcodedVertex.STRIDE)
                .order(ByteOrder.LITTLE_ENDIAN);
        return new SectionMeshData(List.of(new SectionMeshData.Surface(
                ChunkManager.CAT_SOLID, "solid", RedstoneEmission.NONE, vertices, 4, 6, true)));
    }

    @Test
    void snapshotsAreDeterministicAndUseWorldSectionOffsets() {
        TerrainState terrain = state();
        terrain.addSection(-2, 7, 5, 1, "high");
        terrain.addSection(-2, -1, 5, 2, "low");
        terrain.addSection(-2, 3, 5, 3, "middle");

        List<TerrainState.SectionSnapshot> snapshots =
                terrain.createSnapshot(terrain.findColumn(-2, 5));

        assertEquals(List.of(-16, 48, 112),
                snapshots.stream().map(TerrainState.SectionSnapshot::yOff).toList());
        assertEquals(List.of("low", "middle", "high"),
                snapshots.stream().map(TerrainState.SectionSnapshot::data).toList());
        for (TerrainState.SectionSnapshot snapshot : snapshots) {
            assertEquals(-32, snapshot.xOff());
            assertEquals(80, snapshot.zOff());
        }
    }

    @Test
    void playerMovementLeavesGeometryStateUntouched() {
        TerrainState terrain = state();
        TerrainState.SectionEntry lower = terrain.addSection(-3, -1, 8, 1, "lower");
        TerrainState.SectionEntry upper = terrain.addSection(-3, 2, 8, 2, "upper");
        TerrainState.ChunkColumn column = terrain.findColumn(-3, 8);
        List<TerrainState.SectionSnapshot> before = terrain.createSnapshot(column);

        terrain.setPlayerPos(-3, 8);
        terrain.setPlayerPos(20, -40);
        terrain.setPlayerPos(-1000, 1000);

        assertSame(column, terrain.findColumn(-3, 8));
        assertSame(lower, terrain.findSection(-3, -1, 8));
        assertSame(upper, terrain.findSection(-3, 2, 8));
        assertEquals(before, terrain.createSnapshot(column));
        assertEquals(1, terrain.columnCount());
        assertEquals(2, terrain.sectionCount());
    }

    @Test
    void removingLastSectionRemovesColumn() {
        TerrainState terrain = state();
        terrain.addSection(1, 2, 3, 1, new Object());
        terrain.addSection(1, 4, 3, 2, new Object());

        terrain.removeSectionForRetirement(1, 2, 3);
        assertNotNull(terrain.findColumn(1, 3));
        assertNull(terrain.findSection(1, 2, 3));
        assertNotNull(terrain.findSection(1, 4, 3));

        terrain.removeSectionForRetirement(1, 4, 3);
        assertNull(terrain.findColumn(1, 3));
        assertEquals(0, terrain.columnCount());
        assertEquals(0, terrain.sectionCount());
    }

    @Test
    void unloadingAndReloadingCoordinateCreatesNewStateIncarnation() {
        TerrainState terrain = state();
        TerrainState.SectionEntry firstEntry = terrain.addSection(-7, 3, 9, 41, "old");
        TerrainState.ChunkColumn firstColumn = terrain.findColumn(-7, 9);

        assertTrue(terrain.isCurrentSectionEntry(-7, 3, 9, firstEntry));
        assertTrue(terrain.removeColumnForRetirement(-7, 9).isEmpty());
        assertNull(terrain.findColumn(-7, 9));
        assertFalse(terrain.isCurrentSectionEntry(-7, 3, 9, firstEntry));

        TerrainState.SectionEntry secondEntry = terrain.addSection(-7, 3, 9, 41, "new");
        TerrainState.ChunkColumn secondColumn = terrain.findColumn(-7, 9);

        assertNotSame(firstColumn, secondColumn);
        assertNotSame(firstEntry, secondEntry);
        assertEquals("new", terrain.findSection(-7, 3, 9).data);
        assertTrue(terrain.isCurrentSectionEntry(-7, 3, 9, secondEntry));
        assertFalse(terrain.isCurrentSectionEntry(-7, 3, 9, firstEntry));
    }

    @Test
    void columnKeysSnapshotPreservesSignedCoordinateIdentity() {
        TerrainState terrain = state();
        terrain.addSection(-7, 0, 9, 1, "a");
        terrain.addSection(0, 0, 0, 2, "b");
        terrain.addSection(12, 0, -4, 3, "c");

        long[] snapshot = terrain.columnKeysSnapshot();
        long[] expected = {
                TerrainState.packKey(-7, 9),
                TerrainState.packKey(0, 0),
                TerrainState.packKey(12, -4)
        };
        Arrays.sort(snapshot);
        Arrays.sort(expected);
        assertArrayEquals(expected, snapshot);

        terrain.removeColumnForRetirement(-7, 9);
        terrain.addSection(2, 0, 3, 4, "later");
        assertArrayEquals(expected, snapshot, "The snapshot must not be a live map view");
    }

    @Test
    void lightReachabilityTraversesOnlyMatchingOpenFaces() {
        TerrainState terrain = state();
        long open = -1L;
        terrain.addSection(0, 0, 0, 1, new Object(),
                open, open, open, open, open, open, new long[64]);
        addSection(terrain, 1, 0, 0, open, open, open, open, open, open);
        addSection(terrain, 2, 0, 0, open, open, open, 0, open, open);

        terrain.setPlayerPos(0, 0);
        terrain.setPlayerBlockPos(1, 1, 1);
        terrain.ensureReachabilityOnce();

        assertTrue(terrain.isSectionReachableForLights(0, 0, 0));
        assertTrue(terrain.isSectionReachableForLights(1, 0, 0));
        assertFalse(terrain.isSectionReachableForLights(2, 0, 0));
        assertEquals(2, terrain.reachableCount());
    }

    @Test
    void opacityBitmapConstrainsPlayerSectionSeed() {
        TerrainState terrain = state();
        long[] opacity = new long[64];
        Arrays.fill(opacity, -1L);
        int playerBit = (1 << 8) | (1 << 4) | 1;
        opacity[playerBit >> 6] &= ~(1L << (playerBit & 63));

        terrain.addSection(0, 0, 0, 1, new Object(),
                -1L, -1L, -1L, -1L, -1L, -1L, opacity);
        addSection(terrain, 1, 0, 0, -1L, -1L, -1L, -1L, -1L, -1L);
        terrain.setPlayerPos(0, 0);
        terrain.setPlayerBlockPos(1, 1, 1);
        terrain.ensureReachabilityOnce();

        assertTrue(terrain.isSectionReachableForLights(0, 0, 0));
        assertFalse(terrain.isSectionReachableForLights(1, 0, 0));
        assertEquals(1, terrain.reachableCount());
    }

    @Test
    void lightCullDistanceRejectsFarColumnsAndClampsToOneChunk() {
        TerrainState terrain = state();
        addSection(terrain, 0, 0, 0, -1L, -1L, -1L, -1L, -1L, -1L);
        terrain.setPlayerPos(0, 0);
        terrain.setPlayerBlockPos(1, 1, 1);
        terrain.ensureReachabilityOnce();

        assertFalse(terrain.isColumnWithinLightCullRange(10, 0));
        assertFalse(terrain.isSectionReachableForLights(10, 0, 0));

        // setLightCullBlocks clamps every value up to MIN_LIGHT_CULL_BLOCKS:
        // ChunkManager resolves Auto before a radius reaches TerrainState,
        // so one chunk is the floor.
        terrain.setLightCullBlocks(0);
        assertEquals(TerrainState.MIN_LIGHT_CULL_BLOCKS, terrain.getLightCullBlocks());
        assertTrue(terrain.isColumnWithinLightCullRange(1, 0));
        assertFalse(terrain.isColumnWithinLightCullRange(2, 0));
        assertEquals(TerrainState.MIN_LIGHT_CULL_BLOCKS, new TerrainState(0).getLightCullBlocks());
    }

    @Test
    void movementWithinSameConnectivityComponentKeepsReachabilityCacheClean() throws Exception {
        TerrainState terrain = state();
        long[] splitOpacity = new long[64];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                int bit = (y << 8) | (z << 4) | 8;
                splitOpacity[bit >> 6] |= 1L << (bit & 63);
            }
        }
        terrain.addSection(0, 0, 0, 1, "seed",
                -1L, -1L, -1L, -1L, -1L, -1L, splitOpacity);
        terrain.setPlayerPos(0, 0);
        terrain.setPlayerBlockPos(1, 1, 1);
        terrain.ensureReachabilityOnce();

        Field dirtyField = TerrainState.class.getDeclaredField("reachabilityDirty");
        dirtyField.setAccessible(true);
        assertFalse(dirtyField.getBoolean(terrain));

        terrain.setPlayerBlockPos(3, 1, 1);
        assertFalse(dirtyField.getBoolean(terrain),
                "Movement inside the cached component should not schedule a full BFS");

        terrain.setPlayerBlockPos(10, 1, 1);
        assertTrue(dirtyField.getBoolean(terrain),
                "Crossing the sealed wall should invalidate cached connectivity");
    }

    @Test
    void lightCountsCommitOnlyToCurrentSectionIncarnation() {
        TerrainState terrain = state();
        TerrainState.SectionEntry first = terrain.addSection(0, 0, 0, 1, "first");
        assertTrue(terrain.adjustSectionLightCount(0, 0, 0, first, 2));
        assertEquals(2, terrain.findColumn(0, 0).lightCount());

        TerrainState.SectionEntry replacement = terrain.addSection(0, 0, 0, 2, "replacement");
        assertFalse(terrain.adjustSectionLightCount(0, 0, 0, first, -1));
        assertEquals(2, terrain.findColumn(0, 0).lightCount());
        assertTrue(terrain.adjustSectionLightCount(0, 0, 0, replacement, -1));
        assertEquals(1, terrain.findColumn(0, 0).lightCount());

        terrain.removeColumnForRetirement(0, 0);
        terrain.addSection(0, 0, 0, 2, "reloaded");
        assertFalse(terrain.adjustSectionLightCount(0, 0, 0, replacement, 1));
        assertEquals(0, terrain.findColumn(0, 0).lightCount());
    }

    @Test
    void removeSectionForRetirementTransfersLightOwnershipExactlyOnce() {
        TerrainState terrain = state();
        RemixLight first = new RemixLight(null, null);
        RemixLight second = new RemixLight(null, null);
        TerrainState.SectionEntry entry = terrain.addSection(0, 0, 0, 1, "section");
        entry.putLight(lightKey("first"), new TerrainLight(first));
        entry.putLight(lightKey("second"), new TerrainLight(second));
        assertTrue(terrain.adjustSectionLightCount(0, 0, 0, entry, 2));

        List<RemixLight> retired = terrain.removeSectionForRetirement(0, 0, 0);

        assertEquals(List.of(first, second), retired);
        assertTrue(terrain.removeSectionForRetirement(0, 0, 0).isEmpty());
        assertNull(terrain.findColumn(0, 0));
        assertFalse(first.isDeleted());
        assertFalse(second.isDeleted());
        retired.forEach(RemixLight::destroy);
    }

    @Test
    void clearForRetirementTransfersEveryLightExactlyOnce() {
        TerrainState terrain = state();
        RemixLight first = new RemixLight(null, null);
        RemixLight second = new RemixLight(null, null);
        RemixLight third = new RemixLight(null, null);
        TerrainState.SectionEntry a = terrain.addSection(0, 0, 0, 1, "a");
        TerrainState.SectionEntry b = terrain.addSection(3, 1, -2, 2, "b");
        a.putLight(lightKey("first"), new TerrainLight(first));
        a.putLight(lightKey("second"), new TerrainLight(second));
        b.putLight(lightKey("third"), new TerrainLight(third));
        assertTrue(terrain.adjustSectionLightCount(0, 0, 0, a, 2));
        assertTrue(terrain.adjustSectionLightCount(3, 1, -2, b, 1));

        List<RemixLight> retired = terrain.clearForRetirement();

        assertEquals(3, retired.size());
        assertEquals(new HashSet<>(List.of(first, second, third)), new HashSet<>(retired));
        assertEquals(3, new HashSet<>(retired).size());
        assertEquals(0, terrain.columnCount());
        assertEquals(0, terrain.sectionCount());
        assertEquals(0, terrain.reachableCount());
        assertTrue(terrain.clearForRetirement().isEmpty());
        for (RemixLight light : retired) assertFalse(light.isDeleted());
        retired.forEach(RemixLight::destroy);
    }

    @Test
    void clearResetsColumnsPlayerPositionAndReachability() {
        TerrainState terrain = state();
        addSection(terrain, 0, 0, 0, -1L, -1L, -1L, -1L, -1L, -1L);
        terrain.setPlayerPos(0, 0);
        terrain.setPlayerBlockPos(1, 1, 1);
        terrain.ensureReachabilityOnce();
        terrain.setLightCullBlocks(16);
        assertFalse(terrain.isColumnWithinLightCullRange(100, 100));

        assertTrue(terrain.clearForRetirement().isEmpty());

        assertEquals(0, terrain.columnCount());
        assertEquals(0, terrain.sectionCount());
        assertEquals(0, terrain.reachableCount());
        assertTrue(terrain.isColumnWithinLightCullRange(100, 100),
                "Clearing must reset the player-relative cull origin");
    }

    /** A 20x20 all-open grid at one level, player in the middle: 400 sections to flood. */
    private static TerrainState openGrid(long floodSliceNanos, long cooldownNanos) {
        TerrainState terrain = new TerrainState(20 * 16, floodSliceNanos, cooldownNanos);
        for (int cx = -10; cx < 10; cx++) {
            for (int cz = -10; cz < 10; cz++) {
                terrain.addSection(cx, 0, cz, 1, new Object(),
                        -1L, -1L, -1L, -1L, -1L, -1L, new long[64]);
            }
        }
        terrain.setPlayerPos(0, 0);
        terrain.setPlayerBlockPos(1, 1, 1);
        return terrain;
    }

    private static int framesUntilPublished(TerrainState terrain) {
        int frames = 0;
        do {
            terrain.ensureReachabilityOnce();
            frames++;
        } while (terrain.isFloodBuildInFlight() && frames < 1000);
        return frames;
    }

    @Test
    void aSlicedFloodPublishesTheSameSetAsOneSynchronousPass() {
        TerrainState synchronous = openGrid(Long.MAX_VALUE / 4, 0L);
        assertEquals(1, framesUntilPublished(synchronous));
        assertEquals(400, synchronous.reachableCount());

        TerrainState sliced = openGrid(0L, 0L);
        int frames = framesUntilPublished(sliced);
        assertTrue(frames > 1, "a zero budget must need more than one frame for 400 sections");
        assertEquals(400, sliced.reachableCount());
        assertTrue(sliced.isSectionReachableForLights(9, -10, 0));
    }

    @Test
    void queriesDuringABuildAnswerFromThePublishedSet() {
        TerrainState terrain = openGrid(0L, 0L);
        framesUntilPublished(terrain);
        assertEquals(400, terrain.reachableCount());

        // Seal the far edge column so the rebuild will drop it, then start the
        // rebuild without finishing it.
        terrain.removeSectionForRetirement(9, 0, 9);
        terrain.ensureReachabilityOnce();
        assertTrue(terrain.isFloodBuildInFlight());
        assertEquals(400, terrain.reachableCount(),
                "the published set stays visible until the rebuild completes");

        framesUntilPublished(terrain);
        assertFalse(terrain.isFloodBuildInFlight());
        assertEquals(399, terrain.reachableCount());
    }

    @Test
    void aDirtyMarkDuringABuildSchedulesExactlyOneFollowUpBuild() throws Exception {
        TerrainState terrain = openGrid(0L, 0L);
        framesUntilPublished(terrain);

        terrain.removeSectionForRetirement(9, 0, 9);
        terrain.ensureReachabilityOnce();                 // build 2 starts
        assertTrue(terrain.isFloodBuildInFlight());
        terrain.removeSectionForRetirement(-10, 0, -10);  // dirty again mid-build
        terrain.removeSectionForRetirement(-10, 0, 9);

        framesUntilPublished(terrain);                    // build 2 publishes
        assertFalse(terrain.isFloodBuildInFlight());
        int afterBuildTwo = terrain.reachableCount();
        assertTrue(afterBuildTwo >= 397 && afterBuildTwo <= 399,
                "build 2 must publish on its own rather than restarting itself mid-flight");
        Field dirty = TerrainState.class.getDeclaredField("reachabilityDirty");
        dirty.setAccessible(true);
        assertTrue(dirty.getBoolean(terrain), "removals during the build leave one follow-up pending");

        framesUntilPublished(terrain);                    // build 3 runs and publishes
        assertFalse(dirty.getBoolean(terrain));
        assertEquals(397, terrain.reachableCount());
    }

    @Test
    void sectionsAddedDuringABuildAreNotFloodedIncrementallyButMarkDirty() throws Exception {
        TerrainState terrain = openGrid(0L, 0L);
        framesUntilPublished(terrain);
        terrain.removeSectionForRetirement(9, 0, 9);
        terrain.ensureReachabilityOnce();
        assertTrue(terrain.isFloodBuildInFlight());

        terrain.addSection(9, 0, 9, 2, new Object(), -1L, -1L, -1L, -1L, -1L, -1L, new long[64]);
        assertEquals(400, terrain.reachableCount(),
                "no incremental flood may touch the published set while a build is in flight");
        assertEquals(0L, terrain.takeIncrementalFloodNanos(),
                "no incremental flood may touch the published set while a build is in flight");
        Field dirty = TerrainState.class.getDeclaredField("reachabilityDirty");
        dirty.setAccessible(true);
        assertTrue(dirty.getBoolean(terrain));
    }

    @Test
    void aColumnLoadedDuringABuildStaysLitUntilTheBuildCoversIt() {
        TerrainState terrain = openGrid(0L, 0L);
        framesUntilPublished(terrain);
        terrain.removeSectionForRetirement(9, 0, 9);
        terrain.ensureReachabilityOnce();
        assertTrue(terrain.isFloodBuildInFlight());

        // A column beyond the grid edge but inside the cull box loads mid-build.
        terrain.addSection(10, 0, 5, 2, new Object(), -1L, -1L, -1L, -1L, -1L, -1L, new long[64]);
        assertTrue(terrain.isSectionReachableForLights(10, 5, 0),
                "an uncovered column is drawn while the rebuild that will reach it is in flight");

        framesUntilPublished(terrain);
        assertFalse(terrain.isFloodBuildInFlight());
        assertTrue(terrain.isSectionReachableForLights(10, 5, 0),
                "the follow-up build covers the new column");
    }

    @Test
    void changingLightCullBlocksDuringABuildDropsItSoTheRadiusDoesNotMix() {
        TerrainState terrain = openGrid(0L, 0L);
        framesUntilPublished(terrain);
        terrain.removeSectionForRetirement(9, 0, 9);
        terrain.ensureReachabilityOnce();
        assertTrue(terrain.isFloodBuildInFlight());

        terrain.setLightCullBlocks(8 * 16);

        assertFalse(terrain.isFloodBuildInFlight());
        framesUntilPublished(terrain);
        assertEquals(289, terrain.reachableCount());
    }

    @Test
    void addingASectionAdjacentToThePublishedSetFloodsItInWithoutABuild() {
        TerrainState terrain = state();
        long open = -1L;
        terrain.addSection(0, 0, 0, 1, new Object(),
                open, open, open, open, open, open, new long[64]);
        terrain.setPlayerPos(0, 0);
        terrain.setPlayerBlockPos(1, 1, 1);
        terrain.ensureReachabilityOnce();
        assertFalse(terrain.isFloodBuildInFlight());
        assertEquals(1, terrain.reachableCount());

        addSection(terrain, 1, 0, 0, open, open, open, open, open, open);

        assertEquals(2, terrain.reachableCount(),
                "a section that connects to the published set must be flooded in immediately");
        assertTrue(terrain.takeIncrementalFloodNanos() > 0L,
                "the incremental flood on the published set must be timed");
    }

    private static BlockLightKey lightKey(String path) {
        return new BlockLightKey(BlockPos.ORIGIN, Identifier.of("test", path), 0);
    }

    private static void addSection(TerrainState terrain, int cx, int sy, int cz,
                                   long top, long bottom, long east, long west,
                                   long south, long north) {
        terrain.addSection(cx, sy, cz, 1, new Object(), top, bottom,
                east, west, south, north, null);
    }
}

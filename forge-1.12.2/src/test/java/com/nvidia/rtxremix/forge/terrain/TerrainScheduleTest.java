/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class TerrainScheduleTest {
    private final List<TerrainSchedule.Column> removed = new ArrayList<TerrainSchedule.Column>();
    private final TerrainSchedule.RemovedColumn retirement = new TerrainSchedule.RemovedColumn() {
        @Override public void remove(TerrainSchedule.Column column) { removed.add(column); }
    };

    @Test public void discoversEveryColumnAtMaximumVanillaDistanceWithoutDuplicates() {
        TerrainSchedule schedule = new TerrainSchedule();
        schedule.move(-18, 4, -31, 32, retirement);
        assertEquals(4225, schedule.windowSize());
        Set<TerrainSchedule.Column> visited = new HashSet<TerrainSchedule.Column>();
        TerrainSchedule.Column center = schedule.nextDiscovery();
        assertEquals(-18, center.x);
        assertEquals(-31, center.z);
        visited.add(center);
        for (int i = 1; i < schedule.windowSize(); i++) assertTrue(visited.add(schedule.nextDiscovery()));
        assertSame(center, schedule.nextDiscovery());
        assertNotNull(schedule.column(-50, -63));
        assertNotNull(schedule.column(14, 1));
        assertNull(schedule.column(15, 1));
    }

    @Test public void movementReusesOverlapRetiresOnlyDeparturesAndReprioritizes() {
        TerrainSchedule schedule = new TerrainSchedule();
        schedule.move(0, 3, 0, 2, retirement);
        TerrainSchedule.Column overlap = schedule.column(1, 0);
        TerrainSchedule.Column departure = schedule.column(-2, 0);
        overlap.source = new Object();
        departure.source = new Object();
        schedule.invalidate(overlap.sections[3]);
        schedule.invalidate(departure.sections[3]);
        schedule.move(1, 3, 0, 2, retirement);
        assertSame(overlap, schedule.column(1, 0));
        assertEquals(5, removed.size());
        assertFalse(departure.resident);
        assertSame(overlap.sections[3], schedule.next());
        assertNull(schedule.next());
        assertFalse(schedule.move(1, 3, 0, 2, retirement));
    }

    @Test public void dirtyDuringSlicedBuildCannotPublishStaleGeometry() {
        TerrainSchedule schedule = new TerrainSchedule();
        schedule.move(0, 0, 0, 1, retirement);
        TerrainSchedule.Column column = schedule.column(0, 0);
        Object source = column.source = new Object();
        TerrainSchedule.Section section = column.sections[0];
        schedule.invalidate(section);
        assertSame(section, schedule.next());
        long revision = section.revision;
        assertTrue(schedule.valid(section, source, revision));
        schedule.invalidate(section);
        schedule.invalidate(section);
        assertFalse(schedule.valid(section, source, revision));
        assertEquals(0, schedule.pending());
        schedule.discard(section);
        assertEquals(1, schedule.pending());
        assertSame(section, schedule.next());
        schedule.publish(section, section.revision);
        assertTrue(section.ready());
        long firstPublication = section.publication;
        schedule.invalidate(section);
        assertFalse(section.ready());
        assertEquals(firstPublication, section.publication);
        schedule.next();
        schedule.publish(section, section.revision);
        assertTrue(section.publication > firstPublication);
    }

    @Test public void chunkReplacementAndDepartureInvalidateActiveBuilds() {
        TerrainSchedule schedule = new TerrainSchedule();
        schedule.move(0, 4, 0, 1, retirement);
        TerrainSchedule.Column column = schedule.column(0, 0);
        Object original = column.source = new Object();
        TerrainSchedule.Section section = column.sections[4];
        schedule.invalidate(section);
        schedule.next();
        long revision = section.revision;
        column.source = new Object();
        assertFalse(schedule.valid(section, original, revision));
        Object replacement = column.source;
        assertTrue(schedule.valid(section, replacement, revision));
        schedule.move(4, 4, 0, 1, retirement);
        assertFalse(schedule.valid(section, replacement, revision));
        schedule.discard(section);
        assertEquals(0, schedule.pending());
    }

    @Test public void failureDoesNotRetryUntilAnotherInvalidation() {
        TerrainSchedule schedule = new TerrainSchedule();
        schedule.move(0, 0, 0, 1, retirement);
        TerrainSchedule.Column column = schedule.column(0, 0);
        column.source = new Object();
        TerrainSchedule.Section section = column.sections[0];
        schedule.invalidate(section);
        schedule.next();
        schedule.discard(section);
        schedule.fail(section);
        assertEquals(0, schedule.pending());
        schedule.invalidate(section);
        assertSame(section, schedule.next());
    }

    @Test public void shrinkingAndClearingRetireEveryOwnedColumnExactlyOnce() {
        TerrainSchedule schedule = new TerrainSchedule();
        schedule.move(4, 4, 4, 4, retirement);
        schedule.move(4, 4, 4, 2, retirement);
        assertEquals(81 - 25, removed.size());
        schedule.clear(retirement);
        assertEquals(81, removed.size());
        assertEquals(81, new HashSet<TerrainSchedule.Column>(removed).size());
        assertEquals(0, schedule.windowSize());
        assertNull(schedule.nextDiscovery());
    }

    @Test public void resetCannotReviveAnOldBuildEvenWhenTheChunkObjectIsReused() {
        TerrainSchedule schedule = new TerrainSchedule();
        schedule.move(0, 0, 0, 0, retirement);
        Object chunk = new Object();
        TerrainSchedule.Column column = schedule.column(0, 0);
        column.source = chunk;
        schedule.invalidate(column.sections[0]);
        TerrainSchedule.Section building = schedule.next();
        long revision = building.revision;
        schedule.clear(retirement);
        schedule.move(0, 0, 0, 0, retirement);
        TerrainSchedule.Column replacement = schedule.column(0, 0);
        replacement.source = chunk;
        assertNotSame(column, replacement);
        assertFalse(schedule.valid(building, chunk, revision));
        schedule.discard(building);
        assertNull(schedule.next());
        schedule.invalidate(replacement.sections[0]);
        assertSame(replacement.sections[0], schedule.next());
    }

    @Test public void movingVerticallyReordersPendingSectionsWithoutRecreatingColumns() {
        TerrainSchedule schedule = new TerrainSchedule();
        schedule.move(0, 0, 0, 0, retirement);
        TerrainSchedule.Column column = schedule.column(0, 0);
        column.source = new Object();
        schedule.invalidate(column.sections[0]);
        schedule.invalidate(column.sections[12]);
        schedule.move(0, 12, 0, 0, retirement);
        assertSame(column, schedule.column(0, 0));
        assertSame(column.sections[12], schedule.next());
        assertEquals(0, removed.size());
    }
}

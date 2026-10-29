/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.nvidia.rtxremix.forge.terrain.TerrainAdmission.Result.*;

public class TerrainAdmissionTest {
    private final TerrainSchedule schedule = new TerrainSchedule();
    private final TerrainBudget budget = new TerrainBudget(1000);
    private final TerrainAdmission admission = new TerrainAdmission(schedule, budget);
    private final TerrainSchedule.RemovedColumn removed = column -> {
        for (TerrainSchedule.Section section : column.sections) {
            schedule.forget(section);
            section.asset = null;
        }
    };

    public TerrainAdmissionTest() { schedule.move(0, 4, 0, 4, removed); }

    @Test public void nearerArrivalEvictsTheFarthestThenWaitsForActualDestruction() {
        TerrainSchedule.Section middle = section(1, 4, 0), far = section(4, 4, 0), near = section(0, 4, 0);
        retain(middle, 400); retain(far, 600);
        assertEquals(EVICT, admission.decide(near, 500));
        assertSame(far, admission.victim());
        evict(far, 600);
        assertFalse(far.ready());
        assertEquals(WAIT, admission.decide(near, 500));
        assertNull(admission.victim());
        assertNotNull(middle.asset);
        budget.releaseRetired(300);
        assertEquals(WAIT, admission.decide(near, 500));
        budget.releaseRetired(300);
        assertEquals(ADMIT, admission.decide(near, 500));
        retain(near, 500);
        for (int i = 0; i < 200; i++) {
            schedule.retryDeferred(far.column, budget.available());
            assertEquals(DEFER, admission.decide(far, 600));
            assertNull(schedule.next());
        }
        assertEquals(1, schedule.deferred());
        assertEquals(900, budget.bytes());
    }

    @Test public void fullBudgetDoesNotBlockAnEmptyRemovalBehindColdCapture() {
        TerrainSchedule.Section old = section(3, 4, 0), cold = section(0, 4, 0);
        retain(old, 1000);
        schedule.invalidate(cold);
        assertSame(cold, schedule.next());
        schedule.invalidate(old);
        assertTrue(schedule.hasRefresh());
        schedule.discard(cold);
        assertSame(old, schedule.next());
        assertEquals(ADMIT, admission.decide(old, 0));
        assertNull(admission.victim());
        budget.retire(1000);
        old.asset = null;
        schedule.publish(old, old.revision);
        assertTrue(old.ready());
        assertEquals(1000, budget.bytes());
        assertEquals(WAIT, admission.decide(cold, 600));
        budget.releaseRetired(1000);
        assertEquals(ADMIT, admission.decide(cold, 600));
    }

    @Test public void residentEditCanReleaseItsOwnStaleAssetWhenDuplicationCannotFit() {
        TerrainSchedule.Section edited = section(2, 4, 0), nearer = section(0, 4, 0);
        retain(edited, 600); retain(nearer, 400);
        schedule.invalidate(edited); assertSame(edited, schedule.next());
        assertEquals(EVICT, admission.decide(edited, 500));
        assertSame(edited, admission.victim());
        schedule.removeAsset(edited); edited.asset = null;
        budget.retire(600);
        assertEquals(WAIT, admission.decide(edited, 500));
        budget.releaseRetired(600);
        assertEquals(ADMIT, admission.decide(edited, 500));
        retain(edited, 500);
        assertTrue(edited.ready());
        assertNotNull(nearer.asset);
        assertEquals(900, budget.bytes());
    }

    @Test public void unrepresentableReplacementRemovesStaleOwnerWithoutEvictingUnrelatedTerrain() {
        TerrainSchedule.Section edited = section(0, 4, 0), other = section(4, 4, 0);
        retain(edited, 600); retain(other, 400);
        assertEquals(EVICT, admission.decide(edited, 1001));
        assertSame(edited, admission.victim());
        evict(edited, 600);
        assertEquals(DEFER, admission.decide(edited, 1001));
        assertNotNull(other.asset);
        assertNull(admission.victim());
    }

    @Test public void movementReopensDeferredWorkAndReversesSpatialPriority() {
        TerrainSchedule.Section oldNear = section(0, 4, 0), nextNear = section(3, 4, 0);
        retain(oldNear, 1000);
        schedule.defer(nextNear, 700);
        assertEquals(DEFER, admission.decide(nextNear, 700));
        schedule.move(3, 4, 0, 4, removed);
        assertEquals(0, schedule.deferred());
        assertSame(nextNear, schedule.next());
        assertEquals(EVICT, admission.decide(nextNear, 700));
        assertSame(oldNear, admission.victim());
    }

    @Test public void equalDistanceTieCannotOscillateResidency() {
        TerrainSchedule.Section left = section(-1, 4, 0), right = section(1, 4, 0);
        retain(right, 1000);
        assertEquals(EVICT, admission.decide(left, 1000));
        assertSame(right, admission.victim());
        evict(right, 1000); budget.releaseRetired(1000); retain(left, 1000);
        for (int i = 0; i < 100; i++) {
            assertEquals(DEFER, admission.decide(right, 1000));
            assertNull(admission.victim());
        }
    }

    @Test public void actualInvalidationReopensDeferredSizeSoRemovalCanPublish() {
        TerrainSchedule.Section section = section(0, 4, 0);
        schedule.defer(section, 2000);
        schedule.retryDeferred(section.column, 1000);
        assertEquals(0, schedule.pending());
        schedule.invalidate(section);
        assertEquals(-1, section.requiredBytes);
        assertEquals(0, schedule.deferred());
        assertSame(section, schedule.next());
        assertEquals(ADMIT, admission.decide(section, 0));
        schedule.publish(section, section.revision);
        assertTrue(section.ready());
    }

    @Test public void ordinaryRemovalReconsidersDeferredSectionThatAlsoNeedsAFartherVictim() {
        TerrainSchedule.Section nearest = section(0, 4, 0), removed = section(0, 4, 1);
        TerrainSchedule.Section middle = section(2, 4, 0), far = section(4, 4, 0);
        retain(nearest, 600); retain(removed, 200); retain(far, 100);
        schedule.defer(middle, 400);
        // The removal leaves only300 free; middle also needs the farther100.
        schedule.removeAsset(removed); removed.asset = null;
        budget.retire(200); budget.releaseRetired(200); schedule.capacityChanged();
        assertEquals(300, budget.available());
        schedule.retryDeferred(middle.column, budget.available());
        assertSame(middle, schedule.next());
        assertEquals(EVICT, admission.decide(middle, 400));
        assertSame(far, admission.victim());
        evict(far, 100); budget.releaseRetired(100);
        assertEquals(ADMIT, admission.decide(middle, 400));
        retain(middle, 400);
        assertNotNull(nearest.asset);
        assertEquals(1000, budget.bytes());
    }

    @Test public void pressureRetirementDoesNotReopenUnadmittableWorkAndChurnTheSameVictim() {
        TerrainSchedule.Section nearest = section(0, 4, 0), middle = section(2, 4, 0), far = section(4, 4, 0);
        retain(nearest, 700); retain(far, 300);
        assertEquals(EVICT, admission.decide(middle, 400));
        evict(far, 300); budget.releaseRetired(300);
        assertEquals(DEFER, admission.decide(middle, 400));
        schedule.defer(middle, 400);
        // Pressure retirements never signal capacityChanged: near700 still prevents middle400 fitting.
        for (int i = 0; i < 100; i++) schedule.retryDeferred(middle.column, budget.available());
        assertNull(schedule.next());
        schedule.retryDeferred(far.column, budget.available());
        assertSame(far, schedule.next());
        assertEquals(ADMIT, admission.decide(far, 300));
        retain(far, 300);
        for (int i = 0; i < 100; i++) {
            schedule.retryDeferred(middle.column, budget.available());
            assertNull(schedule.next());
        }
        assertEquals(1, schedule.deferred());
    }

    @Test public void evictingAnUnmeasuredEditKeepsItsPriorityAndDoesNotReuseTheOldSize() {
        TerrainSchedule.Section near = section(0, 4, 0), far = section(4, 4, 0);
        retain(near, 400);
        retain(far, 600);
        schedule.invalidate(near);
        schedule.invalidate(far);
        assertSame(near, schedule.next());
        assertEquals(EVICT, admission.decide(near, 600));
        assertSame(far, admission.victim());
        evict(far, 600);
        assertTrue(far.refresh);
        assertEquals(-1, far.requiredBytes);
        assertEquals(0, schedule.deferred());
        budget.releaseRetired(600);
        assertEquals(ADMIT, admission.decide(near, 600));
        retain(near, 600);
        budget.retire(400);
        budget.releaseRetired(400);
        assertSame(far, schedule.next());
        // This farther edit shrank; the old 600-byte asset would not fit here.
        assertEquals(ADMIT, admission.decide(far, 100));
        retain(far, 100);
        assertEquals(700, budget.bytes());
    }

    @Test public void wholeSectionMustFitBeforeAnyOfItsPagesCanBeUploaded() {
        TerrainSchedule.Section resident = section(0, 4, 0), incoming = section(1, 4, 0);
        retain(resident, 800);
        assertTrue(budget.canReserve(100));
        assertEquals(DEFER, admission.decide(incoming, 300));
        assertEquals(800, budget.bytes());
        assertNull(admission.victim());
    }

    @Test public void deferredUnloadCannotRequeueAnOldWorldOrKeepACapacityWarning() {
        TerrainSchedule.Section deferred = section(-4, 4, 0);
        schedule.defer(deferred, 500);
        schedule.move(1, 4, 0, 4, removed);
        assertEquals(0, schedule.deferred());
        assertFalse(deferred.column.resident);
        assertNull(schedule.next());
        schedule.clear(removed);
        assertEquals(0, schedule.pending());
        assertEquals(0, schedule.deferred());
    }

    private TerrainSchedule.Section section(int x, int y, int z) {
        TerrainSchedule.Column column = schedule.column(x, z);
        if (column.source == null) column.source = new Object();
        return column.sections[y];
    }
    private void retain(TerrainSchedule.Section section, long bytes) {
        budget.reserve(bytes);
        section.asset = new Object();
        schedule.publish(section, section.revision);
    }
    private void evict(TerrainSchedule.Section section, long bytes) {
        schedule.evict(section, bytes, true);
        budget.retire(bytes);
    }
}

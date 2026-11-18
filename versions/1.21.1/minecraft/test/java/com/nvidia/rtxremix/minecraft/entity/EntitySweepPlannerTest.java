/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;




class EntitySweepPlannerTest {

    private static final double MAX_DIST_SQ = 112.0 * 112.0;

    private static EntitySweepPlanner<String> sweepPlanner(int budget) {
        EntitySweepPlanner<String> planner = new EntitySweepPlanner<>(256);
        planner.begin(256 - budget);
        return planner;
    }

    private static Set<String> keptItems(EntitySweepPlanner<String> planner) {
        Set<String> kept = new HashSet<>();
        for (int i = 0; i < planner.size(); i++) {
            kept.add(planner.get(i));
        }
        return kept;
    }

    @ParameterizedTest
    @CsvSource({
            "2500.0, false, true",
            "12544.0, false, true",
            "12769.0, false, false",
            "1000000.0, true, true",
            "0.0, true, true"
    })
    void sweepAdmissionHonorsDistanceUnlessEntityOwnsLight(
            double distSq, boolean ownsLight, boolean expected) {
        assertEquals(expected, EntitySweepPlanner.admits(distSq, MAX_DIST_SQ, ownsLight));
        if (!ownsLight) {
            assertEquals(distSq, EntitySweepPlanner.priority(distSq, false));
        }
    }

    @Test
    void lightOwnerRanksBelowEveryNonOwner() {
        // A non-owner standing at the camera is the best any non-owner can rank.
        double bestNonOwner = EntitySweepPlanner.priority(0.0, false);
        assertTrue(EntitySweepPlanner.priority(1000.0 * 1000.0, true) < bestNonOwner);
        assertTrue(EntitySweepPlanner.priority(0.0, true) < bestNonOwner);
    }

    @Test
    void lightOwnerSurvivesASaturatedClosestFirstHeap() {
        // The guarantee the priority exists for: a lighthouse 130 blocks out must not be
        // evicted by a crowd of nearer entities once the budget is full.
        EntitySweepPlanner<String> planner = sweepPlanner(2);
        planner.offer("near-cow", 4.0, MAX_DIST_SQ, false);
        planner.offer("nearer-cow", 1.0, MAX_DIST_SQ, false);
        planner.offer("lighthouse", 130.0 * 130.0, MAX_DIST_SQ, true);

        assertTrue(keptItems(planner).contains("lighthouse"),
                "a light owner must never lose the closest-first selection");
        assertEquals(1, planner.overflowCount());
    }

    @Test
    void nearerLightOwnerWinsWhenTwoOwnersCompeteForOneSlot() {
        // The farther owner iterates first, which is the order that would win on a tie.
        EntitySweepPlanner<String> planner = sweepPlanner(1);
        planner.offer("far-lantern", 130.0 * 130.0, MAX_DIST_SQ, true);
        planner.offer("near-lantern", 30.0 * 30.0, MAX_DIST_SQ, true);

        assertEquals(Set.of("near-lantern"), keptItems(planner));
        assertEquals(1, planner.overflowCount());
    }

    @Test
    void fullVisibleBudgetAdmitsNoSweepCandidates() {
        EntitySweepPlanner<String> planner = new EntitySweepPlanner<>(2);

        assertFalse(planner.begin(2));
        planner.offer("lighthouse", 1.0, MAX_DIST_SQ, true);

        assertEquals(0, planner.size());
    }
}

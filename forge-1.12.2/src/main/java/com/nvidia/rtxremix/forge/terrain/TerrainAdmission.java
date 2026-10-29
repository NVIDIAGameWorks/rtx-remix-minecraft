/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

/** Complete-section admission; queued native destruction is not yet available memory. */
final class TerrainAdmission {
    enum Result { ADMIT, WAIT, EVICT, DEFER }
    private final TerrainSchedule schedule;
    private final TerrainBudget budget;
    private TerrainSchedule.Section victim;

    TerrainAdmission(TerrainSchedule schedule, TerrainBudget budget) {
        this.schedule = schedule;
        this.budget = budget;
    }

    Result decide(TerrainSchedule.Section incoming, long requiredBytes) {
        if (requiredBytes < 0) throw new IllegalArgumentException("Terrain size must be measured before admission");
        victim = null;
        if (budget.canReserve(requiredBytes)) return Result.ADMIT;
        if (requiredBytes <= budget.limit()) {
            if (budget.canReserveAfterRetirement(requiredBytes)) return Result.WAIT;
            victim = schedule.evictionCandidate(incoming);
            if (victim != null) return Result.EVICT;
        }
        // A dirty replacement must not preserve stale blocks forever, even when
        // duplicating its old and new meshes cannot fit in the ownership budget.
        if (incoming.asset != null) {
            victim = incoming;
            return Result.EVICT;
        }
        return Result.DEFER;
    }

    TerrainSchedule.Section victim() { return victim; }
}

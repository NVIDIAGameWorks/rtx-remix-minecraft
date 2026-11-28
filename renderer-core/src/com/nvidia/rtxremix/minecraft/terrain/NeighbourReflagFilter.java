/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decides which of vanilla's chunk-load neighbour re-flags are worth a section rebuild.
 *
 * <p>When a chunk column loads, vanilla flags the 3x3x3 section neighbourhood so that a
 * section captured while that column was missing is captured again with it present. The
 * Remix emitter reads nothing outside the 3x3 column region snapshot taken on the render
 * thread when a build is scheduled, so a re-flag can only change captured geometry when the
 * loading column was absent from that snapshot. Every built-chunk slot records which of its
 * eight horizontal neighbour columns the snapshot held as an 8-bit mask, and a re-flag from
 * a column whose bit is set is dropped.
 *
 * <p>Invariant: bit k of a slot's mask is set only if neighbour column (DX[k], DZ[k]) was
 * loaded when the slot's pending or published capture took its region snapshot, and that
 * column has neither unloaded nor been reloaded in place since. The mask is written and
 * read on the render thread only.
 */
public final class NeighbourReflagFilter {
    public static final String PROPERTY = "rtxremix.chunk.neighbourReflagFilter";

    /** Read once: a session is wholly in one mode, so the per-slot masks never need re-deriving. */
    public static final boolean ENABLED = enabledFrom(System.getProperty(PROPERTY));

    /** Horizontal neighbour offsets in chunk columns. Mask bit k stands for neighbour (DX[k], DZ[k]). */
    public static final int[] DX = {-1, 0, 1, -1, 1, -1, 0, 1};
    public static final int[] DZ = {-1, -1, -1, 0, 0, 1, 1, 1};
    public static final int NEIGHBOUR_COUNT = 8;
    public static final int COMPLETE = 0xFF;

    /** Vanilla's shouldBuild exempts sections within 24 blocks of the camera from the neighbour requirement. */
    private static final double NEAR_CAMERA_SQUARED_DISTANCE = 576.0;

    private static final AtomicInteger KEPT = new AtomicInteger();
    private static final AtomicInteger INCOMPLETE_FIRST_BUILDS = new AtomicInteger();

    /** What a chunk load does to one horizontal neighbour slot of the loading column. */
    public enum Decision {
        /** The slot's capture never saw this column: flag it as vanilla would. */
        FLAG,
        /** The slot holds a different section; vanilla's flag would rebuild an unrelated one. */
        SKIP_NOT_RESIDENT,
        /** A rebuild is already pending and will snapshot the world after this load. */
        SKIP_AWAITING_BUILD,
        /** The slot's last snapshot already contained this column, so a rebuild would read identical data. */
        DROP_IN_SNAPSHOT
    }

    private NeighbourReflagFilter() {
    }

    static boolean enabledFrom(String property) {
        return property == null || Boolean.parseBoolean(property);
    }

    /** Index of the neighbour at horizontal chunk offset (dx, dz), each in -1..1 and not both zero. */
    public static int neighbourIndex(int dx, int dz) {
        if (dx < -1 || dx > 1 || dz < -1 || dz > 1 || (dx == 0 && dz == 0)) {
            throw new IllegalArgumentException("not a horizontal neighbour offset: " + dx + ", " + dz);
        }
        int linear = (dz + 1) * 3 + (dx + 1);
        return linear < 4 ? linear : linear - 1;
    }

    /** The index under which neighbour k sees the column at the centre. */
    public static int mirroredIndex(int k) {
        return neighbourIndex(-DX[k], -DZ[k]);
    }

    /** Mask bit for neighbour index k. */
    public static int bit(int k) {
        return 1 << k;
    }

    /**
     * Whether a section Y has a slot of its own. Vanilla aliases the light sections just
     * below and above the world onto the far slot through floorMod, which rebuilds an
     * unrelated section.
     */
    public static boolean sectionInRange(int sy, int bottomSection, int sizeY) {
        return sy >= bottomSection && sy < bottomSection + sizeY;
    }

    /**
     * Applies the neighbour rule for one slot when a column loads.
     *
     * @param resident     whether the slot holds the section at that neighbour position
     * @param needsRebuild vanilla's pending-rebuild flag on the slot
     * @param snapshotMask the slot's recorded snapshot mask
     * @param loaderBit    the loading column's bit as seen from the slot
     */
    public static Decision decideNeighbour(boolean resident, boolean needsRebuild, int snapshotMask, int loaderBit) {
        if (!resident) {
            return Decision.SKIP_NOT_RESIDENT;
        }
        if (needsRebuild) {
            return Decision.SKIP_AWAITING_BUILD;
        }
        if ((snapshotMask & loaderBit) != 0) {
            return Decision.DROP_IN_SNAPSHOT;
        }
        return Decision.FLAG;
    }

    /**
     * A first build beyond vanilla's near-camera exemption whose snapshot lacked a neighbour
     * column. Such a section will be re-flagged and rebuilt when that column arrives, exactly
     * as in vanilla; the count measures how much of that cost the filter cannot remove.
     */
    public static boolean isIncompleteFirstBuild(boolean firstBuild, double squaredCameraDistance, int mask) {
        return firstBuild && squaredCameraDistance > NEAR_CAMERA_SQUARED_DISTANCE && mask != COMPLETE;
    }

    /** Records a neighbour re-flag that was passed through because the slot's snapshot lacked the column. */
    public static void recordKept() {
        KEPT.incrementAndGet();
    }

    /** Returns and resets the kept count, for the per-frame terrain counters. */
    public static int drainKept() {
        return KEPT.getAndSet(0);
    }

    /** Records a far first build whose snapshot lacked a neighbour column. */
    public static void recordIncompleteFirstBuild() {
        INCOMPLETE_FIRST_BUILDS.incrementAndGet();
    }

    /** Returns and resets the incomplete-first-build count, for the per-frame terrain counters. */
    public static int drainIncompleteFirstBuilds() {
        return INCOMPLETE_FIRST_BUILDS.getAndSet(0);
    }

    /** One startup log line that makes every run self-describing. */
    public static String effectiveStateLine() {
        return ENABLED
                ? "Chunk-load neighbour re-flags: filtered by snapshot mask (" + PROPERTY + "=true)"
                : "Chunk-load neighbour re-flags: vanilla 3x3x3 (" + PROPERTY + "=false)";
    }
}

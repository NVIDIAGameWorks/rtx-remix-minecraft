/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import net.minecraft.block.entity.BeaconBlockEntity;
import net.minecraft.world.World;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vanilla beacons in the client world, whose beams {@link LightManager} lights.
 *
 * <p>An active beam is a landmark seen from far outside the frustum and the
 * block-entity render distance, so its lights cannot wait for vanilla to draw
 * the beacon; {@code BeaconBlockEntityMixin} records every client-side beacon
 * as its world is set and drops it as it is removed. A world swap does not
 * remove the old world's block entities, so {@link LightManager} also prunes
 * entries whose world is no longer the client's.
 */
public final class LoadedVanillaBeacons {

    private static final Set<BeaconBlockEntity> LOADED = ConcurrentHashMap.newKeySet();

    private LoadedVanillaBeacons() {}

    /** Records {@code beacon} if it belongs to a client world. */
    public static void track(BeaconBlockEntity beacon) {
        World world = beacon.getWorld();
        if (world != null && world.isClient()) {
            LOADED.add(beacon);
        }
    }

    public static void untrack(BeaconBlockEntity beacon) {
        LOADED.remove(beacon);
    }

    /** A weakly consistent iterator whose {@code remove} drops the current beacon. */
    static Iterator<BeaconBlockEntity> iterator() {
        return LOADED.iterator();
    }
}

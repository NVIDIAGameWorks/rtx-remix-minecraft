/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.logic;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.world.PersistentStateManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZonePersistentStateTest {

    @Test
    void sphereRoundTripsThroughNbt() {
        ZoneDefinition zone = ZoneDefinition.parse(
                "rtx zone define cave --center 10.5 20 -30.25 --inner 8 --outer 24 --channel mc.zone.underground",
                0, 0, 0);

        ZonePersistentState loaded = roundTrip(zone);

        assertEquals(1, loaded.snapshot().size());
        assertEquals(zone, loaded.snapshot().iterator().next());
    }

    @Test
    void normalizedBoxRoundTripsThroughNbt() {
        ZoneDefinition zone = ZoneDefinition.parse(
                "rtx zone define hall --box 10 20 30 -5 4 6 --fade 12 --channel mc.zone.interior",
                0, 0, 0);

        ZoneDefinition restored = roundTrip(zone).snapshot().iterator().next();

        assertEquals(zone, restored);
        assertEquals(-5.0, restored.minX());
        assertEquals(10.0, restored.maxX());
        assertEquals(4.0, restored.minY());
        assertEquals(20.0, restored.maxY());
    }

    @Test
    void effectiveChangesAloneDirtyTheState() {
        ZonePersistentState state = new ZonePersistentState();
        ZoneDefinition first = ZoneDefinition.parse(
                "rtx zone define cave --center 0 0 0 --inner 5 --outer 10", 0, 0, 0);
        ZoneDefinition moved = ZoneDefinition.parse(
                "rtx zone define cave --center 100 0 0 --inner 5 --outer 10", 0, 0, 0);

        assertFalse(state.isDirty());
        assertTrue(state.put(first));
        assertTrue(state.isDirty());

        state.setDirty(false);
        assertFalse(state.put(first));
        assertFalse(state.isDirty(), "an identical redefinition must not dirty the save");

        assertTrue(state.put(moved));
        assertTrue(state.isDirty());

        state.setDirty(false);
        assertFalse(state.remove("missing"));
        assertFalse(state.isDirty());
        assertTrue(state.remove("cave"));
        assertTrue(state.isDirty());
    }

    @Test
    void malformedEntryDoesNotDiscardValidZones() {
        ZoneDefinition valid = ZoneDefinition.parse(
                "rtx zone define valid --center 1 2 3 --inner 4 --outer 8", 0, 0, 0);
        ZonePersistentState source = new ZonePersistentState();
        source.put(valid);
        NbtCompound nbt = source.writeNbt(new NbtCompound(), null);

        NbtCompound malformed = new NbtCompound();
        malformed.putString("name", "broken");
        malformed.putString("channel", "mc.zone.broken");
        malformed.putString("shape", "sphere");
        nbt.getList("zones", NbtElement.COMPOUND_TYPE).add(malformed);

        ZonePersistentState loaded = ZonePersistentState.fromNbt(nbt, null);

        assertEquals(1, loaded.snapshot().size());
        assertEquals(valid, loaded.snapshot().iterator().next());
    }

    @Test
    void unsupportedSchemaLoadsAsEmptyWithoutDirtying() {
        NbtCompound nbt = new NbtCompound();
        nbt.putInt("version", 999);
        nbt.put("zones", new NbtList());

        ZonePersistentState loaded = ZonePersistentState.fromNbt(nbt, null);

        assertTrue(loaded.snapshot().isEmpty());
        assertFalse(loaded.isDirty());
        assertThrows(IllegalStateException.class,
                () -> loaded.put(ZoneDefinition.parse("rtx zone define newer", 0, 0, 0)));
        assertThrows(IllegalStateException.class, () -> loaded.remove("anything"));
        assertFalse(loaded.isDirty(), "an older mod must never overwrite a newer schema");
    }

    @Test
    void duplicateNamesUseLastDefinition() {
        ZoneDefinition first = ZoneDefinition.parse(
                "rtx zone define cave --center 0 0 0 --inner 5 --outer 10", 0, 0, 0);
        ZoneDefinition second = ZoneDefinition.parse(
                "rtx zone define cave --center 100 0 0 --inner 5 --outer 10", 0, 0, 0);

        NbtCompound firstNbt = writeOne(first);
        NbtCompound secondNbt = writeOne(second);
        NbtList zones = new NbtList();
        zones.add(firstNbt);
        zones.add(secondNbt);
        NbtCompound root = new NbtCompound();
        root.putInt("version", 1);
        root.put("zones", zones);

        ZoneDefinition restored = ZonePersistentState.fromNbt(root, null).snapshot().iterator().next();

        assertEquals(second, restored);
        assertEquals(1.0f, restored.weight(100, 0, 0), 1e-6f);
    }

    @Test
    void snapshotIsImmutableAndNameSorted() {
        ZonePersistentState state = new ZonePersistentState();
        state.put(ZoneDefinition.parse("rtx zone define zed", 0, 0, 0));
        state.put(ZoneDefinition.parse("rtx zone define alpha", 0, 0, 0));

        Collection<ZoneDefinition> snapshot = state.snapshot();
        ArrayList<String> names = new ArrayList<>();
        snapshot.forEach(zone -> names.add(zone.name()));

        assertEquals(List.of("alpha", "zed"), names);
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }

    @Test
    void writesMinecraftPersistentStateFile(@TempDir Path tempDir) throws Exception {
        ZoneDefinition zone = ZoneDefinition.parse(
                "rtx zone define saved --center 4 5 6 --inner 7 --outer 11", 0, 0, 0);
        ZonePersistentState state = new ZonePersistentState();
        state.put(zone);
        Path file = tempDir.resolve("rtx_remix_zones.dat");

        ensureGameVersionForPersistentStateSave();
        // 1.21.4 moved disk writes to the manager; save() waits for its I/O future.
        // This write-only fixture needs neither data fixing nor registry lookups.
        try (PersistentStateManager manager = new PersistentStateManager(tempDir, null, null)) {
            manager.set("rtx_remix_zones", state);
            manager.save();
        }

        assertFalse(state.isDirty());
        NbtCompound root = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes());
        ZonePersistentState loaded = ZonePersistentState.fromNbt(root.getCompound("data"), null);
        assertEquals(List.of(zone), new ArrayList<>(loaded.snapshot()));
    }

    private static ZonePersistentState roundTrip(ZoneDefinition zone) {
        ZonePersistentState state = new ZonePersistentState();
        state.put(zone);
        NbtCompound nbt = state.writeNbt(new NbtCompound(), null);
        return ZonePersistentState.fromNbt(nbt, null);
    }

    private static NbtCompound writeOne(ZoneDefinition zone) {
        ZonePersistentState state = new ZonePersistentState();
        state.put(zone);
        return state.writeNbt(new NbtCompound(), null)
                .getList("zones", NbtElement.COMPOUND_TYPE)
                .getCompound(0)
                .copy();
    }

    private static void ensureGameVersionForPersistentStateSave() {
        try {
            SharedConstants.getGameVersion();
        } catch (IllegalStateException unset) {
            SharedConstants.createGameVersion();
        }
    }
}

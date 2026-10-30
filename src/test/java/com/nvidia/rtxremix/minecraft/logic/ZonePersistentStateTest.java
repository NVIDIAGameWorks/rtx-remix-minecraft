/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.logic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
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
        CompoundTag nbt = source.toNbt();

        CompoundTag malformed = new CompoundTag();
        malformed.putString("name", "broken");
        malformed.putString("channel", "mc.zone.broken");
        malformed.putString("shape", "sphere");
        nbt.getListOrEmpty("zones").add(malformed);

        ZonePersistentState loaded = ZonePersistentState.fromNbt(nbt);

        assertEquals(1, loaded.snapshot().size());
        assertEquals(valid, loaded.snapshot().iterator().next());
    }

    @Test
    void unsupportedSchemaLoadsAsEmptyWithoutDirtying() {
        CompoundTag nbt = new CompoundTag();
        nbt.putInt("version", 999);
        nbt.put("zones", new ListTag());

        ZonePersistentState loaded = ZonePersistentState.fromNbt(nbt);

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

        CompoundTag firstNbt = writeOne(first);
        CompoundTag secondNbt = writeOne(second);
        ListTag zones = new ListTag();
        zones.add(firstNbt);
        zones.add(secondNbt);
        CompoundTag root = new CompoundTag();
        root.putInt("version", 1);
        root.put("zones", zones);

        ZoneDefinition restored = ZonePersistentState.fromNbt(root).snapshot().iterator().next();

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
    void roundTripsThroughSavedDataTypeCodec(@TempDir Path tempDir) throws Exception {
        ZoneDefinition zone = ZoneDefinition.parse(
                "rtx zone define saved --center 4 5 6 --inner 7 --outer 11", 0, 0, 0);
        ZonePersistentState state = new ZonePersistentState();
        state.put(zone);
        Path file = tempDir.resolve("zones.dat");

        // The same envelope SavedDataStorage writes: the codec's tag under "data".
        CompoundTag written = new CompoundTag();
        written.put("data", ZonePersistentState.TYPE.codec().encodeStart(NbtOps.INSTANCE, state).getOrThrow());
        NbtIo.writeCompressed(written, file);

        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        ZonePersistentState loaded = ZonePersistentState.TYPE.codec()
                .parse(NbtOps.INSTANCE, root.getCompoundOrEmpty("data")).getOrThrow();
        assertEquals(List.of(zone), new ArrayList<>(loaded.snapshot()));
    }

    private static ZonePersistentState roundTrip(ZoneDefinition zone) {
        ZonePersistentState state = new ZonePersistentState();
        state.put(zone);
        CompoundTag nbt = state.toNbt();
        return ZonePersistentState.fromNbt(nbt);
    }

    private static CompoundTag writeOne(ZoneDefinition zone) {
        ZonePersistentState state = new ZonePersistentState();
        state.put(zone);
        return state.toNbt()
                .getListOrEmpty("zones")
                .getCompoundOrEmpty(0)
                .copy();
    }

}

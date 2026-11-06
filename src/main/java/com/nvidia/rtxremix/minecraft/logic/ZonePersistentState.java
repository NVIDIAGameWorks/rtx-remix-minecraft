/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.logic;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * World-save owner for zones authored with {@code /rtx zone define} in an
 * integrated single-player server. The renderer consumes a copied projection
 * in {@link ZoneRegistry}; it never reads this server-thread state directly.
 *
 * <p>The state is attached to the overworld's persistent-state manager so all
 * definitions live in one {@code data/rtx_remix_zones.dat} file, and a zone is
 * global to the save rather than scoped to a dimension.</p>
 */
public final class ZonePersistentState extends SavedData {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZonePersistentState.class);
    private static final String DATA_ID = "rtx_remix_zones";
    private static final int SCHEMA_VERSION = 1;

    private static final String VERSION_KEY = "version";
    private static final String ZONES_KEY = "zones";
    private static final String NAME_KEY = "name";
    private static final String CHANNEL_KEY = "channel";
    private static final String SHAPE_KEY = "shape";
    private static final String SPHERE_SHAPE = "sphere";
    private static final String BOX_SHAPE = "box";

    private static final SavedData.Factory<ZonePersistentState> TYPE =
            new SavedData.Factory<>(ZonePersistentState::new,
                    ZonePersistentState::fromNbt,
                    DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<String, ZoneDefinition> zonesByName = new HashMap<>();
    private final boolean writable;

    ZonePersistentState() {
        this(true);
    }

    private ZonePersistentState(boolean writable) {
        this.writable = writable;
    }

    /**
     * Gets the state attached to this integrated server's world save, creating
     * it when absent. Server thread only.
     *
     * <p>Use this only on a path that is about to store a definition.
     * Read-only callers must use {@link #getIfPresent(MinecraftServer)} so a
     * world that never uses {@code /rtx zone define} is not given a state
     * object it does not need.</p>
     */
    public static ZonePersistentState getOrCreate(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE, DATA_ID);
    }

    /**
     * The saved state for this world, or null when the world has none.
     *
     * <p>{@code rtx_remix_zones.dat} is optional: a map can define its zones
     * entirely with command blocks, which are stored as blocks in the region
     * files and never touch this state. Reading through the nullable lookup
     * keeps that structural rather than incidental. Minecraft would not write
     * an un-dirtied state either way, but this way an unused world is not
     * handed a state object at all, and nothing about the published world
     * depends on that behavior staying as it is.</p>
     */
    public static ZonePersistentState getIfPresent(MinecraftServer server) {
        return server.overworld().getDataStorage().get(TYPE, DATA_ID);
    }

    /** Immutable, name-sorted copy safe to publish to the client/render registry. */
    public Collection<ZoneDefinition> snapshot() {
        ArrayList<ZoneDefinition> zones = new ArrayList<>(zonesByName.values());
        zones.sort(Comparator.comparing(ZoneDefinition::name));
        return List.copyOf(zones);
    }

    /** Adds or replaces a definition and dirties the save only for an effective change. */
    public boolean put(ZoneDefinition zone) {
        requireWritable();
        Objects.requireNonNull(zone, "zone");
        ZoneDefinition previous = zonesByName.get(zone.name());
        if (zone.equals(previous)) {
            return false;
        }
        zonesByName.put(zone.name(), zone);
        setDirty();
        return true;
    }

    /** Removes a saved definition and dirties the save only when it existed. */
    public boolean remove(String name) {
        requireWritable();
        if (zonesByName.remove(name) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) {
        nbt.putInt(VERSION_KEY, SCHEMA_VERSION);
        ListTag zones = new ListTag();
        for (ZoneDefinition zone : snapshot()) {
            zones.add(writeZone(zone));
        }
        nbt.put(ZONES_KEY, zones);
        return nbt;
    }

    static ZonePersistentState fromNbt(CompoundTag nbt, HolderLookup.Provider registries) {
        if (!nbt.contains(VERSION_KEY, Tag.TAG_INT)) {
            LOGGER.warn("Ignoring RTX Remix zone state without a schema version");
            return new ZonePersistentState(false);
        }
        int version = nbt.getInt(VERSION_KEY);
        if (version != SCHEMA_VERSION) {
            LOGGER.warn("Ignoring RTX Remix zone state schema version {} (expected {})",
                    version, SCHEMA_VERSION);
            return new ZonePersistentState(false);
        }
        ZonePersistentState state = new ZonePersistentState();
        if (!nbt.contains(ZONES_KEY, Tag.TAG_LIST)) {
            return state;
        }

        ListTag zones = nbt.getList(ZONES_KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < zones.size(); i++) {
            try {
                ZoneDefinition zone = readZone(zones.getCompound(i));
                ZoneDefinition previous = state.zonesByName.put(zone.name(), zone);
                if (previous != null) {
                    LOGGER.warn("Duplicate saved RTX Remix zone '{}'; using the last definition", zone.name());
                }
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Ignoring malformed saved RTX Remix zone at index {}: {}", i, e.getMessage());
            }
        }
        return state;
    }

    private void requireWritable() {
        if (!writable) {
            throw new IllegalStateException(
                    "saved zone data uses an unsupported schema; refusing to overwrite it");
        }
    }

    private static CompoundTag writeZone(ZoneDefinition zone) {
        CompoundTag nbt = new CompoundTag();
        nbt.putString(NAME_KEY, zone.name());
        nbt.putString(CHANNEL_KEY, zone.channel());
        if (zone.isBox()) {
            nbt.putString(SHAPE_KEY, BOX_SHAPE);
            nbt.putDouble("minX", zone.minX());
            nbt.putDouble("minY", zone.minY());
            nbt.putDouble("minZ", zone.minZ());
            nbt.putDouble("maxX", zone.maxX());
            nbt.putDouble("maxY", zone.maxY());
            nbt.putDouble("maxZ", zone.maxZ());
            nbt.putDouble("fade", zone.fade());
        } else {
            nbt.putString(SHAPE_KEY, SPHERE_SHAPE);
            nbt.putDouble("centerX", zone.centerX());
            nbt.putDouble("centerY", zone.centerY());
            nbt.putDouble("centerZ", zone.centerZ());
            nbt.putDouble("innerRadius", zone.innerRadius());
            nbt.putDouble("outerRadius", zone.outerRadius());
        }
        return nbt;
    }

    private static ZoneDefinition readZone(CompoundTag nbt) {
        String name = requireString(nbt, NAME_KEY);
        String channel = requireString(nbt, CHANNEL_KEY);
        String shape = requireString(nbt, SHAPE_KEY);
        return switch (shape) {
            case SPHERE_SHAPE -> ZoneDefinition.storedSphere(name, channel,
                    requireDouble(nbt, "centerX"),
                    requireDouble(nbt, "centerY"),
                    requireDouble(nbt, "centerZ"),
                    requireDouble(nbt, "innerRadius"),
                    requireDouble(nbt, "outerRadius"));
            case BOX_SHAPE -> ZoneDefinition.storedBox(name, channel,
                    requireDouble(nbt, "minX"),
                    requireDouble(nbt, "minY"),
                    requireDouble(nbt, "minZ"),
                    requireDouble(nbt, "maxX"),
                    requireDouble(nbt, "maxY"),
                    requireDouble(nbt, "maxZ"),
                    requireDouble(nbt, "fade"));
            default -> throw new IllegalArgumentException("unknown shape '" + shape + "'");
        };
    }

    private static String requireString(CompoundTag nbt, String key) {
        if (!nbt.contains(key, Tag.TAG_STRING)) {
            throw new IllegalArgumentException("missing string '" + key + "'");
        }
        return nbt.getString(key);
    }

    private static double requireDouble(CompoundTag nbt, String key) {
        if (!nbt.contains(key, Tag.TAG_ANY_NUMERIC)) {
            throw new IllegalArgumentException("missing number '" + key + "'");
        }
        return nbt.getDouble(key);
    }
}

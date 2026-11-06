/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.logic;

import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NumericTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>The state lives in the server's save-wide data storage, so all
 * definitions live in one {@code data/rtx-remix-renderer/zones.dat} file, and a
 * zone is global to the save rather than scoped to a dimension.</p>
 *
 * <p>The 1.21.1 build kept them in {@code data/rtx_remix_zones.dat}, a name
 * Minecraft's own world upgrade does not know and leaves in place. The first
 * lookup in a world without the current file copies that file's zones into
 * it; the legacy file is left untouched, so an older build still reads it.</p>
 */
public final class ZonePersistentState extends SavedData {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZonePersistentState.class);
    private static final int SCHEMA_VERSION = 1;
    /** The 1.21.1 build's file, relative to the world's {@code data} folder. */
    static final String LEGACY_FILE = "rtx_remix_zones.dat";

    private static final String VERSION_KEY = "version";
    private static final String ZONES_KEY = "zones";
    private static final String NAME_KEY = "name";
    private static final String CHANNEL_KEY = "channel";
    private static final String SHAPE_KEY = "shape";
    private static final String SPHERE_SHAPE = "sphere";
    private static final String BOX_SHAPE = "box";

    static final SavedDataType<ZonePersistentState> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(RtxRemixCommonMod.MOD_ID, "zones"),
            ZonePersistentState::new,
            CompoundTag.CODEC.xmap(ZonePersistentState::fromNbt, ZonePersistentState::toNbt),
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
        ZonePersistentState state = getIfPresent(server);
        return state != null ? state : server.getDataStorage().computeIfAbsent(TYPE);
    }

    /**
     * The saved state for this world, or null when the world has none.
     *
     * <p>{@code zones.dat} is optional: a map can define its zones
     * entirely with command blocks, which are stored as blocks in the region
     * files and never touch this state. Reading through the nullable lookup
     * keeps that structural rather than incidental. Minecraft would not write
     * an un-dirtied state either way, but this way an unused world is not
     * handed a state object at all, and nothing about the published world
     * depends on that behavior staying as it is.</p>
     */
    public static ZonePersistentState getIfPresent(MinecraftServer server) {
        ZonePersistentState state = server.getDataStorage().get(TYPE);
        if (state != null) {
            return state;
        }
        ZonePersistentState legacy = readLegacy(server.getWorldPath(LevelResource.DATA).resolve(LEGACY_FILE));
        if (legacy == null) {
            return null;
        }
        // Copied into a fresh state, which saves under the current name with the world.
        ZonePersistentState migrated = server.getDataStorage().computeIfAbsent(TYPE);
        for (ZoneDefinition zone : legacy.snapshot()) {
            migrated.put(zone);
        }
        LOGGER.info("Migrated {} RTX Remix zone(s) from the 1.21.1 file {}", legacy.zonesByName.size(), LEGACY_FILE);
        return migrated;
    }

    /**
     * The zones in a file the 1.21.1 build wrote, or null when there is no such file or it cannot be read. That
     * build saved the same schema inside the {@code data} envelope every persistent state used.
     */
    static ZonePersistentState readLegacy(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            return fromNbt(root.getCompoundOrEmpty("data"));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read the 1.21.1 RTX Remix zone file {}: {}", file, e.getMessage());
            return null;
        }
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

    CompoundTag toNbt() {
        CompoundTag nbt = new CompoundTag();
        nbt.putInt(VERSION_KEY, SCHEMA_VERSION);
        ListTag zones = new ListTag();
        for (ZoneDefinition zone : snapshot()) {
            zones.add(writeZone(zone));
        }
        nbt.put(ZONES_KEY, zones);
        return nbt;
    }

    static ZonePersistentState fromNbt(CompoundTag nbt) {
        if (nbt.getInt(VERSION_KEY).isEmpty()) {
            LOGGER.warn("Ignoring RTX Remix zone state without a schema version");
            return new ZonePersistentState(false);
        }
        int version = nbt.getIntOr(VERSION_KEY, 0);
        if (version != SCHEMA_VERSION) {
            LOGGER.warn("Ignoring RTX Remix zone state schema version {} (expected {})",
                    version, SCHEMA_VERSION);
            return new ZonePersistentState(false);
        }
        ZonePersistentState state = new ZonePersistentState();
        if (!(nbt.get(ZONES_KEY) instanceof ListTag zones)) {
            return state;
        }

        for (int i = 0; i < zones.size(); i++) {
            try {
                if (!(zones.get(i) instanceof CompoundTag zoneNbt)) {
                    throw new IllegalArgumentException("not a compound");
                }
                ZoneDefinition zone = readZone(zoneNbt);
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
        return nbt.getString(key).orElseThrow(
                () -> new IllegalArgumentException("missing string '" + key + "'"));
    }

    private static double requireDouble(CompoundTag nbt, String key) {
        if (!(nbt.get(key) instanceof NumericTag number)) {
            throw new IllegalArgumentException("missing number '" + key + "'");
        }
        return number.doubleValue();
    }
}

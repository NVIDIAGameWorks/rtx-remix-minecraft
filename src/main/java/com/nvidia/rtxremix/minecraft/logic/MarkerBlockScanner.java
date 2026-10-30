/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.function.BooleanSupplier;

/**
 * Discovers zones authored as command blocks whose command text starts with
 * "rtx zone define" (leading slash optional). The command is parsed as
 * declarative data and never executed. Reads go through the integrated
 * server's worlds (single-player only) - client-side command-block NBT is not
 * reliably synced.
 *
 * Discovery is passive: RtxRemixMod registers a ServerChunkEvents.CHUNK_LOAD
 * listener that calls {@link #scanChunk} on the server thread as chunks
 * stream in around the player, plus an on-demand full rescan for authoring
 * iteration (/rtx zone rescan). Zones persist in the registry once seen; a
 * removed command block needs /rtx zone remove or a world reload to
 * disappear. Marker definitions are not copied into
 * {@link ZonePersistentState}; the command block remains their storage.
 */
public final class MarkerBlockScanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(MarkerBlockScanner.class);
    private static final String MARKER = "rtx zone define";

    private MarkerBlockScanner() {
    }

    /** Full rescan of loaded chunks around every player; hops to the server thread. */
    public static void rescan(MinecraftServer server, ZoneRegistry registry) {
        rescan(server, registry, () -> true);
    }

    /**
     * Full rescan with a session guard. The guarded form is used by join-time
     * hydration so a closing integrated server cannot repopulate the registry
     * after the client has disconnected.
     */
    public static void rescan(MinecraftServer server, ZoneRegistry registry, BooleanSupplier sessionIsCurrent) {
        server.execute(() -> {
            if (!sessionIsCurrent.getAsBoolean()) {
                return;
            }
            int scanned = 0;
            int viewDistance = server.getPlayerList().getViewDistance();
            for (ServerLevel world : server.getAllLevels()) {
                // Overlapping player view ranges must not double-scan a chunk.
                HashSet<Long> visited = new HashSet<>();
                for (var player : world.players()) {
                    ChunkPos center = player.chunkPosition();
                    for (int cx = center.x - viewDistance; cx <= center.x + viewDistance; cx++) {
                        for (int cz = center.z - viewDistance; cz <= center.z + viewDistance; cz++) {
                            if (!sessionIsCurrent.getAsBoolean()) {
                                return;
                            }
                            if (!visited.add(ChunkPos.asLong(cx, cz))) {
                                continue;
                            }
                            ChunkAccess chunk = world.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                            if (chunk instanceof LevelChunk worldChunk) {
                                scanChunk(registry, worldChunk);
                                scanned++;
                            }
                        }
                    }
                }
            }
            LOGGER.info("Zone rescan complete: {} loaded chunks scanned, {} zones registered", scanned, registry.zones().size());
        });
    }

    /** Registers every well-formed "rtx zone define" command block in the chunk. Server thread. */
    public static void scanChunk(ZoneRegistry registry, LevelChunk chunk) {
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (!(be instanceof CommandBlockEntity commandBlock)) {
                continue;
            }
            String command = commandBlock.getCommandBlock().getCommand();
            if (command == null) {
                continue;
            }
            String trimmed = command.strip();
            String withoutSlash = trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
            if (!withoutSlash.startsWith(MARKER)) {
                continue;
            }
            // Token boundary: silently skip e.g. "rtx zone definitely..." rather than warn.
            if (withoutSlash.length() > MARKER.length()
                    && !Character.isWhitespace(withoutSlash.charAt(MARKER.length()))) {
                continue;
            }
            BlockPos pos = be.getBlockPos();
            try {
                ZoneDefinition zone = ZoneDefinition.parse(trimmed,
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                ZoneDefinition previous = registry.put(zone);
                if (previous == null) {
                    // CHUNK_LOAD re-fires on border->full re-promotions; only announce new zones.
                    LOGGER.info("Zone '{}' -> channel '{}' registered from command block at {}",
                            zone.name(), zone.channel(), pos.toShortString());
                } else {
                    LOGGER.debug("Zone '{}' -> channel '{}' re-registered from command block at {}",
                            zone.name(), zone.channel(), pos.toShortString());
                }
            } catch (RuntimeException e) {
                // Never let a bad block entity escape into the chunk-load future chain.
                LOGGER.warn("Ignoring malformed rtx zone command block at {}: {}", pos.toShortString(), e.getMessage());
            }
        }
    }
}

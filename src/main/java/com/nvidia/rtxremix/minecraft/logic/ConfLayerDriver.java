/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.logic;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.phys.Vec3;

/**
 * Drives the minecraft-logic conf option-layers directly: one runtime layer
 * per conf, acquired and fed its blend strength and authored threshold when
 * gameplay state changes.
 *
 * Profiles come from two roots. The MOD root,
 * {@code <gameDir>/rtx-remix/mods/minecraft-logic/confs/}, ships with the
 * installation and holds generic archetypes; it is acquired at init. The WORLD
 * root, {@code <world>/rtx-remix/confs/}, lives inside a save directory and is
 * acquired on world join and released on leave, so a published map carries its
 * own looks with no separate download. World profiles sit just above their mod
 * counterparts in priority, so a map overrides the options it names and
 * inherits the rest from the archetype. Single-player only: a remote server
 * gives this client no save directory.
 *
 * Water, automatic view distance, volumetric settling, weather, and four fixed
 * time-of-day layers come from a static table and are mod-owned. Optional
 * biome and zone layers are discovered from confs/biomes/*.conf and
 * confs/zones/*.conf file names in either root, at acquire, join, and reload
 * time. The runtime resolves the layer stack per frame; temporal easing
 * lives here.
 *
 * Smoothed layers converge on their target with an exponential decay whose
 * gap to the target halves every 0.5 s, so a transition looks settled after a
 * few seconds and snaps to the target (gap under 0.001) by roughly 5 s from
 * a full swing. mc.underwater is deliberately unsmoothed so entering or
 * leaving water switches instantly. Time-of-day strengths are also
 * unsmoothed: the Minecraft clock and hardcoded smoothstep windows are the
 * curve, so commands and benchmark time jumps must take effect immediately.
 * Automatic view distance is unsmoothed as well, so each discrete Minecraft
 * render-distance change takes effect immediately. Weather follows vanilla's
 * rain and thunder gradients directly: they already ramp over about five
 * seconds, and a second smoothing pass would only lengthen the transition
 * the settling layer exists to cover. The volumetric settling strength is
 * unsmoothed because it is already an envelope: full while a weather, biome,
 * or zone transition is in progress and for a short hold afterwards, zero
 * once the scene is still.
 * Strengths persist runtime-side, so a layer costs a JNI call only when the
 * value to send actually changes.
 *
 * Acquire failures are normally tolerated per layer: a layer that fails to
 * acquire is skipped (visible in snapshot(), retried by reload()) while the
 * rest keep driving. The four time profiles fail together as one interpolation
 * family. Only water.conf gates the whole driver: it is the one profile every
 * install must have, so its failure deactivates the driver.
 *
 * Render thread only (per-frame update from RemixRenderer's camera update;
 * client commands run on the same thread).
 */
public final class ConfLayerDriver {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfLayerDriver.class);

    /** Halvings per second of the remaining gap in {@link #smoothStep}. */
    static final float SMOOTHING_FACTOR = 2.0f;
    /** Snap to the target once the remaining gap is imperceptible. */
    static final float SNAP_EPSILON = 0.001f;
    /** Skip the JNI call for sub-epsilon strength deltas mid-transition. */
    static final float SEND_EPSILON = 1.0e-4f;
    /** Clamp per-frame dt so hitches don't teleport smoothed values. */
    private static final float MAX_FRAME_DT_SECONDS = 0.25f;

    // Fixed Minecraft day schedule. These are intentionally code constants,
    // not an authoring schema: artists tune four predictable conf files while
    // the mod owns when each look blends.
    static final long DAY_LENGTH_TICKS = 24000L;
    static final long DAWN_TO_DAY_END_TICK = 1000L;
    static final long DAY_TO_DUSK_START_TICK = 11000L;
    static final long DUSK_TICK = 12000L;
    static final long DUSK_TO_NIGHT_END_TICK = 13000L;
    static final long NIGHT_TO_DAWN_START_TICK = 23000L;

    /**
     * Which root a layer's conf came from. The mod root ships generic
     * archetypes with the installation; the world root travels inside a map's
     * save directory, so a published map carries its own looks.
     */
    enum LayerSource {
        MOD,
        WORLD
    }

    /**
     * One row of the layer catalog.
     *
     * <p>{@code name} is the logical channel (for example {@code mc.zone.ruins}).
     * Two roots may drive the same channel, so the map of live layers is keyed
     * by {@link #id()} instead, and per-frame targets are looked up by channel.</p>
     */
    record LayerSpec(String name, String confPath, float priority,
                     float blendThreshold, boolean smoothed, LayerSource source) {

        /** Unique key for this layer; distinguishes the two roots for one channel. */
        String id() {
            return source == LayerSource.WORLD ? WORLD_ID_PREFIX + name : name;
        }
    }

    /** Marks a world-sourced layer in {@code layersById} and in status output. */
    static final String WORLD_ID_PREFIX = "world/";

    // Discovered biome and zone layers: each valid *.conf becomes a layer.
    private static final float BIOME_PRIORITY = 6000f;
    private static final float BIOME_BLEND_THRESHOLD = 0.01f;
    private static final float ZONE_PRIORITY = 11000f;
    private static final float ZONE_BLEND_THRESHOLD = 0.01f;
    // World profiles sit just above their mod counterparts, so a map's file
    // wins per option while the mod archetype still supplies keys the map
    // leaves out. Sparse override rather than wholesale replacement, which is
    // how every other layer in this system already behaves.
    private static final float WORLD_BIOME_PRIORITY = 6500f;
    private static final float WORLD_ZONE_PRIORITY = 11500f;

    /**
     * The fixed layer catalog: water, automatic view distance, volumetric
     * settling, weather, and four fixed time-of-day profiles (9 rows, one per
     * shipped conf; ConfLayerDriverTest cross-checks them against the shipped
     * confs). Biome and zone layers are NOT listed here: they are discovered
     * from their conf file names at acquire and reload time, so a new authored
     * look needs no code.
     * Priorities: water dominates everything, zones beat biomes, thunder
     * beats rain, time supplies the environmental foundation, and the two
     * integration-owned controllers (automatic view distance, volumetric
     * settling) sit below every situational profile so authors can
     * intentionally override their keys. The four time priorities must stay
     * ordered dawn/day/dusk/night so the hardcoded strengths compensate for the
     * runtime's nested LERP.
     * Avoid 10000 (the runtime's default dynamic-layer priority).
     */
    static final List<LayerSpec> STATIC_LAYER_TABLE = buildStaticLayerTable();

    /**
     * A new gameplay effect that is neither a biome nor a zone is six things:
     * a row here, a conf file shipped under packaging/, a target computed in
     * {@link #update}, a smoothing decision on the row, a priority chosen
     * against the rows already present, and the row count ConfLayerDriverTest
     * asserts. The author guide beside the shipped confs states the resulting
     * catalog, so it changes with this table. A new zone or biome look needs
     * none of this: it is discovered from its file name.
     */
    private static List<LayerSpec> buildStaticLayerTable() {
        List<LayerSpec> table = new ArrayList<>();
        table.add(new LayerSpec(LogicChannels.UNDERWATER, "confs/water.conf", 20000f, 0.5f, false,
                LayerSource.MOD));
        table.add(new LayerSpec(LogicChannels.VIEW_DISTANCE,
                "confs/view_distance.conf", 3000f, 0.01f, false, LayerSource.MOD));
        table.add(new LayerSpec(LogicChannels.VOLUMETRICS_SETTLING,
                "confs/volumetrics/settling.conf", 3100f, 0.01f, false, LayerSource.MOD));
        table.add(new LayerSpec(LogicChannels.WEATHER_THUNDER, "confs/weather/thunder.conf", 5500f, 0.01f, false,
                LayerSource.MOD));
        table.add(new LayerSpec(LogicChannels.WEATHER_RAIN, "confs/weather/rain.conf", 5000f, 0.01f, false,
                LayerSource.MOD));
        table.add(new LayerSpec(LogicChannels.TIME_DAWN, "confs/time/dawn.conf", 4000f, 0.5f, false,
                LayerSource.MOD));
        table.add(new LayerSpec(LogicChannels.TIME_DAY, "confs/time/day.conf", 4001f, 0.5f, false,
                LayerSource.MOD));
        table.add(new LayerSpec(LogicChannels.TIME_DUSK, "confs/time/dusk.conf", 4002f, 0.5f, false,
                LayerSource.MOD));
        table.add(new LayerSpec(LogicChannels.TIME_NIGHT, "confs/time/night.conf", 4003f, 0.5f, false,
                LayerSource.MOD));
        return List.copyOf(table);
    }

    /** Four runtime strengths for the fixed time-of-day profile stack. */
    record TimeLayerStrengths(float dawn, float day, float dusk, float night) {
        private static final TimeLayerStrengths ZERO = new TimeLayerStrengths(0.0f, 0.0f, 0.0f, 0.0f);
    }

    /**
     * Maps the cyclic Minecraft clock onto the four fixed profile layers.
     * Holds use one full-strength layer; transitions use the two neighboring
     * layers and a smoothstep curve. The seemingly asymmetric midnight case
     * is deliberate: night has the highest time priority, so fading its
     * strength down over a full-strength dawn layer produces an exact
     * night-to-dawn crossfade under the runtime's nested-LERP resolution.
     */
    static TimeLayerStrengths timeLayerStrengths(long timeOfDay) {
        long tick = Math.floorMod(timeOfDay, DAY_LENGTH_TICKS);
        if (tick < DAWN_TO_DAY_END_TICK) {
            float day = transitionStrength(tick, 0L, DAWN_TO_DAY_END_TICK);
            return new TimeLayerStrengths(1.0f, day, 0.0f, 0.0f);
        }
        if (tick < DAY_TO_DUSK_START_TICK) {
            return new TimeLayerStrengths(0.0f, 1.0f, 0.0f, 0.0f);
        }
        if (tick < DUSK_TICK) {
            float dusk = transitionStrength(tick, DAY_TO_DUSK_START_TICK, DUSK_TICK);
            return new TimeLayerStrengths(0.0f, 1.0f, dusk, 0.0f);
        }
        if (tick < DUSK_TO_NIGHT_END_TICK) {
            float night = transitionStrength(tick, DUSK_TICK, DUSK_TO_NIGHT_END_TICK);
            return new TimeLayerStrengths(0.0f, 0.0f, 1.0f, night);
        }
        if (tick < NIGHT_TO_DAWN_START_TICK) {
            return new TimeLayerStrengths(0.0f, 0.0f, 0.0f, 1.0f);
        }
        float transition = transitionStrength(tick, NIGHT_TO_DAWN_START_TICK, DAY_LENGTH_TICKS);
        return new TimeLayerStrengths(1.0f, 0.0f, 0.0f, 1.0f - transition);
    }

    private static float transitionStrength(long tick, long start, long end) {
        float linear = (float) (tick - start) / (float) (end - start);
        return linear * linear * (3.0f - 2.0f * linear);
    }

    /**
     * Maps authored biome conf file stems to layer rows. Only exact vanilla
     * 1.21.1 biome paths are accepted: the file-name contract carries no
     * namespace, so modded biomes are deliberately not driven. Sorted for a
     * deterministic layer order.
     */
    static List<LayerSpec> biomeRowsFromStems(List<String> stems, LayerSource source) {
        Set<String> vanillaBiomes = Set.copyOf(vanillaBiomeNames());
        List<String> sorted = new ArrayList<>(stems);
        sorted.sort(String::compareTo);
        List<LayerSpec> rows = new ArrayList<>();
        float priority = source == LayerSource.WORLD ? WORLD_BIOME_PRIORITY : BIOME_PRIORITY;
        for (String stem : sorted) {
            if (!vanillaBiomes.contains(stem)) {
                LOGGER.warn("Ignoring biome conf with unknown filename stem '{}' in confs/biomes/ "
                        + "(use an exact vanilla 1.21.1 biome path)", stem);
                continue;
            }
            rows.add(new LayerSpec(LogicChannels.BIOME_PREFIX + stem,
                    "confs/biomes/" + stem + ".conf",
                    priority, BIOME_BLEND_THRESHOLD, true, source));
        }
        return rows;
    }

    /**
     * Maps zone conf file stems to zone layer rows: each valid stem becomes
     * mc.zone.&lt;stem&gt; at the shared zone priority, so the conf FILE NAME
     * is the zone channel contract. Stems that would not be legal zone names
     * under ZoneDefinition's grammar (empty, whitespace, or flag-like) are
     * skipped with one WARN. Sorted for a deterministic layer order.
     */
    static List<LayerSpec> zoneRowsFromStems(List<String> stems, LayerSource source) {
        List<String> sorted = new ArrayList<>(stems);
        sorted.sort(String::compareTo);
        List<LayerSpec> rows = new ArrayList<>();
        float priority = source == LayerSource.WORLD ? WORLD_ZONE_PRIORITY : ZONE_PRIORITY;
        for (String stem : sorted) {
            if (!isValidZoneStem(stem)) {
                LOGGER.warn("Ignoring zone conf with invalid zone name '{}' in confs/zones/ "
                        + "(zone names are single non-flag tokens)", stem);
                continue;
            }
            rows.add(new LayerSpec(LogicChannels.ZONE_PREFIX + stem, "confs/zones/" + stem + ".conf",
                    priority, ZONE_BLEND_THRESHOLD, true, source));
        }
        return rows;
    }

    /**
     * Mirrors the constraint ZoneDefinition's parser puts on zone names: a
     * single non-empty, non-flag token (names are whitespace-split there, so
     * whitespace can never appear).
     */
    static boolean isValidZoneStem(String stem) {
        return !stem.isEmpty() && !stem.startsWith("--")
                && stem.chars().noneMatch(Character::isWhitespace);
    }

    /**
     * Every vanilla biome name, reflected from BiomeKeys' public static
     * RegistryKey constants and sorted for deterministic filename validation.
     */
    static List<String> vanillaBiomeNames() {
        List<String> names = new ArrayList<>();
        for (Field field : Biomes.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != ResourceKey.class) {
                continue;
            }
            try {
                names.add(((ResourceKey<?>) field.get(null)).location().getPath());
            } catch (IllegalAccessException e) {
                throw new AssertionError("BiomeKeys public field not readable: " + field.getName(), e);
            }
        }
        names.sort(String::compareTo);
        return names;
    }

    private static final class LayerState {
        final LayerSpec spec;
        long handle;
        float target;
        float current;
        float lastSent;
        boolean sendFailureLogged;
        /** The conf sets no options, so nothing on screen follows this layer's easing. */
        boolean inert;

        LayerState(LayerSpec spec) {
            this.spec = spec;
        }
    }

    /**
     * Strength envelope for the volumetric settling layer. The froxel
     * radiance cache accumulates over many frames, so when a profile moves
     * the medium the fog and light shafts would otherwise show the change
     * seconds after the sky does. The envelope is full while any input is in
     * motion, which switches the cache to a short history, and stays full for
     * HOLD_SECONDS after the last movement so the cache converges on the final
     * medium before its long history is trusted again. Package-private so
     * ConfLayerDriverTest can exercise it.
     */
    static final class SettlingEnvelope {
        static final float HOLD_SECONDS = 0.75f;
        private float holdSeconds;

        /** Advances one frame; returns the layer strength, 1 while held and 0 once released. */
        float advance(boolean transitioning, float dtSeconds) {
            holdSeconds = transitioning ? HOLD_SECONDS : Math.max(0.0f, holdSeconds - dtSeconds);
            return holdSeconds > 0.0f ? 1.0f : 0.0f;
        }
    }

    /** Narrow config-layer seam so lifecycle behavior can be tested without JNI. */
    interface ConfigLayerApi {
        long acquireConfigLayer(String path, float priority, float blendThreshold);

        void setConfigLayerBlending(long handle, float strength, float blendThreshold);

        void releaseConfigLayer(long handle);
    }

    private record RemixConfigLayerApi(RemixAPI delegate) implements ConfigLayerApi {
        @Override
        public long acquireConfigLayer(String path, float priority, float blendThreshold) {
            return delegate.acquireConfigLayer(path, priority, blendThreshold);
        }

        @Override
        public void setConfigLayerBlending(long handle, float strength, float blendThreshold) {
            delegate.setConfigLayerBlending(handle, strength, blendThreshold);
        }

        @Override
        public void releaseConfigLayer(long handle) {
            delegate.releaseConfigLayer(handle);
        }
    }

    // Keyed by LayerSpec.id(), so one channel can hold a mod layer and a world
    // layer at once. Per-frame targets go through channelIndex instead.
    private final Map<String, LayerState> layersById = new LinkedHashMap<>();
    private final Map<String, List<LayerState>> channelIndex = new HashMap<>();
    // Prefix scans are hoisted out of update() into these, rebuilt whenever the
    // catalog changes.
    private List<LayerState> zoneLayers = List.of();
    private List<LayerState> biomeLayers = List.of();
    private ConfigLayerApi configLayerApi;
    private Path gameDir;
    /** {@code <world>/rtx-remix} while a single-player world is joined, else null. */
    private Path worldConfRoot;
    private boolean active = false;
    private long lastFrameNanos = 0;
    private final SettlingEnvelope settling = new SettlingEnvelope();
    private float lastRainGradient;
    private float lastThunderGradient;

    public ConfLayerDriver() {
        for (LayerSpec spec : STATIC_LAYER_TABLE) {
            layersById.put(spec.id(), new LayerState(spec));
        }
        reindex();
    }

    /** Rebuilds the channel and family lookups from the current layer map. */
    private void reindex() {
        channelIndex.clear();
        List<LayerState> zones = new ArrayList<>();
        List<LayerState> biomes = new ArrayList<>();
        for (LayerState layer : layersById.values()) {
            channelIndex.computeIfAbsent(layer.spec.name(), key -> new ArrayList<>()).add(layer);
            if (layer.spec.name().startsWith(LogicChannels.ZONE_PREFIX)) {
                zones.add(layer);
            } else if (layer.spec.name().startsWith(LogicChannels.BIOME_PREFIX)) {
                biomes.add(layer);
            }
        }
        zoneLayers = List.copyOf(zones);
        biomeLayers = List.copyOf(biomes);
    }

    /**
     * Acquires the catalog's layers (the runtime parses each conf
     * synchronously): the static table plus one layer per valid biome or zone
     * conf discovered on disk (a missing or empty optional directory simply
     * means no layers of that kind). Acquire failures are generally
     * per-layer tolerant: a non-gate layer that fails logs one WARN and stays
     * unacquired (update() skips it; reload() retries it), while the rest keep
     * driving. The four time-of-day profiles are the one additive exception:
     * they form a single interpolation family, so a partial family is released
     * and disabled together to avoid discontinuities or lower-layer leakage.
     * Only a water.conf failure disables the whole driver - it is the one
     * profile every install must have - releasing whatever was acquired. Call
     * with no live handles (initial init, or after releaseAll()).
     *
     * @return true when the driver is active (the water layer acquired)
     */
    public boolean acquireAll(RemixAPI remixApi, Path gameDir) {
        return acquireAll(new RemixConfigLayerApi(remixApi), gameDir);
    }

    /** Package-private test seam; production callers use the RemixAPI overload. */
    boolean acquireAll(ConfigLayerApi configLayerApi, Path gameDir) {
        this.configLayerApi = configLayerApi;
        this.gameDir = gameDir;
        rebuildLayerStates();
        if (!acquireMissing(true)) {
            return false;
        }
        disableIncompleteTimeFamily();
        lastFrameNanos = 0;
        active = true;
        return true;
    }

    /** {@code <gameDir>/rtx-remix/mods/minecraft-logic}, the mod-owned root. */
    private Path modRoot() {
        return gameDir.resolve("rtx-remix").resolve("mods").resolve("minecraft-logic");
    }

    /**
     * The root a layer's conf is read from. rebuildLayerStates() only adds
     * WORLD rows while a world root is joined, so a WORLD layer always has one.
     */
    private Path rootFor(LayerSource source) {
        if (source == LayerSource.WORLD) {
            return Objects.requireNonNull(worldConfRoot, "world layer outside a joined world");
        }
        return modRoot();
    }

    /**
     * Acquires every layer that does not already hold a handle, so this is
     * safe to call again after the catalog grows (a world join) without
     * disturbing layers that are already driving.
     *
     * @param waterGates when true, a water.conf failure disables the whole
     *                   driver; world-root acquires pass false because they are
     *                   purely additive and must never take the mod down
     * @return false only when the gating layer failed
     */
    private boolean acquireMissing(boolean waterGates) {
        for (LayerState layer : layersById.values()) {
            if (layer.handle != 0) {
                continue;
            }
            Path confPath = rootFor(layer.spec.source())
                    .resolve(layer.spec.confPath()).toAbsolutePath();
            try {
                layer.handle = configLayerApi.acquireConfigLayer(
                        confPath.toString(), layer.spec.priority(), layer.spec.blendThreshold());
            } catch (RemixException e) {
                if (waterGates && acquireFailureDisablesDriver(layer.spec.name())) {
                    LOGGER.error("Failed to acquire conf layer {} from {}: {} (code: {}) - conf-layer driving disabled",
                            layer.spec.name(), confPath, e.getMessage(), e.getErrorCode());
                    releaseAll();
                    return false;
                }
                LOGGER.warn("Failed to acquire conf layer {} from {}: {} (code: {}) - layer skipped "
                        + "(retry with /rtx conf reload)",
                        layer.spec.name(), confPath, e.getMessage(), e.getErrorCode());
                layer.handle = 0;
                continue;
            }
            layer.lastSent = 0.0f; // every acquire starts the layer at strength 0
            layer.inert = !confFileSetsOptions(confPath);
            LOGGER.info("Acquired {} conf layer {} (priority {}, threshold {}): {}",
                    layer.spec.source() == LayerSource.WORLD ? "world" : "mod",
                    layer.spec.name(), layer.spec.priority(), layer.spec.blendThreshold(), confPath);
        }
        return true;
    }

    /**
     * Adds the joined single-player world's own profiles on top of the mod
     * catalog. {@code worldDir} is the save directory; profiles are read from
     * {@code <worldDir>/rtx-remix/confs/}, which means they travel inside the
     * save and a published map carries its looks with no separate download.
     *
     * <p>Purely additive: a world profile resolves above the same-named mod
     * archetype, so a map overrides the options it names and inherits the rest.
     * Failures here never disable the mod catalog. No-op when the driver is
     * inactive or the world has no profiles.</p>
     */
    public void onWorldJoin(Path worldDir) {
        if (!active || worldDir == null) {
            return;
        }
        Path root = worldDir.resolve("rtx-remix");
        if (!Files.isDirectory(root.resolve("confs"))) {
            releaseWorldLayers(); // previous world's profiles must not linger
            worldConfRoot = null;
            rebuildLayerStates();
            return;
        }
        releaseWorldLayers();
        worldConfRoot = root;
        rebuildLayerStates();
        acquireMissing(false);
    }

    /** Drops the joined world's profiles; the mod catalog keeps driving. */
    public void onWorldLeave() {
        if (worldConfRoot == null) {
            return;
        }
        releaseWorldLayers();
        worldConfRoot = null;
        rebuildLayerStates();
    }

    private void releaseWorldLayers() {
        for (LayerState layer : layersById.values()) {
            if (layer.spec.source() == LayerSource.WORLD) {
                releaseLayer(layer);
            }
        }
    }

    /**
     * The fixed schedule assumes all four authored endpoints exist. Running a
     * partial family would make omitted endpoints fall through to lower layers,
     * so disable just this feature until a reload can acquire the whole set.
     */
    private void disableIncompleteTimeFamily() {
        boolean complete = true;
        for (LayerState layer : layersById.values()) {
            if (isTimeLayer(layer) && layer.handle == 0) {
                complete = false;
                break;
            }
        }
        if (complete) {
            return;
        }

        LOGGER.warn("Time-of-day conf family is incomplete; disabling dawn, day, dusk, and night "
                + "together (retry with /rtx conf reload)");
        for (LayerState layer : layersById.values()) {
            if (!isTimeLayer(layer)) {
                continue;
            }
            releaseLayer(layer);
            layer.target = 0.0f;
            layer.current = 0.0f;
            layer.lastSent = 0.0f;
            layer.sendFailureLogged = false;
        }
    }

    private static boolean isTimeLayer(LayerState layer) {
        return layer.spec.name().startsWith(LogicChannels.TIME_PREFIX);
    }

    /**
     * Whether an acquire failure for the given layer disables the whole
     * driver. Only mc.underwater gates: the water layer is the one profile
     * every install must have, so its failure deactivates the driver, while
     * every other layer is an additive extra that can be skipped.
     */
    static boolean acquireFailureDisablesDriver(String layerName) {
        return LogicChannels.UNDERWATER.equals(layerName);
    }

    /**
     * The runtime's assignment grammar: optional whitespace, a key of
     * {@code [A-Za-z0-9._]}, optional whitespace, then {@code =}. Any other
     * line ({@code #} comments, {@code [section]} headers, blank lines) sets
     * nothing.
     */
    private static final Pattern OPTION_LINE = Pattern.compile("\\s*[A-Za-z0-9._]*\\s*=");

    /**
     * Whether a conf's contents set at least one option. A profile that sets
     * none is inert: acquiring it succeeds and its strength still eases, but
     * nothing on screen follows, so it must not open the settling window.
     * Pure and static (package-private) so ConfLayerDriverTest can exercise
     * it.
     */
    static boolean confSetsOptions(String contents) {
        return contents.lines().anyMatch(line -> OPTION_LINE.matcher(line).lookingAt());
    }

    /**
     * {@link #confSetsOptions(String)} for the file the runtime has just
     * parsed. Decoded as Latin-1 because the grammar is ASCII and the check
     * must not fail on whatever encoding an author saved comments in. A read
     * failure is unexpected here; it is logged and the layer treated as live,
     * which at worst opens an unnecessary settling window.
     */
    private static boolean confFileSetsOptions(Path confPath) {
        try {
            return confSetsOptions(Files.readString(confPath, StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            LOGGER.warn("Could not read {} to check whether it sets options: {}", confPath, e.getMessage());
            return true;
        }
    }

    /**
     * Rebuilds the layer-state map as fixed rows plus the biome and zone rows
     * discovered on disk. States persisting across a reload keep their eased
     * strength; newly discovered layers start at 0 like any fresh acquire,
     * and states whose conf disappeared are dropped.
     */
    private void rebuildLayerStates() {
        Path modDir = modRoot();
        List<LayerSpec> specs = new ArrayList<>(STATIC_LAYER_TABLE);
        specs.addAll(biomeRowsFromStems(scanConfStems(modDir, "biomes"), LayerSource.MOD));
        specs.addAll(zoneRowsFromStems(scanConfStems(modDir, "zones"), LayerSource.MOD));
        if (worldConfRoot != null) {
            specs.addAll(biomeRowsFromStems(scanConfStems(worldConfRoot, "biomes"), LayerSource.WORLD));
            specs.addAll(zoneRowsFromStems(scanConfStems(worldConfRoot, "zones"), LayerSource.WORLD));
        }
        Map<String, LayerState> rebuilt = new LinkedHashMap<>();
        for (LayerSpec spec : specs) {
            LayerState existing = layersById.get(spec.id());
            rebuilt.put(spec.id(),
                    existing != null && existing.spec.equals(spec) ? existing : new LayerState(spec));
        }
        // Anything dropped from the catalog must give its handle back first.
        for (Map.Entry<String, LayerState> entry : layersById.entrySet()) {
            if (!rebuilt.containsKey(entry.getKey())) {
                releaseLayer(entry.getValue());
            }
        }
        layersById.clear();
        layersById.putAll(rebuilt);
        reindex();
    }

    /**
     * File-name stems of direct *.conf children under the named conf
     * directory; empty when the directory is missing (not an error because
     * discovered layer families are optional).
     */
    private static List<String> scanConfStems(Path modDir, String directoryName) {
        Path confDir = modDir.resolve("confs").resolve(directoryName);
        if (!Files.isDirectory(confDir)) {
            return List.of();
        }
        List<String> stems = new ArrayList<>();
        try (var files = Files.list(confDir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String fileName = file.getFileName().toString();
                if (fileName.endsWith(".conf") && Files.isRegularFile(file)) {
                    stems.add(fileName.substring(0, fileName.length() - ".conf".length()));
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to scan {} for confs: {}", confDir, e.getMessage());
        }
        return stems;
    }

    /**
     * True when any acquired layer drives this channel, from either root.
     * Zone layers are discovered from conf file names, so this tells commands
     * whether a zone's channel actually drives anything.
     */
    public boolean hasAcquiredLayer(String channel) {
        for (LayerState layer : channelIndex.getOrDefault(channel, List.of())) {
            if (layer.handle != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Render thread, once per frame (from RemixRenderer's camera update).
     * Computes per-layer targets, eases smoothed layers toward them, and
     * sends changed strengths to the runtime.
     */
    public void update(ClientLevel world, Vec3 cameraPos, boolean underwater,
                       float viewDistanceStrength, float tickDelta, ZoneRegistry zones) {
        if (!active) {
            return;
        }
        float dtSeconds = frameDtSeconds();

        setTarget(LogicChannels.UNDERWATER, underwater ? 1.0f : 0.0f);
        setTarget(LogicChannels.VIEW_DISTANCE, Math.clamp(viewDistanceStrength, 0.0f, 1.0f));
        // Time layers follow the shared Overworld clock only; other dimensions
        // contribute no time-of-day strength.
        TimeLayerStrengths time = Level.OVERWORLD.equals(world.dimension())
                ? timeLayerStrengths(world.getDayTime())
                : TimeLayerStrengths.ZERO;
        setTarget(LogicChannels.TIME_DAWN, time.dawn());
        setTarget(LogicChannels.TIME_DAY, time.day());
        setTarget(LogicChannels.TIME_DUSK, time.dusk());
        setTarget(LogicChannels.TIME_NIGHT, time.night());
        boolean weatherMoving = targetWeather(world, tickDelta);

        // Resolve the camera-position biome once (vanilla namespace only) and
        // target its authored layer when one was discovered. An unconfigured
        // or modded biome targets every discovered biome layer back to 0.
        String biomeLayerName = world.getBiome(BlockPos.containing(cameraPos)).unwrapKey()
                .filter(key -> "minecraft".equals(key.location().getNamespace()))
                .map(key -> LogicChannels.BIOME_PREFIX + key.location().getPath())
                .orElse(null);
        for (LayerState layer : biomeLayers) {
            layer.target = layer.spec.name().equals(biomeLayerName) ? 1.0f : 0.0f;
        }

        // Zones sharing a layer's channel are already max-combined by the
        // registry; a channel with no zones left (removed / world change) is
        // absent from the map and eases back to 0. Channels that don't name a
        // shipped conf have no layer to drive and are ignored. A mod archetype
        // and a world profile for one channel both read the same weight; the
        // runtime resolves them by priority.
        Map<String, Float> weights = zones.channelWeights(cameraPos.x, cameraPos.y, cameraPos.z);
        for (LayerState layer : zoneLayers) {
            layer.target = weights.getOrDefault(layer.spec.name(), 0.0f);
        }

        // Every other target is a medium the froxel cache integrates; this one
        // tells the cache how much history to trust, so it follows them.
        setTarget(LogicChannels.VOLUMETRICS_SETTLING,
                settling.advance(weatherMoving || anySmoothedLayerEasing(), dtSeconds));

        for (LayerState layer : layersById.values()) {
            if (layer.handle == 0) {
                continue; // unacquired (acquire failed); retried by reload()
            }
            advance(layer, dtSeconds);
            sendIfChanged(layer);
        }
    }

    /**
     * Targets the weather layers from vanilla's gradients and reports whether
     * either gradient moved since the previous frame, the leading edge of a
     * weather transition.
     */
    private boolean targetWeather(ClientLevel world, float tickDelta) {
        float rain = world.getRainLevel(tickDelta);
        float thunder = world.getThunderLevel(tickDelta);
        boolean moving = moved(lastRainGradient, rain) || moved(lastThunderGradient, thunder);
        lastRainGradient = rain;
        lastThunderGradient = thunder;
        setTarget(LogicChannels.WEATHER_RAIN, rain);
        setTarget(LogicChannels.WEATHER_THUNDER, thunder);
        return moving;
    }

    /**
     * True while any acquired smoothed layer that sets options is still
     * easing toward its target. Inert layers ease too, but nothing on screen
     * follows them, so they never open the settling window.
     */
    private boolean anySmoothedLayerEasing() {
        for (LayerState layer : layersById.values()) {
            if (layer.handle != 0 && layer.spec.smoothed() && !layer.inert
                    && moved(layer.current, layer.target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Releases and re-acquires every layer. The release is what makes the
     * re-parse happen: the runtime identifies a layer by its path and
     * priority and hands back its cached parse for a still-held identity, so
     * acquiring over a live handle would return the values already in memory.
     * With the handle released, the runtime re-parses the conf
     * files from disk, so edited option values go live without a restart,
     * and the biome and zone directories under both the mod root and the
     * joined world's root are re-scanned, so newly dropped conf files become
     * live layers (and deleted ones disappear). A layer whose acquire failed is
     * retried.
     * Blend state carries over for persisting layers - the next update()
     * re-sends current strengths - while new discovered layers start at 0.
     *
     * @return true when the whole reload succeeded and the driver is active again
     */
    public boolean reload() {
        if (configLayerApi == null || gameDir == null) {
            return false;
        }
        releaseAll();
        return acquireAll(configLayerApi, gameDir);
    }

    /** Releases every acquired layer; affected options revert in the runtime. */
    public void releaseAll() {
        active = false;
        for (LayerState layer : layersById.values()) {
            releaseLayer(layer);
        }
    }

    private void releaseLayer(LayerState layer) {
        if (layer.handle == 0) {
            return;
        }
        releaseHandle(layer.handle, layer.spec.name());
        layer.handle = 0;
    }

    /**
     * Hands one handle back. A failed release is logged and dropped: the
     * caller is already giving the layer up, so there is nothing to retry.
     */
    private void releaseHandle(long handle, String layerName) {
        try {
            configLayerApi.releaseConfigLayer(handle);
        } catch (RemixException e) {
            LOGGER.error("Failed to release conf layer {}: {} (code: {})",
                    layerName, e.getMessage(), e.getErrorCode());
        }
    }

    /**
     * Per-layer view for /rtx conf status.
     *
     * <p>{@code name} is the layer id, so a world-sourced profile shows as
     * {@code world/mc.zone.ruins} beside the mod's {@code mc.zone.ruins} and an
     * author can see at a glance which root a value came from.</p>
     */
    public record LayerStatus(String name, float strength, boolean acquired) {
    }

    /** Weather strengths as driven into the conf layers, shared with render systems. */
    public record WeatherStrengths(float rain, float thunder) {
    }

    public WeatherStrengths weatherStrengths() {
        return new WeatherStrengths(
                currentStrength(LogicChannels.WEATHER_RAIN),
                currentStrength(LogicChannels.WEATHER_THUNDER));
    }

    private float currentStrength(String channel) {
        float strength = 0.0f;
        for (LayerState layer : channelIndex.getOrDefault(channel, List.of())) {
            strength = Math.max(strength, layer.current);
        }
        return strength;
    }

    /**
     * Per-layer status sorted by name, for /rtx conf status. Layers that
     * failed to acquire report acquired() == false rather than a misleading
     * strength.
     */
    public List<LayerStatus> snapshot() {
        List<LayerStatus> snapshot = new ArrayList<>(layersById.size());
        for (LayerState layer : layersById.values()) {
            snapshot.add(new LayerStatus(layer.spec.id(), layer.current, layer.handle != 0));
        }
        snapshot.sort(Comparator.comparing(LayerStatus::name));
        return snapshot;
    }

    /** Strips the world-root marker so category checks see the channel. */
    private static String channelOf(String layerId) {
        return layerId.startsWith(WORLD_ID_PREFIX) ? layerId.substring(WORLD_ID_PREFIX.length()) : layerId;
    }

    // Default /rtx conf status view limits.
    /** The active block never exceeds this many lines (rows plus overflow line). */
    static final int STATUS_ACTIVE_LINE_CAP = 10;
    /** Above this many unacquired layers, collapse them to per-category counts. */
    static final int STATUS_UNACQUIRED_LIST_CAP = 5;

    /**
     * Formats the default /rtx conf status view from a status snapshot:
     * always a one-line summary header; active layers (strength > 0,
     * including ones easing out) individually with the block capped at
     * STATUS_ACTIVE_LINE_CAP lines (an overflow line replaces the last row
     * when over); unacquired layers as one line, named when at most
     * STATUS_UNACQUIRED_LIST_CAP or collapsed to per-category counts when
     * more (a large authored catalog still must not flood chat). Idle layers
     * appear only in the header, so the worst case is 12 lines and the healthy
     * quiet state is 1. The full dump ("status all") is formatted by the
     * command directly.
     */
    public static List<String> formatStatusSummary(List<LayerStatus> layers) {
        List<LayerStatus> activeLayers = new ArrayList<>();
        List<LayerStatus> unacquiredLayers = new ArrayList<>();
        int idle = 0;
        for (LayerStatus layer : layers) {
            if (!layer.acquired()) {
                unacquiredLayers.add(layer);
            } else if (layer.strength() > 0.0f) {
                activeLayers.add(layer);
            } else {
                idle++;
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add(String.format("Conf layers: %d active, %d idle, %d unacquired",
                activeLayers.size(), idle, unacquiredLayers.size()));
        int shownActive = activeLayers.size() <= STATUS_ACTIVE_LINE_CAP
                ? activeLayers.size() : STATUS_ACTIVE_LINE_CAP - 1;
        for (int i = 0; i < shownActive; i++) {
            lines.add("  " + activeLayers.get(i).name() + " = " + activeLayers.get(i).strength());
        }
        if (shownActive < activeLayers.size()) {
            lines.add("  +" + (activeLayers.size() - shownActive)
                    + " more active (see /rtx conf status all)");
        }
        if (!unacquiredLayers.isEmpty()) {
            if (unacquiredLayers.size() <= STATUS_UNACQUIRED_LIST_CAP) {
                StringBuilder names = new StringBuilder();
                for (LayerStatus layer : unacquiredLayers) {
                    if (names.length() > 0) {
                        names.append(", ");
                    }
                    names.append(layer.name());
                }
                lines.add("  unacquired: " + names + " (see log)");
            } else {
                int biomes = 0;
                int zones = 0;
                int weather = 0;
                int time = 0;
                int viewDistance = 0;
                int volumetricSettling = 0;
                int other = 0;
                boolean water = false;
                for (LayerStatus layer : unacquiredLayers) {
                    // Categorize by channel; a world profile counts as the same
                    // kind as the mod archetype it sits above.
                    String channel = channelOf(layer.name());
                    if (channel.startsWith(LogicChannels.BIOME_PREFIX)) {
                        biomes++;
                    } else if (channel.startsWith(LogicChannels.ZONE_PREFIX)) {
                        zones++;
                    } else if (channel.startsWith(LogicChannels.TIME_PREFIX)) {
                        time++;
                    } else if (channel.equals(LogicChannels.UNDERWATER)) {
                        water = true;
                    } else if (channel.equals(LogicChannels.VIEW_DISTANCE)) {
                        viewDistance++;
                    } else if (channel.equals(LogicChannels.VOLUMETRICS_SETTLING)) {
                        volumetricSettling++;
                    } else if (channel.equals(LogicChannels.WEATHER_RAIN)
                            || channel.equals(LogicChannels.WEATHER_THUNDER)) {
                        weather++;
                    } else {
                        other++;
                    }
                }
                StringBuilder categories = new StringBuilder();
                appendCategoryCount(categories, biomes, "biome", "biomes");
                appendCategoryCount(categories, weather, "weather", "weather");
                appendCategoryCount(categories, time, "time profile", "time profiles");
                appendCategoryCount(categories, zones, "zone", "zones");
                appendCategoryCount(categories, viewDistance, "view distance", "view distance");
                appendCategoryCount(categories, volumetricSettling, "volumetric settling", "volumetric settling");
                appendCategoryCount(categories, other, "other layer", "other layers");
                if (water) {
                    if (categories.length() > 0) {
                        categories.append(", ");
                    }
                    categories.append("water");
                }
                lines.add("  unacquired: " + categories + " (see log; /rtx conf status all)");
            }
        }
        return lines;
    }

    private static void appendCategoryCount(StringBuilder out, int count, String singular, String plural) {
        if (count == 0) {
            return;
        }
        if (out.length() > 0) {
            out.append(", ");
        }
        out.append(count).append(' ').append(count == 1 ? singular : plural);
    }

    public boolean isActive() {
        return active;
    }

    /** Targets every layer on a channel, so both roots follow one gameplay input. */
    private void setTarget(String channel, float target) {
        for (LayerState layer : channelIndex.getOrDefault(channel, List.of())) {
            layer.target = target;
        }
    }

    private void advance(LayerState layer, float dtSeconds) {
        layer.current = layer.spec.smoothed()
                ? smoothStep(layer.current, layer.target, dtSeconds)
                : layer.target;
    }

    /**
     * One exponential-decay step from current toward target over dtSeconds,
     * snapping once the remaining gap drops below SNAP_EPSILON.
     */
    static float smoothStep(float current, float target, float dtSeconds) {
        float decay = (float) Math.pow(2.0, -SMOOTHING_FACTOR * dtSeconds);
        float next = target + (current - target) * decay;
        if (Math.abs(next - target) < SNAP_EPSILON) {
            return target;
        }
        return next;
    }

    /**
     * Whether a strength is worth a JNI call given the last value sent:
     * mid-transition, sub-epsilon deltas accumulate against lastSent until
     * they exceed SEND_EPSILON; a settled value (snapped or unsmoothed)
     * always lands exactly.
     */
    static boolean shouldSend(float value, float target, float lastSent) {
        if (value == lastSent) {
            return false;
        }
        return value == target || Math.abs(value - lastSent) > SEND_EPSILON;
    }

    /**
     * Whether a value differs from its reference by more than SEND_EPSILON:
     * the same tolerance that decides a send, so a strength the runtime would
     * see change is also one the settling envelope counts as in motion. Pure
     * and static (package-private) so ConfLayerDriverTest can exercise it.
     */
    static boolean moved(float from, float to) {
        return Math.abs(to - from) > SEND_EPSILON;
    }

    private void sendIfChanged(LayerState layer) {
        if (!shouldSend(layer.current, layer.target, layer.lastSent)) {
            return;
        }
        try {
            configLayerApi.setConfigLayerBlending(
                    layer.handle, layer.current, layer.spec.blendThreshold());
            layer.lastSent = layer.current;
            layer.sendFailureLogged = false;
        } catch (RemixException e) {
            // Keep lastSent untouched (the value retries next frame), keep the
            // frame's remaining layers advancing, and log once per failure
            // streak so a persistently failing layer doesn't spam the log.
            if (!layer.sendFailureLogged) {
                layer.sendFailureLogged = true;
                LOGGER.error("Failed to set conf layer {} blending: {} (code: {}) - retrying each "
                        + "frame (further failures logged after the next success)",
                        layer.spec.name(), e.getMessage(), e.getErrorCode());
            }
        }
    }

    private float frameDtSeconds() {
        long now = System.nanoTime();
        long previous = lastFrameNanos;
        lastFrameNanos = now;
        if (previous == 0) {
            return 0.0f;
        }
        return Math.clamp((now - previous) / 1.0e9f, 0.0f, MAX_FRAME_DT_SECONDS);
    }
}

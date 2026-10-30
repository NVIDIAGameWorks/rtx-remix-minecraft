/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Records and plays time-based, world-scoped camera paths.
 *
 * <p>Recording is sampled from client ticks, with catch-up tick bursts
 * coalesced before they become path control points. Interactive playback is
 * sampled from render frames. The default time clock smooths isolated frame
 * stalls, while the fixed-60 clock advances one deterministic virtual frame
 * per eligible rendered frame. Clocks accumulate only while a player and
 * world are available and the client is not paused.</p>
 *
 * <p>The controller moves Minecraft's client player rather than introducing a
 * detached camera, zeroing velocity at each pose and optionally applying a
 * recorded time of day, so playback and the benchmark share one camera.</p>
 */
public final class CameraController {

    public enum State {
        IDLE,
        ARMED,
        RECORDING,
        PLAYING,
        PAUSED
    }

    public enum PlaybackMode {
        TIME("time"),
        FIXED_60("fixed-60");

        private final String commandName;

        PlaybackMode(String commandName) {
            this.commandName = commandName;
        }

        public String commandName() {
            return commandName;
        }
    }

    private record ManagedTarget(String storageKey, Path canonicalDirectory, Path shotDirectory) {}

    private record StorageTargets(Path runtimeDirectory, Path canonicalDirectory, Path shotDirectory) {}

    private static final Logger LOGGER = LoggerFactory.getLogger(CameraController.class);
    private static final long NANOS_PER_MILLISECOND = 1_000_000L;
    private static final long MINECRAFT_DAY_TICKS = 24_000L;
    static final long MIN_RECORDING_WAYPOINT_INTERVAL_MS = 25L;
    private static final String FIXTURE_SUBDIRECTORY = "fixture";
    private static final String STILL_SHOT_SUFFIX = ".shots.json";
    private static final String RESERVED_SHOT_SET_NAME_SUFFIX = ".shots";

    private final LongSupplier nanoTimeSource;

    private State state = State.IDLE;
    private ManagedTarget managedTarget;
    private String armedPathName;
    private String loadedPathName;
    private CameraPath loadedPath;
    private String lastError = "";

    private final List<CameraPath.Waypoint> recordedWaypoints = new ArrayList<>();
    private StorageTargets recordingTargets;
    private long recordingElapsedNanos;
    private long recordingLastNanos;
    private boolean recordingClockActive;
    private int recordingHudTicks;

    private final InteractivePlaybackTimeline playbackTimeline =
            new InteractivePlaybackTimeline();
    private long playbackLastNanos;

    /**
     * Creates a controller backed by {@link System#nanoTime()}.
     */
    public CameraController() {
        this(System::nanoTime);
    }

    /**
     * Creates a controller with an injectable monotonic nanosecond clock.
     */
    public CameraController(LongSupplier nanoTimeSource) {
        this.nanoTimeSource = Objects.requireNonNull(nanoTimeSource, "nanoTimeSource");
    }

    /**
     * Advances camera recording from the client tick clock.
     */
    public void onClientTick(Minecraft client) {
        long now = nowNanos();

        if (state == State.RECORDING) {
            if (!isActive(client)) {
                suspendRecordingClock(now);
            } else {
                advanceRecordingClock(now);
                long elapsedMs = recordingElapsedMs();
                CameraPath.Waypoint lastWaypoint = recordedWaypoints.getLast();
                if (elapsedMs > lastWaypoint.timeMs()) {
                    storeRecordingWaypoint(
                            recordedWaypoints,
                            captureWaypoint(client.player, elapsedMs, client)
                    );
                }
            }
            recordingHudTicks++;
            if (client.player != null && recordingHudTicks % 20 == 0) {
                client.player.displayClientMessage(Component.literal("[Camera] " + status()), true);
            }
        }

        // A client tick continues through screens and world transitions even
        // when a world render callback does not. Resetting the render clock
        // here prevents that unavailable interval from being charged on the
        // first frame after playback resumes.
        if (state == State.PLAYING && !isActive(client)) {
            suspendPlaybackClock(now);
        }
    }

    /**
     * Advances interactive playback from a render-frame callback.
     */
    public void onFrameStart(Minecraft client) {
        long now = nowNanos();

        // Frame callbacks are also useful for observing a pause promptly when
        // the 20 Hz recording clock has not run yet.
        if (state == State.RECORDING && !isActive(client)) {
            suspendRecordingClock(now);
        }

        if (state != State.PLAYING) {
            return;
        }
        if (!isActive(client)) {
            suspendPlaybackClock(now);
            return;
        }

        long rawFrameNanos = monotonicDelta(now, playbackLastNanos);
        long positionMs = playbackTimeline.advanceFrame(rawFrameNanos, durationMs());
        playbackLastNanos = now;

        long durationMs = durationMs();
        applyLoadedPath(client, positionMs);
        if (positionMs >= durationMs) {
            state = State.IDLE;
            LOGGER.info("Camera playback '{}' completed at {} ms using {}",
                    loadedPathName, durationMs, playbackTimeline.mode().commandName());
            return;
        }
    }

    /**
     * Selects caller-managed storage for a benchmark or demo fixture.
     *
     * <p>The camera package intentionally does not know the fixture layout.
     * The caller supplies the runtime storage key and, when authoring, the
     * canonical directory that should receive a second copy of each camera
     * path and the directory that holds the authoritative shot sets. Shot
     * sets outlive world fixtures, so they have a home of their own.</p>
     */
    public void setManagedTarget(String storageKey, Path canonicalDirectory, Path shotDirectory) {
        String normalizedKey = requireManagedComponent(storageKey, "camera storage key");
        ManagedTarget nextTarget = new ManagedTarget(
                normalizedKey, normalizeDirectory(canonicalDirectory), normalizeDirectory(shotDirectory));
        if (!nextTarget.equals(managedTarget)) {
            unloadPlayback();
        }
        managedTarget = nextTarget;
    }

    private static Path normalizeDirectory(Path directory) {
        return directory == null ? null : directory.toAbsolutePath().normalize();
    }

    public void clearManagedTarget() {
        if (managedTarget != null) {
            unloadPlayback();
        }
        managedTarget = null;
    }

    /**
     * Clears all camera state when leaving a world.
     */
    public void resetSession() {
        state = State.IDLE;
        managedTarget = null;
        armedPathName = null;
        recordedWaypoints.clear();
        recordingTargets = null;
        resetRecordingClock();
        recordingHudTicks = 0;
        unloadPlayback();
        clearError();
    }

    /**
     * Returns the directory name used for a managed world fixture.
     */
    public static String fixtureName(String worldName, String worldVersion) {
        String safeWorld = requireManagedComponent(worldName, "world name");
        String version = worldVersion == null ? "" : worldVersion.trim();
        if (version.isEmpty()) {
            return safeWorld;
        }
        String safeVersion = requireManagedComponent(version, "world version");
        String versionSuffix = "_" + safeVersion;
        return safeWorld.endsWith(versionSuffix) ? safeWorld : safeWorld + versionSuffix;
    }

    /**
     * Derives a path-safe key for an ordinary single-player or multiplayer
     * world. Managed fixtures use {@link #fixtureName(String, String)} instead.
     */
    public static String deriveWorldKey(Minecraft client) {
        if (client == null) {
            return "unknown-world";
        }

        MinecraftServer server = client.getSingleplayerServer();
        if (server != null) {
            Path saveRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            Path saveFolder = saveRoot.getFileName();
            String identity = saveFolder == null
                    ? server.getWorldData().getLevelName()
                    : saveFolder.toString();
            return safePathComponent(identity, "singleplayer-world");
        }

        ServerData serverInfo = client.getCurrentServer();
        if (serverInfo != null) {
            String serverIdentity = serverInfo.ip;
            if (serverIdentity == null || serverIdentity.isBlank()) {
                serverIdentity = serverInfo.name;
            }
            return "server-" + safePathComponent(serverIdentity, "multiplayer-world");
        }

        if (client.level != null) {
            return "world-" + safePathComponent(
                    client.level.dimension().location().toString(),
                    "unknown-dimension"
            );
        }
        return "unknown-world";
    }

    /**
     * Arms the plain-R recording workflow for a named path.
     */
    public String arm(String name) {
        String normalizedName = normalizeCameraName(name);
        if (normalizedName == null) {
            return lastError;
        }
        if (state == State.RECORDING) {
            return fail("Cannot arm a camera while recording; save or cancel the current recording first.");
        }
        if (state == State.PLAYING || state == State.PAUSED) {
            return fail("Cannot arm a camera while playback is active; stop playback first.");
        }

        armedPathName = normalizedName;
        recordedWaypoints.clear();
        recordingTargets = null;
        resetRecordingClock();
        state = State.ARMED;
        clearError();
        return "Camera recording armed for '" + armedPathName
                + "'. Press R to start, then R again to save.";
    }

    /**
     * Starts an armed recording and captures an exact waypoint at time zero.
     */
    public String startRecording() {
        if (state != State.ARMED || armedPathName == null) {
            return fail("Camera recording is not armed. Run /rtx camera record <name> first.");
        }

        Minecraft client = Minecraft.getInstance();
        if (!isActive(client)) {
            return fail("Cannot start camera recording while the game is paused or no player/world is loaded.");
        }

        recordedWaypoints.clear();
        recordedWaypoints.add(captureWaypoint(client.player, 0L, client));
        recordingTargets = storageTargets(client);
        recordingElapsedNanos = 0L;
        recordingLastNanos = nowNanos();
        recordingClockActive = true;
        recordingHudTicks = 0;
        state = State.RECORDING;
        clearError();
        LOGGER.info("Camera recording '{}' started in {}", armedPathName, recordingTargets.runtimeDirectory());
        return "Recording camera '" + armedPathName + "'. Press R again to save.";
    }

    /**
     * Saves the recording after coalescing any near-simultaneous catch-up
     * ticks.
     *
     * <p>The current pose is always captured at the exact accumulated active
     * time. If the latest client-tick sample already has that timestamp, it is
     * replaced so the final waypoint still reflects the pose at the save
     * action.</p>
     */
    public String saveRecording() {
        if (state != State.RECORDING) {
            return fail("No camera recording is in progress.");
        }

        Minecraft client = Minecraft.getInstance();
        if (!hasPlayerAndWorld(client)) {
            return fail("Cannot save camera recording until a player and world are available.");
        }

        long now = nowNanos();
        if (isActive(client)) {
            advanceRecordingClock(now);
        } else {
            suspendRecordingClock(now);
        }
        captureExactFinalWaypoint(client);

        if (recordedWaypoints.size() < 2
                || recordedWaypoints.getLast().timeMs() <= recordedWaypoints.getFirst().timeMs()) {
            return fail("Camera recording is too short; record some movement before saving.");
        }

        final CameraPath path;
        final String json;
        try {
            path = new CameraPath(recordedWaypoints);
            json = CameraPathCodec.toJson(path);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to finalize camera recording '{}'", armedPathName, e);
            return fail("Failed to finalize camera recording: " + safeExceptionMessage(e));
        }

        StorageTargets targets = recordingTargets != null ? recordingTargets : storageTargets(client);
        Path runtimeFile = targets.runtimeDirectory().resolve(armedPathName + ".json");
        try {
            writeAtomically(runtimeFile, json);
        } catch (IOException e) {
            LOGGER.warn("Failed to save camera recording '{}' to {}", armedPathName, runtimeFile, e);
            return fail("Failed to save camera '" + armedPathName + "' to " + runtimeFile + ": "
                    + safeExceptionMessage(e));
        }

        Path canonicalFile = null;
        String canonicalWarning = null;
        if (targets.canonicalDirectory() != null
                && Files.isDirectory(targets.canonicalDirectory().getParent())) {
            Path canonicalCandidate = targets.canonicalDirectory().resolve(armedPathName + ".json");
            try {
                writeAtomically(canonicalCandidate, json);
                canonicalFile = canonicalCandidate;
            } catch (IOException e) {
                canonicalWarning = " The runtime copy is safe, but the managed fixture copy failed: "
                        + safeExceptionMessage(e);
                LOGGER.warn("Saved runtime camera '{}', but failed to update {}",
                        armedPathName, canonicalCandidate, e);
            }
        }

        String savedName = armedPathName;
        loadedPathName = savedName;
        loadedPath = path;
        playbackTimeline.reset(path.durationMs());
        state = State.IDLE;
        armedPathName = null;
        recordingTargets = null;
        recordedWaypoints.clear();
        resetRecordingClock();
        recordingHudTicks = 0;
        clearError();

        LOGGER.info("Camera '{}' saved with {} waypoints and duration {} ms to {}{}",
                savedName,
                path.waypoints().size(),
                path.durationMs(),
                runtimeFile,
                canonicalFile == null ? "" : " and " + canonicalFile);
        String savedLocations = runtimeFile
                + (canonicalFile == null ? "" : " and " + canonicalFile);
        return "Saved camera '" + savedName + "' (" + path.waypoints().size()
                + " waypoints, " + formatSeconds(path.durationMs()) + "s) to "
                + savedLocations + (canonicalWarning == null ? "" : canonicalWarning);
    }

    /**
     * Implements the armed plain-R workflow.
     */
    public String toggleRecording() {
        return switch (state) {
            case ARMED -> startRecording();
            case RECORDING -> saveRecording();
            default -> fail("Camera recording is not armed. Run /rtx camera record <name> first.");
        };
    }

    /**
     * Disarms or cancels a recording without saving it.
     */
    public String cancelRecording() {
        if (state == State.ARMED) {
            String name = armedPathName;
            armedPathName = null;
            state = State.IDLE;
            clearError();
            return "Camera recording '" + name + "' disarmed.";
        }
        if (state != State.RECORDING) {
            return fail("No camera recording is armed or in progress.");
        }

        String name = armedPathName;
        armedPathName = null;
        recordedWaypoints.clear();
        recordingTargets = null;
        resetRecordingClock();
        recordingHudTicks = 0;
        state = State.IDLE;
        clearError();
        LOGGER.info("Camera recording '{}' cancelled", name);
        return "Camera recording '" + name + "' cancelled without saving.";
    }

    public boolean shouldHandleRecordingShortcut() {
        return state == State.ARMED || state == State.RECORDING;
    }

    public boolean isRecording() {
        return state == State.RECORDING;
    }

    /**
     * Loads a named path and starts time-based playback from its beginning.
     */
    public String play(String name) {
        return play(name, PlaybackMode.TIME);
    }

    /**
     * Loads a named path and starts interactive playback from its beginning.
     */
    public String play(String name, PlaybackMode mode) {
        if (state == State.ARMED || state == State.RECORDING) {
            return fail("Cannot play a camera while recording is armed or active; cancel it first.");
        }
        PlaybackMode selectedMode = Objects.requireNonNull(mode, "mode");
        if (!loadNamedPath(name, Minecraft.getInstance())) {
            return lastError;
        }

        Minecraft client = Minecraft.getInstance();
        playbackTimeline.start(selectedMode, 0L);
        playbackLastNanos = nowNanos();
        // Anchor on the first eligible world render so t=0 is actually
        // presented and command-to-render latency is not charged.
        state = State.PLAYING;
        if (hasPlayerAndWorld(client)) {
            applyLoadedPath(client, 0L);
        }
        clearError();
        LOGGER.info("Camera playback '{}' started ({} ms, clock {})",
                loadedPathName, durationMs(), playbackTimeline.mode().commandName());
        return "Playing camera '" + loadedPathName + "' (" + formatSeconds(durationMs())
                + "s) with " + playbackTimeline.mode().commandName() + " clock.";
    }

    public String pause() {
        if (state != State.PLAYING) {
            return fail(state == State.PAUSED
                    ? "Camera playback is already paused."
                    : "No camera playback is active.");
        }

        Minecraft client = Minecraft.getInstance();
        long now = nowNanos();
        if (hasPlayerAndWorld(client)) {
            applyLoadedPath(client, positionMs());
        }
        playbackTimeline.reanchor(false);
        playbackLastNanos = now;
        state = State.PAUSED;
        clearError();
        return "Paused camera '" + loadedPathName + "' at " + formatSeconds(positionMs())
                + "s (" + playbackTimeline.mode().commandName() + ").";
    }

    public String resume() {
        if (state != State.PAUSED) {
            return fail(state == State.PLAYING
                    ? "Camera playback is already running."
                    : "No paused camera playback is available.");
        }

        Minecraft client = Minecraft.getInstance();
        playbackTimeline.reanchor(true);
        playbackLastNanos = nowNanos();
        state = State.PLAYING;
        if (hasPlayerAndWorld(client)) {
            applyLoadedPath(client, positionMs());
        }
        clearError();
        return "Resumed camera '" + loadedPathName + "' at " + formatSeconds(positionMs())
                + "s (" + playbackTimeline.mode().commandName() + ").";
    }

    /**
     * Seeks the loaded path in milliseconds. Playback/paused state is
     * preserved except that seeking either state to the endpoint completes it;
     * an idle loaded path is applied as a one-off camera pose.
     */
    public String seek(long timeMs) {
        if (state == State.ARMED || state == State.RECORDING) {
            return fail("Cannot seek a camera while recording is armed or active; cancel it first.");
        }
        if (loadedPath == null) {
            return fail("No camera path is loaded. Specify a camera name first.");
        }

        long clampedTimeMs = clamp(timeMs, 0L, durationMs());
        playbackTimeline.reset(clampedTimeMs);
        playbackLastNanos = nowNanos();

        Minecraft client = Minecraft.getInstance();
        if (hasPlayerAndWorld(client)) {
            applyLoadedPath(client, clampedTimeMs);
        }
        state = stateAfterSeek(state, clampedTimeMs, durationMs());
        clearError();
        return "Camera '" + loadedPathName + "' moved to " + formatSeconds(clampedTimeMs)
                + "s/" + formatSeconds(durationMs()) + "s.";
    }

    /**
     * Loads a named path without starting playback, then seeks it.
     */
    public String seek(String name, long timeMs) {
        if (state == State.ARMED || state == State.RECORDING) {
            return fail("Cannot seek a camera while recording is armed or active; cancel it first.");
        }
        if (!loadForBenchmark(name)) {
            return lastError;
        }
        return seek(timeMs);
    }

    /**
     * Stops playback, or explicitly cancels an armed/active recording.
     */
    public String stop() {
        return switch (state) {
            case ARMED, RECORDING -> cancelRecording();
            case PLAYING, PAUSED -> {
                String name = loadedPathName;
                state = State.IDLE;
                playbackTimeline.reanchor(true);
                clearError();
                LOGGER.info("Camera playback '{}' stopped at {} ms", name, positionMs());
                yield "Stopped camera '" + name + "' at " + formatSeconds(positionMs()) + "s.";
            }
            case IDLE -> {
                clearError();
                yield "Camera is already idle.";
            }
        };
    }

    public String status() {
        return switch (state) {
            case IDLE -> loadedPath == null
                    ? "Camera: idle"
                    : "Camera: idle | loaded '" + loadedPathName + "' | "
                            + formatSeconds(positionMs()) + "s/" + formatSeconds(durationMs()) + "s";
            case ARMED -> "Camera: armed '" + armedPathName + "' | press R to record";
            case RECORDING -> "Camera: recording '" + armedPathName + "' | "
                    + formatSeconds(recordingElapsedMs()) + "s | "
                    + recordedWaypoints.size() + " waypoints | press R to save";
            case PLAYING -> "Camera: playing '" + loadedPathName + "' | "
                    + formatSeconds(positionMs()) + "s/" + formatSeconds(durationMs()) + "s"
                    + playbackClockStatus();
            case PAUSED -> "Camera: paused '" + loadedPathName + "' | "
                    + formatSeconds(positionMs()) + "s/" + formatSeconds(durationMs()) + "s"
                    + playbackClockStatus();
        };
    }

    /**
     * Loads a camera path for deterministic benchmark sampling and resets its
     * interactive positions to time zero without starting playback.
     */
    public boolean loadForBenchmark(String name) {
        if (state == State.ARMED || state == State.RECORDING) {
            fail("Cannot load a benchmark camera while recording is armed or active.");
            return false;
        }
        if (!loadNamedPath(name, Minecraft.getInstance())) {
            return false;
        }

        state = State.IDLE;
        playbackTimeline.reset(0L);
        clearError();
        return true;
    }

    /**
     * Applies an explicit time from an external deterministic benchmark clock
     * without changing either interactive timeline or its diagnostics.
     */
    public boolean applyBenchmarkTime(long timeMs) {
        if (loadedPath == null) {
            fail("No camera path is loaded for the benchmark.");
            return false;
        }

        Minecraft client = Minecraft.getInstance();
        if (!hasPlayerAndWorld(client)) {
            fail("Cannot apply benchmark camera time because no player/world is loaded.");
            return false;
        }

        long clampedTimeMs = clamp(timeMs, 0L, durationMs());
        applyLoadedPath(client, clampedTimeMs);
        clearError();
        return true;
    }

    public boolean holdBenchmarkStart() {
        return applyBenchmarkTime(0L);
    }

    /**
     * Returns the deterministic 60 Hz path time used by both fixed interactive
     * playback and benchmarks.
     */
    public static long fixed60TimeMs(long frameIndex) {
        return FixedStepClock.timeMsForFrame(frameIndex);
    }

    public long durationMs() {
        return loadedPath == null ? 0L : loadedPath.durationMs();
    }

    public String setTimeOfDay(long timeOfDay) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return fail("No world loaded.");
        }

        long normalized = normalizeTimeOfDay(timeOfDay);
        applyTimeOfDay(client, normalized);
        clearError();
        return "Set time of day to " + normalized + ".";
    }

    public Long adjustTimeOfDay(long deltaTicks) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            fail("No world loaded.");
            return null;
        }

        long normalized = normalizeTimeOfDay(client.level.getDayTime() + deltaTicks);
        applyTimeOfDay(client, normalized);
        clearError();
        return normalized;
    }

    // --- Still shots ---

    /**
     * Records the player's current pose as a still shot in the named set,
     * replacing a shot of the same name in any letter case (the image file
     * system is case-insensitive), and writes the set at once.
     *
     * <p>Marking is independent of recording and playback: a mark is the
     * waypoint, taken from the pose the player is frozen in while chat is
     * open, so nothing needs saving afterwards and a crash loses nothing.</p>
     *
     * @param scene the state a waypoint cannot carry, read by the caller
     * @return feedback for the player
     */
    public String markStillShot(String setName, String shotName, StillShotScene scene) {
        Minecraft client = Minecraft.getInstance();
        if (!hasPlayerAndWorld(client)) {
            return fail("No world loaded.");
        }
        String set = normalizeCameraName(setName);
        String name = normalizeCameraName(shotName);
        if (set == null || name == null) {
            return lastError;
        }
        List<StillShot> shots = readStillShots(client, set);
        if (shots == null) {
            return lastError;
        }
        LocalPlayer player = client.player;
        StillShot shot = new StillShot(
                name, player.getX(), player.getY(), player.getZ(),
                Mth.wrapDegrees(player.getYRot()), player.getXRot(),
                normalizeTimeOfDay(client.level.getDayTime()),
                scene.weather(), scene.rtxEnabled(), scene.proceduralClouds());
        boolean replaced = shots.removeIf(existing -> existing.name().equalsIgnoreCase(name));
        shots.add(shot);
        String written = writeStillShots(client, set, shots);
        if (written == null) {
            return lastError;
        }
        clearError();
        return (replaced ? "Replaced" : "Marked") + " shot '" + name + "' (" + shots.size()
                + " in set '" + set + "') at " + formatPose(shot) + "; saved to " + written;
    }

    /** Removes one shot from the named set and writes the set. */
    public String removeStillShot(String setName, String shotName) {
        Minecraft client = Minecraft.getInstance();
        if (!hasPlayerAndWorld(client)) {
            return fail("No world loaded.");
        }
        String set = normalizeCameraName(setName);
        String name = normalizeCameraName(shotName);
        if (set == null || name == null) {
            return lastError;
        }
        List<StillShot> shots = readStillShots(client, set);
        if (shots == null) {
            return lastError;
        }
        if (!shots.removeIf(existing -> existing.name().equalsIgnoreCase(name))) {
            return fail("No shot '" + name + "' in set '" + set + "'.");
        }
        String written = writeStillShots(client, set, shots);
        if (written == null) {
            return lastError;
        }
        clearError();
        return "Removed shot '" + name + "' (" + shots.size() + " left in set '" + set + "').";
    }

    /** Lists the named set, one line per shot, or explains why it could not be read. */
    public String describeStillShots(String setName) {
        Minecraft client = Minecraft.getInstance();
        if (!hasPlayerAndWorld(client)) {
            return fail("No world loaded.");
        }
        String set = normalizeCameraName(setName);
        if (set == null) {
            return lastError;
        }
        List<StillShot> shots = readStillShots(client, set);
        if (shots == null) {
            return lastError;
        }
        clearError();
        if (shots.isEmpty()) {
            return "Set '" + set + "' has no shots. Mark one with /rtx shot mark <name>.";
        }
        StringBuilder out = new StringBuilder("Set '" + set + "': " + shots.size() + " shot(s)");
        for (StillShot shot : shots) {
            out.append('\n').append("  ").append(shot.name()).append(": ").append(formatPose(shot))
                    .append(", time ").append(shot.timeOfDay())
                    .append(", ").append(shot.weather().wireName())
                    .append(shot.rtxEnabled() ? ", rtx on" : ", rtx off")
                    .append(shot.proceduralClouds() ? ", procedural clouds" : "");
        }
        return out.toString();
    }

    /**
     * Loads the named set for a capture pass, or returns {@code null} with
     * {@link #lastError()} set. A missing file is an empty set, which is an
     * error here: a capture with nothing to capture is a mistake worth saying.
     */
    public List<StillShot> loadStillShots(String setName) {
        Minecraft client = Minecraft.getInstance();
        String set = normalizeCameraName(setName);
        if (set == null) {
            return null;
        }
        List<StillShot> shots = readStillShots(client, set);
        if (shots == null) {
            return null;
        }
        if (shots.isEmpty()) {
            fail("Shot set '" + set + "' is empty or missing at " + stillShotFile(storageTargets(client), set));
            return null;
        }
        clearError();
        return shots;
    }

    /**
     * Puts the player at a shot's pose for this frame and sets its time of day.
     * A capture pass calls this every frame: a pose applied once drifts with
     * gravity and the day cycle while the scene settles.
     */
    public void holdStillShot(Minecraft client, StillShot shot) {
        if (!hasPlayerAndWorld(client)) {
            return;
        }
        LocalPlayer player = client.player;
        player.moveTo(shot.x(), shot.y(), shot.z(), shot.yaw(), shot.pitch());
        player.setDeltaMovement(0.0, 0.0, 0.0);
        applyTimeOfDay(client, shot.timeOfDay());
    }

    /**
     * Moves the server-side player to a shot so the integrated server loads
     * and sends the chunks around it; the client-side hold alone would leave
     * the server's view of the player, and so its chunk filter, behind.
     */
    public void teleportToStillShot(Minecraft client, StillShot shot) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null) {
            return;
        }
        UUID uuid = client.player.getUUID();
        server.execute(() -> {
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(uuid);
            if (serverPlayer == null) {
                return;
            }
            serverPlayer.teleportTo(serverPlayer.serverLevel(),
                    shot.x(), shot.y(), shot.z(), shot.yaw(), shot.pitch());
            serverPlayer.setDeltaMovement(0.0, 0.0, 0.0);
        });
    }

    private List<StillShot> readStillShots(Minecraft client, String set) {
        StorageTargets targets = storageTargets(client);
        Path runtimeFile = stillShotFile(targets, set);
        Path managedFile = managedStillShotFile(targets, set);
        // Same precedence as camera paths: the newer of the runtime and
        // managed copies wins, so a mark made in a staged fixture is seen
        // even before the harness restages.
        Path source = runtimeFile;
        if (managedFile != null && Files.isRegularFile(managedFile)
                && (!Files.isRegularFile(runtimeFile) || isNewer(managedFile, runtimeFile))) {
            source = managedFile;
        }
        if (!Files.isRegularFile(source)) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(StillShotCodec.parse(Files.readString(source, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Failed to read shot set '{}' from {}", set, source, e);
            fail("Failed to read shot set '" + set + "' from " + source + ": " + safeExceptionMessage(e));
            return null;
        }
    }

    /** Writes the set to the runtime file and, when managed, the shot directory; returns where. */
    private String writeStillShots(Minecraft client, String set, List<StillShot> shots) {
        StorageTargets targets = storageTargets(client);
        String json = StillShotCodec.toJson(shots);
        Path runtimeFile = stillShotFile(targets, set);
        try {
            writeAtomically(runtimeFile, json);
        } catch (IOException e) {
            LOGGER.warn("Failed to save shot set '{}' to {}", set, runtimeFile, e);
            fail("Failed to save shot set '" + set + "' to " + runtimeFile + ": " + safeExceptionMessage(e));
            return null;
        }
        Path managedFile = managedStillShotFile(targets, set);
        if (managedFile == null || !Files.isDirectory(managedFile.getParent().getParent())) {
            return runtimeFile.toString();
        }
        try {
            writeAtomically(managedFile, json);
            return runtimeFile + " and " + managedFile;
        } catch (IOException e) {
            LOGGER.warn("Saved runtime shot set '{}', but failed to update {}", set, managedFile, e);
            return runtimeFile + " (the managed copy failed: " + safeExceptionMessage(e) + ")";
        }
    }

    private static Path managedStillShotFile(StorageTargets targets, String set) {
        return targets.shotDirectory() == null ? null : targets.shotDirectory().resolve(set + STILL_SHOT_SUFFIX);
    }

    private static Path stillShotFile(StorageTargets targets, String set) {
        return targets.runtimeDirectory().resolve(set + STILL_SHOT_SUFFIX);
    }

    private static String formatPose(StillShot shot) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f yaw %.1f pitch %.1f",
                shot.x(), shot.y(), shot.z(), shot.yaw(), shot.pitch());
    }

    /** Scene state a mark records beside the pose; read by the command, since the camera package owns no renderer state. */
    public record StillShotScene(StillShot.Weather weather, boolean rtxEnabled, boolean proceduralClouds) {}

    public State state() {
        return state;
    }

    public String loadedPathName() {
        return loadedPathName;
    }

    CameraPath loadedPath() {
        return loadedPath;
    }

    public long positionMs() {
        if (loadedPath == null) {
            return 0L;
        }
        return playbackTimeline.positionMs(durationMs());
    }

    public String lastError() {
        return lastError;
    }

    private boolean loadNamedPath(String name, Minecraft client) {
        String normalizedName = normalizeCameraName(name);
        if (normalizedName == null) {
            return false;
        }

        StorageTargets targets = storageTargets(client);
        Path canonicalFile = targets.canonicalDirectory() == null
                ? null
                : targets.canonicalDirectory().resolve(normalizedName + ".json");
        Path runtimeFile = targets.runtimeDirectory().resolve(normalizedName + ".json");

        Path fixtureFile = targets.runtimeDirectory()
                .resolve(FIXTURE_SUBDIRECTORY)
                .resolve(normalizedName + ".json");
        List<Path> candidates = new ArrayList<>();
        boolean runtimeFirst = canonicalFile != null
                && Files.isRegularFile(runtimeFile)
                && Files.isRegularFile(canonicalFile)
                && isNewer(runtimeFile, canonicalFile);
        if (runtimeFirst) {
            candidates.add(runtimeFile);
        }
        if (canonicalFile != null) {
            candidates.add(canonicalFile);
        }
        if (!runtimeFirst) {
            candidates.add(runtimeFile);
        }
        candidates.add(fixtureFile);

        List<String> failures = new ArrayList<>();
        boolean foundCandidate = false;
        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            foundCandidate = true;
            try {
                String json = Files.readString(candidate, StandardCharsets.UTF_8);
                CameraPath path = CameraPathCodec.parse(json);
                loadedPathName = normalizedName;
                loadedPath = path;
                playbackTimeline.reset(0L);
                clearError();
                LOGGER.info("Loaded camera '{}' from {} ({} waypoints, {} ms)",
                        normalizedName, candidate, path.waypoints().size(), path.durationMs());
                return true;
            } catch (IOException | RuntimeException e) {
                String failure = candidate + ": " + safeExceptionMessage(e);
                failures.add(failure);
                LOGGER.warn("Failed to load camera '{}' from {}; trying the next source",
                        normalizedName, candidate, e);
            }
        }

        if (!foundCandidate) {
            fail("Camera '" + normalizedName + "' was not found at "
                    + String.join(", ", candidates.stream().map(Path::toString).toList()) + ".");
        } else {
            fail("Failed to load camera '" + normalizedName + "' from every available source: "
                    + String.join("; ", failures));
        }
        return false;
    }

    private static boolean isNewer(Path candidate, Path reference) {
        try {
            return Files.getLastModifiedTime(candidate).compareTo(Files.getLastModifiedTime(reference)) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private StorageTargets storageTargets(Minecraft client) {
        Minecraft resolvedClient = client != null ? client : Minecraft.getInstance();
        Path runDirectory = resolvedClient.gameDirectory.toPath().toAbsolutePath().normalize();
        String worldKey = managedTarget == null
                ? deriveWorldKey(resolvedClient)
                : managedTarget.storageKey();
        Path runtimeDirectory = runDirectory.resolve("camera-paths").resolve(worldKey);

        if (managedTarget == null) {
            return new StorageTargets(runtimeDirectory, null, null);
        }
        return new StorageTargets(
                runtimeDirectory, managedTarget.canonicalDirectory(), managedTarget.shotDirectory());
    }

    private void unloadPlayback() {
        if (state == State.PLAYING || state == State.PAUSED) {
            state = State.IDLE;
        }
        loadedPathName = null;
        loadedPath = null;
        playbackTimeline.resetToDefault(0L);
        playbackLastNanos = 0L;
    }

    private void advanceRecordingClock(long now) {
        if (!recordingClockActive) {
            recordingLastNanos = now;
            recordingClockActive = true;
            return;
        }
        recordingElapsedNanos = saturatingAdd(
                recordingElapsedNanos,
                monotonicDelta(now, recordingLastNanos)
        );
        recordingLastNanos = now;
    }

    private void suspendRecordingClock(long now) {
        recordingLastNanos = now;
        recordingClockActive = false;
    }

    private void suspendPlaybackClock(long now) {
        playbackTimeline.reanchor(true);
        playbackLastNanos = now;
    }

    private void resetRecordingClock() {
        recordingElapsedNanos = 0L;
        recordingLastNanos = 0L;
        recordingClockActive = false;
    }

    private long recordingElapsedMs() {
        return recordingElapsedNanos / NANOS_PER_MILLISECOND;
    }

    private String playbackClockStatus() {
        return formatPlaybackClockStatus(
                playbackTimeline.mode(),
                playbackTimeline.lastRawFrameNanos(),
                playbackTimeline.lastAdvanceNanos(),
                playbackTimeline.smoothingLagNanos()
        );
    }

    static String formatPlaybackClockStatus(
            PlaybackMode mode,
            long rawFrameNanos,
            long cameraAdvanceNanos,
            long smoothingLagNanos
    ) {
        String status = " | clock " + mode.commandName()
                + " | raw frame " + formatMilliseconds(Math.max(0L, rawFrameNanos)) + "ms"
                + " | camera advance "
                + formatMilliseconds(Math.max(0L, cameraAdvanceNanos)) + "ms";
        if (mode == PlaybackMode.TIME && smoothingLagNanos > 0L) {
            long lagMs = Math.max(
                    1L,
                    Math.round(smoothingLagNanos / (double) NANOS_PER_MILLISECOND)
            );
            status += " | smoothing lag " + formatSeconds(lagMs) + "s";
        }
        return status;
    }

    static State stateAfterSeek(State currentState, long positionMs, long durationMs) {
        if ((currentState == State.PLAYING || currentState == State.PAUSED)
                && positionMs >= durationMs) {
            return State.IDLE;
        }
        return currentState;
    }

    private void captureExactFinalWaypoint(Minecraft client) {
        long elapsedMs = recordingElapsedMs();
        storeRecordingWaypoint(
                recordedWaypoints,
                captureWaypoint(client.player, elapsedMs, client)
        );
    }

    /**
     * Stores a tick-sampled waypoint without encoding back-to-back catch-up
     * ticks as near-zero-duration, extremely high-speed path segments.
     *
     * <p>Minecraft may execute several client ticks in a burst after a render
     * stall. Their wall-clock timestamps can be only a few milliseconds apart
     * even though each tick advances player movement. Coalescing those samples
     * into the latest control point preserves their combined elapsed time and
     * final pose while keeping the original time-based recording duration.</p>
     */
    static void storeRecordingWaypoint(
            List<CameraPath.Waypoint> waypoints,
            CameraPath.Waypoint waypoint
    ) {
        if (waypoints.isEmpty()) {
            waypoints.add(waypoint);
            return;
        }

        int lastIndex = waypoints.size() - 1;
        CameraPath.Waypoint previous = waypoints.get(lastIndex);
        if (waypoint.timeMs() < previous.timeMs()) {
            throw new IllegalArgumentException("recording waypoint time must not move backwards");
        }
        if (waypoint.timeMs() == previous.timeMs()) {
            waypoints.set(lastIndex, waypoint);
            return;
        }

        long intervalMs = waypoint.timeMs() - previous.timeMs();
        if (waypoints.size() > 1 && intervalMs < MIN_RECORDING_WAYPOINT_INTERVAL_MS) {
            waypoints.set(lastIndex, waypoint);
            return;
        }
        if (waypoints.size() == 1 && intervalMs < MIN_RECORDING_WAYPOINT_INTERVAL_MS) {
            return;
        }
        waypoints.add(waypoint);
    }

    private static CameraPath.Waypoint captureWaypoint(
            LocalPlayer player,
            long timeMs,
            Minecraft client
    ) {
        Long timeOfDay = client.level == null
                ? null
                : normalizeTimeOfDay(client.level.getDayTime());
        return new CameraPath.Waypoint(
                timeMs,
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getYRot(),
                player.getXRot(),
                timeOfDay
        );
    }

    private void applyLoadedPath(Minecraft client, long timeMs) {
        CameraPath.Sample sample = loadedPath.sampleAt(timeMs);
        LocalPlayer player = client.player;
        // Playback already supplies a fully interpolated render-frame pose.
        // Keep Minecraft's previous/current entity transforms identical so
        // Camera.update() does not interpolate that pose a second time against
        // the 20 Hz client-tick history.
        player.moveTo(
                sample.x(),
                sample.y(),
                sample.z(),
                sample.yaw(),
                sample.pitch()
        );
        player.setDeltaMovement(0.0, 0.0, 0.0);
        if (sample.timeOfDay() != null) {
            applyTimeOfDay(client, sample.timeOfDay());
        }
    }

    private static void applyTimeOfDay(Minecraft client, long timeOfDay) {
        long normalized = normalizeTimeOfDay(timeOfDay);
        if (client.level != null) {
            client.level.setDayTime(normalized);
        }

        MinecraftServer server = client.getSingleplayerServer();
        if (server != null) {
            for (ServerLevel world : server.getAllLevels()) {
                world.setDayTime(normalized);
            }
        }
    }

    private static long normalizeTimeOfDay(long timeOfDay) {
        return Math.floorMod(timeOfDay, MINECRAFT_DAY_TICKS);
    }

    private static boolean isActive(Minecraft client) {
        return hasPlayerAndWorld(client) && !isPausedOrWillPause(client);
    }

    private static boolean hasPlayerAndWorld(Minecraft client) {
        return client != null && client.player != null && client.level != null;
    }

    /**
     * Includes a pausing screen or overlay that Minecraft has opened during
     * this render. {@link Minecraft#isPaused()} is a cached value that is
     * not recomputed until the end of {@code MinecraftClient.render}; relying
     * on it alone would advance playback once after the pause menu appears.
     */
    private static boolean isPausedOrWillPause(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        boolean screenPauses = client.screen != null && client.screen.isPauseScreen();
        boolean overlayPauses = client.getOverlay() != null && client.getOverlay().isPauseScreen();
        return isPausedOrWillPause(
                client.isPaused(),
                client.hasSingleplayerServer(),
                server != null && server.isPublished(),
                screenPauses,
                overlayPauses
        );
    }

    static boolean isPausedOrWillPause(
            boolean cachedPaused,
            boolean integratedServerRunning,
            boolean serverRemote,
            boolean screenPauses,
            boolean overlayPauses
    ) {
        return cachedPaused
                || integratedServerRunning
                && !serverRemote
                && (screenPauses || overlayPauses);
    }

    private static void writeAtomically(Path file, String json) throws IOException {
        Path directory = file.getParent();
        Files.createDirectories(directory);
        Path temporaryFile = Files.createTempFile(directory, file.getFileName().toString() + ".", ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temporaryFile, json, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporaryFile,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporaryFile);
            }
        }
    }

    private String normalizeCameraName(String name) {
        if (name == null || name.isBlank()) {
            fail("Camera name must not be blank.");
            return null;
        }
        String normalized = name.trim();
        if (normalized.toLowerCase(Locale.ROOT).endsWith(".json")) {
            normalized = normalized.substring(0, normalized.length() - ".json".length());
        }
        if (!CameraNames.isValid(normalized)) {
            fail("Invalid camera name '" + name + "'. " + CameraNames.RULE);
            return null;
        }
        // Shot sets share the camera directory as <set>.shots.json, so a camera
        // path may not take that spelling or a recording would replace a set.
        if (normalized.toLowerCase(Locale.ROOT).endsWith(RESERVED_SHOT_SET_NAME_SUFFIX)) {
            fail("Invalid camera name '" + name + "'. Names ending in '" + RESERVED_SHOT_SET_NAME_SUFFIX
                    + "' are reserved for shot sets.");
            return null;
        }
        return normalized;
    }

    private static String requireManagedComponent(String value, String description) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
        String normalized = value.trim();
        if (!normalized.equals(safePathComponent(normalized, ""))) {
            throw new IllegalArgumentException(
                    description + " contains characters that are unsafe in a fixture path: " + value
            );
        }
        return normalized;
    }

    private static String safePathComponent(String value, String fallback) {
        String source = value == null ? "" : value.trim();
        StringBuilder result = new StringBuilder(Math.min(source.length(), 96));
        boolean lastWasReplacement = false;
        for (int i = 0; i < source.length() && result.length() < 96; i++) {
            char c = source.charAt(i);
            boolean safe = c >= 'a' && c <= 'z'
                    || c >= 'A' && c <= 'Z'
                    || c >= '0' && c <= '9'
                    || c == '.'
                    || c == '_'
                    || c == '-';
            if (safe) {
                result.append(c);
                lastWasReplacement = false;
            } else if (!lastWasReplacement) {
                result.append('_');
                lastWasReplacement = true;
            }
        }

        while (!result.isEmpty() && (result.charAt(0) == '.' || result.charAt(0) == ' ')) {
            result.deleteCharAt(0);
        }
        while (!result.isEmpty()) {
            char last = result.charAt(result.length() - 1);
            if (last != '.' && last != ' ') {
                break;
            }
            result.deleteCharAt(result.length() - 1);
        }

        String safe = result.toString();
        if (safe.isBlank() || safe.equals(".") || safe.equals("..")) {
            safe = fallback;
        }
        if (CameraNames.isWindowsReserved(safe)) {
            safe = "_" + safe;
        }
        return safe.isBlank() ? "unknown-world" : safe;
    }

    private long nowNanos() {
        return nanoTimeSource.getAsLong();
    }

    private static long monotonicDelta(long now, long previous) {
        long delta = now - previous;
        return delta > 0L ? delta : 0L;
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0L) {
            return left;
        }
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private static String formatSeconds(long milliseconds) {
        return String.format(Locale.ROOT, "%.3f", milliseconds / 1_000.0);
    }

    private static String formatMilliseconds(long nanoseconds) {
        return String.format(Locale.ROOT, "%.3f", nanoseconds / 1_000_000.0);
    }

    private static String safeExceptionMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }

    private String fail(String message) {
        lastError = message;
        return message;
    }

    private void clearError() {
        lastError = "";
    }
}

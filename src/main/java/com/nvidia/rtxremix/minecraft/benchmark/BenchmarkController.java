/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.benchmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.profiling.PerformanceTimer;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.terrain.ChunkManager;
import com.nvidia.rtxremix.minecraft.camera.CameraController;
import com.nvidia.rtxremix.minecraft.camera.StillShot;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controls automated benchmark runs and performance data capture.
 *
 * Camera recording and real-time playback are owned by {@link CameraController}.
 * Benchmarks drive that same time-based path with a deterministic virtual
 * 60 Hz clock so every run samples the same locations.
 *
 * Sampling is direct rather than clocked: frame {@code n} samples the path at
 * {@code round(n * 1000 / 60)} milliseconds, clamped to the duration, and the
 * auto-sized count is {@code max(2, ceil(durationMs * 60 / 1000) + 1)} so the
 * exact final pose is always included and a very short path holds it. Setup
 * loads the path at time zero and warmup holds the first pose; measurement
 * bypasses every interactive clock, its stall smoothing, target lag, and
 * reanchoring, and per-frame sampling must not mutate either interactive clock
 * or its diagnostics. A slow machine therefore takes longer in wall-clock time
 * to walk the same virtual timeline, and the run can keep frame-indexed
 * statistics. The integer frame index caps a benchmarked path at
 * {@link #MAX_BENCHMARK_CAMERA_DURATION_MS}; a longer path stays valid for
 * interactive playback and is rejected here.
 *
 * States: IDLE -> WAITING_FOR_WORLD -> WARMUP -> RECORDING -> COMPLETE
 *
 * Can be triggered manually via /rtx benchmark or automatically via an
 * auto_benchmark.json trigger file (used by the benchmark CLI for scripted runs).
 */
public class BenchmarkController {

    private static final Logger LOGGER = LoggerFactory.getLogger(BenchmarkController.class);

    private enum State { IDLE, WAITING_FOR_WORLD, WARMUP, RECORDING, STILLS, COMPLETE }

    private static final String BENCHMARK_WORLD_NAME = "rtx-benchmark";
    private static final String DEFAULT_CAMERA_PATH_NAME = "simple";
    private static final String TRIGGER_MODE_BENCHMARK = "benchmark";
    private static final String TRIGGER_MODE_PLAY = "play";
    private static final String TRIGGER_MODE_STILLS = "stills";
    private static final String DEFAULT_SHOT_SET = "docs";
    private static final int BENCHMARK_CAMERA_HZ = 60;
    static final long MAX_BENCHMARK_CAMERA_DURATION_MS =
            CameraController.fixed60TimeMs(Integer.MAX_VALUE - 1L);
    private static final int QUICKPLAY_FALLBACK_TICKS = 20 * 45;
    private static final String STATUS_FILE_NAME = "benchmark_status.json";
    private static final long STATUS_HEARTBEAT_INTERVAL_MS = 1_000L;
    /** Weather set for a still shot lasts this long, so it cannot change while the shot settles. */
    private static final int WEATHER_HOLD_TICKS = 24_000;

    private State state = State.IDLE;
    private boolean worldJoinRequested = false;
    private boolean autoTriggerPending = false;
    private boolean quickPlayWorldLoad = false;
    private int quickPlayWaitTicks = 0;
    private boolean jfrFlushed = false;
    private boolean disableWorldSaving = false;
    private boolean worldSavingDisabled = false;
    private final CameraController cameraController;
    private final IdentityHashMap<ServerLevel, Boolean> previousWorldSavingDisabled = new IdentityHashMap<>();
    // Warmup is held (not counted) while a world-load screen is up; this flag
    // only limits the hold/resume log lines to state transitions.
    private boolean warmupHeldForLoadScreen = false;
    // Configuration
    private int warmupFrames = 1200;
    /** Frames at the end of a warmup lap spent holding the path start, so recording begins from rest. */
    static final int WARMUP_SETTLE_FRAMES = 120;
    /**
     * Fastest warmup-lap sweep, as a multiple of recorded path speed. A faster sweep compresses
     * terrain streaming and entity capture into a burst no real session produces (a 6x sweep
     * reproducibly ended in DeviceLost), so warmup is extended instead of sweeping faster.
     */
    static final double WARMUP_LAP_MAX_SPEED = 2.0;
    private boolean warmupLap = false;
    private int recordFrames = 900;
    private boolean exitOnComplete = false;
    private String exitMode = "halt";
    private int postResultExitDelaySeconds = 0;
    private String benchmarkWorldName = "";
    private String benchmarkWorldVersion = "";
    private String cameraStorageKey = "";
    private Path cameraCanonicalDirectory = null;
    private Path shotSetDirectory = null;
    private String cameraPathName = DEFAULT_CAMERA_PATH_NAME;
    private String autoTriggerMode = TRIGGER_MODE_BENCHMARK;
    private String shotSetName = DEFAULT_SHOT_SET;
    private final List<String> shotFilter = new ArrayList<>();
    private StillShotSession.Settle shotSettle = StillShotSession.Settle.DEFAULT;
    private StillShotSession stillShots;
    private int stillShotColumns = -1;
    private boolean captureStillShotAtFrameEnd = false;
    private boolean hudHiddenBeforeStills = false;
    private final AtomicInteger pendingStillShotWrites = new AtomicInteger();
    private final AtomicBoolean stillShotWriteFailed = new AtomicBoolean();
    private int screenshotFrameCount = 5;
    private int screenshotInterval = 0; // optional legacy capture every N recording frames (0 = disabled)
    private final SortedSet<Integer> explicitScreenshotFrames = new TreeSet<>();
    private final SortedSet<Integer> scheduledScreenshotFrames = new TreeSet<>();
    private final Set<Integer> capturedScreenshotFrames = new HashSet<>();

    // Deterministic number of 60 Hz path samples used by benchmark mode.
    private int totalPathFrames = 0;
    private String cameraPreparationError = "";

    // Frame tracking
    private int currentFrame = 0;

    // Per-frame recording data
    private long[] frameTimeNanos;
    private long[][] sectionTimeNanos;
    private long[][] counterValues;
    private int sectionCount;
    private int counterCount;
    private int pendingFrameTimeIndex = -1;
    private boolean finishAfterPendingFrameTime = false;
    private long lastStatusHeartbeatMillis = 0L;
    private long statusSequence = 0L;

    // Git info (loaded from benchmark.properties at build time)
    private String gitCommit = "unknown";
    private String gitBranch = "unknown";

    public BenchmarkController() {
        this(new CameraController());
    }

    public BenchmarkController(CameraController cameraController) {
        this.cameraController = Objects.requireNonNull(cameraController, "cameraController");
        loadGitInfo();
    }

    private void loadGitInfo() {
        try (InputStream is = getClass().getResourceAsStream("/benchmark.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                gitCommit = props.getProperty("git.commit", "unknown");
                gitBranch = props.getProperty("git.branch", "unknown");
            }
        } catch (IOException e) {
            LOGGER.warn("Could not load benchmark.properties", e);
        }
    }

    /**
     * Called every client tick (even on the main menu, before a world is loaded).
     * If an auto_benchmark.json trigger file exists, reads it, deletes it immediately,
     * and waits for the benchmark world to load.
     */
    public void onClientTick(Minecraft client) {
        if (state != State.IDLE) return;
        if (autoTriggerPending) {
            maybeFallbackFromQuickPlay(client);
            return;
        }
        if (worldJoinRequested) return;

        Path triggerFile = client.gameDirectory.toPath().resolve("benchmark").resolve("auto_benchmark.json");
        if (!Files.exists(triggerFile)) return;

        // Read and delete the trigger file immediately so it never fires twice
        try {
            String json = Files.readString(triggerFile);
            Files.delete(triggerFile);

            applyTrigger(JsonParser.parseString(json).getAsJsonObject());
            // Do not disturb interactive camera state until the complete
            // trigger has parsed successfully.
            configureCameraTarget();
            cameraController.stop();
            autoTriggerPending = true;
            quickPlayWaitTicks = 0;
        } catch (Exception e) {
            LOGGER.error("Failed to read auto_benchmark.json", e);
            resetAutoTriggerFields();
            return;
        }

        // Verify the benchmark world exists
        Path worldDir = client.gameDirectory.toPath().resolve("saves").resolve(BENCHMARK_WORLD_NAME);
        if (!Files.isDirectory(worldDir)) {
            LOGGER.warn("Auto-benchmark trigger found but world '{}' does not exist at {}",
                    BENCHMARK_WORLD_NAME, worldDir);
            clearPendingAutoBenchmark();
            return;
        }

        if (client.level != null) {
            LOGGER.info("Auto-benchmark trigger detected in loaded world, waiting for player...");
            return;
        }

        if (quickPlayWorldLoad) {
            LOGGER.info("Benchmark trigger mode '{}' detected, waiting for vanilla QuickPlay to load world '{}'...",
                    autoTriggerMode, BENCHMARK_WORLD_NAME);
            return;
        }

        requestBenchmarkWorldLoad(client, "auto-trigger");
    }

    /**
     * Applies a parsed auto_benchmark.json to the fields of the pending run.
     *
     * <p>The harness owns the fixture layout: the trigger names the world and,
     * when a recording should also keep an authored copy, the absolute
     * directory that copy belongs in. The mod derives neither.</p>
     *
     * @param config the trigger object read from disk
     * @throws IllegalArgumentException if the trigger names no world or its
     *         screenshot schedule is malformed
     */
    void applyTrigger(JsonObject config) {
        benchmarkWorldName = requiredTriggerString(config, "benchmarkWorld");
        autoTriggerMode = config.has("mode")
                ? sanitizeAutoTriggerMode(config.get("mode").getAsString())
                : TRIGGER_MODE_BENCHMARK;
        warmupFrames = config.has("warmupFrames") ? config.get("warmupFrames").getAsInt() : 1200;
        warmupLap = config.has("warmupLap") && config.get("warmupLap").getAsBoolean();
        exitOnComplete = config.has("exitOnComplete") && config.get("exitOnComplete").getAsBoolean();
        postResultExitDelaySeconds = config.has("postResultExitDelaySeconds")
                ? Math.max(0, config.get("postResultExitDelaySeconds").getAsInt())
                : 0;
        exitMode = config.has("exitMode") ? sanitizeExitMode(config.get("exitMode").getAsString()) : "halt";
        benchmarkWorldVersion = config.has("worldVersion")
                ? config.get("worldVersion").getAsString()
                : "";
        cameraStorageKey = config.has("cameraStorageKey")
                ? config.get("cameraStorageKey").getAsString()
                : "";
        String canonicalDirectory = optionalTriggerString(config, "cameraCanonicalDirectory");
        cameraCanonicalDirectory = canonicalDirectory.isEmpty() ? null : Path.of(canonicalDirectory);
        String shotDirectory = optionalTriggerString(config, "shotSetDirectory");
        shotSetDirectory = shotDirectory.isEmpty() ? null : Path.of(shotDirectory);
        cameraPathName = config.has("cameraPath")
                ? config.get("cameraPath").getAsString()
                : DEFAULT_CAMERA_PATH_NAME;
        shotSetName = config.has("shotSet") ? requiredTriggerString(config, "shotSet") : DEFAULT_SHOT_SET;
        shotFilter.clear();
        if (config.has("shots") && config.get("shots").isJsonArray()) {
            for (JsonElement element : config.getAsJsonArray("shots")) {
                shotFilter.add(element.getAsString());
            }
        }
        shotSettle = new StillShotSession.Settle(
                getNonNegativeInt(config, "settleFrames", StillShotSession.Settle.DEFAULT.minFrames()),
                getNonNegativeInt(config, "quietFrames", StillShotSession.Settle.DEFAULT.quietFrames()),
                Math.max(1, getNonNegativeInt(config, "maxSettleFrames", StillShotSession.Settle.DEFAULT.maxFrames())));
        configureScreenshots(config);
        quickPlayWorldLoad = config.has("quickPlayWorldLoad") && config.get("quickPlayWorldLoad").getAsBoolean();
        disableWorldSaving = config.has("disableWorldSaving") && config.get("disableWorldSaving").getAsBoolean();
        if (config.has("recordFrames")) {
            recordFrames = config.get("recordFrames").getAsInt();
        } else {
            recordFrames = -1; // signal to auto-match from path length
        }
    }

    private static String requiredTriggerString(JsonObject config, String key) {
        String value = optionalTriggerString(config, key);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("auto_benchmark.json needs " + key);
        }
        return value;
    }

    private static String optionalTriggerString(JsonObject config, String key) {
        if (!config.has(key) || config.get(key).isJsonNull()) {
            return "";
        }
        return config.get(key).getAsString().trim();
    }

    private void requestBenchmarkWorldLoad(Minecraft client, String reason) {
        if (worldJoinRequested) return;

        LOGGER.info("Auto-benchmark trigger detected, loading world '{}' via {}...", BENCHMARK_WORLD_NAME, reason);
        worldJoinRequested = true;
        quickPlayWorldLoad = false;
        quickPlayWaitTicks = 0;

        try {
            WorldOpenFlows loader = client.createWorldOpenFlows();
            loader.openWorld(BENCHMARK_WORLD_NAME, () -> {
                LOGGER.warn("World load was cancelled");
                clearPendingAutoBenchmark();
            });
        } catch (Exception e) {
            LOGGER.error("Failed to auto-load benchmark world", e);
            clearPendingAutoBenchmark();
        }
    }

    private void clearPendingAutoBenchmark() {
        resetAutoTriggerFields();
        cameraController.clearManagedTarget();
    }

    private void resetAutoTriggerFields() {
        worldJoinRequested = false;
        autoTriggerPending = false;
        quickPlayWorldLoad = false;
        quickPlayWaitTicks = 0;
        disableWorldSaving = false;
        benchmarkWorldName = "";
        benchmarkWorldVersion = "";
        cameraStorageKey = "";
        cameraCanonicalDirectory = null;
        shotSetDirectory = null;
        cameraPathName = DEFAULT_CAMERA_PATH_NAME;
        shotSetName = DEFAULT_SHOT_SET;
        shotFilter.clear();
        shotSettle = StillShotSession.Settle.DEFAULT;
        endStillShots(null);
        autoTriggerMode = TRIGGER_MODE_BENCHMARK;
        pendingFrameTimeIndex = -1;
        finishAfterPendingFrameTime = false;
        resetScreenshotConfig();
    }

    private String describeCurrentScreen(Minecraft client) {
        return client.screen == null ? "none" : client.screen.getClass().getName();
    }

    static boolean isWorldLoadScreenName(String screenSimpleName) {
        return screenSimpleName.contains("LevelLoading") || screenSimpleName.contains("DownloadingTerrain");
    }

    private boolean isWorldLoadScreen(Minecraft client) {
        if (client.screen == null) return false;
        return isWorldLoadScreenName(client.screen.getClass().getSimpleName());
    }

    private void maybeFallbackFromQuickPlay(Minecraft client) {
        if (!quickPlayWorldLoad || worldJoinRequested || client.level != null) return;
        quickPlayWaitTicks++;
        if (quickPlayWaitTicks < QUICKPLAY_FALLBACK_TICKS || isWorldLoadScreen(client)) return;

        LOGGER.warn("QuickPlay did not load benchmark world '{}' after {} ticks; current screen is {}; falling back to direct world load.",
                BENCHMARK_WORLD_NAME, quickPlayWaitTicks, describeCurrentScreen(client));
        requestBenchmarkWorldLoad(client, "QuickPlay fallback");
    }

    /**
     * Called at the start of each frame, before camera update.
     * Checks for auto-trigger and overrides camera position during benchmark.
     */
    public void onFrameStart(PerformanceTimer perfTimer) {
        // If auto-trigger was parsed in onClientTick and world is now loaded, apply it.
        if (state == State.IDLE && autoTriggerPending) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null && client.level != null) {
                autoTriggerPending = false;
                worldJoinRequested = false;
                quickPlayWorldLoad = false;
                quickPlayWaitTicks = 0;

                if (TRIGGER_MODE_PLAY.equals(autoTriggerMode)) {
                    notifyPlayer(client, "Benchmark world loaded: " + benchmarkWorldName
                            + ". Use /rtx camera record/play/seek in game.");
                    LOGGER.info("Benchmark world '{}' loaded for play mode", benchmarkWorldName);
                    autoTriggerMode = TRIGGER_MODE_BENCHMARK;
                    return;
                }

                if (TRIGGER_MODE_STILLS.equals(autoTriggerMode)) {
                    startStillShots(client);
                    return;
                }

                if (!prepareCameraPath()) {
                    notifyPlayer(client, "Cannot start benchmark: " + cameraPreparationError);
                    LOGGER.error("Cannot start benchmark: {}", cameraPreparationError);
                    clearPendingAutoBenchmark();
                    return;
                }
                if (recordFrames < 0) {
                    recordFrames = Math.max(totalPathFrames, 60);
                }
                if (warmupLap && totalPathFrames > 0) {
                    int lapNeeded = warmupLapFrames(totalPathFrames);
                    if (warmupFrames < lapNeeded) {
                        LOGGER.info("warmupLap: extending warmup {} -> {} frames to cover the path at <= {}x",
                                warmupFrames, lapNeeded, WARMUP_LAP_MAX_SPEED);
                        warmupFrames = lapNeeded;
                    }
                }
                LOGGER.info("Auto-trigger starting: warmup={}, record={}, exit={}, world={}, path={}",
                        warmupFrames, recordFrames, exitOnComplete, benchmarkWorldName, cameraPathName);
                disableWorldSavingForBenchmark(client);
                resetBenchmarkStatus();
                state = State.WARMUP;
                currentFrame = 0;
                warmupHeldForLoadScreen = false;
                perfTimer.setBenchmarkMode(true);
                writeBenchmarkStatus("warmup-start", true);
                // This transition frame is the first counted warmup frame, so
                // put it at the held start pose before Minecraft builds its
                // render camera.
                cameraController.holdBenchmarkStart();
            }
            return;
        }

        if (state == State.STILLS) {
            Minecraft client = Minecraft.getInstance();
            StillShot shot = stillShots.current();
            if (shot == null || client.player == null || client.level == null) {
                return;
            }
            if (stillShots.needsApply()) {
                applyStillShotScene(client, shot);
                stillShots.markApplied();
                writeBenchmarkStatus("stills:" + shot.name(), true);
            }
            cameraController.holdStillShot(client, shot);
            return;
        }

        if (state == State.IDLE) return;

        if (state == State.WAITING_FOR_WORLD) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null && client.level != null) {
                LOGGER.info("World loaded, starting warmup ({} frames)", warmupFrames);
                resetBenchmarkStatus();
                state = State.WARMUP;
                currentFrame = 0;
                warmupHeldForLoadScreen = false;
                perfTimer.setBenchmarkMode(true);
                writeBenchmarkStatus("warmup-start", true);
                cameraController.holdBenchmarkStart();
            }
            return;
        }

        // During warmup the camera either holds the first waypoint, so cold
        // first-visit costs land in the recorded window, or with warmupLap sweeps
        // the path once and then settles at the start, so pipeline compiles, first
        // BLAS builds and texture uploads happen before recording.
        if (state == State.WARMUP) {
            int pathFrame = warmupLap && totalPathFrames > 0
                    ? warmupLapPathFrame(currentFrame, warmupFrames, totalPathFrames)
                    : 0;
            cameraController.applyBenchmarkTime(benchmarkCameraTimeMs(pathFrame));
        }

        // Benchmarks use a deterministic virtual 60 Hz camera clock. The path
        // itself is time-based; only the benchmark clock is frame-stepped.
        if (state == State.RECORDING) {
            cameraController.applyBenchmarkTime(benchmarkCameraTimeMs(currentFrame));
        }
    }

    /**
     * Called at the end of each frame, after perfTimer.endFrame().
     * Captures per-frame timing data during recording.
     */
    public void onFrameEnd(PerformanceTimer perfTimer) {
        if (state == State.WARMUP) {
            // Frames rendered under a world-load overlay are nearly free
            // (sub-millisecond) and must not consume the warmup budget: on a
            // slow machine the whole warmup can elapse before terrain renders,
            // and recording then starts on the loading screen, skewing every
            // frame statistic. Hold the counter until the overlay is gone.
            if (isWorldLoadScreen(Minecraft.getInstance())) {
                if (!warmupHeldForLoadScreen) {
                    warmupHeldForLoadScreen = true;
                    LOGGER.info("Holding warmup at frame {}/{}: world-load screen still up ({})",
                            currentFrame, warmupFrames, describeCurrentScreen(Minecraft.getInstance()));
                }
                writeBenchmarkStatus("warmup", false);
                return;
            }
            if (warmupHeldForLoadScreen) {
                warmupHeldForLoadScreen = false;
                LOGGER.info("World-load screen dismissed; resuming warmup at frame {}/{}", currentFrame, warmupFrames);
            }
            currentFrame++;
            writeBenchmarkStatus("warmup", false);
            if (currentFrame >= warmupFrames) {
                LOGGER.info("Warmup complete, recording {} frames", recordFrames);
                emitSessionMarker("recording-start");
                state = State.RECORDING;
                currentFrame = 0;
                perfTimer.setBenchmarkMode(true); // reset accumulators for clean recording
                prepareScreenshotSchedule();
                allocateRecordingArrays(perfTimer);
                writeBenchmarkStatus("recording-start", true);
            }
            return;
        }

        if (state == State.RECORDING) {
            captureFrame(perfTimer);
            pendingFrameTimeIndex = currentFrame;
            currentFrame++;
            writeBenchmarkStatus("recording", false);
            if (currentFrame >= recordFrames) {
                emitSessionMarker("recording-end");
                finishAfterPendingFrameTime = true;
                writeBenchmarkStatus("recording-end", true);
            }
        }

        if (state == State.STILLS) {
            onStillShotFrameEnd();
            return;
        }
    }

    private void waitForProfilersBeforeExit() {
        if (postResultExitDelaySeconds <= 0) {
            return;
        }

        LOGGER.info("Benchmark complete; waiting {}s before JVM exit so profilers can drain...",
                postResultExitDelaySeconds);
        try {
            Thread.sleep(postResultExitDelaySeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Profiler drain wait interrupted", e);
        }
    }

    private String sanitizeExitMode(String mode) {
        return "systemExit".equals(mode) ? "systemExit" : "halt";
    }

    private String sanitizeAutoTriggerMode(String mode) {
        return switch (mode) {
            case TRIGGER_MODE_PLAY, TRIGGER_MODE_STILLS -> mode;
            default -> TRIGGER_MODE_BENCHMARK;
        };
    }

    private void notifyPlayer(Minecraft client, String message) {
        if (client.player != null) {
            client.player.displayClientMessage(Component.literal("[Benchmark] " + message), false);
        }
    }

    private void exitProcess() {
        if ("systemExit".equals(exitMode)) {
            LOGGER.info("exitOnComplete=true, exiting JVM through System.exit(0)...");
            System.out.flush();
            System.err.flush();
            System.exit(0);
            return;
        }

        // Skip Minecraft's slow graceful shutdown (world save + CLIENT_STOPPING
        // listeners + GL cleanup can take 2+ min). Use only when we explicitly
        // choose speed over normal DLL detach/shutdown behavior.
        LOGGER.info("exitOnComplete=true, halting JVM now...");
        System.out.flush();
        System.err.flush();
        Runtime.getRuntime().halt(0);
    }

    /** Start a benchmark run, matching recording length to the camera path. */
    public String start() {
        if (state != State.IDLE) {
            LOGGER.warn("Benchmark already in progress (state={})", state);
            return "Benchmark already in progress (" + getStatus() + ").";
        }
        if (cameraStorageKey != null
                && !cameraStorageKey.isBlank()
                && cameraController.loadedPathName() != null) {
            cameraPathName = cameraController.loadedPathName();
        }
        this.exitOnComplete = false;
        this.exitMode = "halt";
        this.postResultExitDelaySeconds = 0;
        this.disableWorldSaving = false;
        resetScreenshotConfig();
        if (cameraController.state() != CameraController.State.IDLE) {
            return "Cannot start benchmark while " + cameraController.status() + ".";
        }
        configureCameraTarget();
        if (!prepareCameraPath()) {
            return "Cannot start benchmark: " + cameraPreparationError;
        }
        this.warmupFrames = 1200;
        this.warmupLap = false;
        this.recordFrames = Math.max(totalPathFrames, 60);
        state = State.WAITING_FOR_WORLD;
        LOGGER.info("Benchmark started: warmup={}, record={} (from path length {}), path={}",
                warmupFrames, recordFrames, totalPathFrames, cameraPathName);
        return "Benchmark started (" + recordFrames + " deterministic camera samples).";
    }

    /** Abort a running benchmark. */
    public void stop(PerformanceTimer perfTimer) {
        if (state == State.IDLE) return;
        LOGGER.info("Benchmark aborted at frame {}", currentFrame);
        state = State.IDLE;
        pendingFrameTimeIndex = -1;
        finishAfterPendingFrameTime = false;
        perfTimer.setBenchmarkMode(false);
        endStillShots(Minecraft.getInstance());
        writeBenchmarkStatus("aborted", true);
        restoreWorldSavingAfterBenchmark();
    }

    /**
     * Clears active and pending benchmark state when the client leaves a world.
     */
    public void onDisconnect(PerformanceTimer perfTimer) {
        if (state != State.IDLE || autoTriggerPending || worldJoinRequested) {
            LOGGER.info("Resetting benchmark state after disconnect (state={}, pending={}, joinRequested={})",
                    state, autoTriggerPending, worldJoinRequested);
        }
        perfTimer.setBenchmarkMode(false);
        restoreWorldSavingAfterBenchmark();
        endStillShots(Minecraft.getInstance());

        state = State.IDLE;
        currentFrame = 0;
        warmupHeldForLoadScreen = false;
        pendingFrameTimeIndex = -1;
        finishAfterPendingFrameTime = false;
        frameTimeNanos = null;
        sectionTimeNanos = null;
        counterValues = null;
        sectionCount = 0;
        counterCount = 0;
        jfrFlushed = false;
        clearPendingAutoBenchmark();
    }

    private void disableWorldSavingForBenchmark(Minecraft client) {
        if (!disableWorldSaving || worldSavingDisabled) return;

        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            LOGGER.debug("Benchmark requested world-save suppression, but no integrated server is available");
            return;
        }

        previousWorldSavingDisabled.clear();
        for (ServerLevel world : server.getAllLevels()) {
            previousWorldSavingDisabled.put(world, world.noSave);
            world.noSave = true;
        }

        worldSavingDisabled = true;
        LOGGER.info("Disabled integrated-server world saving during benchmark recording");
    }

    private void restoreWorldSavingAfterBenchmark() {
        if (!worldSavingDisabled) {
            disableWorldSaving = false;
            return;
        }

        for (Map.Entry<ServerLevel, Boolean> entry : previousWorldSavingDisabled.entrySet()) {
            entry.getKey().noSave = entry.getValue();
        }
        LOGGER.info("Restored integrated-server world saving after benchmark");

        previousWorldSavingDisabled.clear();
        worldSavingDisabled = false;
        disableWorldSaving = false;
    }

    public boolean isRunning() {
        return state != State.IDLE && state != State.COMPLETE;
    }

    public String getStatus() {
        return switch (state) {
            case IDLE -> "idle";
            case WAITING_FOR_WORLD -> "waiting for world";
            case WARMUP -> "warmup " + currentFrame + "/" + warmupFrames;
            case RECORDING -> "recording " + currentFrame + "/" + recordFrames;
            case STILLS -> stillShots == null
                    ? "still shots"
                    : "still shots " + stillShots.capturedCount() + "/" + stillShots.totalCount();
            case COMPLETE -> "complete";
        };
    }

    public String getTargetStatus() {
        return benchmarkWorldName.isEmpty() ? cameraPathName : benchmarkWorldName + "/" + cameraPathName;
    }

    private void resetBenchmarkStatus() {
        lastStatusHeartbeatMillis = 0L;
        statusSequence = 0L;
    }

    private void writeBenchmarkStatus(String phase, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastStatusHeartbeatMillis < STATUS_HEARTBEAT_INTERVAL_MS) {
            return;
        }
        lastStatusHeartbeatMillis = now;

        Minecraft client = Minecraft.getInstance();
        Path resultsDir = client.gameDirectory.toPath().resolve("benchmark").resolve("results");
        JsonObject status = new JsonObject();
        status.addProperty("schemaVersion", 1);
        status.addProperty("sequence", ++statusSequence);
        status.addProperty("timestampEpochMs", now);
        status.addProperty("state", state.name());
        status.addProperty("phase", phase);
        status.addProperty("currentFrame", currentFrame);
        status.addProperty("warmupFrames", warmupFrames);
        status.addProperty("recordFrames", recordFrames);
        status.addProperty("totalPathFrames", totalPathFrames);
        status.addProperty("cameraPathDurationMs", cameraController.durationMs());
        status.addProperty("cameraClock", "fixed-60hz");
        status.addProperty("pendingFrameTimeIndex", pendingFrameTimeIndex);
        status.addProperty("finishAfterPendingFrameTime", finishAfterPendingFrameTime);
        status.addProperty("benchmarkWorld", benchmarkWorldName);
        status.addProperty("cameraStorageKey", effectiveCameraStorageKey());
        status.addProperty("cameraPath", cameraPathName);

        LocalPlayer player = client.player;
        if (player != null) {
            JsonObject camera = new JsonObject();
            camera.addProperty("x", player.getX());
            camera.addProperty("y", player.getY());
            camera.addProperty("z", player.getZ());
            camera.addProperty("yaw", player.getYRot());
            camera.addProperty("pitch", player.getXRot());
            status.add("camera", camera);
        }

        RtxRemixMod mod = RtxRemixMod.getInstance();
        if (mod != null && mod.getRenderer() != null && mod.getRenderer().getChunkManager() != null) {
            ChunkManager.TerrainDiagnostics terrainDiag =
                    mod.getRenderer().getChunkManager().getTerrainDiagnostics();
            JsonObject terrain = new JsonObject();
            terrain.addProperty("columns", terrainDiag.columns());
            terrain.addProperty("columnsPublished", terrainDiag.columnsPublished());
            terrain.addProperty("columnsWithGeometry", terrainDiag.columnsWithGeometry());
            terrain.addProperty("columnsMissingGeometry", terrainDiag.columnsMissingGeometry());
            terrain.addProperty("missingGeometrySections", terrainDiag.missingGeometrySections());
            terrain.addProperty("retainedPages", terrainDiag.retainedPages());
            terrain.addProperty("knownSections", terrainDiag.knownSections());
            terrain.addProperty("pendingBuilds", terrainDiag.pendingBuilds());
            terrain.addProperty("readyMeshes", terrainDiag.readyMeshes());
            terrain.addProperty("captureQueue", terrainDiag.captureQueue());
            terrain.addProperty("pendingMeshBytes", terrainDiag.pendingMeshBytes());
            status.add("terrain", terrain);
        }

        Path output = resultsDir.resolve(STATUS_FILE_NAME);
        Path temp = resultsDir.resolve(STATUS_FILE_NAME + ".tmp");
        try {
            Files.createDirectories(resultsDir);
            Gson gson = new GsonBuilder().create();
            try (Writer writer = Files.newBufferedWriter(temp)) {
                gson.toJson(status, writer);
            }
            moveBenchmarkStatus(temp, output);
        } catch (IOException e) {
            LOGGER.warn("Failed to write benchmark status heartbeat: {}", e.toString());
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
        }
    }

    private static void moveBenchmarkStatus(Path temp, Path output) throws IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                try {
                    Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException e) {
                lastError = e;
                if (attempt == 3) {
                    break;
                }
                try {
                    Thread.sleep(2L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw lastError;
    }

    private boolean prepareCameraPath() {
        if (!cameraController.loadForBenchmark(cameraPathName)) {
            totalPathFrames = 0;
            cameraPreparationError = cameraController.lastError();
            return false;
        }

        long durationMs = cameraController.durationMs();
        if (!isBenchmarkCameraDurationSupported(durationMs)) {
            totalPathFrames = 0;
            cameraPreparationError = "Camera '" + cameraPathName + "' is "
                    + durationMs + " ms long; deterministic 60 Hz benchmarks support at most "
                    + MAX_BENCHMARK_CAMERA_DURATION_MS + " ms.";
            return false;
        }

        totalPathFrames = benchmarkFrameCount(durationMs);
        cameraPreparationError = "";
        return true;
    }

    /**
     * Points the camera controller at the fixture storage the trigger named.
     *
     * <p>A run with no storage key has no fixture, so the controller falls back
     * to its own per-world storage. That is the manual {@code /rtx benchmark}
     * case.</p>
     */
    private void configureCameraTarget() {
        String storageKey = effectiveCameraStorageKey();
        if (storageKey.isEmpty()) {
            cameraController.clearManagedTarget();
            return;
        }
        cameraController.setManagedTarget(storageKey, cameraCanonicalDirectory, shotSetDirectory);
    }

    private String effectiveCameraStorageKey() {
        if (cameraStorageKey != null && !cameraStorageKey.isBlank()) {
            return cameraStorageKey;
        }
        return benchmarkWorldName.isEmpty()
                ? ""
                : CameraController.fixtureName(benchmarkWorldName, benchmarkWorldVersion);
    }

    /** The canonical fixture directory the trigger named, or null when it named none. */
    Path cameraCanonicalDirectory() {
        return cameraCanonicalDirectory;
    }

    /** The world the trigger named, or an empty string for a manual benchmark. */
    String benchmarkWorldName() {
        return benchmarkWorldName;
    }

    Path shotSetDirectory() {
        return shotSetDirectory;
    }

    String autoTriggerMode() {
        return autoTriggerMode;
    }

    String shotSetName() {
        return shotSetName;
    }

    List<String> shotFilter() {
        return List.copyOf(shotFilter);
    }

    StillShotSession.Settle shotSettle() {
        return shotSettle;
    }

    static int benchmarkFrameCount(long durationMs) {
        long clampedDurationMs = Math.max(0L, durationMs);
        long maxDurationMs = (Integer.MAX_VALUE - 1L) * 1_000L / BENCHMARK_CAMERA_HZ;
        if (clampedDurationMs >= maxDurationMs) {
            return Integer.MAX_VALUE;
        }
        long frameCount = (clampedDurationMs * BENCHMARK_CAMERA_HZ + 999L) / 1_000L + 1L;
        return (int) Math.max(2L, frameCount);
    }

    static boolean isBenchmarkCameraDurationSupported(long durationMs) {
        return durationMs >= 0L && durationMs <= MAX_BENCHMARK_CAMERA_DURATION_MS;
    }

    static long benchmarkCameraTimeMs(int frame) {
        return CameraController.fixed60TimeMs(frame);
    }

    /** Warmup frames a lap needs: the whole path at the capped sweep speed, then the settle window. */
    static int warmupLapFrames(int totalPathFrames) {
        return (int) Math.ceil(totalPathFrames / WARMUP_LAP_MAX_SPEED) + WARMUP_SETTLE_FRAMES;
    }

    /**
     * Path frame the warmup lap samples at {@code warmupFrame}. The sweep spreads the path over
     * the warmup frames left after the settle window, no faster than
     * {@link #WARMUP_LAP_MAX_SPEED}, and returns to frame 0 for the settle window itself.
     */
    static int warmupLapPathFrame(int warmupFrame, int warmupFrames, int totalPathFrames) {
        int settleFrames = Math.min(WARMUP_SETTLE_FRAMES, warmupFrames / 4);
        int lapFrames = warmupFrames - settleFrames;
        if (warmupFrame >= lapFrames) {
            return 0;
        }
        double speed = Math.min(WARMUP_LAP_MAX_SPEED, (double) totalPathFrames / Math.max(1, lapFrames));
        return (int) Math.min((long) (warmupFrame * speed), totalPathFrames - 1L);
    }

    public void onMinecraftRenderFrameEnd(long fullFrameNanos, PerformanceTimer perfTimer) {
        if (pendingFrameTimeIndex >= 0 && pendingFrameTimeIndex < recordFrames) {
            int completedFrame = pendingFrameTimeIndex;
            frameTimeNanos[completedFrame] = fullFrameNanos;
            pendingFrameTimeIndex = -1;
            captureScreenshotIfNeeded(completedFrame);
        }

        if (state == State.STILLS) {
            completePendingStillShot();
            return;
        }

        if (finishAfterPendingFrameTime) {
            finishAfterPendingFrameTime = false;
            finishBenchmark(perfTimer);
        }
    }

    private void finishBenchmark(PerformanceTimer perfTimer) {
        state = State.COMPLETE;
        perfTimer.setBenchmarkMode(false);
        writeBenchmarkStatus("finalizing", true);
        writeResults(perfTimer);
        logSummary(perfTimer);
        flushJfrRecording();  // JFR data on disk BEFORE any exit path
        writeBenchmarkStatus("complete", true);
        if (exitOnComplete) {
            waitForProfilersBeforeExit();
            exitProcess();
        }
        restoreWorldSavingAfterBenchmark();
    }

    // --- Still shots ---

    /**
     * Starts a capture pass over the trigger's shot set. The pass borrows the
     * benchmark's world join, status heartbeat, and exit path; the schedule is
     * {@link StillShotSession}'s and the poses are the camera controller's.
     */
    private void startStillShots(Minecraft client) {
        List<StillShot> shots = cameraController.loadStillShots(shotSetName);
        if (shots == null) {
            rejectStillShots(client, cameraController.lastError());
            return;
        }
        if (!shotFilter.isEmpty()) {
            Set<String> wanted = new LinkedHashSet<>(shotFilter);
            shots.forEach(shot -> wanted.remove(shot.name()));
            if (!wanted.isEmpty()) {
                rejectStillShots(client,
                        "Shot set '" + shotSetName + "' has no shot named " + String.join(", ", wanted));
                return;
            }
            shots.removeIf(shot -> !shotFilter.contains(shot.name()));
        }
        stillShots = new StillShotSession(shots, shotSettle);
        pendingStillShotWrites.set(0);
        stillShotWriteFailed.set(false);
        disableWorldSavingForBenchmark(client);
        resetBenchmarkStatus();
        state = State.STILLS;
        currentFrame = 0;
        hudHiddenBeforeStills = client.options.hideGui;
        client.options.hideGui = true;
        LOGGER.info("Still shot capture starting: {} shot(s) from set '{}', settle {}",
                shots.size(), shotSetName, shotSettle);
        writeBenchmarkStatus("stills-start", true);
    }

    /**
     * A capture pass that cannot start publishes {@code stills-failed} so the
     * harness stops waiting, then exits as a completed pass would.
     */
    private void rejectStillShots(Minecraft client, String reason) {
        notifyPlayer(client, "Cannot capture still shots: " + reason);
        LOGGER.error("Cannot capture still shots: {}", reason);
        writeBenchmarkStatus("stills-failed", true);
        if (exitOnComplete) {
            exitProcess();
        }
        clearPendingAutoBenchmark();
    }

    /**
     * One-time scene setup for a shot: the server-side teleport that loads the
     * chunks around it, and the weather, path-tracing, and cloud state the
     * mark saw. The game mode is left alone, as a benchmark leaves it: the
     * pose was reachable when it was marked, and the per-frame hold keeps it
     * against gravity. Time of day is reapplied with the pose every frame.
     */
    private void applyStillShotScene(Minecraft client, StillShot shot) {
        cameraController.teleportToStillShot(client, shot);
        MinecraftServer server = client.getSingleplayerServer();
        if (server != null) {
            boolean raining = shot.weather() != StillShot.Weather.CLEAR;
            boolean thundering = shot.weather() == StillShot.Weather.THUNDER;
            // A day of each so the weather cannot flip while the shot settles.
            server.execute(() -> server.overworld().setWeatherParameters(raining ? 0 : WEATHER_HOLD_TICKS,
                    raining ? WEATHER_HOLD_TICKS : 0, raining, thundering));
        }
        if (RtxRemixMod.isRtxEnabled() != shot.rtxEnabled()) {
            RtxRemixMod.setRtxEnabled(shot.rtxEnabled());
        }
        RtxRemixMod mod = RtxRemixMod.getInstance();
        if (mod != null && mod.getRenderer() != null && mod.getRenderer().getCloudRenderer() != null) {
            mod.getRenderer().getCloudRenderer().setProceduralClouds(shot.proceduralClouds());
        }
        LOGGER.info("Still shot '{}': teleported to {} {} {}, time {}, {}, rtx {}, procedural clouds {}",
                shot.name(), shot.x(), shot.y(), shot.z(), shot.timeOfDay(),
                shot.weather().wireName(), shot.rtxEnabled(), shot.proceduralClouds());
    }

    /**
     * Decides whether this frame's shot is settled. The capture itself waits
     * for {@link #onMinecraftRenderFrameEnd}: this hook runs inside the Remix
     * submit, before the path-traced image has landed in Minecraft's
     * framebuffer, so reading the framebuffer here would grab the wrong frame.
     */
    private void onStillShotFrameEnd() {
        Minecraft client = Minecraft.getInstance();
        StillShot shot = stillShots.current();
        if (shot == null) {
            return;
        }
        captureStillShotAtFrameEnd = stillShots.onFrameEnd(isWorldLoadScreen(client), terrainIdle());
        writeBenchmarkStatus("stills:" + shot.name(), false);
    }

    /**
     * Captures the settled shot and advances. The pass finishes only once
     * every PNG write has landed: {@link Screenshot} encodes on the IO
     * worker and reports back later, and a halt on completion would otherwise
     * cut the last file short.
     */
    private void completePendingStillShot() {
        Minecraft client = Minecraft.getInstance();
        if (captureStillShotAtFrameEnd) {
            captureStillShotAtFrameEnd = false;
            StillShot shot = stillShots.current();
            if (shot != null) {
                saveStillShot(client, shot);
                stillShots.advance();
            }
        }
        if (stillShots.isDone() && pendingStillShotWrites.get() == 0) {
            finishStillShots(client);
        }
    }

    /**
     * Whether terrain had nothing in flight this frame: no builds pending or
     * awaiting publish, an empty capture queue, every known column published
     * with geometry, and no column arrived since the previous frame. The last
     * test is what tells "the server is still streaming chunks" from "done":
     * between two chunk packets the client pipeline is briefly empty.
     */
    private boolean terrainIdle() {
        RtxRemixMod mod = RtxRemixMod.getInstance();
        if (mod == null || mod.getRenderer() == null || mod.getRenderer().getChunkManager() == null) {
            return true;
        }
        ChunkManager.TerrainDiagnostics terrain = mod.getRenderer().getChunkManager().getTerrainDiagnostics();
        boolean columnsChanged = terrain.columns() != stillShotColumns;
        stillShotColumns = terrain.columns();
        return !columnsChanged
                && terrain.pendingBuilds() == 0
                && terrain.readyMeshes() == 0
                && terrain.captureQueue() == 0
                && terrain.columnsMissingGeometry() == 0;
    }

    private void saveStillShot(Minecraft client, StillShot shot) {
        Path benchmarkDir = client.gameDirectory.toPath().resolve("benchmark");
        try {
            Files.createDirectories(benchmarkDir);
        } catch (IOException e) {
            LOGGER.warn("Failed to create benchmark directory", e);
            stillShotWriteFailed.set(true);
            return;
        }
        RtxRemixMod mod = RtxRemixMod.getInstance();
        LOGGER.info("Still shot '{}' settled after {} frames; capturing (terrain {}; screen {}, game mode {})",
                shot.name(),
                stillShots.framesSinceApply(),
                mod != null && mod.getRenderer() != null && mod.getRenderer().getChunkManager() != null
                        ? mod.getRenderer().getChunkManager().getTerrainDiagnostics()
                        : "unavailable",
                describeCurrentScreen(client),
                client.gameMode == null ? "unknown" : client.gameMode.getPlayerMode());
        Path written = benchmarkDir.resolve("screenshots").resolve(shot.name() + ".png");
        pendingStillShotWrites.incrementAndGet();
        Screenshot.grab(
                benchmarkDir.toFile(),
                shot.name() + ".png",
                client.getMainRenderTarget(),
                text -> {
                    // Runs on the IO worker after the encode; the file is the
                    // verdict, not the message text.
                    LOGGER.info("Screenshot: {}", text.getString());
                    if (!Files.isRegularFile(written)) {
                        stillShotWriteFailed.set(true);
                    }
                    pendingStillShotWrites.decrementAndGet();
                }
        );
    }

    private void finishStillShots(Minecraft client) {
        state = State.COMPLETE;
        boolean failed = stillShotWriteFailed.get();
        LOGGER.info("Still shot capture {}: {} shot(s)", failed ? "finished with a failed write" : "complete",
                stillShots.totalCount());
        endStillShots(client);
        writeBenchmarkStatus(failed ? "stills-failed" : "stills-complete", true);
        if (exitOnComplete) {
            exitProcess();
        }
        restoreWorldSavingAfterBenchmark();
    }

    /** Hands the HUD back and drops the session; shared by completion, abort, and disconnect. */
    private void endStillShots(Minecraft client) {
        if (stillShots == null) {
            return;
        }
        if (client != null && client.options != null) {
            client.options.hideGui = hudHiddenBeforeStills;
        }
        stillShots = null;
        stillShotColumns = -1;
        captureStillShotAtFrameEnd = false;
    }

    // --- Data capture ---

    private void allocateRecordingArrays(PerformanceTimer perfTimer) {
        sectionCount = perfTimer.getSectionCount();
        counterCount = perfTimer.getCounterCount();
        frameTimeNanos = new long[recordFrames];
        sectionTimeNanos = new long[sectionCount][recordFrames];
        counterValues = new long[counterCount][recordFrames];
        pendingFrameTimeIndex = -1;
        finishAfterPendingFrameTime = false;
    }

    private void captureFrame(PerformanceTimer perfTimer) {
        if (currentFrame >= recordFrames) return;
        for (int i = 0; i < sectionCount; i++) {
            sectionTimeNanos[i][currentFrame] = perfTimer.getSectionTimeNanos(i);
        }
        for (int i = 0; i < counterCount; i++) {
            counterValues[i][currentFrame] = perfTimer.getCounterValue(i);
        }
    }

    // --- Screenshots ---

    private void resetScreenshotConfig() {
        screenshotFrameCount = 5;
        screenshotInterval = 0;
        explicitScreenshotFrames.clear();
        scheduledScreenshotFrames.clear();
        capturedScreenshotFrames.clear();
    }

    private void configureScreenshots(JsonObject config) {
        resetScreenshotConfig();

        if (config.has("screenshots") && config.get("screenshots").isJsonObject()) {
            JsonObject screenshots = config.getAsJsonObject("screenshots");
            screenshotFrameCount = getNonNegativeInt(screenshots, "count", screenshotFrameCount);
            screenshotInterval = getNonNegativeInt(screenshots, "interval", screenshotInterval);
            readScreenshotFrames(screenshots.get("frames"));
        }

        screenshotFrameCount = getNonNegativeInt(config, "screenshotFrameCount", screenshotFrameCount);
        screenshotFrameCount = getNonNegativeInt(config, "screenshotCount", screenshotFrameCount);
        screenshotInterval = getNonNegativeInt(config, "screenshotInterval", screenshotInterval);
        readScreenshotFrames(config.get("screenshotFrames"));
    }

    private int getNonNegativeInt(JsonObject config, String key, int defaultValue) {
        if (!config.has(key) || config.get(key).isJsonNull()) {
            return defaultValue;
        }
        return Math.max(0, config.get(key).getAsInt());
    }

    private void readScreenshotFrames(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException("screenshotFrames must be an array");
        }
        for (JsonElement frameElement : element.getAsJsonArray()) {
            int frame = frameElement.getAsInt();
            if (frame < 0) {
                throw new IllegalArgumentException("screenshot frame numbers must be zero or greater");
            }
            explicitScreenshotFrames.add(frame);
        }
    }

    private void prepareScreenshotSchedule() {
        scheduledScreenshotFrames.clear();
        capturedScreenshotFrames.clear();
        if (recordFrames <= 0) {
            return;
        }

        int evenlySpacedCount = Math.min(screenshotFrameCount, recordFrames);
        if (evenlySpacedCount == 1) {
            scheduledScreenshotFrames.add(recordFrames / 2);
        } else if (evenlySpacedCount > 1) {
            for (int i = 0; i < evenlySpacedCount; i++) {
                int frame = (int) Math.round(i * (recordFrames - 1) / (double) (evenlySpacedCount - 1));
                scheduledScreenshotFrames.add(frame);
            }
        }

        if (screenshotInterval > 0) {
            for (int frame = 0; frame < recordFrames; frame += screenshotInterval) {
                scheduledScreenshotFrames.add(frame);
            }
        }

        for (int frame : explicitScreenshotFrames) {
            if (frame < recordFrames) {
                scheduledScreenshotFrames.add(frame);
            } else {
                LOGGER.warn("Ignoring screenshot frame {} outside recording range 0..{}",
                        frame, recordFrames - 1);
            }
        }

        if (!scheduledScreenshotFrames.isEmpty()) {
            LOGGER.info("Benchmark screenshots scheduled at recording frames {}", scheduledScreenshotFrames);
        }
    }

    private JsonArray screenshotFrameArray(Collection<Integer> frames) {
        JsonArray array = new JsonArray();
        for (int frame : frames) {
            array.add(frame);
        }
        return array;
    }

    private void captureScreenshotIfNeeded(int frame) {
        if (!scheduledScreenshotFrames.contains(frame)) return;
        if (!capturedScreenshotFrames.add(frame)) return;

        Minecraft client = Minecraft.getInstance();
        // saveScreenshot() creates a "screenshots" subdirectory under the given directory,
        // so we pass "run/benchmark" and it writes to "run/benchmark/screenshots/"
        Path benchmarkDir = client.gameDirectory.toPath().resolve("benchmark");
        try {
            Files.createDirectories(benchmarkDir);
        } catch (IOException e) {
            LOGGER.warn("Failed to create benchmark directory", e);
            return;
        }

        String filename = String.format("frame_%04d.png", frame);
        Screenshot.grab(
                benchmarkDir.toFile(),
                filename,
                client.getMainRenderTarget(),
                text -> LOGGER.info("Screenshot: {}", text.getString())
        );
    }

    // --- Results output ---

    /** One value per recorded frame, in capture order. */
    private JsonArray frameSeries(long[] values) {
        JsonArray series = new JsonArray();
        for (int f = 0; f < recordFrames; f++) series.add(values[f]);
        return series;
    }

    private void writeResults(PerformanceTimer perfTimer) {
        Minecraft client = Minecraft.getInstance();
        Path resultsDir = client.gameDirectory.toPath().resolve("benchmark").resolve("results");
        try {
            Files.createDirectories(resultsDir);
        } catch (IOException e) {
            LOGGER.error("Failed to create results directory", e);
            return;
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = gitCommit + "_" + timestamp + ".json";
        Path outputFile = resultsDir.resolve(filename);

        JsonObject root = new JsonObject();

        // Metadata
        JsonObject metadata = new JsonObject();
        metadata.addProperty("gitCommit", gitCommit);
        metadata.addProperty("gitBranch", gitBranch);
        metadata.addProperty("timestamp", LocalDateTime.now().toString());
        metadata.addProperty("modVersion", RtxRemixMod.getModVersion());
        metadata.addProperty("warmupFrames", warmupFrames);
        metadata.addProperty("warmupLap", warmupLap);
        metadata.addProperty("recordFrames", recordFrames);
        metadata.addProperty("benchmarkWorld", benchmarkWorldName);
        metadata.addProperty("cameraStorageKey", effectiveCameraStorageKey());
        metadata.addProperty("cameraPath", cameraPathName);
        metadata.addProperty("cameraPathDurationMs", cameraController.durationMs());
        metadata.addProperty("cameraClock", "fixed-60hz");
        metadata.addProperty("cameraSampleRateHz", BENCHMARK_CAMERA_HZ);
        metadata.addProperty(
                "tracyJavaMaxDepth",
                System.getProperty("rtxremix.profile.tracyJavaMaxDepth", "unlimited"));
        metadata.addProperty("postResultExitDelaySeconds", postResultExitDelaySeconds);
        metadata.addProperty("benchmarkExitMode", exitMode);
        metadata.addProperty("frameTimeSource", "MinecraftClient.render");
        metadata.addProperty("screenshotFrameCount", screenshotFrameCount);
        metadata.addProperty("screenshotInterval", screenshotInterval);
        metadata.add("screenshotFrames", screenshotFrameArray(scheduledScreenshotFrames));
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null && mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT) != null) {
            metadata.addProperty("worldName", mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).getParent().getFileName().toString());
        }
        // The runtime's version, when the launcher recorded one beside the results.
        try {
            Path dxvkVersionFile = mc.gameDirectory.toPath().resolve("benchmark").resolve("dxvk_version.txt");
            if (Files.exists(dxvkVersionFile)) {
                metadata.addProperty("dxvkRemixVersion", Files.readString(dxvkVersionFile).trim());
            }
        } catch (IOException e) {
            LOGGER.debug("Could not read the dxvk-remix version file", e);
        }
        metadata.addProperty("resolution", mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight());
        metadata.addProperty("windowResolution", mc.getWindow().getScreenWidth() + "x" + mc.getWindow().getScreenHeight());
        metadata.addProperty("fullscreen", mc.getWindow().isFullscreen());
        metadata.addProperty("monitorAvailable", mc.getWindow().findBestMonitor() != null);
        metadata.addProperty("renderDistance", mc.options.renderDistance().get());
        metadata.addProperty("simulationDistance", mc.options.simulationDistance().get());
        metadata.addProperty("fov", mc.options.fov().get());
        metadata.addProperty("graphicsMode", mc.options.graphicsMode().get().toString());
        metadata.addProperty("entityDistanceScaling", mc.options.entityDistanceScaling().get());
        root.add("metadata", metadata);

        // System info
        JsonObject system = new JsonObject();
        try {
            system.addProperty("gpu", GL11.glGetString(GL11.GL_RENDERER));
            system.addProperty("gpuVendor", GL11.glGetString(GL11.GL_VENDOR));
            system.addProperty("glVersion", GL11.glGetString(GL11.GL_VERSION));
        } catch (Exception e) {
            system.addProperty("gpu", "unknown");
        }
        system.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        system.addProperty("cpu", getCpuName());
        system.addProperty("javaVersion", System.getProperty("java.version"));
        Runtime rt = Runtime.getRuntime();
        system.addProperty("availableProcessors", rt.availableProcessors());
        system.addProperty("maxHeapMb", rt.maxMemory() / (1024 * 1024));
        system.addProperty("totalSystemMemoryMb", getSystemMemoryMb());
        root.add("system", system);

        // Summary
        JsonObject summary = new JsonObject();
        SampleStats frame = SampleStats.of(frameTimeNanos, recordFrames);
        double avgNanos = frame.avgNanos();

        summary.addProperty("avgFrameTimeMs", round(avgNanos / 1_000_000.0));
        summary.addProperty("minFrameTimeMs", round(frame.minNanos() / 1_000_000.0));
        summary.addProperty("maxFrameTimeMs", round(frame.maxNanos() / 1_000_000.0));
        // p1 names the slowest 1%, which is the 99th percentile.
        summary.addProperty("p1FrameTimeMs", round(frame.p99Nanos() / 1_000_000.0));
        summary.addProperty("avgFps", round(1_000_000_000.0 / avgNanos));
        summary.addProperty("stddevFrameTimeMs", round(frame.stddevNanos() / 1_000_000.0));
        summary.addProperty("cvPct", round(frame.cvPct()));

        // Per-section summary
        JsonObject sections = new JsonObject();
        for (int i = 0; i < sectionCount; i++) {
            SampleStats stats = SampleStats.of(sectionTimeNanos[i], recordFrames);

            JsonObject sec = new JsonObject();
            sec.addProperty("avgMs", round(stats.avgNanos() / 1_000_000.0));
            sec.addProperty("pct", round(stats.pctOfFrame(avgNanos)));
            sec.addProperty("minMs", round(stats.minNanos() / 1_000_000.0));
            sec.addProperty("p50Ms", round(stats.p50Nanos() / 1_000_000.0));
            sec.addProperty("p95Ms", round(stats.p95Nanos() / 1_000_000.0));
            sec.addProperty("p99Ms", round(stats.p99Nanos() / 1_000_000.0));
            sec.addProperty("maxMs", round(stats.maxNanos() / 1_000_000.0));
            sections.add(perfTimer.getSectionName(i), sec);
        }
        summary.add("sections", sections);

        // Per-counter summary
        JsonObject counters = new JsonObject();
        for (int i = 0; i < counterCount; i++) {
            long total = 0;
            for (int f = 0; f < recordFrames; f++) total += counterValues[i][f];
            JsonObject ctr = new JsonObject();
            ctr.addProperty("avg", round(total / (double) recordFrames));
            counters.add(perfTimer.getCounterName(i), ctr);
        }
        summary.add("counters", counters);
        root.add("summary", summary);

        // Per-frame raw data. The section and counter traces hold the same
        // samples the summaries above reduce, so a spike in perFrame can be
        // attributed to the section that grew and the counters that moved.
        root.add("perFrame", frameSeries(frameTimeNanos));

        JsonObject perFrameSections = new JsonObject();
        for (int i = 0; i < sectionCount; i++) {
            perFrameSections.add(perfTimer.getSectionName(i), frameSeries(sectionTimeNanos[i]));
        }
        root.add("perFrameSections", perFrameSections);

        JsonObject perFrameCounters = new JsonObject();
        for (int i = 0; i < counterCount; i++) {
            perFrameCounters.add(perfTimer.getCounterName(i), frameSeries(counterValues[i]));
        }
        root.add("perFrameCounters", perFrameCounters);

        // Publish the result only after JFR is safely on disk. The benchmark
        // harness treats this JSON file as the success signal and may kill the
        // JVM soon after it appears.
        flushJfrRecording();

        // Write JSON
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (Writer writer = Files.newBufferedWriter(outputFile)) {
            gson.toJson(root, writer);
            LOGGER.info("Benchmark results written to: {}", outputFile);
        } catch (IOException e) {
            LOGGER.error("Failed to write benchmark results", e);
        }
    }

    private void logSummary(PerformanceTimer perfTimer) {
        SampleStats frame = SampleStats.of(frameTimeNanos, recordFrames);
        double avgNanos = frame.avgNanos();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("\n=== Benchmark Complete (%d frames) ===\n", recordFrames));
        sb.append(String.format("  Branch:   %s (%s)\n", gitBranch, gitCommit));
        sb.append(String.format("  GPU:      %s\n", GL11.glGetString(GL11.GL_RENDERER)));
        sb.append(String.format("  CPU:      %s\n", getCpuName()));
        sb.append(String.format("  Memory:   %d MB | Heap: %d MB\n", getSystemMemoryMb(), Runtime.getRuntime().maxMemory() / (1024 * 1024)));
        sb.append(String.format("  World:    %s | Path: %s | Warmup: %d frames\n",
                benchmarkWorldName, cameraPathName, warmupFrames));
        sb.append(String.format("\n  %-20s %10s %10s %10s %8s\n", "", "avg", "min", "max", ""));
        sb.append(String.format("  %-20s %9.2fms %9.2fms %9.2fms\n",
                "Frame time",
                avgNanos / 1_000_000.0,
                frame.minNanos() / 1_000_000.0,
                frame.maxNanos() / 1_000_000.0));
        sb.append(String.format("  %-20s %9.1f   %9.1f   %9.1f\n",
                "FPS",
                1_000_000_000.0 / avgNanos,
                1_000_000_000.0 / frame.maxNanos(),
                1_000_000_000.0 / frame.minNanos()));
        sb.append(String.format("  %-20s %9.2fms (99th percentile)\n",
                "P1 frame time",
                frame.p99Nanos() / 1_000_000.0));
        double stddevMs = frame.stddevNanos() / 1_000_000.0;
        double cvPct = frame.cvPct();
        sb.append(String.format("  %-20s %9.2fms (CV: %.1f%%)\n", "Stddev", stddevMs, cvPct));

        sb.append("\n  Sections:              avg       p50       p99       max\n");
        for (int i = 0; i < sectionCount; i++) {
            SampleStats stats = SampleStats.of(sectionTimeNanos[i], recordFrames);
            sb.append(String.format("  %-20s %7.2fms %7.2fms %7.2fms %7.2fms  (%5.1f%%)\n",
                    perfTimer.getSectionName(i),
                    stats.avgNanos() / 1_000_000.0,
                    stats.p50Nanos() / 1_000_000.0,
                    stats.p99Nanos() / 1_000_000.0,
                    stats.maxNanos() / 1_000_000.0,
                    stats.pctOfFrame(avgNanos)));
        }

        sb.append("\n  Counters:\n");
        for (int i = 0; i < counterCount; i++) {
            long total = 0, min = Long.MAX_VALUE, max = 0;
            for (int f = 0; f < recordFrames; f++) {
                long v = counterValues[i][f];
                total += v;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            sb.append(String.format("  %-20s avg=%-8.1f min=%-6d max=%-6d\n",
                    perfTimer.getCounterName(i),
                    total / (double) recordFrames,
                    min, max));
        }

        sb.append("==========================================");
        LOGGER.info(sb.toString());
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * Nearest-rank percentile over an ascending-sorted sample array. Truncating index with no
     * interpolation, matching the frame-time percentile this replaced.
     */
    private static long percentile(long[] sortedNanos, double fraction) {
        int index = Math.min((int) (sortedNanos.length * fraction), sortedNanos.length - 1);
        return sortedNanos[index];
    }

    /**
     * Distribution of one series of per-frame samples, used for both whole frames and
     * individual sections. Shape matters more than the mean here: a section can hold a flat
     * average while moving a fifth of frames into a slow band.
     */
    private record SampleStats(double avgNanos, long minNanos, long maxNanos,
                               long p50Nanos, long p95Nanos, long p99Nanos,
                               double stddevNanos) {

        /** Sorts a copy: the caller's array stays in capture order for the perFrame trace. */
        static SampleStats of(long[] samples, int count) {
            long[] sorted = Arrays.copyOf(samples, count);
            Arrays.sort(sorted);
            long total = 0;
            for (int i = 0; i < count; i++) total += sorted[i];
            double avg = total / (double) count;
            double variance = 0;
            for (int i = 0; i < count; i++) {
                double diff = sorted[i] - avg;
                variance += diff * diff;
            }
            return new SampleStats(avg, sorted[0], sorted[count - 1],
                    percentile(sorted, 0.50), percentile(sorted, 0.95), percentile(sorted, 0.99),
                    Math.sqrt(variance / count));
        }

        double pctOfFrame(double avgFrameNanos) {
            return avgFrameNanos > 0 ? (avgNanos / avgFrameNanos) * 100.0 : 0;
        }

        double cvPct() {
            return avgNanos > 0 ? (stddevNanos / avgNanos) * 100.0 : 0;
        }
    }

    /** Reads CPU model name via WMIC. */
    private static String getCpuName() {
        try {
            ProcessBuilder pb = new ProcessBuilder("wmic", "cpu", "get", "Name", "/value");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            for (String line : output.split("\n")) {
                line = line.trim();
                if (line.startsWith("Name=")) {
                    return line.substring(5).trim();
                }
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.debug("Could not read the CPU name from wmic", e);
        }
        return System.getProperty("os.arch");
    }

    /** Reads total physical memory via Windows Management Instrumentation, or -1 on failure. */
    private static long getSystemMemoryMb() {
        try {
            ProcessBuilder pb = new ProcessBuilder("wmic", "ComputerSystem", "get", "TotalPhysicalMemory", "/value");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            for (String line : output.split("\n")) {
                line = line.trim();
                if (line.startsWith("TotalPhysicalMemory=")) {
                    return Long.parseLong(line.split("=")[1].trim()) / (1024 * 1024);
                }
            }
        } catch (IOException | InterruptedException | NumberFormatException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.debug("Could not read the total physical memory from wmic", e);
        }
        return -1;
    }

    // --- Session markers and JFR flush ---

    @Name("minecraft.Benchmark.SessionMarker")
    @Label("Benchmark Session Marker")
    static class SessionMarkerEvent extends Event {
        @Label("Marker")
        String marker;
    }

    /**
     * Emits a Tracy message/marker and a JFR event with the given name so post-hoc tools
     * can window to the recording range.
     */
    void emitSessionMarker(String marker) {
        try {
            ProfileScope.markTracyZone("benchmark." + marker);
            ProfileScope.message("benchmark." + marker);
        } catch (Throwable ignored) {
            // Tracy not available (e.g., unit test without d3d9.dll): ignore.
        }
        try {
            SessionMarkerEvent e = new SessionMarkerEvent();
            e.marker = marker;
            e.commit();
        } catch (Throwable ignored) {
            // No JFR recording active: ignore.
        }
    }

    /**
     * Flushes the active JFR recording to its configured file so that a
     * subsequent force-kill cannot lose the data. Safe to call when no
     * recording is active (no-op).
     */
    void flushJfrRecording() {
        if (jfrFlushed) return;

        try {
            Path configuredPath = configuredJfrPath();
            for (Recording r : FlightRecorder.getFlightRecorder().getRecordings()) {
                Path dest = configuredPath != null ? configuredPath : r.getDestination();
                if (dest != null) {
                    Path parent = dest.toAbsolutePath().getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    if (r.getState() == RecordingState.RUNNING) {
                        r.stop();
                    }
                    r.dump(dest);
                    LOGGER.info("JFR recording flushed to {}", dest);
                    jfrFlushed = true;
                    return;
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("JFR flush failed (may be expected if no recording active)", t);
        }
    }

    private Path configuredJfrPath() {
        String path = System.getProperty("rtxremix.benchmark.jfrPath", "").trim();
        if (path.isEmpty()) return null;
        return Path.of(path);
    }

}

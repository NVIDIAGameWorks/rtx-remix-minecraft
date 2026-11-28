/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.profiling;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

import com.nvidia.rtxremix.RemixAPI;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;

/**
 * Shared Java profiling scope for Java phases mirrored into Tracy and/or JFR.
 */
public final class ProfileScope implements AutoCloseable {

    private static final boolean BENCHMARK_JFR_ENABLED =
            !System.getProperty("rtxremix.benchmark.jfrPath", "").trim().isEmpty();
    private static final boolean JFR_PHASES_ENABLED =
            Boolean.getBoolean("rtxremix.profile.javaPhases") || BENCHMARK_JFR_ENABLED;
    // Defaults to JFR_PHASES_ENABLED: a normal client run (no profiling flags)
    // gets the cheap NOOP fast path in begin(), while benchmark/profiling runs
    // (which set rtxremix.profile.javaPhases / rtxremix.benchmark.jfrPath)
    // still capture Tracy Java zones. An explicit
    // -Drtxremix.profile.tracyJavaZones overrides this, so a Tracy-only dev
    // session can still opt in without JFR.
    private static final boolean TRACY_JAVA_ZONES_ENABLED =
            Boolean.parseBoolean(System.getProperty(
                    "rtxremix.profile.tracyJavaZones",
                    Boolean.toString(JFR_PHASES_ENABLED)));
    private static final int TRACY_JAVA_MAX_DEPTH =
            intProperty("rtxremix.profile.tracyJavaMaxDepth", -1);
    private static final boolean CLIENT_TASK_PROFILING_ENABLED =
            JFR_PHASES_ENABLED && Boolean.parseBoolean(System.getProperty(
                    "rtxremix.profile.clientTasks",
                    "true"));
    private static final long CLIENT_TASK_SLOW_THRESHOLD_NANOS =
            longProperty("rtxremix.profile.clientTaskSlowThresholdNs", 500_000L);
    private static final AtomicLong NEXT_FRAME_INDEX = new AtomicLong();
    private static final ThreadLocal<Long> CURRENT_FRAME_INDEX =
            ThreadLocal.withInitial(() -> -1L);
    private static final ThreadLocal<FrameStats> CURRENT_FRAME_STATS = new ThreadLocal<>();
    private static final ThreadLocal<TaskDrainStats> CURRENT_TASK_DRAIN = new ThreadLocal<>();
    // The two stacks are created once per thread and kept when they empty: an empty
    // ArrayDeque per thread is a fixed cost, while removing and re-creating one around
    // every executor task and Tracy scope allocated tens of megabytes a second.
    private static final ThreadLocal<ArrayDeque<TaskToken>> CURRENT_TASK_STACK =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Integer> TRACY_JAVA_DEPTH =
            ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<ArrayDeque<ProfileScope>> TRACY_JAVA_STACK =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ProfileScope NOOP = new ProfileScope(0L, null);

    private final long tracyContext;
    private final JavaPhaseEvent jfrEvent;
    private final String phaseName;
    private final boolean tracyDepthTracked;
    private final long startNanos;
    private String closePlotName;
    private boolean closed;
    private boolean tracyClosed;
    private boolean tracyClosePending;

    private ProfileScope(long tracyContext, JavaPhaseEvent jfrEvent) {
        this(tracyContext, jfrEvent, jfrEvent == null ? null : jfrEvent.phase,
                false, tracyContext != 0L || jfrEvent != null);
    }

    private ProfileScope(
            long tracyContext,
            JavaPhaseEvent jfrEvent,
            String phaseName,
            boolean tracyDepthTracked,
            boolean timed) {
        this.tracyContext = tracyContext;
        this.jfrEvent = jfrEvent;
        this.phaseName = phaseName;
        this.tracyDepthTracked = tracyDepthTracked;
        this.startNanos = timed ? System.nanoTime() : 0L;
    }

    /**
     * Opens a scope named {@code phase} in Tracy and/or JFR.
     *
     * <p>{@code phase} must come from a small fixed set of literals, never from
     * per-call data such as coordinates or counts. Tracy allocates one source
     * location per distinct name and addresses them through a signed 16-bit
     * index, so a capture that sees more than 32767 distinct names silently
     * corrupts every later zone. Per-scope detail belongs in the JFR event
     * fields ({@link #blockCount(int)} and friends).
     */
    public static ProfileScope begin(String phase) {
        boolean trackTracyDepth = TRACY_JAVA_ZONES_ENABLED;
        long tracyContext = 0L;
        if (trackTracyDepth) {
            int depth = TRACY_JAVA_DEPTH.get();
            if (isWithinTracyDepth(depth)) {
                tracyContext = RemixAPI.tracyBegin(phase);
            }
            TRACY_JAVA_DEPTH.set(depth + 1);
        }
        if (!JFR_PHASES_ENABLED && tracyContext == 0L && !trackTracyDepth) {
            return NOOP;
        }
        JavaPhaseEvent event = null;

        if (JFR_PHASES_ENABLED) {
            JavaPhaseEvent candidate = new JavaPhaseEvent();
            if (candidate.isEnabled()) {
                candidate.phase = phase;
                candidate.thread = Thread.currentThread().getName();
                candidate.frameIndex = CURRENT_FRAME_INDEX.get();
                candidate.begin();
                event = candidate;
            }
        }

        ProfileScope scope = new ProfileScope(tracyContext, event, phase, trackTracyDepth, true);
        scope.pushTracyScope();
        return scope;
    }

    public static ProfileScope beginTracyOnly(String name) {
        if (name == null || name.isBlank()) {
            return NOOP;
        }
        long tracyContext = RemixAPI.tracyBegin(name);
        if (tracyContext == 0L) {
            return NOOP;
        }
        ProfileScope scope = new ProfileScope(tracyContext, null);
        scope.pushTracyScope();
        return scope;
    }

    public static void markTracyZone(String name) {
        try (ProfileScope ignored = beginTracyOnly(name)) {
            // Marker scope intentionally has no body.
        }
    }

    public static ProfileScope beginJfrOnly(String phase) {
        if (!JFR_PHASES_ENABLED) {
            return NOOP;
        }

        JavaPhaseEvent event = null;
        JavaPhaseEvent candidate = new JavaPhaseEvent();
        if (candidate.isEnabled()) {
            candidate.phase = phase;
            candidate.thread = Thread.currentThread().getName();
            candidate.frameIndex = CURRENT_FRAME_INDEX.get();
            candidate.begin();
            event = candidate;
        }

        return new ProfileScope(0L, event, phase, false, true);
    }

    public static long beginFrame() {
        long frameIndex = NEXT_FRAME_INDEX.incrementAndGet();
        CURRENT_FRAME_INDEX.set(frameIndex);
        CURRENT_FRAME_STATS.set(new FrameStats(frameIndex, System.nanoTime()));
        return frameIndex;
    }

    public static long currentFrameIndex() {
        return CURRENT_FRAME_INDEX.get();
    }

    public static long setFrameIndexForCurrentThread(long frameIndex) {
        long previous = CURRENT_FRAME_INDEX.get();
        CURRENT_FRAME_INDEX.set(frameIndex);
        return previous;
    }

    public static void restoreFrameIndexForCurrentThread(long previousFrameIndex) {
        if (previousFrameIndex < 0) {
            CURRENT_FRAME_INDEX.remove();
        } else {
            CURRENT_FRAME_INDEX.set(previousFrameIndex);
        }
    }

    public static void markWorldRenderEnd() {
        FrameStats stats = CURRENT_FRAME_STATS.get();
        if (stats != null && stats.worldRenderEndOffsetNanos < 0) {
            stats.worldRenderEndOffsetNanos = System.nanoTime() - stats.frameStartNanos;
        }
    }

    public static void emitFrameLedger(long fullFrameNanos) {
        FrameStats stats = CURRENT_FRAME_STATS.get();
        if (stats == null || !JFR_PHASES_ENABLED) {
            CURRENT_FRAME_STATS.remove();
            return;
        }

        stats.fullFrameNanos = fullFrameNanos;
        FrameLedgerEvent event = new FrameLedgerEvent();
        if (event.isEnabled()) {
            event.frameIndex = stats.frameIndex;
            event.fullFrameNanos = fullFrameNanos;
            event.minecraftRenderNanos = stats.minecraftRenderNanos;
            event.minecraftRenderTopLevelNanos = stats.minecraftRenderTopLevelNanos;
            event.minecraftRenderUnattributedNanos =
                    positiveDelta(stats.minecraftRenderNanos, stats.minecraftRenderTopLevelNanos);
            event.minecraftPreGameRendererNanos = stats.minecraftPreGameRendererNanos;
            event.minecraftGameRendererNanos = stats.minecraftGameRendererNanos;
            event.minecraftPostGameRendererNanos = stats.minecraftPostGameRendererNanos;
            event.minecraftRunTasksNanos = stats.minecraftRunTasksNanos;
            event.minecraftTickNanos = stats.minecraftTickNanos;
            event.worldRendererNanos = stats.worldRendererNanos;
            event.worldRendererPhaseNanos = stats.worldRendererPhaseNanos;
            // Only WorldRenderer.phase.* spans nest inside WorldRenderer.render, so only they
            // are subtracted here. WorldRender.END is a separate top-level span; including it
            // would push attributed time past the frame.
            event.worldRendererUnattributedNanos =
                    positiveDelta(stats.worldRendererNanos, stats.worldRendererPhaseNanos);
            event.preWorldRenderEndNanos = stats.worldRenderEndOffsetNanos;
            event.worldRenderEndNanos = stats.worldRenderEndNanos;
            event.renderAndSubmitFrameNanos = stats.renderAndSubmitFrameNanos;
            event.updateVisibleChunksNanos = stats.updateVisibleChunksNanos;
            event.chunkGenNanos = stats.chunkGenNanos;
            event.chunkDrawNanos = stats.chunkDrawNanos;
            event.entitiesNanos = stats.entitiesNanos;
            event.skyNanos = stats.skyNanos;
            event.atlasNanos = stats.atlasNanos;
            event.submitNanos = stats.submitNanos;
            event.gcAssetStoresNanos = stats.gcAssetStoresNanos;
            event.taskDrainCount = stats.taskDrainCount;
            event.taskCount = stats.taskCount;
            event.taskNanos = stats.taskNanos;
            event.slowTaskCount = stats.slowTaskCount;
            event.maxTaskNanos = stats.maxTaskNanos;
            event.maxTaskName = stats.maxTaskName;
            event.maxTaskExecutor = stats.maxTaskExecutor;
            event.maxTaskQueueDepth = stats.maxTaskQueueDepth;
            event.commit();
        }
        CURRENT_FRAME_STATS.remove();
    }

    public static void beginClientTaskDrain(String executorName, int initialQueueDepth) {
        if (!CLIENT_TASK_PROFILING_ENABLED) {
            return;
        }
        TaskDrainStats previous = CURRENT_TASK_DRAIN.get();
        TaskDrainStats stats = new TaskDrainStats(
                previous,
                CURRENT_FRAME_INDEX.get(),
                safeString(executorName),
                Math.max(initialQueueDepth, 0),
                System.nanoTime());
        CURRENT_TASK_DRAIN.set(stats);
    }

    public static void endClientTaskDrain(int endQueueDepth) {
        TaskDrainStats stats = CURRENT_TASK_DRAIN.get();
        if (stats == null) {
            return;
        }
        CURRENT_TASK_DRAIN.set(stats.previous);
        // A task that throws never reaches endThreadExecutorTask, because the mixin's
        // end hook is @At("RETURN"), so its token stays on the per-thread stack.
        // Finalizing every token belonging to the drain being closed completes their
        // stats and JFR events and bounds the stack. Parent-drain tokens, below these
        // on the stack, are left untouched.
        ArrayDeque<TaskToken> taskStack = CURRENT_TASK_STACK.get();
        while (!taskStack.isEmpty() && taskStack.peek().drainStats == stats) {
            finalizeTaskToken(taskStack.pop());
        }
        long elapsed = System.nanoTime() - stats.startNanos;
        stats.endQueueDepth = Math.max(endQueueDepth, 0);

        FrameStats frame = CURRENT_FRAME_STATS.get();
        if (frame != null && frame.frameIndex == stats.frameIndex) {
            frame.taskDrainCount++;
            frame.taskCount += stats.taskCount;
            frame.taskNanos += stats.taskNanos;
            frame.slowTaskCount += stats.slowTaskCount;
            if (stats.maxTaskNanos > frame.maxTaskNanos) {
                frame.maxTaskNanos = stats.maxTaskNanos;
                frame.maxTaskName = stats.maxTaskName;
                frame.maxTaskExecutor = stats.executorName;
                frame.maxTaskQueueDepth = stats.maxQueueDepth;
            }
        }

        ClientTaskDrainEvent event = new ClientTaskDrainEvent();
        if (event.isEnabled()) {
            event.executorName = stats.executorName;
            event.thread = Thread.currentThread().getName();
            event.frameIndex = stats.frameIndex;
            event.taskCount = stats.taskCount;
            event.slowTaskCount = stats.slowTaskCount;
            event.taskNanos = stats.taskNanos;
            event.drainNanos = elapsed;
            event.maxTaskNanos = stats.maxTaskNanos;
            event.maxTaskName = stats.maxTaskName;
            event.initialQueueDepth = stats.initialQueueDepth;
            event.endQueueDepth = stats.endQueueDepth;
            event.maxQueueDepth = stats.maxQueueDepth;
            event.commit();
        }
    }

    /**
     * True when client-task profiling is armed at all.
     *
     * <p>Callers use this to skip building arguments for the per-task hooks. The
     * queue depth those hooks want costs a {@code ConcurrentLinkedQueue.size()}
     * walk, so computing it for a call that returns immediately is pure loss.
     */
    public static boolean isClientTaskProfilingEnabled() {
        return CLIENT_TASK_PROFILING_ENABLED;
    }

    /** True when a task drain is currently being recorded on this thread. */
    public static boolean isClientTaskDrainActive() {
        return CLIENT_TASK_PROFILING_ENABLED && CURRENT_TASK_DRAIN.get() != null;
    }

    public static void beginThreadExecutorTask(String executorName, int queueDepthAfterRemove, Runnable task) {
        TaskDrainStats drain = CURRENT_TASK_DRAIN.get();
        if (!CLIENT_TASK_PROFILING_ENABLED || drain == null) {
            return;
        }

        String taskName = describeTask(task);
        int queueDepth = Math.max(queueDepthAfterRemove, 0);
        ClientTaskEvent event = null;
        ClientTaskEvent candidate = new ClientTaskEvent();
        if (candidate.isEnabled()) {
            candidate.executorName = drain.executorName;
            candidate.thread = Thread.currentThread().getName();
            candidate.frameIndex = drain.frameIndex;
            candidate.taskName = taskName;
            candidate.queueDepthAfterRemove = queueDepth;
            candidate.begin();
            event = candidate;
        }
        CURRENT_TASK_STACK.get().push(new TaskToken(
                drain,
                event,
                taskName,
                queueDepth,
                System.nanoTime()));
    }

    public static void endThreadExecutorTask() {
        ArrayDeque<TaskToken> stack = CURRENT_TASK_STACK.get();
        if (stack.isEmpty()) {
            return;
        }
        finalizeTaskToken(stack.pop());
    }

    private static void finalizeTaskToken(TaskToken token) {
        long elapsed = System.nanoTime() - token.startNanos;
        TaskDrainStats drain = token.drainStats;
        if (drain != null) {
            drain.taskCount++;
            drain.taskNanos += elapsed;
            drain.maxQueueDepth = Math.max(drain.maxQueueDepth, token.queueDepthAfterRemove + 1);
            if (elapsed >= CLIENT_TASK_SLOW_THRESHOLD_NANOS) {
                drain.slowTaskCount++;
            }
            if (elapsed > drain.maxTaskNanos) {
                drain.maxTaskNanos = elapsed;
                drain.maxTaskName = token.taskName;
            }
        }

        if (token.event != null) {
            token.event.end();
            token.event.durationNanos = elapsed;
            token.event.slow = elapsed >= CLIENT_TASK_SLOW_THRESHOLD_NANOS;
            token.event.slowThresholdNanos = CLIENT_TASK_SLOW_THRESHOLD_NANOS;
            if (elapsed >= CLIENT_TASK_SLOW_THRESHOLD_NANOS && token.event.shouldCommit()) {
                token.event.commit();
            }
        }
    }

    public static boolean isDeepProfilingEnabled() {
        return JFR_PHASES_ENABLED;
    }

    /**
     * True when a {@link #begin} scope would do any real work (JFR phase event
     * or Tracy zone). Callers on the hot path should guard scope creation with
     * this to avoid building scope names / arguments when profiling is off.
     */
    public static boolean isJavaScopeEnabled() {
        return JFR_PHASES_ENABLED || TRACY_JAVA_ZONES_ENABLED;
    }

    public static void plot(String name, double value) {
        if (JFR_PHASES_ENABLED) {
            RemixAPI.tracyPlot(name, value);
        }
    }

    public static void message(String message) {
        if (JFR_PHASES_ENABLED) {
            RemixAPI.tracyMessage(message);
        }
    }

    public ProfileScope plotOnClose(String name) {
        if (this != NOOP && name != null && !name.isBlank()) {
            closePlotName = name;
        }
        return this;
    }

    public ProfileScope entityCount(int value) {
        if (jfrEvent != null) jfrEvent.entityCount = value;
        return this;
    }

    public ProfileScope layerCount(int value) {
        if (jfrEvent != null) jfrEvent.layerCount = value;
        return this;
    }

    public ProfileScope boneCount(int value) {
        if (jfrEvent != null) jfrEvent.boneCount = value;
        return this;
    }

    public ProfileScope drawCalls(int value) {
        if (jfrEvent != null) jfrEvent.drawCalls = value;
        return this;
    }

    public ProfileScope meshCreates(int value) {
        if (jfrEvent != null) jfrEvent.meshCreates = value;
        return this;
    }

    public ProfileScope captureCount(int value) {
        if (jfrEvent != null) jfrEvent.captureCount = value;
        return this;
    }

    public ProfileScope surfaceCount(int value) {
        if (jfrEvent != null) jfrEvent.surfaceCount = value;
        return this;
    }

    public ProfileScope dirtyLeaves(int value) {
        if (jfrEvent != null) jfrEvent.dirtyLeaves = value;
        return this;
    }

    public ProfileScope installCount(int value) {
        if (jfrEvent != null) jfrEvent.installCount = value;
        return this;
    }

    public ProfileScope budgetHit(boolean value) {
        if (jfrEvent != null) jfrEvent.budgetHit = value;
        return this;
    }

    public ProfileScope meshCategory(int value) {
        if (jfrEvent != null) jfrEvent.meshCategory = value;
        return this;
    }

    public ProfileScope vertexCount(int value) {
        if (jfrEvent != null) jfrEvent.vertexCount = value;
        return this;
    }

    public ProfileScope blockCount(int value) {
        if (jfrEvent != null) jfrEvent.blockCount = value;
        return this;
    }

    public ProfileScope fluidCount(int value) {
        if (jfrEvent != null) jfrEvent.fluidCount = value;
        return this;
    }

    public ProfileScope bytes(long value) {
        if (jfrEvent != null) jfrEvent.bytes = value;
        return this;
    }

    public ProfileScope collisionChecks(int value) {
        if (jfrEvent != null) jfrEvent.collisionChecks = value;
        return this;
    }

    public ProfileScope collisionSkips(int value) {
        if (jfrEvent != null) jfrEvent.collisionSkips = value;
        return this;
    }

    public ProfileScope collisionNanos(long value) {
        if (jfrEvent != null) jfrEvent.collisionNanos = value;
        return this;
    }

    public ProfileScope visibleChunkCount(int value) {
        if (jfrEvent != null) jfrEvent.visibleChunkCount = value;
        return this;
    }

    public ProfileScope scannedChunkCount(int value) {
        if (jfrEvent != null) jfrEvent.scannedChunkCount = value;
        return this;
    }

    public ProfileScope blockEntityChunkCount(int value) {
        if (jfrEvent != null) jfrEvent.blockEntityChunkCount = value;
        return this;
    }

    public ProfileScope blockEntityCount(int value) {
        if (jfrEvent != null) jfrEvent.blockEntityCount = value;
        return this;
    }

    public ProfileScope noCullBlockEntityCount(int value) {
        if (jfrEvent != null) jfrEvent.noCullBlockEntityCount = value;
        return this;
    }

    public ProfileScope blockBreakingCount(int value) {
        if (jfrEvent != null) jfrEvent.blockBreakingCount = value;
        return this;
    }

    public ProfileScope renderLayerCalls(int value) {
        if (jfrEvent != null) jfrEvent.renderLayerCalls = value;
        return this;
    }

    public ProfileScope renderLayerSkips(int value) {
        if (jfrEvent != null) jfrEvent.renderLayerSkips = value;
        return this;
    }

    @Override
    public void close() {
        if (this == NOOP || closed) {
            return;
        }
        closed = true;
        long elapsedNanos = startNanos == 0L ? 0L : System.nanoTime() - startNanos;
        try {
            if (jfrEvent != null) {
                jfrEvent.end();
                if (jfrEvent.shouldCommit()) {
                    jfrEvent.commit();
                }
            }
        } finally {
            if (phaseName != null && elapsedNanos > 0L) {
                recordPhaseDuration(phaseName, elapsedNanos);
            }
            if (closePlotName != null && startNanos != 0L) {
                RemixAPI.tracyPlot(closePlotName, elapsedNanos / 1_000_000.0);
            }
            try {
                if (tracyContext != 0L) {
                    closeTracyScope();
                }
            } finally {
                if (tracyDepthTracked) {
                    popTracyDepth();
                }
            }
        }
    }

    private void pushTracyScope() {
        if (tracyContext != 0L) {
            TRACY_JAVA_STACK.get().push(this);
        }
    }

    private void closeTracyScope() {
        ArrayDeque<ProfileScope> stack = TRACY_JAVA_STACK.get();
        if (stack.isEmpty()) {
            endTracyNow();
            return;
        }

        if (stack.peek() == this) {
            stack.pop();
            endTracyNow();
            closePendingTracyParents(stack);
            return;
        }

        // Tracy CPU zones must close in strict LIFO order. Some Minecraft/loader
        // callbacks describe phase transitions rather than lexical scopes; if a
        // parent is asked to close while an injected child is still open, defer
        // only the Tracy end. JFR timing still records the true Java close time.
        tracyClosePending = true;
    }

    private static void closePendingTracyParents(ArrayDeque<ProfileScope> stack) {
        while (!stack.isEmpty() && stack.peek().tracyClosePending) {
            ProfileScope pending = stack.pop();
            pending.endTracyNow();
        }
    }

    private void endTracyNow() {
        if (!tracyClosed) {
            tracyClosed = true;
            RemixAPI.tracyEnd(tracyContext);
        }
    }

    private static boolean isWithinTracyDepth(int depth) {
        return TRACY_JAVA_MAX_DEPTH < 0 || depth < TRACY_JAVA_MAX_DEPTH;
    }

    private static void recordPhaseDuration(String phase, long nanos) {
        FrameStats stats = CURRENT_FRAME_STATS.get();
        if (stats == null) {
            return;
        }

        switch (phase) {
            case "MinecraftClient.render" -> stats.minecraftRenderNanos += nanos;
            case "MinecraftClient.render.preGameRenderer" -> {
                stats.minecraftPreGameRendererNanos += nanos;
                stats.minecraftRenderTopLevelNanos += nanos;
            }
            case "MinecraftClient.render.gameRenderer" -> {
                stats.minecraftGameRendererNanos += nanos;
                stats.minecraftRenderTopLevelNanos += nanos;
            }
            case "MinecraftClient.render.postGameRenderer" -> {
                stats.minecraftPostGameRendererNanos += nanos;
                stats.minecraftRenderTopLevelNanos += nanos;
            }
            case "MinecraftClient.render.swapBuffers",
                    "MinecraftClient.render.frameLimit",
                    "MinecraftClient.render.limitDisplayFPS",
                    "MinecraftClient.render.afterFrameLimit",
                    "MinecraftClient.render.yield",
                    "MinecraftClient.render.postFrame" -> stats.minecraftRenderTopLevelNanos += nanos;
            case "MinecraftClient.render.runTasks" -> stats.minecraftRunTasksNanos += nanos;
            case "MinecraftClient.tick" -> stats.minecraftTickNanos += nanos;
            case "WorldRenderer.render" -> stats.worldRendererNanos += nanos;
            case "WorldRender.END" -> stats.worldRenderEndNanos += nanos;
            case "renderAndSubmitFrame" -> stats.renderAndSubmitFrameNanos += nanos;
            case "updateVisibleChunks" -> stats.updateVisibleChunksNanos += nanos;
            case "Chunk.Gen" -> stats.chunkGenNanos += nanos;
            case "Chunk.Draw" -> stats.chunkDrawNanos += nanos;
            case "Entities" -> stats.entitiesNanos += nanos;
            case "Sky" -> stats.skyNanos += nanos;
            case "Atlas" -> stats.atlasNanos += nanos;
            case "Submit" -> stats.submitNanos += nanos;
            case "gcAssetStores" -> stats.gcAssetStoresNanos += nanos;
            default -> {
                if (phase.startsWith("WorldRenderer.phase.")) {
                    stats.worldRendererPhaseNanos += nanos;
                }
            }
        }
    }

    private static long positiveDelta(long parent, long child) {
        if (parent <= 0L) {
            return 0L;
        }
        return Math.max(0L, parent - Math.max(0L, child));
    }

    private static String describeTask(Runnable task) {
        return task == null ? "null" : task.getClass().getName();
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    private static void popTracyDepth() {
        int depth = TRACY_JAVA_DEPTH.get() - 1;
        if (depth <= 0) {
            TRACY_JAVA_DEPTH.remove();
        } else {
            TRACY_JAVA_DEPTH.set(depth);
        }
    }

    private static int intProperty(String name, int defaultValue) {
        String raw = System.getProperty(name, "").trim();
        if (raw.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static long longProperty(String name, long defaultValue) {
        String raw = System.getProperty(name, "").trim();
        if (raw.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static final class FrameStats {
        final long frameIndex;
        final long frameStartNanos;
        long fullFrameNanos;
        long minecraftRenderNanos;
        long minecraftRenderTopLevelNanos;
        long minecraftPreGameRendererNanos;
        long minecraftGameRendererNanos;
        long minecraftPostGameRendererNanos;
        long minecraftRunTasksNanos;
        long minecraftTickNanos;
        long worldRendererNanos;
        long worldRendererPhaseNanos;
        long worldRenderEndOffsetNanos = -1L;
        long worldRenderEndNanos;
        long renderAndSubmitFrameNanos;
        long updateVisibleChunksNanos;
        long chunkGenNanos;
        long chunkDrawNanos;
        long entitiesNanos;
        long skyNanos;
        long atlasNanos;
        long submitNanos;
        long gcAssetStoresNanos;
        int taskDrainCount;
        int taskCount;
        int slowTaskCount;
        int maxTaskQueueDepth;
        long taskNanos;
        long maxTaskNanos;
        String maxTaskName = "";
        String maxTaskExecutor = "";

        FrameStats(long frameIndex, long frameStartNanos) {
            this.frameIndex = frameIndex;
            this.frameStartNanos = frameStartNanos;
        }
    }

    private static final class TaskDrainStats {
        final TaskDrainStats previous;
        final long frameIndex;
        final String executorName;
        final int initialQueueDepth;
        final long startNanos;
        int endQueueDepth;
        int maxQueueDepth;
        int taskCount;
        int slowTaskCount;
        long taskNanos;
        long maxTaskNanos;
        String maxTaskName = "";

        TaskDrainStats(
                TaskDrainStats previous,
                long frameIndex,
                String executorName,
                int initialQueueDepth,
                long startNanos) {
            this.previous = previous;
            this.frameIndex = frameIndex;
            this.executorName = executorName;
            this.initialQueueDepth = initialQueueDepth;
            this.maxQueueDepth = initialQueueDepth;
            this.startNanos = startNanos;
        }
    }

    private static final class TaskToken {
        final TaskDrainStats drainStats;
        final ClientTaskEvent event;
        final String taskName;
        final int queueDepthAfterRemove;
        final long startNanos;

        TaskToken(
                TaskDrainStats drainStats,
                ClientTaskEvent event,
                String taskName,
                int queueDepthAfterRemove,
                long startNanos) {
            this.drainStats = drainStats;
            this.event = event;
            this.taskName = taskName;
            this.queueDepthAfterRemove = queueDepthAfterRemove;
            this.startNanos = startNanos;
        }
    }

    @Name("minecraft.JavaPhase")
    @Label("RTX Remix Java Phase")
    @Description("Duration of a Java-side RTX Remix renderer phase.")
    @Category({"Minecraft", "RTX Remix"})
    @StackTrace(false)
    @Threshold("0 ms")
    static class JavaPhaseEvent extends Event {
        @Label("Phase")
        String phase;

        @Label("Thread")
        String thread;

        @Label("Frame Index")
        long frameIndex = -1;

        @Label("Entity Count")
        int entityCount = -1;

        @Label("Layer Count")
        int layerCount = -1;

        @Label("Bone Count")
        int boneCount = -1;

        @Label("Draw Calls")
        int drawCalls = -1;

        @Label("Mesh Creates")
        int meshCreates = -1;

        @Label("Capture Count")
        int captureCount = -1;

        @Label("Surface Count")
        int surfaceCount = -1;

        @Label("Dirty Leaves")
        int dirtyLeaves = -1;

        @Label("Install Count")
        int installCount = -1;

        @Label("Budget Hit")
        boolean budgetHit;

        @Label("Mesh Category")
        int meshCategory = -1;

        @Label("Vertex Count")
        int vertexCount = -1;

        @Label("Block Count")
        int blockCount = -1;

        @Label("Fluid Count")
        int fluidCount = -1;

        @Label("Bytes")
        long bytes = -1;

        @Label("Collision Checks")
        int collisionChecks = -1;

        @Label("Collision Skips")
        int collisionSkips = -1;

        @Label("Collision Nanos")
        long collisionNanos = -1;

        @Label("Visible Chunk Count")
        int visibleChunkCount = -1;

        @Label("Scanned Chunk Count")
        int scannedChunkCount = -1;

        @Label("Block Entity Chunk Count")
        int blockEntityChunkCount = -1;

        @Label("Block Entity Count")
        int blockEntityCount = -1;

        @Label("No-Cull Block Entity Count")
        int noCullBlockEntityCount = -1;

        @Label("Block Breaking Count")
        int blockBreakingCount = -1;

        @Label("Render Layer Calls")
        int renderLayerCalls = -1;

        @Label("Render Layer Skips")
        int renderLayerSkips = -1;
    }

    @Name("minecraft.ClientTask")
    @Label("Minecraft Client Scheduled Task")
    @Description("Slow task executed while MinecraftClient.render.runTasks drains the client executor.")
    @Category({"Minecraft", "RTX Remix"})
    @StackTrace(true)
    @Threshold("0 ms")
    static class ClientTaskEvent extends Event {
        @Label("Executor")
        String executorName;

        @Label("Thread")
        String thread;

        @Label("Frame Index")
        long frameIndex = -1;

        @Label("Task")
        String taskName;

        @Label("Queue Depth After Remove")
        int queueDepthAfterRemove;

        @Label("Duration Nanos")
        long durationNanos;

        @Label("Slow")
        boolean slow;

        @Label("Slow Threshold Nanos")
        long slowThresholdNanos;
    }

    @Name("minecraft.ClientTaskDrain")
    @Label("Minecraft Client Task Drain")
    @Description("Aggregate for one MinecraftClient.render.runTasks queue drain.")
    @Category({"Minecraft", "RTX Remix"})
    @StackTrace(false)
    @Threshold("0 ms")
    static class ClientTaskDrainEvent extends Event {
        @Label("Executor")
        String executorName;

        @Label("Thread")
        String thread;

        @Label("Frame Index")
        long frameIndex = -1;

        @Label("Task Count")
        int taskCount;

        @Label("Slow Task Count")
        int slowTaskCount;

        @Label("Task Nanos")
        long taskNanos;

        @Label("Drain Nanos")
        long drainNanos;

        @Label("Max Task Nanos")
        long maxTaskNanos;

        @Label("Max Task")
        String maxTaskName;

        @Label("Initial Queue Depth")
        int initialQueueDepth;

        @Label("End Queue Depth")
        int endQueueDepth;

        @Label("Max Queue Depth")
        int maxQueueDepth;
    }

    @Name("minecraft.FrameLedger")
    @Label("RTX Remix Frame Ledger")
    @Description("One compact per-frame accounting event for Java frame attribution.")
    @Category({"Minecraft", "RTX Remix"})
    @StackTrace(false)
    @Threshold("0 ms")
    static class FrameLedgerEvent extends Event {
        @Label("Frame Index")
        long frameIndex = -1;

        @Label("Full Frame Nanos")
        long fullFrameNanos;

        @Label("Minecraft Render Nanos")
        long minecraftRenderNanos;

        @Label("Minecraft Render Top-Level Nanos")
        long minecraftRenderTopLevelNanos;

        @Label("Minecraft Render Unattributed Nanos")
        long minecraftRenderUnattributedNanos;

        @Label("Minecraft Pre-GameRenderer Nanos")
        long minecraftPreGameRendererNanos;

        @Label("Minecraft GameRenderer Nanos")
        long minecraftGameRendererNanos;

        @Label("Minecraft Post-GameRenderer Nanos")
        long minecraftPostGameRendererNanos;

        @Label("Minecraft RunTasks Nanos")
        long minecraftRunTasksNanos;

        @Label("Minecraft Tick Nanos")
        long minecraftTickNanos;

        @Label("WorldRenderer Nanos")
        long worldRendererNanos;

        @Label("WorldRenderer Phase Nanos")
        long worldRendererPhaseNanos;

        @Label("WorldRenderer Unattributed Nanos")
        long worldRendererUnattributedNanos;

        @Label("Pre WorldRender.END Nanos")
        long preWorldRenderEndNanos;

        @Label("WorldRender.END Nanos")
        long worldRenderEndNanos;

        @Label("RTX Render And Submit Frame Nanos")
        long renderAndSubmitFrameNanos;

        @Label("updateVisibleChunks Nanos")
        long updateVisibleChunksNanos;

        @Label("Chunk Gen Nanos")
        long chunkGenNanos;

        @Label("Chunk Draw Nanos")
        long chunkDrawNanos;

        @Label("Entities Nanos")
        long entitiesNanos;

        @Label("Sky Nanos")
        long skyNanos;

        @Label("Atlas Nanos")
        long atlasNanos;

        @Label("Submit Nanos")
        long submitNanos;

        @Label("GC Asset Stores Nanos")
        long gcAssetStoresNanos;

        @Label("Task Drain Count")
        int taskDrainCount;

        @Label("Task Count")
        int taskCount;

        @Label("Task Nanos")
        long taskNanos;

        @Label("Slow Task Count")
        int slowTaskCount;

        @Label("Max Task Nanos")
        long maxTaskNanos;

        @Label("Max Task")
        String maxTaskName;

        @Label("Max Task Executor")
        String maxTaskExecutor;

        @Label("Max Task Queue Depth")
        int maxTaskQueueDepth;
    }
}

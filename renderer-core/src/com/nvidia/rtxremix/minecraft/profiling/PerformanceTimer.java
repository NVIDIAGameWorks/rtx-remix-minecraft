/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.profiling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight per-frame timing and counting instrumentation.
 *
 * Accumulates timing data and per-frame counters over a configurable
 * interval and logs average/min/max for each named section.
 */
public class PerformanceTimer {

    private static final Logger LOGGER = LoggerFactory.getLogger(PerformanceTimer.class);

    private static final int REPORT_INTERVAL = 300;
    private static final int MAX_SECTIONS = 32;
    private static final int MAX_COUNTERS = 96;
    private static final String FRAME_PLOT_NAME = "Java.Frame.ms";
    private static final ProfileScopeHandle NOOP_PROFILE_SCOPE =
            new ProfileScopeHandle() {
                @Override public void close() { }
            };
    private static final ProfileSink NOOP_PROFILE_SINK = new ProfileSink() {
        @Override public boolean isEnabled() { return false; }
        @Override public void plot(String name, double value) { }
    };
    private static volatile ProfileSink profileSink = NOOP_PROFILE_SINK;

    // Timing sections
    private final String[] sectionNames = new String[MAX_SECTIONS];
    private final String[] sectionPlotNames = new String[MAX_SECTIONS];
    private final long[] sectionTotalNanos = new long[MAX_SECTIONS];
    private final long[] sectionMinNanos = new long[MAX_SECTIONS];
    private final long[] sectionMaxNanos = new long[MAX_SECTIONS];
    private final long[] sectionStartNanos = new long[MAX_SECTIONS];
    private int sectionCount = 0;

    // Per-frame counters (accumulated each frame, reported as avg/min/max per frame)
    private final String[] counterNames = new String[MAX_COUNTERS];
    private final String[] counterPlotNames = new String[MAX_COUNTERS];
    private final long[] counterFrameValue = new long[MAX_COUNTERS];
    private final long[] counterTotal = new long[MAX_COUNTERS];
    private final long[] counterMin = new long[MAX_COUNTERS];
    private final long[] counterMax = new long[MAX_COUNTERS];
    private int counterCount = 0;

    private int frameCount = 0;
    private long frameTotalNanos = 0;
    private long frameMinNanos = Long.MAX_VALUE;
    private long frameMaxNanos = 0;
    private long frameStartNanos = 0;

    private boolean benchmarkMode = false;

    // Per-frame snapshot data (valid between beginFrame/endFrame)
    private final long[] sectionLastNanos = new long[MAX_SECTIONS];
    private final long[] counterLastValue = new long[MAX_COUNTERS];

    public int registerSection(String name) {
        if (sectionCount >= MAX_SECTIONS) {
            LOGGER.warn("Too many perf sections, ignoring: {}", name);
            return -1;
        }
        int idx = sectionCount++;
        sectionNames[idx] = name;
        sectionPlotNames[idx] = "Java.Section." + name + ".ms";
        sectionMinNanos[idx] = Long.MAX_VALUE;
        return idx;
    }

    /**
     * Registers a named per-frame counter. Call once during initialization.
     * Use {@link #count(int, long)} to add to it each frame.
     */
    public int registerCounter(String name) {
        if (counterCount >= MAX_COUNTERS) {
            LOGGER.warn("Too many perf counters, ignoring: {}", name);
            return -1;
        }
        int idx = counterCount++;
        counterNames[idx] = name;
        counterPlotNames[idx] = "Java.Counter." + name;
        counterMin[idx] = Long.MAX_VALUE;
        return idx;
    }

    public void beginFrame() {
        frameStartNanos = System.nanoTime();
        for (int i = 0; i < counterCount; i++) {
            counterFrameValue[i] = 0;
        }
        for (int i = 0; i < sectionCount; i++) {
            sectionLastNanos[i] = 0;
        }
    }

    public void endFrame() {
        long elapsed = System.nanoTime() - frameStartNanos;
        frameTotalNanos += elapsed;
        frameMinNanos = Math.min(frameMinNanos, elapsed);
        frameMaxNanos = Math.max(frameMaxNanos, elapsed);

        for (int i = 0; i < counterCount; i++) {
            long v = counterFrameValue[i];
            counterLastValue[i] = v;
            counterTotal[i] += v;
            counterMin[i] = Math.min(counterMin[i], v);
            counterMax[i] = Math.max(counterMax[i], v);
        }

        emitProfilePlots(elapsed);

        frameCount++;
        if (!benchmarkMode && frameCount >= REPORT_INTERVAL) {
            logReport();
            reset();
        }
    }

    public void begin(int sectionIdx) {
        if (sectionIdx < 0) return;
        sectionStartNanos[sectionIdx] = System.nanoTime();
    }

    public void end(int sectionIdx) {
        if (sectionIdx < 0) return;
        long elapsed = System.nanoTime() - sectionStartNanos[sectionIdx];
        sectionLastNanos[sectionIdx] = elapsed;
        sectionTotalNanos[sectionIdx] += elapsed;
        sectionMinNanos[sectionIdx] = Math.min(sectionMinNanos[sectionIdx], elapsed);
        sectionMaxNanos[sectionIdx] = Math.max(sectionMaxNanos[sectionIdx], elapsed);
    }

    /**
     * Directly injects a pre-measured duration into a section's accumulators.
     * Use when timing is collected outside begin/end (e.g. accumulated inside
     * lambdas). Called once per frame with the total nanos for that frame.
     */
    public void injectNanos(int sectionIdx, long nanos) {
        if (sectionIdx < 0) return;
        sectionLastNanos[sectionIdx] = nanos;
        sectionTotalNanos[sectionIdx] += nanos;
        sectionMinNanos[sectionIdx] = Math.min(sectionMinNanos[sectionIdx], nanos);
        sectionMaxNanos[sectionIdx] = Math.max(sectionMaxNanos[sectionIdx], nanos);
    }

    /** Adds {@code delta} to a per-frame counter (resets each frame). */
    public void count(int counterIdx, long delta) {
        if (counterIdx < 0) return;
        counterFrameValue[counterIdx] += delta;
    }

    private void emitProfilePlots(long frameNanos) {
        ProfileSink sink = profileSink;
        if (!sink.isEnabled()) return;

        // Plot names are built once at registration: this runs every frame and the
        // bridge interns each distinct name for Tracy's lifetime.
        sink.plot(FRAME_PLOT_NAME, frameNanos / 1_000_000.0);
        for (int i = 0; i < sectionCount; i++) {
            sink.plot(sectionPlotNames[i], sectionLastNanos[i] / 1_000_000.0);
        }
        for (int i = 0; i < counterCount; i++) {
            sink.plot(counterPlotNames[i], counterLastValue[i]);
        }
    }

    public static void setProfileSink(ProfileSink sink) {
        profileSink = sink == null ? NOOP_PROFILE_SINK : sink;
    }

    public static void plot(String name, double value) {
        ProfileSink sink = profileSink;
        if (sink.isEnabled()) {
            sink.plot(name, value);
        }
    }

    /** Opens an optional host profiling scope without coupling core code to its backend. */
    public static ProfileScopeHandle beginProfileScope(String name) {
        ProfileScopeHandle scope = profileSink.begin(name);
        return scope == null ? NOOP_PROFILE_SCOPE : scope;
    }

    private void logReport() {
        if (frameCount == 0) return;

        double avgFrameMs = (frameTotalNanos / (double) frameCount) / 1_000_000.0;
        double minFrameMs = frameMinNanos / 1_000_000.0;
        double maxFrameMs = frameMaxNanos / 1_000_000.0;
        double fps = 1000.0 / avgFrameMs;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("\n=== Performance Report (%d frames) ===\n", frameCount));
        sb.append(String.format("  Frame total:  avg=%.2fms  min=%.2fms  max=%.2fms  (~%.0f fps)\n",
            avgFrameMs, minFrameMs, maxFrameMs, fps));

        for (int i = 0; i < sectionCount; i++) {
            double avgMs = (sectionTotalNanos[i] / (double) frameCount) / 1_000_000.0;
            double minMs = sectionMinNanos[i] / 1_000_000.0;
            double maxMs = sectionMaxNanos[i] / 1_000_000.0;
            double pct = frameTotalNanos > 0 ? (sectionTotalNanos[i] * 100.0 / frameTotalNanos) : 0;

            sb.append(String.format("  %-20s avg=%.2fms  min=%.2fms  max=%.2fms  (%.1f%%)\n",
                sectionNames[i] + ":", avgMs, minMs, maxMs, pct));
        }

        if (counterCount > 0) {
            sb.append("  --- Counters (per frame) ---\n");
            for (int i = 0; i < counterCount; i++) {
                double avg = counterTotal[i] / (double) frameCount;
                sb.append(String.format("  %-20s avg=%.1f  min=%d  max=%d  total=%d\n",
                    counterNames[i] + ":", avg, counterMin[i], counterMax[i], counterTotal[i]));
            }
        }

        sb.append("=====================================");
        LOGGER.info(sb.toString());
    }

    private void reset() {
        frameCount = 0;
        frameTotalNanos = 0;
        frameMinNanos = Long.MAX_VALUE;
        frameMaxNanos = 0;

        for (int i = 0; i < sectionCount; i++) {
            sectionTotalNanos[i] = 0;
            sectionMinNanos[i] = Long.MAX_VALUE;
            sectionMaxNanos[i] = 0;
        }
        for (int i = 0; i < counterCount; i++) {
            counterTotal[i] = 0;
            counterMin[i] = Long.MAX_VALUE;
            counterMax[i] = 0;
        }
    }

    /** When true, suppresses periodic log reports and accumulates all frames. */
    public void setBenchmarkMode(boolean benchmarkMode) {
        this.benchmarkMode = benchmarkMode;
        if (benchmarkMode) reset();
    }

    /** Returns the most recent frame's elapsed time for a section in nanoseconds. */
    public long getSectionTimeNanos(int sectionIdx) {
        return (sectionIdx >= 0 && sectionIdx < sectionCount) ? sectionLastNanos[sectionIdx] : 0;
    }

    /** Returns the most recent frame's value for a counter. */
    public long getCounterValue(int counterIdx) {
        return (counterIdx >= 0 && counterIdx < counterCount) ? counterLastValue[counterIdx] : 0;
    }

    public int getSectionCount() { return sectionCount; }
    public String getSectionName(int idx) { return sectionNames[idx]; }
    public int getCounterCount() { return counterCount; }
    public String getCounterName(int idx) { return counterNames[idx]; }

    /** Host bridge for optional profiling backends such as Tracy and JFR. */
    public interface ProfileSink {
        boolean isEnabled();
        void plot(String name, double value);

        default ProfileScopeHandle begin(String name) {
            return NOOP_PROFILE_SCOPE;
        }
    }

    public interface ProfileScopeHandle extends AutoCloseable {
        default ProfileScopeHandle vertexCount(int value) {
            return this;
        }

        default ProfileScopeHandle surfaceCount(int value) {
            return this;
        }

        default ProfileScopeHandle bytes(long value) {
            return this;
        }

        @Override
        void close();
    }
}

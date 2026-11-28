/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.camera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An immutable, time-based camera path.
 *
 * <p>Waypoint times are absolute milliseconds from the beginning of playback.
 * Positions and rotations use a shape-preserving cubic Hermite spline whose
 * derivatives are expressed in units per millisecond. This keeps velocity
 * continuous when recorded waypoint intervals are not uniform. Sampling before
 * or after the path clamps to the first or last waypoint.</p>
 */
final class CameraPath {

    static final long MAX_DURATION_MS = Long.MAX_VALUE / 1_000_000L;
    private static final long MINECRAFT_DAY_TICKS = 24_000L;
    private static final int X = 0;
    private static final int Y = 1;
    private static final int Z = 2;
    private static final int YAW = 3;
    private static final int PITCH = 4;
    private static final int INTERPOLATED_COMPONENT_COUNT = 5;

    private final List<Waypoint> waypoints;
    private final double[][] interpolationValues;
    private final double[][] interpolationTangents;

    /**
     * Creates a validated camera path.
     *
     * @throws IllegalArgumentException if there are fewer than two waypoints,
     *                                  the first waypoint is not at zero, or
     *                                  waypoint times are not strictly increasing,
     *                                  or the duration cannot be represented by
     *                                  the nanosecond playback clock
     */
    CameraPath(List<Waypoint> waypoints) {
        Objects.requireNonNull(waypoints, "waypoints");
        List<Waypoint> copy = new ArrayList<>(waypoints);
        if (copy.size() < 2) {
            throw new IllegalArgumentException("camera path requires at least two waypoints");
        }

        long previousTimeMs = -1L;
        for (int i = 0; i < copy.size(); i++) {
            Waypoint waypoint = copy.get(i);
            if (waypoint == null) {
                throw new IllegalArgumentException("waypoint " + i + " must not be null");
            }
            if (i == 0 && waypoint.timeMs() != 0L) {
                throw new IllegalArgumentException("first waypoint timeMs must be 0");
            }
            if (waypoint.timeMs() <= previousTimeMs) {
                throw new IllegalArgumentException("waypoint timeMs values must be strictly increasing");
            }
            if (waypoint.timeMs() > MAX_DURATION_MS) {
                throw new IllegalArgumentException(
                        "waypoint timeMs exceeds the maximum supported duration of "
                                + MAX_DURATION_MS
                );
            }
            previousTimeMs = waypoint.timeMs();
        }
        this.waypoints = Collections.unmodifiableList(copy);
        this.interpolationValues = buildInterpolationValues(this.waypoints);
        this.interpolationTangents = new double[INTERPOLATED_COMPONENT_COUNT][];
        for (int component = 0; component < INTERPOLATED_COMPONENT_COUNT; component++) {
            interpolationTangents[component] = computeTangents(
                    interpolationValues[component],
                    this.waypoints
            );
        }
    }

    List<Waypoint> waypoints() {
        return waypoints;
    }

    long durationMs() {
        return waypoints.get(waypoints.size() - 1).timeMs();
    }

    /**
     * Samples the path at an elapsed playback time.
     */
    Sample sampleAt(long timeMs) {
        if (timeMs <= 0L) {
            return sampleOf(0);
        }
        if (timeMs >= durationMs()) {
            return sampleOf(waypoints.size() - 1);
        }

        // Recordings can contain thousands of tick samples. Locate the segment
        // in logarithmic time so sampling the shared path does not add a
        // path-length-dependent cost to performance benchmarks.
        int low = 1;
        int high = waypoints.size() - 1;
        while (low < high) {
            int midpoint = (low + high) >>> 1;
            if (waypoints.get(midpoint).timeMs() <= timeMs) {
                low = midpoint + 1;
            } else {
                high = midpoint;
            }
        }
        int segmentIndex = low - 1;

        Waypoint start = waypoints.get(segmentIndex);
        Waypoint end = waypoints.get(segmentIndex + 1);
        double segmentDurationMs = (double) (end.timeMs() - start.timeMs());
        double t = (timeMs - start.timeMs()) / segmentDurationMs;
        t = Math.max(0.0, Math.min(1.0, t));

        return new Sample(
                interpolateComponent(X, segmentIndex, segmentDurationMs, t),
                interpolateComponent(Y, segmentIndex, segmentDurationMs, t),
                interpolateComponent(Z, segmentIndex, segmentDurationMs, t),
                (float) interpolateComponent(YAW, segmentIndex, segmentDurationMs, t),
                (float) interpolateComponent(PITCH, segmentIndex, segmentDurationMs, t),
                interpolateTimeOfDay(start.timeOfDay(), end.timeOfDay(), t)
        );
    }

    private Sample sampleOf(int index) {
        Waypoint waypoint = waypoints.get(index);
        return new Sample(
                waypoint.x(),
                waypoint.y(),
                waypoint.z(),
                (float) interpolationValues[YAW][index],
                waypoint.pitch(),
                waypoint.timeOfDay()
        );
    }

    private double interpolateComponent(
            int component,
            int segmentIndex,
            double segmentDurationMs,
            double t
    ) {
        double start = interpolationValues[component][segmentIndex];
        double end = interpolationValues[component][segmentIndex + 1];
        double startTangent = interpolationTangents[component][segmentIndex];
        double endTangent = interpolationTangents[component][segmentIndex + 1];

        double t2 = t * t;
        double t3 = t2 * t;
        double h00 = 2.0 * t3 - 3.0 * t2 + 1.0;
        double h10 = t3 - 2.0 * t2 + t;
        double h01 = -2.0 * t3 + 3.0 * t2;
        double h11 = t3 - t2;
        double value = h00 * start
                + h10 * segmentDurationMs * startTangent
                + h01 * end
                + h11 * segmentDurationMs * endTangent;

        if (!Double.isFinite(value)) {
            value = (1.0 - t) * start + t * end;
        }
        return clamp(value, Math.min(start, end), Math.max(start, end));
    }

    private static double[][] buildInterpolationValues(List<Waypoint> waypoints) {
        int size = waypoints.size();
        double[][] values = new double[INTERPOLATED_COMPONENT_COUNT][size];
        for (int i = 0; i < size; i++) {
            Waypoint waypoint = waypoints.get(i);
            values[X][i] = waypoint.x();
            values[Y][i] = waypoint.y();
            values[Z][i] = waypoint.z();
            values[PITCH][i] = waypoint.pitch();
        }

        values[YAW][0] = waypoints.get(0).yaw();
        for (int i = 1; i < size; i++) {
            double rawDelta = (double) waypoints.get(i).yaw() - waypoints.get(i - 1).yaw();
            values[YAW][i] = values[YAW][i - 1] + wrapDegrees(rawDelta);
        }
        return values;
    }

    /**
     * Computes shape-preserving PCHIP derivatives in units per millisecond.
     *
     * <p>The weighted harmonic mean gives a single derivative at every interior
     * waypoint, so adjoining Hermite segments are C1 in elapsed time. Setting
     * the derivative to zero at a flat spot or direction change prevents the
     * spline from inventing motion between stationary waypoints.</p>
     */
    private static double[] computeTangents(double[] values, List<Waypoint> waypoints) {
        int size = values.length;
        double[] tangents = new double[size];
        if (size == 2) {
            double slope = secantSlope(values, waypoints, 0);
            tangents[0] = slope;
            tangents[1] = slope;
            return tangents;
        }

        double firstInterval = intervalMs(waypoints, 0);
        double secondInterval = intervalMs(waypoints, 1);
        double firstSlope = secantSlope(values, waypoints, 0);
        double secondSlope = secantSlope(values, waypoints, 1);
        tangents[0] = endpointTangent(firstInterval, secondInterval, firstSlope, secondSlope);

        for (int i = 1; i < size - 1; i++) {
            double previousInterval = intervalMs(waypoints, i - 1);
            double nextInterval = intervalMs(waypoints, i);
            double previousSlope = secantSlope(values, waypoints, i - 1);
            double nextSlope = secantSlope(values, waypoints, i);
            tangents[i] = interiorTangent(
                    previousInterval,
                    nextInterval,
                    previousSlope,
                    nextSlope
            );
        }

        double penultimateInterval = intervalMs(waypoints, size - 3);
        double lastInterval = intervalMs(waypoints, size - 2);
        double penultimateSlope = secantSlope(values, waypoints, size - 3);
        double lastSlope = secantSlope(values, waypoints, size - 2);
        tangents[size - 1] = endpointTangent(
                lastInterval,
                penultimateInterval,
                lastSlope,
                penultimateSlope
        );
        return tangents;
    }

    private static double intervalMs(List<Waypoint> waypoints, int segmentIndex) {
        return (double) (waypoints.get(segmentIndex + 1).timeMs()
                - waypoints.get(segmentIndex).timeMs());
    }

    private static double secantSlope(double[] values, List<Waypoint> waypoints, int segmentIndex) {
        double slope = (values[segmentIndex + 1] - values[segmentIndex])
                / intervalMs(waypoints, segmentIndex);
        return Double.isFinite(slope) ? slope : 0.0;
    }

    private static double interiorTangent(
            double previousInterval,
            double nextInterval,
            double previousSlope,
            double nextSlope
    ) {
        if (previousSlope == 0.0 || nextSlope == 0.0
                || Math.signum(previousSlope) != Math.signum(nextSlope)) {
            return 0.0;
        }

        double previousWeight = 2.0 * nextInterval + previousInterval;
        double nextWeight = nextInterval + 2.0 * previousInterval;
        double tangent = (previousWeight + nextWeight)
                / (previousWeight / previousSlope + nextWeight / nextSlope);
        return Double.isFinite(tangent) ? tangent : 0.0;
    }

    private static double endpointTangent(
            double endpointInterval,
            double adjacentInterval,
            double endpointSlope,
            double adjacentSlope
    ) {
        double tangent = (
                (2.0 * endpointInterval + adjacentInterval) * endpointSlope
                        - endpointInterval * adjacentSlope
        ) / (endpointInterval + adjacentInterval);
        if (!Double.isFinite(tangent) || Math.signum(tangent) != Math.signum(endpointSlope)) {
            return 0.0;
        }
        if (Math.signum(endpointSlope) != Math.signum(adjacentSlope)
                && Math.abs(tangent) > 3.0 * Math.abs(endpointSlope)) {
            return 3.0 * endpointSlope;
        }
        return tangent;
    }

    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        } else if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Long interpolateTimeOfDay(Long startTimeOfDay, Long endTimeOfDay, double t) {
        if (startTimeOfDay == null && endTimeOfDay == null) {
            return null;
        }
        if (startTimeOfDay == null) {
            return normalizeTimeOfDay(endTimeOfDay);
        }
        if (endTimeOfDay == null) {
            return normalizeTimeOfDay(startTimeOfDay);
        }

        long start = normalizeTimeOfDay(startTimeOfDay);
        long end = normalizeTimeOfDay(endTimeOfDay);
        long delta = end - start;
        if (delta > MINECRAFT_DAY_TICKS / 2) {
            delta -= MINECRAFT_DAY_TICKS;
        } else if (delta < -MINECRAFT_DAY_TICKS / 2) {
            delta += MINECRAFT_DAY_TICKS;
        }
        return normalizeTimeOfDay(Math.round(start + delta * t));
    }

    private static long normalizeTimeOfDay(long timeOfDay) {
        return Math.floorMod(timeOfDay, MINECRAFT_DAY_TICKS);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof CameraPath
                && waypoints.equals(((CameraPath) other).waypoints);
    }

    @Override
    public int hashCode() {
        return waypoints.hashCode();
    }

    @Override
    public String toString() {
        return "CameraPath[waypoints=" + waypoints + ']';
    }

    /**
     * A camera pose at an absolute time in the path. {@code timeOfDay} is
     * nullable; non-null values are normalized to Minecraft's 0-23999 day.
     */
    static final class Waypoint {
        private final long timeMs;
        private final double x;
        private final double y;
        private final double z;
        private final float yaw;
        private final float pitch;
        private final Long timeOfDay;

        Waypoint(long timeMs, double x, double y, double z, float yaw, float pitch, Long timeOfDay) {
            if (timeMs < 0L) {
                throw new IllegalArgumentException("waypoint timeMs must be non-negative");
            }
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("waypoint position must be finite");
            }
            if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("waypoint rotation must be finite");
            }
            this.timeMs = timeMs;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.timeOfDay = timeOfDay != null ? normalizeTimeOfDay(timeOfDay) : null;
        }

        long timeMs() { return timeMs; }
        double x() { return x; }
        double y() { return y; }
        double z() { return z; }
        float yaw() { return yaw; }
        float pitch() { return pitch; }
        Long timeOfDay() { return timeOfDay; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Waypoint)) return false;
            Waypoint waypoint = (Waypoint) other;
            return timeMs == waypoint.timeMs
                    && Double.compare(x, waypoint.x) == 0
                    && Double.compare(y, waypoint.y) == 0
                    && Double.compare(z, waypoint.z) == 0
                    && Float.compare(yaw, waypoint.yaw) == 0
                    && Float.compare(pitch, waypoint.pitch) == 0
                    && Objects.equals(timeOfDay, waypoint.timeOfDay);
        }

        @Override
        public int hashCode() {
            return Objects.hash(timeMs, x, y, z, yaw, pitch, timeOfDay);
        }

        @Override
        public String toString() {
            return "Waypoint[timeMs=" + timeMs + ", x=" + x + ", y=" + y
                    + ", z=" + z + ", yaw=" + yaw + ", pitch=" + pitch
                    + ", timeOfDay=" + timeOfDay + "]";
        }
    }

    /**
     * An interpolated camera pose. {@code timeOfDay} is null when neither
     * endpoint of the sampled segment specifies one.
     */
    static final class Sample {
        private final double x;
        private final double y;
        private final double z;
        private final float yaw;
        private final float pitch;
        private final Long timeOfDay;

        Sample(double x, double y, double z, float yaw, float pitch, Long timeOfDay) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.timeOfDay = timeOfDay;
        }

        double x() { return x; }
        double y() { return y; }
        double z() { return z; }
        float yaw() { return yaw; }
        float pitch() { return pitch; }
        Long timeOfDay() { return timeOfDay; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Sample)) return false;
            Sample sample = (Sample) other;
            return Double.compare(x, sample.x) == 0
                    && Double.compare(y, sample.y) == 0
                    && Double.compare(z, sample.z) == 0
                    && Float.compare(yaw, sample.yaw) == 0
                    && Float.compare(pitch, sample.pitch) == 0
                    && Objects.equals(timeOfDay, sample.timeOfDay);
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, z, yaw, pitch, timeOfDay);
        }

        @Override
        public String toString() {
            return "Sample[x=" + x + ", y=" + y + ", z=" + z + ", yaw=" + yaw
                    + ", pitch=" + pitch + ", timeOfDay=" + timeOfDay + "]";
        }
    }
}

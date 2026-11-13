/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.logic;

import java.util.Locale;
import java.util.Objects;

/**
 * A location zone parsed from a declarative "rtx zone define ..." string,
 * typed by a player or carried (never executed) by a command block.
 *
 * Grammar:
 *   [/]rtx zone define <name>
 *       ([--inner r] [--outer r] [--center x y z] | --box x1 y1 z1 x2 y2 z2 [--fade d])
 *       [--channel mc.zone.<name>]             (defaults to ZONE_PREFIX + name)
 *
 * Sphere radii are optional for fast walk-around marking: with both omitted
 * they default to inner 10 / outer 30; a lone --inner r implies
 * outer = r + 20 and a lone --outer r implies inner = max(0, r - 20)
 * (DEFAULT_FADE_BAND). Box --fade defaults to 20. All validation applies
 * after defaulting, and usedDefaultRadii()/usedDefaultFade() report when
 * defaults were used so callers can say so.
 *
 * --center is sphere-only (box corners are absolute) and defaults to the
 * source block/player position. --fade is box-only; spheres fall off across
 * the --inner/--outer band. Duplicate flags and non-finite numbers are
 * rejected.
 *
 * Pure Java on purpose: no Minecraft types, so parsing and weight math are
 * unit-testable without the client.
 */
public final class ZoneDefinition {
    // Parser defaults.
    /** Sphere inner radius when both --inner and --outer are omitted. */
    static final double DEFAULT_INNER_RADIUS = 10.0;
    /** Default falloff band: outer - inner for spheres, --fade for boxes. */
    static final double DEFAULT_FADE_BAND = 20.0;

    private final String name;
    private final String channel;
    private final boolean isBox;
    // Sphere fields (isBox == false)
    private final double cx, cy, cz, innerRadius, outerRadius;
    // Box fields (isBox == true)
    private final double minX, minY, minZ, maxX, maxY, maxZ, fade;
    private final boolean usedDefaultRadii;
    private final boolean usedDefaultFade;

    private ZoneDefinition(String name, String channel, boolean isBox,
                           double cx, double cy, double cz, double innerRadius, double outerRadius,
                           double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double fade,
                           boolean usedDefaultRadii, boolean usedDefaultFade) {
        this.name = name;
        this.channel = channel;
        this.isBox = isBox;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.innerRadius = innerRadius;
        this.outerRadius = outerRadius;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.fade = fade;
        this.usedDefaultRadii = usedDefaultRadii;
        this.usedDefaultFade = usedDefaultFade;
    }

    public String name() {
        return name;
    }

    public String channel() {
        return channel;
    }

    public boolean isBox() {
        return isBox;
    }

    /** Sphere center X (0 for box zones). */
    public double centerX() {
        return cx;
    }

    /** Sphere center Y (0 for box zones). */
    public double centerY() {
        return cy;
    }

    /** Sphere center Z (0 for box zones). */
    public double centerZ() {
        return cz;
    }

    /** Sphere-only (0 for box zones). */
    public double innerRadius() {
        return innerRadius;
    }

    /** Sphere-only (0 for box zones). */
    public double outerRadius() {
        return outerRadius;
    }

    /** Box-only minimum X (0 for sphere zones). */
    public double minX() {
        return minX;
    }

    /** Box-only minimum Y (0 for sphere zones). */
    public double minY() {
        return minY;
    }

    /** Box-only minimum Z (0 for sphere zones). */
    public double minZ() {
        return minZ;
    }

    /** Box-only maximum X (0 for sphere zones). */
    public double maxX() {
        return maxX;
    }

    /** Box-only maximum Y (0 for sphere zones). */
    public double maxY() {
        return maxY;
    }

    /** Box-only maximum Z (0 for sphere zones). */
    public double maxZ() {
        return maxZ;
    }

    /** Box-only (0 for sphere zones). */
    public double fade() {
        return fade;
    }

    /** True for sphere zones whose --inner/--outer were fully or partly defaulted. */
    public boolean usedDefaultRadii() {
        return usedDefaultRadii;
    }

    /** True for box zones whose --fade was defaulted. */
    public boolean usedDefaultFade() {
        return usedDefaultFade;
    }

    /**
     * Reconstructs a normalized sphere from trusted storage fields. Package
     * private so the world-save codec can share the parser's validation
     * without introducing Minecraft NBT types into this pure value object.
     */
    static ZoneDefinition storedSphere(String name, String channel,
                                       double cx, double cy, double cz,
                                       double innerRadius, double outerRadius) {
        return createSphere(name, channel, cx, cy, cz, innerRadius, outerRadius, false);
    }

    /** Reconstructs a normalized box from trusted storage fields. */
    static ZoneDefinition storedBox(String name, String channel,
                                    double minX, double minY, double minZ,
                                    double maxX, double maxY, double maxZ,
                                    double fade) {
        return createBox(name, channel, minX, minY, minZ, maxX, maxY, maxZ, fade, false);
    }

    /** Spatial weight in [0,1] for a camera position; smoothstep falloff. */
    public float weight(double x, double y, double z) {
        double t;
        if (isBox) {
            double dx = Math.max(Math.max(minX - x, 0.0), x - maxX);
            double dy = Math.max(Math.max(minY - y, 0.0), y - maxY);
            double dz = Math.max(Math.max(minZ - z, 0.0), z - maxZ);
            double outside = Math.sqrt(dx * dx + dy * dy + dz * dz);
            t = 1.0 - Math.min(outside / fade, 1.0);
        } else {
            double dist = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz));
            if (dist <= innerRadius) {
                t = 1.0;
            } else if (dist >= outerRadius) {
                t = 0.0;
            } else {
                t = (outerRadius - dist) / (outerRadius - innerRadius);
            }
        }
        return (float) (t * t * (3.0 - 2.0 * t)); // smoothstep
    }

    public static ZoneDefinition parse(String spec, double defaultX, double defaultY, double defaultZ) {
        String[] tokens = spec.trim().split("\\s+");
        int i = 0;
        if (i < tokens.length && tokens[i].startsWith("/")) {
            tokens[i] = tokens[i].substring(1);
        }
        if (tokens.length < 3 || !tokens[0].equals("rtx") || !tokens[1].equals("zone") || !tokens[2].equals("define")) {
            throw new IllegalArgumentException("not an 'rtx zone define' command: " + spec);
        }
        if (tokens.length < 4) {
            throw new IllegalArgumentException("zone name missing");
        }
        String name = tokens[3];
        if (name.startsWith("--")) {
            throw new IllegalArgumentException("zone name missing");
        }

        double cx = defaultX, cy = defaultY, cz = defaultZ;
        boolean centerSpecified = false;
        Double inner = null, outer = null, fade = null;
        double[] box = null;
        String channel = null;

        i = 4;
        while (i < tokens.length) {
            String flag = tokens[i].toLowerCase(Locale.ROOT);
            switch (flag) {
                case "--center": {
                    rejectDuplicate(centerSpecified, flag);
                    centerSpecified = true;
                    cx = parseDouble(tokens, i + 1, flag);
                    cy = parseDouble(tokens, i + 2, flag);
                    cz = parseDouble(tokens, i + 3, flag);
                    i += 4;
                    break;
                }
                case "--inner": {
                    rejectDuplicate(inner != null, flag);
                    inner = parseDouble(tokens, i + 1, flag);
                    i += 2;
                    break;
                }
                case "--outer": {
                    rejectDuplicate(outer != null, flag);
                    outer = parseDouble(tokens, i + 1, flag);
                    i += 2;
                    break;
                }
                case "--fade": {
                    rejectDuplicate(fade != null, flag);
                    fade = parseDouble(tokens, i + 1, flag);
                    i += 2;
                    break;
                }
                case "--box": {
                    rejectDuplicate(box != null, flag);
                    box = new double[6];
                    for (int k = 0; k < 6; k++) {
                        box[k] = parseDouble(tokens, i + 1 + k, flag);
                    }
                    i += 7;
                    break;
                }
                case "--channel": {
                    rejectDuplicate(channel != null, flag);
                    if (i + 1 >= tokens.length || tokens[i + 1].startsWith("--")) {
                        throw new IllegalArgumentException("--channel needs a value");
                    }
                    channel = tokens[i + 1];
                    i += 2;
                    break;
                }
                default:
                    throw new IllegalArgumentException("unknown flag: " + tokens[i]);
            }
        }

        if (channel == null) {
            channel = LogicChannels.ZONE_PREFIX + name;
        }

        if (box != null) {
            if (inner != null || outer != null) {
                throw new IllegalArgumentException("--box cannot be combined with --inner/--outer");
            }
            if (centerSpecified) {
                throw new IllegalArgumentException("--center is not valid with --box (box corners are absolute)");
            }
            boolean usedDefaultFade = false;
            if (fade == null) {
                fade = DEFAULT_FADE_BAND;
                usedDefaultFade = true;
            }
            return createBox(name, channel,
                    box[0], box[1], box[2], box[3], box[4], box[5], fade, usedDefaultFade);
        }
        if (fade != null) {
            throw new IllegalArgumentException("--fade is only valid with --box (use --inner/--outer for sphere falloff)");
        }
        // Optional radii for fast walk-around marking; validation below still
        // applies to the defaulted values.
        boolean usedDefaultRadii = false;
        if (inner == null && outer == null) {
            inner = DEFAULT_INNER_RADIUS;
            outer = DEFAULT_INNER_RADIUS + DEFAULT_FADE_BAND;
            usedDefaultRadii = true;
        } else if (outer == null) {
            outer = inner + DEFAULT_FADE_BAND;
            usedDefaultRadii = true;
        } else if (inner == null) {
            inner = Math.max(0.0, outer - DEFAULT_FADE_BAND);
            usedDefaultRadii = true;
        }
        return createSphere(name, channel, cx, cy, cz, inner, outer, usedDefaultRadii);
    }

    private static ZoneDefinition createSphere(String name, String channel,
                                               double cx, double cy, double cz,
                                               double innerRadius, double outerRadius,
                                               boolean usedDefaultRadii) {
        validateIdentity(name, channel);
        requireFinite("sphere center/radii", cx, cy, cz, innerRadius, outerRadius);
        if (innerRadius < 0) {
            throw new IllegalArgumentException("--inner must be >= 0");
        }
        if (innerRadius >= outerRadius) {
            throw new IllegalArgumentException("--inner must be smaller than --outer");
        }
        return new ZoneDefinition(name, channel, false, cx, cy, cz, innerRadius, outerRadius,
                0, 0, 0, 0, 0, 0, 0, usedDefaultRadii, false);
    }

    private static ZoneDefinition createBox(String name, String channel,
                                            double x1, double y1, double z1,
                                            double x2, double y2, double z2,
                                            double fade, boolean usedDefaultFade) {
        validateIdentity(name, channel);
        requireFinite("box corners/fade", x1, y1, z1, x2, y2, z2, fade);
        if (fade <= 0) {
            throw new IllegalArgumentException("--fade must be > 0");
        }
        return new ZoneDefinition(name, channel, true,
                0, 0, 0, 0, 0,
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2), fade,
                false, usedDefaultFade);
    }

    private static void validateIdentity(String name, String channel) {
        if (name == null || name.trim().isEmpty() || name.startsWith("--") || containsWhitespace(name)) {
            throw new IllegalArgumentException("invalid zone name");
        }
        if (channel == null || channel.trim().isEmpty() || containsWhitespace(channel)) {
            throw new IllegalArgumentException("invalid zone channel");
        }
    }

    private static boolean containsWhitespace(String value) {
        return value.codePoints().anyMatch(Character::isWhitespace);
    }

    private static void requireFinite(String fields, double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(fields + " must be finite");
            }
        }
    }

    private static void rejectDuplicate(boolean seen, String flag) {
        if (seen) {
            throw new IllegalArgumentException("duplicate flag: " + flag);
        }
    }

    private static double parseDouble(String[] tokens, int index, String flag) {
        if (index >= tokens.length) {
            throw new IllegalArgumentException(flag + " needs more values");
        }
        double v;
        try {
            v = Double.parseDouble(tokens[index]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + ": not a number: " + tokens[index]);
        }
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException(flag + ": not a finite number: " + tokens[index]);
        }
        return v;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ZoneDefinition)) {
            return false;
        }
        ZoneDefinition zone = (ZoneDefinition) other;
        return isBox == zone.isBox
                && Double.compare(cx, zone.cx) == 0
                && Double.compare(cy, zone.cy) == 0
                && Double.compare(cz, zone.cz) == 0
                && Double.compare(innerRadius, zone.innerRadius) == 0
                && Double.compare(outerRadius, zone.outerRadius) == 0
                && Double.compare(minX, zone.minX) == 0
                && Double.compare(minY, zone.minY) == 0
                && Double.compare(minZ, zone.minZ) == 0
                && Double.compare(maxX, zone.maxX) == 0
                && Double.compare(maxY, zone.maxY) == 0
                && Double.compare(maxZ, zone.maxZ) == 0
                && Double.compare(fade, zone.fade) == 0
                && name.equals(zone.name)
                && channel.equals(zone.channel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, channel, isBox, cx, cy, cz, innerRadius, outerRadius,
                minX, minY, minZ, maxX, maxY, maxZ, fade);
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

/**
 * Shared tuning for the lighthouse beam emitted by a Copper Beacon.
 *
 * <p>The host builds all three paths: an articulated placed block
 * entity, a {@code block_display} carrying a beacon state, and a held item.
 *
 * <p>Emitter size, softness, focus, and the base cone angle are shared across all
 * three, so a beacon reads as the same instrument wherever it is. A local held
 * beam can widen from that base angle when nearby geometry limits its placement.
 * Radiance and god-ray strength remain path-specific because a lighthouse seen
 * across the map and a lamp at arm's length need very different energy.
 *
 * <p>Keep that split whole. Shaft brightness is radiance times volumetric
 * scale, so a path that borrows another path's volumetric constant silently
 * inherits its radiance changes as well. Each path owns both of its own
 * constants, and changing one path must not move another.
 *
 * <p>A beacon has to read from far away, so it runs much hotter than a lantern;
 * the narrow cone keeps the total light in the scene reasonable despite the high
 * radiance.
 */
public final class BeaconBeam {

    /**
     * Artist-tuned baseline shared by placed and held beacons. The
     * {@code block_display} lighthouse path keeps its independent radiance.
     */
    static final float AUTHORED_INTENSITY_MULTIPLIER = 5.0f;

    /** Radiance multiplier over a normal block light, for a <em>placed</em> beacon. */
    static final float INTENSITY_SCALE = 150.0f;

    /**
     * Radiance multiplier for a {@code block_display} entity carrying a beacon
     * block state, the sweeping lighthouse.
     *
     * <p>A hundred times {@link #INTENSITY_SCALE}: a sweeping display beacon
     * has to throw light across the landscape, while a placed block is usually
     * read from much closer to its own tower. This path deliberately inherits
     * neither {@link #AUTHORED_INTENSITY_MULTIPLIER} nor the Copper Beacon
     * player setting.
     */
    static final float DISPLAY_INTENSITY_SCALE = 15000.0f;

    /**
     * Radiance multiplier for a beacon held in an entity's hand, roughly a
     * third of {@link #INTENSITY_SCALE}.
     *
     * <p>A placed beacon has to read as a lighthouse from a long way off. One in
     * your hand only has to light the ground in front of you, and a world-scale
     * value at arm's length would white the whole view out.
     *
     * <p>Both this and {@link #INTENSITY_SCALE} inherit
     * {@link #AUTHORED_INTENSITY_MULTIPLIER}, so the ratio between them holds
     * when that baseline moves.
     */
    static final float HELD_INTENSITY_SCALE = 45.0f;

    /**
     * God-ray strength for a held beam seen in world space: another entity's
     * hand, or the local player's in third person. Kept separate from
     * {@link #VOLUMETRIC_SCALE} so the two can be tuned independently.
     *
     * <p>A held beam is a quarter as foggy as a placed one at equal radiance.
     * The emitter is near a viewer rather than out in the landscape, so the
     * shaft fills much more of the screen for the same energy.
     *
     * <p>The local first-person beam does not use this. It has no shaft at
     * all, because a camera-locked emitter smears the volumetric grid; see
     * {@code LightManager#updateLocalFirstPersonLights}.
     */
    static final float HELD_VOLUMETRIC_SCALE = 0.0125f;

    /**
     * How far ahead of the hand the held emitter sits. World models use the
     * holder's look direction; the local first-person model uses horizontal look
     * so pitching down cannot move the emitter back into the player.
     *
     * <p>This is a world-space clearance rather than part of the view-model
     * transform. The holder's body is real shadow-casting geometry in Remix,
     * so the emitter must remain far enough ahead of the hand and torso to
     * avoid being occluded by the model it is attached to.
     */
    static final float HELD_LENS_OFFSET = 0.5f;

    /**
     * Surface gap beyond which the local hidden-player model stops attenuating
     * the held beam. The shaped emitter needs geometric separation, unlike the
     * softer falloff used by omnidirectional held lights.
     */
    static final float HELD_SHADOW_CLEARANCE = 0.05f;

    /**
     * Beam visibility in air (god rays) for a <em>placed</em> beacon. It scales
     * {@link #INTENSITY_SCALE}'s very large radiance, so read it as a damping
     * factor rather than as a fraction of a default.
     *
     * <p>Because the two multiply, changing {@link #INTENSITY_SCALE} moves the
     * shaft as well. Hold this ratio in mind before tuning either in isolation.
     */
    static final float VOLUMETRIC_SCALE = 0.05f;

    /**
     * God-ray strength for a {@code block_display} beacon. Held separate from
     * {@link #VOLUMETRIC_SCALE} even at an equal value, so the display shaft can
     * be tuned without dragging the placed shaft with it: shaft brightness is
     * radiance times this scale, and the two paths' radiance scales differ by
     * 100x, their resulting radiances by 20x once the placed path's
     * {@link #AUTHORED_INTENSITY_MULTIPLIER} is applied.
     */
    static final float DISPLAY_VOLUMETRIC_SCALE = 0.05f;

    /** Disk radius, matching half the six-pixel-wide lens. */
    static final float RADIUS = 3.0f / 16.0f;

    /**
     * The lamp head pivots two pixels above the block centre along its mounting
     * axis. Its lens face is four pixels forward from that pivot, and the light
     * centre sits one more pixel forward to clear the lens surface.
     */
    private static final float HEAD_UP_OFFSET = 2.0f / 16.0f;
    private static final float LENS_FACE_OFFSET = 4.0f / 16.0f;
    private static final float LENS_CLEARANCE = 1.0f / 16.0f;
    private static final float LIGHT_FORWARD_OFFSET = LENS_FACE_OFFSET + LENS_CLEARANCE;

    /**
     * Half-angle of the beam, measured from the axis, so this is a 60&deg;-wide
     * cone, not 30&deg;. The runtime compares {@code cos(CONE_ANGLE_DEGREES)}
     * against the dot product of the beam axis and the sample direction.
     */
    public static final float CONE_ANGLE_DEGREES = 30.0f;

    /** Maximum held-beam half-angle when a wall forces the emitter toward the camera. */
    static final float HELD_NEAR_WALL_CONE_ANGLE_DEGREES = 80.0f;

    /** Fraction of the preferred lens travel over which near-wall widening blends in. */
    private static final float HELD_NEAR_WALL_TRANSITION_FRACTION = 0.5f;

    /**
     * Width of the soft edge at the rim of the cone, in <em>cosine</em> units,
     * not degrees: the runtime does
     * {@code smoothstep(cosConeAngle, cosConeAngle + CONE_SOFTNESS, cosTheta)}.
     *
     * <p>That unit matters. 0.25 is a wide band relative to any narrow cone, and
     * when {@code cos(CONE_ANGLE_DEGREES) + CONE_SOFTNESS} exceeds 1.0 the
     * smoothstep can never saturate, so even the dead centre of the beam is
     * attenuated. Narrowing the cone therefore makes the beam <em>dimmer</em> on
     * axis rather than more concentrated, the opposite of the intuition.
     */
    static final float CONE_SOFTNESS = 0.25f;

    static final float FOCUS_EXPONENT = 2.0f;

    private BeaconBeam() {}

    /** Beam radiance scalar for a placed beacon before its per-block multiplier. */
    static float intensity(int luminance) {
        return luminance
                * LightPowerPolicy.RADIANCE_PER_LIGHT_LEVEL
                * INTENSITY_SCALE
                * AUTHORED_INTENSITY_MULTIPLIER;
    }

    /** Beam radiance scalar for a {@code block_display} beacon. */
    static float displayIntensity(int luminance) {
        return luminance
                * LightPowerPolicy.RADIANCE_PER_LIGHT_LEVEL
                * DISPLAY_INTENSITY_SCALE;
    }

    /** Beam radiance scalar for a beacon held in an entity's hand. */
    static float heldIntensity(int luminance) {
        return luminance
                * LightPowerPolicy.RADIANCE_PER_LIGHT_LEVEL
                * HELD_INTENSITY_SCALE
                * AUTHORED_INTENSITY_MULTIPLIER;
    }

    static float heldConeAngle(double placementFraction) {
        float blend = (float) Math.max(
                0.0,
                Math.min(
                        1.0,
                        (1.0 - placementFraction)
                                / HELD_NEAR_WALL_TRANSITION_FRACTION));
        blend = blend * blend * (3.0f - 2.0f * blend);
        return CONE_ANGLE_DEGREES
                + (HELD_NEAR_WALL_CONE_ANGLE_DEGREES - CONE_ANGLE_DEGREES) * blend;
    }

    /** Approximately preserves total beam power as the near-wall cone widens. */
    static float heldConeRadianceScale(float coneAngleDegrees) {
        double baseSolidAngle = 1.0 - Math.cos(Math.toRadians(CONE_ANGLE_DEGREES));
        double widenedSolidAngle = 1.0 - Math.cos(Math.toRadians(coneAngleDegrees));
        return (float) (baseSolidAngle / widenedSolidAngle);
    }

    /**
     * Block-local coordinate of the neutral lamp emitter on one axis.
     * Local space is the [0,1]^3 cube occupied by the block.
     */
    static float lensLocal(int mountOffset, int facingOffset) {
        return 0.5f
                + HEAD_UP_OFFSET * mountOffset
                + LIGHT_FORWARD_OFFSET * facingOffset;
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The smoke a held lit item gives off, mirroring the SMOKE particle a placed
 * torch spawns from the same display tick as its flame.
 *
 * <p>Vanilla's puff is a {@code FireSmokeParticle}: a dark speck that
 * appears at the flame anchor with a small random velocity, accelerates
 * upward under negative gravity against drag, and dissolves by stepping
 * through eight sprites over an 8 to 40 tick life. A torch within a few blocks
 * of the player gets a display tick about every 7 ticks, so two puffs are
 * typically in the air. The puff is drawn with the soot grey the particle
 * pipeline gives vanilla's, see {@code ParticleTint}.
 *
 * <p>There is no particle state here. A fixed number of slots each spawn one
 * puff per period, and every property of a puff is a function of its slot,
 * its cycle number and the current time, so any frame can be evaluated on its
 * own and every torch in the world sees the same physics. Lifetimes stay
 * inside the period so a slot never holds two puffs.
 *
 * <p>Every slot is always drawn, a dormant one as a vanishingly small quad
 * parked at the anchor. The submitter keys retained instances by part
 * ordinal, so the slots must keep their positions in the emission order: if
 * dormant slots were skipped, an expiring puff would hand its ordinal to the
 * next puff, whose instance would then teleport, and the next hand's flame
 * would change identity too. Parking at the anchor also means a puff's
 * instance is already where it spawns, so its first live frame has no
 * spurious motion.
 */
final class HeldItemSmoke {

    /** Slots spawning one puff each per period: one puff every 8 ticks overall. */
    static final int SLOTS = 3;
    static final int PERIOD_TICKS = 24;
    /**
     * Vanilla's lower bound, and an upper bound well under vanilla's 40. A held
     * puff is pinned to the hand, so a long-lived one would rise a block or more
     * and swing with the arm as a rigid column; 16 ticks keeps the trail under
     * three quarters of a block.
     */
    static final int MIN_LIFETIME_TICKS = 8;
    static final int MAX_LIFETIME_TICKS = 16;

    // FireSmokeParticle through AscendingParticle and Particle.
    static final float DRAG_PER_TICK = 0.96f;
    /** {@code -0.04 * gravityStrength} with gravity -0.1: the puff accelerates upward. */
    static final float RISE_PER_TICK = 0.004f;
    /** AscendingParticle scales the base particle's random velocity by this. */
    static final float VELOCITY_MULTIPLIER = 0.1f;
    /** Billboard scale 0.1 to 0.2, times AscendingParticle's 0.75. */
    static final float MIN_HALF_EXTENT = 0.075f;
    static final float MAX_HALF_EXTENT = 0.15f;
    /**
     * Vertex alpha of a held puff. In first person the puff is the smoke sprite
     * at its placed size a hand's length from the camera, where an opaque soot
     * speck covers a visible patch of screen and reads as a black block. Below
     * about 0.65 it reads as nothing: its own radiance is near zero and
     * DLSS-RR's transparency layer gives back most of what a partial-alpha
     * surface attenuated. 0.75 is the band where it reads as smoke. A placed
     * torch's puff keeps the particle's own alpha ({@code RemixParticleRenderer}
     * packs it): metres away and a few pixels wide, an opaque speck is the
     * intended look, and 0.75 would put it at that floor. Third-person hands
     * share this constant; it was tuned in first person only.
     */
    static final float OPACITY = 0.75f;
    /** Half-extent of a dormant slot's quad: far too small to hit, but not singular. */
    static final float DORMANT_HALF_EXTENT = 1.0e-4f;
    /** Ticks a hand's phase shifts the schedule, so two hands do not puff in step. */
    private static final float PHASE_TICKS = 5.3f;

    /** One slot evaluated at a moment, in world blocks relative to the flame's centre. */
    static final class Puff {
        boolean alive;
        float x, y, z;
        float halfExtent;
        int age;
        int maxAge;
    }

    private HeldItemSmoke() {}

    /**
     * Whether smoke is drawn for a hand mode. Only first person has a switch;
     * everyone else's torches always smoke, as placed ones do.
     */
    static boolean showsFor(ItemDisplayContext mode, boolean firstPersonSmokeEnabled) {
        return !mode.firstPerson() || firstPersonSmokeEnabled;
    }

    /**
     * Evaluates every slot at {@code ticks} of world time. {@code out[slot]} is
     * always written; a dormant slot is parked at the anchor with
     * {@link Puff#alive} false.
     *
     * @param ticks world time in ticks, fractional
     * @param phase distinguishes flames that share a clock
     * @param out   exactly {@link #SLOTS} entries
     * @return the number of live puffs
     */
    static int evaluate(double ticks, float phase, Puff[] out) {
        int live = 0;
        double shifted = ticks + phase * PHASE_TICKS;
        for (int slot = 0; slot < SLOTS; slot++) {
            double slotTime = shifted + slot * ((double) PERIOD_TICKS / SLOTS);
            long cycle = (long) Math.floor(slotTime / PERIOD_TICKS);
            float age = (float) (slotTime - cycle * (double) PERIOD_TICKS);
            long seed = seed(slot, cycle, phase);
            int maxAge = MIN_LIFETIME_TICKS
                    + (int) (unit(seed, 0) * (MAX_LIFETIME_TICKS - MIN_LIFETIME_TICKS + 1));
            if (age < maxAge) {
                fill(out[slot], seed, age, maxAge);
                live++;
            } else {
                park(out[slot]);
            }
        }
        return live;
    }

    private static void fill(Puff puff, long seed, float age, int maxAge) {
        // Particle(world, x, y, z, 0, 0, 0): a random direction, a random speed,
        // a fixed upward bias, then AscendingParticle's multiplier.
        float dx = (unit(seed, 1) * 2.0f - 1.0f) * 0.4f;
        float dy = (unit(seed, 2) * 2.0f - 1.0f) * 0.4f;
        float dz = (unit(seed, 3) * 2.0f - 1.0f) * 0.4f;
        float speed = (unit(seed, 4) + unit(seed, 5) + 1.0f) * 0.15f;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float vx = dx / length * speed * 0.4f * VELOCITY_MULTIPLIER;
        float vy = (dy / length * speed * 0.4f + 0.1f) * VELOCITY_MULTIPLIER;
        float vz = dz / length * speed * 0.4f * VELOCITY_MULTIPLIER;

        // Particle.tick: gravity, then move, then drag. Integrate whole ticks and
        // interpolate the partial one, as vanilla lerps prevPos to pos.
        int wholeTicks = (int) age;
        float partial = age - wholeTicks;
        float x = 0.0f, y = 0.0f, z = 0.0f;
        for (int tick = 0; tick < wholeTicks; tick++) {
            vy += RISE_PER_TICK;
            x += vx; y += vy; z += vz;
            vx *= DRAG_PER_TICK; vy *= DRAG_PER_TICK; vz *= DRAG_PER_TICK;
        }
        vy += RISE_PER_TICK;
        puff.alive = true;
        puff.x = x + vx * partial;
        puff.y = y + vy * partial;
        puff.z = z + vz * partial;

        float scale = 0.1f * (unit(seed, 6) * 0.5f + 0.5f) * 2.0f * 0.75f;
        puff.halfExtent = scale * Math.min(1.0f, age / maxAge * 32.0f);
        puff.age = wholeTicks;
        puff.maxAge = maxAge;
    }

    private static void park(Puff puff) {
        puff.alive = false;
        puff.x = 0.0f;
        puff.y = 0.0f;
        puff.z = 0.0f;
        puff.halfExtent = DORMANT_HALF_EXTENT;
        puff.age = 0;
        puff.maxAge = 1;
    }

    /**
     * Builds the pose of a unit puff quad in the capture's space. The puff is a
     * world-sized particle like the flame: its anchor follows the item's display
     * transform, its displacement and size divide that transform back out, and
     * what remains of the pose's scale is the holder's own, so a half-size holder
     * trails half-size smoke. The displacement is measured in {@code frame}: world
     * up for a holder in the world, the camera's up on the first-person rig, as the
     * flame's rise is. The depth fraction moves and scales the puff about the eye
     * exactly as it does the flame, so the two keep their picture together.
     *
     * @param itemPose      the item's block-space-to-capture-space pose
     * @param displayScale  the uniform scale of the item model's display transform for
     *                      the hand being drawn, already applied inside {@code itemPose}
     * @param facing        rotation that turns the capture's XY plane toward the camera
     * @param frame         rotation of the frame the drift is measured in, from
     *                      {@link HeldItemFlame#riseFrame}
     * @param puff          the puff to place, in world blocks relative to the flame's centre
     * @param cameraOffset  push toward the camera, in block space
     * @param eye           the viewer's eye in the capture's space, or null when the capture
     *                      does not know it, which leaves the puff at depth 1
     * @param depthFraction the line-of-sight depth from {@link HeldItemFlame#depthFraction}
     * @param anchor        scratch, overwritten
     * @param scale         scratch, overwritten
     * @param dest          receives the pose
     * @return {@code dest}
     */
    static Matrix4f composePose(Matrix4f itemPose, float displayScale, Quaternionfc facing, Quaternionfc frame,
                                Puff puff, float cameraOffset, Vector3fc eye, float depthFraction,
                                Vector3f anchor, Vector3f scale, Matrix4f dest) {
        itemPose.transformPosition(anchor.set(
                HeldItemFlame.TIP_X, HeldItemFlame.TIP_Y, HeldItemFlame.TIP_Z));
        float itemScale = itemPose.getScale(scale).x;
        float worldScale = itemScale / displayScale;
        anchor.add(frame.transform(
                scale.set(puff.x, HeldItemFlame.FLAME_RISE + puff.y, puff.z).mul(worldScale)));
        anchor.add(facing.transform(scale.set(0.0f, 0.0f, cameraOffset * itemScale)));
        float depth = eye == null ? 1.0f : depthFraction;
        if (eye != null) HeldItemFlame.towardEye(anchor, eye, depth);
        return dest.translation(anchor)
                .rotate(facing)
                .scale(puff.halfExtent * worldScale * depth);
    }

    private static long seed(int slot, long cycle, float phase) {
        return mix(cycle * 0x9E3779B97F4A7C15L + slot * 0xC2B2AE3D27D4EB4FL
                + Float.floatToIntBits(phase));
    }

    /** A uniform float in [0, 1) drawn from the seed's {@code index}th stream. */
    private static float unit(long seed, int index) {
        long bits = mix(seed + index * 0x165667B19E3779F9L);
        return (bits >>> 40) * 0x1.0p-24f;
    }

    /** splitmix64 finalizer. */
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}

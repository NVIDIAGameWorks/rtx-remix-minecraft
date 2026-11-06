/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.mixin.TorchBlockAccessor;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.TorchBlock;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The lit particle element a hand-held block item carries.
 *
 * <p>A placed torch reads as lit in Remix because of the flame particle its
 * block spawns from {@code randomDisplayTick}, not because of the two emissive
 * texels in its texture. Vanilla never spawns that particle for a held item, so
 * a held torch has nothing lit about it beyond its analytic light. This class
 * decides which held items get the element and where it sits, in the item's
 * block space, so the same rule serves the first-person view model and every
 * entity's hands.
 *
 * <p>The element is a single camera-facing quad. Its geometry is a constant
 * unit quad so the pooled mesh never changes; anchor, scale, facing and
 * flicker all live in the pose {@link #composePose} builds.
 */
public final class HeldItemFlame {

    /** The top centre of the torch model, in block space; the flame sits on it. */
    static final float TIP_X = 0.5f;
    static final float TIP_Y = 0.625f;
    static final float TIP_Z = 0.5f;
    /**
     * How far above the tip the quad's centre sits, in world blocks. Vanilla spawns
     * a placed torch's flame at {@code y + 0.7}, 0.075 above the tip, which puts the
     * sprite's base texels in the head. It is applied along the up of
     * {@link #riseFrame} rather than the torch's axis: a hand leans the torch, and a
     * flame rises straight up from the tip however the stick leans, rather than
     * sitting further along it.
     */
    static final float FLAME_RISE = 0.075f;

    /** Half-extent of the quad in world blocks; vanilla flame billboards run 0.1 to 0.2. */
    static final float HALF_EXTENT = 0.14f;
    /**
     * How far a first-person flame is moved toward the camera along the view axis,
     * in block space. Vanilla's hand pose holds the card well off the view axis, so
     * this shifts the flame's image outward, over the head the viewer sees (its
     * front face and side sliver) rather than over the card's axis, where it reads
     * as sitting inside of the head. Only first person gets it: on another entity
     * it would float the flame off the torch when seen from the side.
     */
    static final float FIRST_PERSON_CAMERA_OFFSET = 0.05f;
    /**
     * Where a first-person flame is drawn along its line of sight, as a fraction
     * of the anchor's distance from the eye. The quad scales by the same fraction,
     * so its image does not change; only its depth does, and with it where the
     * torch card cuts the sprite.
     *
     * <p>Vanilla's hand pose turns the item card some 60 degrees from the line of
     * sight, and the camera offset has already moved the quad's centre off the
     * card's mid-plane toward the eye to centre its image on the head. Below about
     * 1.03 the whole quad is on the viewer's side of the card: the head goes dark
     * behind it and the flame reads as a card in front of the torch. Above about
     * 1.1 the card hides the flame across the head's whole silhouette. In between,
     * the card's front face cuts the sprite's base texels along a line that moves
     * about one texel per 0.05 of depth and sweeps a third of a texel with the
     * flicker, so the value keeps that line inside a texel rather than on an edge,
     * where the sliver it leaves flickers as a hairline. 1.04 puts it inside the
     * base's second texel: the head's front sits over the middle of the base with
     * a texel of flame showing on either side, as a placed torch's head sits in its
     * particle. Only first person gets it, because only that capture knows where
     * the eye is.
     */
    static final float FIRST_PERSON_DEPTH_FRACTION = 1.04f;
    /** Peak size deviation of the flicker, as a fraction of {@link #HALF_EXTENT}. */
    static final float FLICKER_AMPLITUDE = 0.12f;
    /** Peak vertical wander of the flicker, in world blocks. */
    static final float BOB_AMPLITUDE = 0.03f;

    private static final double TWO_PI = Math.PI * 2.0;

    private HeldItemFlame() {}

    /**
     * The particle a held stack's placed block would show as its lit element, or
     * null when the item has none.
     *
     * <p>Only the torch family qualifies today: its block spawns its own
     * {@link SimpleParticleType} every display tick. Redstone torches are
     * excluded because theirs is a puff of red dust rather than a flame, and an
     * unlit torch variant is excluded because there is nothing lit to show.
     */
    public static SimpleParticleType particleFor(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        Block block = blockItem.getBlock();
        if (!(block instanceof TorchBlock) || block instanceof RedstoneTorchBlock
                || block.defaultBlockState().getLightEmission() <= 0) {
            return null;
        }
        return ((TorchBlockAccessor) block).getParticle();
    }

    /** Whether an item render mode is a hand, in first or third person. */
    public static boolean isHandMode(ItemDisplayContext mode) {
        return mode.firstPerson()
                || mode == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                || mode == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
    }

    /** The toward-camera placement offset for a hand mode, in block space. */
    static float cameraOffset(ItemDisplayContext mode) {
        return mode.firstPerson() ? FIRST_PERSON_CAMERA_OFFSET : 0.0f;
    }

    /** The line-of-sight depth fraction for a hand mode; 1 leaves the quad at its anchor. */
    static float depthFraction(ItemDisplayContext mode) {
        return mode.firstPerson() ? FIRST_PERSON_DEPTH_FRACTION : 1.0f;
    }

    private static final Quaternionfc WORLD_FRAME = new Quaternionf();

    /**
     * The frame the flame rises and the smoke drifts in. A holder in the world gets
     * world up, so its flame rises straight up however the torch leans. The
     * first-person rig is locked to the camera and drawn the same at any pitch, so
     * its effects rise along the camera's up and keep their place on the card as the
     * view tilts; along world up they swung around the head with the pitch.
     */
    static Quaternionfc riseFrame(ItemDisplayContext mode, Quaternionfc facing) {
        return mode.firstPerson() ? facing : WORLD_FRAME;
    }

    /**
     * Size multiplier at {@code seconds} of world time, in
     * {@code [1 - FLICKER_AMPLITUDE, 1 + FLICKER_AMPLITUDE]}. Three incommensurate
     * sines give a pulse without an obvious period; {@code phase} separates
     * flames that would otherwise beat in unison.
     */
    static float flicker(double seconds, float phase) {
        return (float) (1.0 + FLICKER_AMPLITUDE * wave(seconds, phase));
    }

    /** Vertical offset at {@code seconds} of world time, in world blocks. */
    static float bob(double seconds, float phase) {
        return (float) (BOB_AMPLITUDE * wave(seconds * 0.7, phase + 2.1f));
    }

    private static double wave(double seconds, float phase) {
        double t = seconds * TWO_PI;
        return 0.5 * Math.sin(t * 1.7 + phase)
                + 0.35 * Math.sin(t * 4.3 + 1.7 * phase)
                + 0.15 * Math.sin(t * 9.1 + 0.4 * phase);
    }

    /**
     * Builds the pose of the unit flame quad in the capture's space.
     *
     * <p>The flame is a world-sized particle rising from the torch's tip. The tip
     * follows {@code itemPose}, display transform included, so the flame stays on
     * it however small a hand draws the torch; the quad's size, its rise and its
     * bob divide that transform back out, so a held flame is the size of a placed
     * one. What remains of the pose's scale is the holder's own, so a half-size
     * holder still carries a half-size flame. The camera offset stays in item
     * units: it clears the item card, which is drawn at the display transform.
     *
     * <p>The rise is applied along the up of {@code frame}: world up for a holder in
     * the world, the camera's up on the first-person rig, so it raises the flame
     * straight up whichever way the torch leans and, in first person, keeps it on the
     * card as the view pitches. The camera offset and
     * the bob are applied in the quad's own frame, after {@code facing}, so "toward
     * the camera" and "up" mean what the viewer sees. The depth fraction then moves
     * the quad along the line from the eye to its centre and scales it, bob included,
     * by the same amount, which leaves its image exactly where it was.
     *
     * @param itemPose      the item's block-space-to-capture-space pose
     * @param displayScale  the uniform scale of the item model's display transform for
     *                      the hand being drawn, already applied inside {@code itemPose}
     * @param facing        rotation that turns the capture's XY plane toward the camera
     * @param frame         rotation of the frame the rise is measured in, from {@link #riseFrame}
     * @param flicker       size multiplier from {@link #flicker}
     * @param bob           vertical offset from {@link #bob}, in world blocks
     * @param cameraOffset  push toward the camera from {@link #cameraOffset}, in block space
     * @param eye           the viewer's eye in the capture's space, or null when the capture
     *                      does not know it, which leaves the quad at depth 1
     * @param depthFraction the line-of-sight depth from {@link #depthFraction}
     * @param anchor        scratch, overwritten
     * @param scale         scratch, overwritten
     * @param dest          receives the pose
     * @return {@code dest}
     */
    static Matrix4f composePose(Matrix4f itemPose, float displayScale, Quaternionfc facing, Quaternionfc frame,
                                float flicker, float bob, float cameraOffset, Vector3fc eye, float depthFraction,
                                Vector3f anchor, Vector3f scale, Matrix4f dest) {
        itemPose.transformPosition(anchor.set(TIP_X, TIP_Y, TIP_Z));
        // The pose's scale is uniform, so any axis gives it.
        float itemScale = itemPose.getScale(scale).x;
        float worldScale = itemScale / displayScale;
        anchor.add(frame.transform(scale.set(0.0f, FLAME_RISE * worldScale, 0.0f)));
        anchor.add(facing.transform(scale.set(0.0f, 0.0f, cameraOffset * itemScale)));
        float depth = eye == null ? 1.0f : depthFraction;
        if (eye != null) towardEye(anchor, eye, depth);
        float depthScale = worldScale * depth;
        return dest.translation(anchor)
                .rotate(facing)
                .translate(0.0f, bob * depthScale, 0.0f)
                .scale(HALF_EXTENT * depthScale * flicker);
    }

    /** Moves {@code point} to {@code fraction} of its distance from {@code eye}. */
    static void towardEye(Vector3f point, Vector3fc eye, float fraction) {
        point.sub(eye).mul(fraction).add(eye);
    }
}

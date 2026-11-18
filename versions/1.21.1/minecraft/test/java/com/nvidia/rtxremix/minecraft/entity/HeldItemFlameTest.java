/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.item.ItemDisplayContext;

class HeldItemFlameTest {

    private static final float EPSILON = 1.0e-5f;

    @Test
    void onlyHandModesCarryTheFlame() {
        assertTrue(HeldItemFlame.isHandMode(ItemDisplayContext.FIRST_PERSON_LEFT_HAND));
        assertTrue(HeldItemFlame.isHandMode(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND));
        assertTrue(HeldItemFlame.isHandMode(ItemDisplayContext.THIRD_PERSON_LEFT_HAND));
        assertTrue(HeldItemFlame.isHandMode(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND));
        // Inventory, dropped, item frame and worn-on-head renders are not held.
        assertFalse(HeldItemFlame.isHandMode(ItemDisplayContext.GUI));
        assertFalse(HeldItemFlame.isHandMode(ItemDisplayContext.GROUND));
        assertFalse(HeldItemFlame.isHandMode(ItemDisplayContext.FIXED));
        assertFalse(HeldItemFlame.isHandMode(ItemDisplayContext.HEAD));
        assertFalse(HeldItemFlame.isHandMode(ItemDisplayContext.NONE));
    }

    @Test
    void flickerStaysWithinItsAmplitudeAndIsDeterministic() {
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (int step = 0; step < 4000; step++) {
            double seconds = step * 0.005;
            float value = HeldItemFlame.flicker(seconds, 0.0f);
            assertEquals(value, HeldItemFlame.flicker(seconds, 0.0f));
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        assertTrue(min >= 1.0f - HeldItemFlame.FLICKER_AMPLITUDE - EPSILON);
        assertTrue(max <= 1.0f + HeldItemFlame.FLICKER_AMPLITUDE + EPSILON);
        // A pulse, not a constant.
        assertTrue(max - min > HeldItemFlame.FLICKER_AMPLITUDE);
    }

    @Test
    void phaseSeparatesFlamesThatShareAClock() {
        assertNotEquals(HeldItemFlame.flicker(1.234, 0.0f), HeldItemFlame.flicker(1.234, 1.0f));
        assertNotEquals(HeldItemFlame.bob(1.234, 0.0f), HeldItemFlame.bob(1.234, 1.0f));
    }

    @Test
    void bobStaysWithinItsAmplitude() {
        for (int step = 0; step < 4000; step++) {
            float bob = HeldItemFlame.bob(step * 0.005, 0.5f);
            assertTrue(Math.abs(bob) <= HeldItemFlame.BOB_AMPLITUDE + EPSILON);
        }
    }

    @Test
    void onlyFirstPersonPushesTheQuadTowardTheCamera() {
        assertEquals(HeldItemFlame.FIRST_PERSON_CAMERA_OFFSET,
                HeldItemFlame.cameraOffset(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND));
        assertEquals(HeldItemFlame.FIRST_PERSON_CAMERA_OFFSET,
                HeldItemFlame.cameraOffset(ItemDisplayContext.FIRST_PERSON_LEFT_HAND));
        // Seen from the side, a world-space push would float the flame off the torch.
        assertEquals(0.0f, HeldItemFlame.cameraOffset(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND));
        assertEquals(0.0f, HeldItemFlame.cameraOffset(ItemDisplayContext.THIRD_PERSON_LEFT_HAND));
    }

    @Test
    void onlyFirstPersonMovesTheQuadAlongItsLineOfSight() {
        assertEquals(HeldItemFlame.FIRST_PERSON_DEPTH_FRACTION,
                HeldItemFlame.depthFraction(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND));
        assertEquals(HeldItemFlame.FIRST_PERSON_DEPTH_FRACTION,
                HeldItemFlame.depthFraction(ItemDisplayContext.FIRST_PERSON_LEFT_HAND));
        // Past the anchor, so the card's front face cuts the base of the sprite
        // instead of the whole quad floating in front of the head.
        assertTrue(HeldItemFlame.FIRST_PERSON_DEPTH_FRACTION > 1.0f);
        // Another entity's capture has no eye to move along; the flame stays on the torch.
        assertEquals(1.0f, HeldItemFlame.depthFraction(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND));
        assertEquals(1.0f, HeldItemFlame.depthFraction(ItemDisplayContext.THIRD_PERSON_LEFT_HAND));
    }

    @Test
    void anUprightTorchPlacesTheQuadAtTheVanillaSpawnPoint() {
        // TorchBlock.randomDisplayTick spawns the flame at (0.5, 0.7, 0.5) of the block.
        Matrix4f pose = compose(new Matrix4f(), new Quaternionf(), 1.0f, 0.0f, 0.0f, new Vector3f(), 1.0f);

        Vector3f center = pose.transformPosition(new Vector3f(0, 0, 0));
        assertVector(0.5f, 0.7f, 0.5f, center);

        Vector3f right = pose.transformPosition(new Vector3f(1, 0, 0)).sub(center);
        assertVector(HeldItemFlame.HALF_EXTENT, 0, 0, right);
    }

    @Test
    void holderScaleAndFlickerScaleTheQuadAndItsOffsets() {
        // A half-scale pose with no display transform is a half-size holder: its
        // torch really is half size, so everything about the flame halves with it.
        Matrix4f itemPose = new Matrix4f().translation(2, 3, 4).scale(0.5f);
        float flicker = 1.1f;
        float bob = 0.02f;
        float cameraOffset = 0.05f;
        Matrix4f pose = compose(itemPose, new Quaternionf(), flicker, bob, cameraOffset, new Vector3f(), 1.0f);

        Vector3f center = pose.transformPosition(new Vector3f(0, 0, 0));
        assertVector(2 + 0.5f * HeldItemFlame.TIP_X,
                3 + 0.5f * (HeldItemFlame.TIP_Y + HeldItemFlame.FLAME_RISE + bob),
                4 + 0.5f * (HeldItemFlame.TIP_Z + cameraOffset), center);

        Vector3f up = pose.transformPosition(new Vector3f(0, 1, 0)).sub(center);
        assertVector(0, 0.5f * HeldItemFlame.HALF_EXTENT * flicker, 0, up);
    }

    @Test
    void theDisplayTransformScalesTheAnchorButNotTheFlame() {
        // A first-person hand draws the torch at 0.68. The flame is a world-sized
        // particle on that torch's tip: the anchor follows the card, the quad's
        // size, rise and bob do not, and the camera offset stays with the card
        // it clears.
        float displayScale = 0.68f;
        Matrix4f itemPose = new Matrix4f().translation(2, 3, 4).scale(displayScale);
        float flicker = 1.1f;
        float bob = 0.02f;
        float cameraOffset = 0.05f;
        Matrix4f pose = compose(itemPose, displayScale, new Quaternionf(), flicker, bob, cameraOffset,
                new Vector3f(), 1.0f);

        Vector3f center = pose.transformPosition(new Vector3f(0, 0, 0));
        assertVector(2 + displayScale * HeldItemFlame.TIP_X,
                3 + displayScale * HeldItemFlame.TIP_Y + HeldItemFlame.FLAME_RISE + bob,
                4 + displayScale * (HeldItemFlame.TIP_Z + cameraOffset), center);

        Vector3f up = pose.transformPosition(new Vector3f(0, 1, 0)).sub(center);
        assertVector(0, HeldItemFlame.HALF_EXTENT * flicker, 0, up);
    }

    @Test
    void facingTurnsTheQuadAndItsCameraOffsetTowardTheViewer() {
        // A camera looking along -X: its rotation turns view +Z into world +X.
        Quaternionf facing = new Quaternionf().rotationY((float) Math.toRadians(90));
        float cameraOffset = 0.05f;
        Matrix4f pose = compose(new Matrix4f(), facing, 1.0f, 0.0f, cameraOffset, new Vector3f(), 1.0f);

        // The quad's local +Z (its front) now points along +X, toward the camera.
        Vector3f front = pose.transformDirection(new Vector3f(0, 0, 1)).normalize();
        assertVector(1, 0, 0, front);

        // And the camera offset moved the center along that same direction.
        Vector3f center = pose.transformPosition(new Vector3f(0, 0, 0));
        assertVector(HeldItemFlame.TIP_X + cameraOffset,
                HeldItemFlame.TIP_Y + HeldItemFlame.FLAME_RISE, HeldItemFlame.TIP_Z, center);
    }

    @Test
    void depthFractionMovesTheQuadAlongItsLineOfSightAndKeepsItsImage() {
        Vector3f eye = new Vector3f(2.5f, 1.7f, 4.5f);
        float bob = 0.02f;
        float depth = 0.75f;
        Matrix4f near = compose(new Matrix4f(), new Quaternionf(), 1.0f, bob, 0.05f, eye, depth);
        Matrix4f far = compose(new Matrix4f(), new Quaternionf(), 1.0f, bob, 0.05f, eye, 1.0f);

        // Every point of the quad, bob and camera offset included, stays on the eye's ray to its counterpart,
        // at the fraction of the distance: the same picture, nearer.
        Vector3f[] corners = {new Vector3f(0, 0, 0), new Vector3f(1, 1, 0), new Vector3f(-1, 1, 0)};
        for (Vector3f corner : corners) {
            Vector3f nearPoint = near.transformPosition(new Vector3f(corner)).sub(eye);
            Vector3f farPoint = far.transformPosition(new Vector3f(corner)).sub(eye);
            assertVector(farPoint.x * depth, farPoint.y * depth, farPoint.z * depth, nearPoint);
        }
    }

    @Test
    void aCaptureWithoutAnEyeLeavesTheQuadAtDepthOne() {
        Matrix4f itemPose = new Matrix4f().translation(2, 3, 4).scale(0.5f);
        Matrix4f noEye = compose(itemPose, new Quaternionf(), 1.1f, 0.02f, 0.05f, null, 0.95f);
        Matrix4f depthOne = compose(itemPose, new Quaternionf(), 1.1f, 0.02f, 0.05f, new Vector3f(9, 9, 9), 1.0f);
        assertTrue(depthOne.equals(noEye, EPSILON));
    }

    @Test
    void depthMoveSurvivesTheFirstPersonViewCorrection() {
        // The eye is wherever the model-view puts the origin, and the view correction is
        // the model-view with an anisotropic scale in front: so the correction must map
        // the eye to the view origin, and the moved quad must then project like the
        // unmoved one. This is what makes the depth fraction invisible in the picture.
        Matrix4f modelView = new Matrix4f().rotateY(0.7f).rotateX(-0.3f).translate(0.1f, -1.6f, 0.4f);
        Vector3f eye = modelView.invert(new Matrix4f()).transformPosition(new Vector3f());
        Matrix4f correction = new Matrix4f();
        RemixFirstPersonPass.setViewCorrection(correction, modelView, 1.3f);
        Vector3f correctedEye = correction.transformPosition(new Vector3f(eye));
        assertVector(0, 0, 0, correctedEye);

        Matrix4f itemPose = new Matrix4f().translation(0.4f, -0.5f, -0.7f).scale(0.68f);
        Matrix4f near = compose(itemPose, new Quaternionf(), 1.0f, 0.02f, 0.05f, eye, 0.95f);
        Matrix4f far = compose(itemPose, new Quaternionf(), 1.0f, 0.02f, 0.05f, eye, 1.0f);
        Vector3f[] corners = {new Vector3f(0, 0, 0), new Vector3f(1, 1, 0), new Vector3f(-1, 1, 0), new Vector3f(1, -1, 0)};
        for (Vector3f corner : corners) {
            Vector3f n = correction.transformPosition(near.transformPosition(new Vector3f(corner)));
            Vector3f f = correction.transformPosition(far.transformPosition(new Vector3f(corner)));
            assertTrue(Math.abs(f.z) > 1.0e-3f, "corner in front of the eye");
            assertEquals(f.x / f.z, n.x / n.z, 1.0e-4f, "x/z of corner " + corner);
            assertEquals(f.y / f.z, n.y / n.z, 1.0e-4f, "y/z of corner " + corner);
            assertVector(0.95f * f.x, 0.95f * f.y, 0.95f * f.z, n);
        }
    }

    @Test
    void aTorchOnItsSideStillCarriesItsFlameStraightUpFromItsTip() {
        // A hand leans the torch. Vanilla spawns a placed torch's flame 0.075 above
        // the 0.625 tip, and a real flame rises whichever way the torch leans, so a
        // torch lying along +X keeps its flame straight above its tip at x = 0.625,
        // not 0.075 further along the stick.
        Matrix4f itemPose = new Matrix4f().rotationZ((float) Math.toRadians(-90));
        Matrix4f pose = compose(itemPose, new Quaternionf(), 1.0f, 0.0f, 0.0f, new Vector3f(), 1.0f);

        Vector3f center = pose.transformPosition(new Vector3f(0, 0, 0));
        assertVector(0.625f, -0.5f + 0.075f, 0.5f, center);
    }

    @Test
    void theFirstPersonRigRisesAlongTheCamerasUp() {
        Quaternionf level = new Quaternionf();
        Quaternionf lookingDown = new Quaternionf().rotationX((float) Math.toRadians(-60));
        assertSame(level, HeldItemFlame.riseFrame(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, level));
        assertSame(lookingDown, HeldItemFlame.riseFrame(ItemDisplayContext.FIRST_PERSON_LEFT_HAND, lookingDown));
        // A holder in the world keeps world up whatever the camera does.
        Quaternionfc world = HeldItemFlame.riseFrame(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, lookingDown);
        assertVector(0, 1, 0, world.transform(new Vector3f(0, 1, 0)));
    }

    @Test
    void pitchingTheCameraLeavesTheFirstPersonFlameOnTheCard() {
        // The rig is drawn the same at any pitch, so the flame's offset from the tip,
        // seen in the camera's frame, must not change when the camera pitches. Along
        // world up it swung around the head as the view tilted.
        Matrix4f itemPose = new Matrix4f().translation(0.4f, -0.5f, -0.7f).scale(0.68f);
        Vector3f tip = itemPose.transformPosition(new Vector3f(
                HeldItemFlame.TIP_X, HeldItemFlame.TIP_Y, HeldItemFlame.TIP_Z));
        Vector3f reference = null;
        for (float pitch : new float[] {0f, -30f, -60f, 45f}) {
            Quaternionf facing = new Quaternionf().rotationX((float) Math.toRadians(pitch));
            Matrix4f pose = HeldItemFlame.composePose(itemPose, 0.68f, facing, facing, 1.0f, 0.0f, 0.05f,
                    null, 1.0f, new Vector3f(), new Vector3f(), new Matrix4f());
            Vector3f offset = pose.transformPosition(new Vector3f()).sub(tip);
            facing.transformInverse(offset);
            if (reference == null) {
                reference = offset;
            } else {
                assertVector(reference.x, reference.y, reference.z, offset);
            }
        }
        assertVector(0, HeldItemFlame.FLAME_RISE, 0.05f * 0.68f, reference);
    }

    private static Matrix4f compose(Matrix4f itemPose, Quaternionf facing, float flicker, float bob,
                                    float cameraOffset, Vector3f eye, float depthFraction) {
        return compose(itemPose, 1.0f, facing, flicker, bob, cameraOffset, eye, depthFraction);
    }

    private static Matrix4f compose(Matrix4f itemPose, float displayScale, Quaternionf facing, float flicker,
                                    float bob, float cameraOffset, Vector3f eye, float depthFraction) {
        return HeldItemFlame.composePose(itemPose, displayScale, facing, new Quaternionf(), flicker, bob,
                cameraOffset, eye, depthFraction, new Vector3f(), new Vector3f(), new Matrix4f());
    }

    private static void assertVector(float x, float y, float z, Vector3f actual) {
        assertEquals(x, actual.x, EPSILON, "x");
        assertEquals(y, actual.y, EPSILON, "y");
        assertEquals(z, actual.z, EPSILON, "z");
    }
}

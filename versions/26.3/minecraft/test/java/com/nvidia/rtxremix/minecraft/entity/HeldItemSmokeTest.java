/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.item.ItemDisplayContext;

class HeldItemSmokeTest {

    private static final float EPSILON = 1.0e-5f;

    private static HeldItemSmoke.Puff[] puffs() {
        HeldItemSmoke.Puff[] out = new HeldItemSmoke.Puff[HeldItemSmoke.SLOTS];
        for (int i = 0; i < out.length; i++) out[i] = new HeldItemSmoke.Puff();
        return out;
    }

    @Test
    void theSwitchOnlyGovernsFirstPerson() {
        assertTrue(HeldItemSmoke.showsFor(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, true));
        assertFalse(HeldItemSmoke.showsFor(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, false));
        assertFalse(HeldItemSmoke.showsFor(ItemDisplayContext.FIRST_PERSON_LEFT_HAND, false));
        // Other entities' torches smoke like placed ones whatever the switch says.
        assertTrue(HeldItemSmoke.showsFor(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, false));
        assertTrue(HeldItemSmoke.showsFor(ItemDisplayContext.THIRD_PERSON_LEFT_HAND, false));
    }

    @Test
    void livePuffsStayWithinVanillaRanges() {
        HeldItemSmoke.Puff[] out = puffs();
        int seen = 0;
        for (int step = 0; step < 4000; step++) {
            double ticks = step * 0.37;
            int live = HeldItemSmoke.evaluate(ticks, 0.0f, out);
            int counted = 0;
            for (HeldItemSmoke.Puff puff : out) {
                if (!puff.alive) continue;
                counted++;
                seen++;
                assertTrue(puff.maxAge >= HeldItemSmoke.MIN_LIFETIME_TICKS);
                assertTrue(puff.maxAge <= HeldItemSmoke.MAX_LIFETIME_TICKS);
                assertTrue(puff.age >= 0 && puff.age < puff.maxAge);
                // Zero at the spawn instant, as vanilla's getSize is; grown within a tick.
                assertTrue(puff.halfExtent >= 0.0f);
                assertTrue(puff.halfExtent <= HeldItemSmoke.MAX_HALF_EXTENT + EPSILON);
                // A torch's smoke drifts, it does not fly: under a block in any direction.
                assertTrue(Math.abs(puff.x) < 1.0f && Math.abs(puff.z) < 1.0f);
                assertTrue(puff.y > -0.2f && puff.y < 1.0f);
            }
            assertEquals(live, counted);
        }
        assertTrue(seen > 0);
    }

    @Test
    void dormantSlotsKeepTheirIndexAndParkAtTheAnchor() {
        // Slot 0's puff of cycle 10 is born at 240 ticks and lives at most 16, so
        // at 240 + 20 it is dormant while the slot itself is still emitted.
        HeldItemSmoke.Puff[] out = puffs();
        HeldItemSmoke.evaluate(10 * HeldItemSmoke.PERIOD_TICKS + 20.0, 0.0f, out);
        HeldItemSmoke.Puff dormant = out[0];
        assertFalse(dormant.alive);
        assertEquals(0.0f, dormant.x);
        assertEquals(0.0f, dormant.y);
        assertEquals(0.0f, dormant.z);
        assertEquals(HeldItemSmoke.DORMANT_HALF_EXTENT, dormant.halfExtent);
        assertTrue(dormant.halfExtent > 0.0f, "a dormant quad must not be singular");
        assertTrue(dormant.age < dormant.maxAge, "sprite lookup needs age below maxAge");
    }

    @Test
    void evaluationIsDeterministic() {
        HeldItemSmoke.Puff[] a = puffs();
        HeldItemSmoke.Puff[] b = puffs();
        assertEquals(HeldItemSmoke.evaluate(1234.56, 2.0f, a), HeldItemSmoke.evaluate(1234.56, 2.0f, b));
        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i].alive, b[i].alive);
            assertEquals(a[i].x, b[i].x);
            assertEquals(a[i].y, b[i].y);
            assertEquals(a[i].z, b[i].z);
            assertEquals(a[i].halfExtent, b[i].halfExtent);
            assertEquals(a[i].maxAge, b[i].maxAge);
        }
    }

    @Test
    void aPuffRisesOverItsLifeInItsOwnSlot() {
        // Slot 0's puff of one cycle from birth to near death: smoke goes up, and
        // it is still slot 0 when it does.
        HeldItemSmoke.Puff[] out = puffs();
        double birth = 10 * HeldItemSmoke.PERIOD_TICKS + 0.01;
        HeldItemSmoke.evaluate(birth, 0.0f, out);
        assertTrue(out[0].alive);
        assertEquals(0, out[0].age);
        float youngY = out[0].y;
        int maxAge = out[0].maxAge;

        HeldItemSmoke.evaluate(birth + maxAge - 0.5, 0.0f, out);
        assertTrue(out[0].alive);
        assertEquals(maxAge, out[0].maxAge);
        assertTrue(out[0].age > 0);
        assertTrue(out[0].y > youngY + 0.05f, "expected the puff to have risen, y went "
                + youngY + " -> " + out[0].y);
    }

    @Test
    void eachSlotSpawnsOncePerPeriod() {
        // Every whole tick across one period, newborn puffs (age 0) appear exactly
        // SLOTS times: vanilla's roughly one display tick per 8 ticks for a torch
        // a few blocks away.
        HeldItemSmoke.Puff[] out = puffs();
        int newborns = 0;
        for (int tick = 0; tick < HeldItemSmoke.PERIOD_TICKS; tick++) {
            HeldItemSmoke.evaluate(100 * HeldItemSmoke.PERIOD_TICKS + tick + 0.001, 0.0f, out);
            for (HeldItemSmoke.Puff puff : out) {
                if (puff.alive && puff.age == 0) newborns++;
            }
        }
        assertEquals(HeldItemSmoke.SLOTS, newborns);
    }

    @Test
    void composePoseDisplacesInCaptureSpaceAndScalesWithTheHolder() {
        // A half-scale pose with no display transform is a half-size holder,
        // whose torch really is half size and trails half-size smoke.
        HeldItemSmoke.Puff puff = new HeldItemSmoke.Puff();
        puff.x = 0.1f; puff.y = 0.3f; puff.z = -0.05f;
        puff.halfExtent = 0.1f;
        Matrix4f itemPose = new Matrix4f().translation(2, 3, 4).scale(0.5f);
        Matrix4f pose = HeldItemSmoke.composePose(itemPose, 1.0f, new Quaternionf(), new Quaternionf(), puff, 0.0f, new Vector3f(), 1.0f,
                new Vector3f(), new Vector3f(), new Matrix4f());

        Vector3f center = pose.transformPosition(new Vector3f(0, 0, 0));
        assertEquals(2 + 0.5f * (HeldItemFlame.TIP_X + puff.x), center.x, EPSILON);
        assertEquals(3 + 0.5f * (HeldItemFlame.TIP_Y + HeldItemFlame.FLAME_RISE + puff.y), center.y, EPSILON);
        assertEquals(4 + 0.5f * (HeldItemFlame.TIP_Z + puff.z), center.z, EPSILON);

        Vector3f up = pose.transformPosition(new Vector3f(0, 1, 0)).sub(center);
        assertEquals(0.5f * puff.halfExtent, up.y, EPSILON);
    }

    @Test
    void theDisplayTransformScalesTheAnchorButNotThePuff() {
        // A third-person hand draws the torch at 0.55. The puff is a world-sized
        // particle rising from that torch's tip: the anchor follows the card,
        // the drift and the quad do not.
        HeldItemSmoke.Puff puff = new HeldItemSmoke.Puff();
        puff.x = 0.1f; puff.y = 0.3f; puff.z = -0.05f;
        puff.halfExtent = 0.1f;
        float displayScale = 0.55f;
        Matrix4f itemPose = new Matrix4f().translation(2, 3, 4).scale(displayScale);
        Matrix4f pose = HeldItemSmoke.composePose(itemPose, displayScale, new Quaternionf(), new Quaternionf(), puff, 0.0f,
                new Vector3f(), 1.0f, new Vector3f(), new Vector3f(), new Matrix4f());

        Vector3f center = pose.transformPosition(new Vector3f(0, 0, 0));
        assertEquals(2 + displayScale * HeldItemFlame.TIP_X + puff.x, center.x, EPSILON);
        assertEquals(3 + displayScale * HeldItemFlame.TIP_Y + HeldItemFlame.FLAME_RISE + puff.y, center.y, EPSILON);
        assertEquals(4 + displayScale * HeldItemFlame.TIP_Z + puff.z, center.z, EPSILON);

        Vector3f up = pose.transformPosition(new Vector3f(0, 1, 0)).sub(center);
        assertEquals(puff.halfExtent, up.y, EPSILON);
    }

    @Test
    void thePuffDriftsInTheFrameItIsGiven() {
        // Looking straight down, the first-person rig's up is the world's forward: a
        // puff rising in the camera's frame keeps its place beside the flame on screen.
        HeldItemSmoke.Puff puff = new HeldItemSmoke.Puff();
        puff.y = 0.3f;
        puff.halfExtent = 0.1f;
        Quaternionf lookingDown = new Quaternionf().rotationX((float) Math.toRadians(-90));
        Matrix4f pose = HeldItemSmoke.composePose(new Matrix4f(), 1.0f, lookingDown, lookingDown, puff, 0.0f,
                new Vector3f(), 1.0f, new Vector3f(), new Vector3f(), new Matrix4f());

        Vector3f center = pose.transformPosition(new Vector3f());
        Vector3f expected = lookingDown.transform(new Vector3f(0, HeldItemFlame.FLAME_RISE + puff.y, 0))
                .add(HeldItemFlame.TIP_X, HeldItemFlame.TIP_Y, HeldItemFlame.TIP_Z);
        assertEquals(expected.x, center.x, EPSILON);
        assertEquals(expected.y, center.y, EPSILON);
        assertEquals(expected.z, center.z, EPSILON);
    }

    @Test
    void composePoseIgnoresTheItemsRotationForTheDrift() {
        // Smoke rises along world up even when the torch lies on its side.
        HeldItemSmoke.Puff puff = new HeldItemSmoke.Puff();
        puff.y = 0.5f;
        puff.halfExtent = 0.1f;
        Matrix4f itemPose = new Matrix4f().rotationZ((float) Math.toRadians(-90));
        Matrix4f pose = HeldItemSmoke.composePose(itemPose, 1.0f, new Quaternionf(), new Quaternionf(), puff, 0.0f, new Vector3f(), 1.0f,
                new Vector3f(), new Vector3f(), new Matrix4f());

        Vector3f anchor = itemPose.transformPosition(new Vector3f(
                HeldItemFlame.TIP_X, HeldItemFlame.TIP_Y, HeldItemFlame.TIP_Z));
        Vector3f center = pose.transformPosition(new Vector3f(0, 0, 0));
        assertEquals(anchor.x, center.x, EPSILON);
        assertEquals(anchor.y + HeldItemFlame.FLAME_RISE + 0.5f, center.y, EPSILON);
        assertEquals(anchor.z, center.z, EPSILON);
    }

    @Test
    void composePoseWithoutAnEyeIgnoresTheDepthFraction() {
        HeldItemSmoke.Puff puff = new HeldItemSmoke.Puff();
        puff.y = 0.3f;
        puff.halfExtent = 0.1f;
        Matrix4f noEye = HeldItemSmoke.composePose(new Matrix4f(), 1.0f, new Quaternionf(), new Quaternionf(), puff, 0.05f, null, 0.5f,
                new Vector3f(), new Vector3f(), new Matrix4f());
        Matrix4f depthOne = HeldItemSmoke.composePose(new Matrix4f(), 1.0f, new Quaternionf(), new Quaternionf(), puff, 0.05f,
                new Vector3f(9, 9, 9), 1.0f, new Vector3f(), new Vector3f(), new Matrix4f());
        assertTrue(depthOne.equals(noEye, EPSILON));
    }

    @Test
    void composePoseMovesThePuffTowardTheEyeLikeTheFlame() {
        HeldItemSmoke.Puff puff = new HeldItemSmoke.Puff();
        puff.y = 0.3f;
        puff.halfExtent = 0.1f;
        Vector3f eye = new Vector3f(1.5f, 2.7f, 3.5f);
        Matrix4f near = HeldItemSmoke.composePose(new Matrix4f(), 1.0f, new Quaternionf(), new Quaternionf(), puff, 0.05f, eye, 0.5f,
                new Vector3f(), new Vector3f(), new Matrix4f());
        Matrix4f far = HeldItemSmoke.composePose(new Matrix4f(), 1.0f, new Quaternionf(), new Quaternionf(), puff, 0.05f, eye, 1.0f,
                new Vector3f(), new Vector3f(), new Matrix4f());

        Vector3f nearCorner = near.transformPosition(new Vector3f(1, 1, 0)).sub(eye);
        Vector3f farCorner = far.transformPosition(new Vector3f(1, 1, 0)).sub(eye);
        assertEquals(farCorner.x * 0.5f, nearCorner.x, EPSILON);
        assertEquals(farCorner.y * 0.5f, nearCorner.y, EPSILON);
        assertEquals(farCorner.z * 0.5f, nearCorner.z, EPSILON);
    }
}

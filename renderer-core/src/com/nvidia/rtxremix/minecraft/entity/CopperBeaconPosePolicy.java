/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Shared articulated transform used by both the visible head and its Remix light. */
public final class CopperBeaconPosePolicy {

    private static final float HEAD_UP_OFFSET = 2.0f / 16.0f;
    private static final float LIGHT_FORWARD_OFFSET = 5.0f / 16.0f;

    private CopperBeaconPosePolicy() {}

    public static void evaluate(
            int mountX, int mountY, int mountZ,
            int neutralForwardX, int neutralForwardY, int neutralForwardZ,
            float yawDegrees,
            float pitchDegrees,
            Pose destination) {
        if (Math.abs(mountX * neutralForwardX
                + mountY * neutralForwardY
                + mountZ * neutralForwardZ) > 0) {
            throw new IllegalArgumentException("Mount and forward directions must be orthogonal");
        }

        mountingRotation(
                mountX, mountY, mountZ,
                neutralForwardX, neutralForwardY, neutralForwardZ,
                destination.mountingRotation,
                destination.up,
                destination.forward,
                destination.rotatedForward,
                destination.cross,
                destination.upRotation);
        destination.modelYawRadians = (float) Math.toRadians(-yawDegrees);
        destination.modelPitchRadians = (float) Math.toRadians(pitchDegrees);

        destination.articulationRotation
                .rotationY(destination.modelYawRadians)
                .rotateX(destination.modelPitchRadians);
        destination.direction
                .set(0.0f, 0.0f, -1.0f)
                .rotate(destination.articulationRotation)
                .rotate(destination.mountingRotation)
                .normalize();
        destination.xAxis
                .set(1.0f, 0.0f, 0.0f)
                .rotate(destination.articulationRotation)
                .rotate(destination.mountingRotation)
                .normalize();
        destination.yAxis
                .set(0.0f, 1.0f, 0.0f)
                .rotate(destination.articulationRotation)
                .rotate(destination.mountingRotation)
                .normalize();

        destination.emitter
                .set(mountX, mountY, mountZ)
                .mul(HEAD_UP_OFFSET)
                .fma(LIGHT_FORWARD_OFFSET, destination.direction)
                .add(0.5f, 0.5f, 0.5f);
    }

    public static Quaternionf mountingRotation(
            int upX, int upY, int upZ,
            int forwardX, int forwardY, int forwardZ,
            Quaternionf destination) {
        return mountingRotation(
                upX, upY, upZ,
                forwardX, forwardY, forwardZ,
                destination,
                new Vector3f(),
                new Vector3f(),
                new Vector3f(),
                new Vector3f(),
                new Quaternionf());
    }

    private static Quaternionf mountingRotation(
            int upX, int upY, int upZ,
            int forwardX, int forwardY, int forwardZ,
            Quaternionf destination,
            Vector3f up,
            Vector3f forward,
            Vector3f rotatedForward,
            Vector3f cross,
            Quaternionf upRotation) {
        up.set(upX, upY, upZ);
        forward.set(forwardX, forwardY, forwardZ);
        if (Math.abs(up.dot(forward)) > 0.5f) {
            throw new IllegalArgumentException("Mount and forward directions must be orthogonal");
        }

        upRotation.rotationTo(0.0f, 1.0f, 0.0f, up.x, up.y, up.z);
        rotatedForward.set(0.0f, 0.0f, -1.0f).rotate(upRotation);
        float angle = (float) Math.atan2(
                up.dot(cross.set(rotatedForward).cross(forward)),
                rotatedForward.dot(forward));
        return destination.rotationAxis(angle, up).mul(upRotation);
    }

    /** Render-thread scratch output; one instance can be reused across evaluations. */
    public static final class Pose {
        private final Quaternionf mountingRotation = new Quaternionf();
        private final Quaternionf articulationRotation = new Quaternionf();
        private final Quaternionf upRotation = new Quaternionf();
        private final Vector3f direction = new Vector3f();
        private final Vector3f emitter = new Vector3f();
        private final Vector3f xAxis = new Vector3f();
        private final Vector3f yAxis = new Vector3f();
        private final Vector3f up = new Vector3f();
        private final Vector3f forward = new Vector3f();
        private final Vector3f rotatedForward = new Vector3f();
        private final Vector3f cross = new Vector3f();
        private float modelYawRadians;
        private float modelPitchRadians;

        public Quaternionf mountingRotation() {
            return mountingRotation;
        }

        public Vector3f direction() {
            return direction;
        }

        public Vector3f emitter() {
            return emitter;
        }

        public Vector3f xAxis() {
            return xAxis;
        }

        public Vector3f yAxis() {
            return yAxis;
        }

        public float modelYawRadians() {
            return modelYawRadians;
        }

        public float modelPitchRadians() {
            return modelPitchRadians;
        }
    }
}

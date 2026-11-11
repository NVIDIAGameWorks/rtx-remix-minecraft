/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.block;

import com.nvidia.rtxremix.minecraft.entity.CopperBeaconPosePolicy;
import net.minecraft.core.Direction;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Translates Minecraft directions for the canonical articulated beacon pose. */
public final class CopperBeaconPose {
    private CopperBeaconPose() {}

    public static void evaluate(
            Direction mount,
            Direction neutralForward,
            float yawDegrees,
            float pitchDegrees,
            Pose destination) {
        CopperBeaconPosePolicy.evaluate(
                mount.getStepX(), mount.getStepY(), mount.getStepZ(),
                neutralForward.getStepX(),
                neutralForward.getStepY(),
                neutralForward.getStepZ(),
                yawDegrees,
                pitchDegrees,
                destination.delegate);
    }

    public static Quaternionf mountingRotation(
            Direction upDirection, Direction forwardDirection, Quaternionf destination) {
        return CopperBeaconPosePolicy.mountingRotation(
                upDirection.getStepX(), upDirection.getStepY(), upDirection.getStepZ(),
                forwardDirection.getStepX(),
                forwardDirection.getStepY(),
                forwardDirection.getStepZ(),
                destination);
    }

    public static final class Pose {
        private final CopperBeaconPosePolicy.Pose delegate =
                new CopperBeaconPosePolicy.Pose();

        public Quaternionf mountingRotation() { return delegate.mountingRotation(); }
        public Vector3f direction() { return delegate.direction(); }
        public Vector3f emitter() { return delegate.emitter(); }
        public Vector3f xAxis() { return delegate.xAxis(); }
        public Vector3f yAxis() { return delegate.yAxis(); }
        public float modelYawRadians() { return delegate.modelYawRadians(); }
        public float modelPitchRadians() { return delegate.modelPitchRadians(); }
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.Direction;

class BeaconBeamTest {

    private static final float EPSILON = 1.0e-6f;

    @Test
    void authoredBaselineAppliesToPlacedAndHeldButNotDisplayBeacons() {
        assertEquals(56_250.0f, BeaconBeam.intensity(15));
        assertEquals(1_125_000.0f, BeaconBeam.displayIntensity(15));
        assertEquals(16_875.0f, BeaconBeam.heldIntensity(15));
    }

    @Test
    void placedToHeldRadianceRatioRemainsPathTuning() {
        assertEquals(
                BeaconBeam.INTENSITY_SCALE / BeaconBeam.HELD_INTENSITY_SCALE,
                BeaconBeam.intensity(15) / BeaconBeam.heldIntensity(15));
    }

    @Test
    void neutralEmitterTracksEveryMountAndForwardBasisAtLensScale() {
        for (Direction mount : Direction.values()) {
            for (Direction forward : Direction.values()) {
                if (mount.getAxis() == forward.getAxis()) {
                    continue;
                }

                Vector3f offset = new Vector3f(
                        BeaconBeam.lensLocal(mount.getStepX(), forward.getStepX()) - 0.5f,
                        BeaconBeam.lensLocal(mount.getStepY(), forward.getStepY()) - 0.5f,
                        BeaconBeam.lensLocal(mount.getStepZ(), forward.getStepZ()) - 0.5f);
                String placement = "mount=" + mount + ", facing=" + forward;

                assertEquals(2.0f / 16.0f, dot(offset, mount), EPSILON, placement);
                assertEquals(5.0f / 16.0f, dot(offset, forward), EPSILON, placement);
            }
        }
    }

    private static float dot(Vector3f vector, Direction direction) {
        return vector.x * direction.getStepX()
                + vector.y * direction.getStepY()
                + vector.z * direction.getStepZ();
    }
}

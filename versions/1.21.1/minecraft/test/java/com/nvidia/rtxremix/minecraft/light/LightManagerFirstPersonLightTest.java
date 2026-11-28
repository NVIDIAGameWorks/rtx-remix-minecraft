/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import com.nvidia.rtxremix.Float3D;
import com.nvidia.rtxremix.LightInfoLightShaping;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class LightManagerFirstPersonLightTest {

    @Test
    void firstPersonLightKeepsSurfaceLightingButHasNoVolumetricContribution() {
        LightInfoLightShaping shaping = new LightInfoLightShaping();
        shaping.direction = new Float3D(0.0f, 0.0f, 1.0f);
        LightManager.DiskBasis diskBasis = new LightManager.DiskBasis(
                new Vec3(1.0, 0.0, 0.0),
                new Vec3(0.0, 1.0, 0.0),
                new Vec3(0.0, 0.0, 1.0));
        LightManager.PendingAnalyticLight heldBeacon = new LightManager.PendingAnalyticLight(
                null, new Vec3(1.0, 2.0, 3.0), 0.15f, 0.05,
                10.0f, 20.0f, 30.0f, 0.0125f, shaping, diskBasis);

        LightManager.PendingAnalyticLight firstPerson =
                heldBeacon.withoutVolumetricContribution();

        assertEquals(0.0f, firstPerson.volumetricRadianceScale());
        assertEquals(new Vec3(1.0, 2.0, 3.0), firstPerson.position());
        assertEquals(0.15f, firstPerson.emitterRadius());
        assertEquals(0.05, firstPerson.shadowClearance());
        assertEquals(10.0f, firstPerson.radianceR());
        assertEquals(20.0f, firstPerson.radianceG());
        assertEquals(30.0f, firstPerson.radianceB());
        assertSame(shaping, firstPerson.shaping());
        assertSame(diskBasis, firstPerson.diskBasis());
    }
}

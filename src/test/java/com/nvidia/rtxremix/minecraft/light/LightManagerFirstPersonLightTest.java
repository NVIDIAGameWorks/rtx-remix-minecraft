/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import com.nvidia.rtxremix.Float3D;
import com.nvidia.rtxremix.LightInfoLightShaping;

import net.minecraft.util.math.Vec3d;

import org.joml.Vector3f;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class LightManagerFirstPersonLightTest {

    @Test
    void beamCoordinatesAreSnapshotsOfTheReusableHandAnchor() {
        Vector3f anchor = new Vector3f(1.25f, 64.5f, -3.75f);
        var first = new LightManager.PendingAnalyticLight(null, anchor.x, anchor.y, anchor.z,
                0.15f, 0.05, 10, 20, 30, 0, null, null);
        anchor.set(-8, 12, 19);
        var otherHand = new LightManager.PendingAnalyticLight(null, anchor.x, anchor.y, anchor.z,
                0.15f, 0.05, 10, 20, 30, 0, null, null);
        var sphere = LightManager.buildEntityLightInfo(first, 0.5f);
        var other = LightManager.buildEntityLightInfo(otherHand, 1);
        assertEquals(1.25f, sphere.sphereExt.position.x);
        assertEquals(64.5f, sphere.sphereExt.position.y);
        assertEquals(-3.75f, sphere.sphereExt.position.z);
        assertEquals(-8f, other.sphereExt.position.x);
        assertEquals(5f, sphere.radiance.x);
        assertEquals(10f, sphere.radiance.y);
        assertEquals(15f, sphere.radiance.z);
    }

    @Test
    void existingSphereAndDiskPositionsKeepTheirNativeCoordinates() {
        Vec3d worldPosition = new Vec3d(20_000_000.125, 72.25, -200.875);
        var basis = new LightManager.DiskBasis(new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), new Vec3d(0, 0, 1));
        var sphere = new LightManager.PendingAnalyticLight(null, worldPosition, 0.2f, 0.05,
                1, 2, 3, 0.25f, null);
        var disk = new LightManager.PendingAnalyticLight(null, worldPosition, 0.2f, 0.05,
                1, 2, 3, 0.25f, null, basis);
        assertEquals(20_000_000.125, sphere.x());
        assertEquals(20_000_000.125, disk.withoutVolumetricContribution().x());
        var sphereInfo = LightManager.buildEntityLightInfo(sphere, 1);
        var diskInfo = LightManager.buildEntityLightInfo(disk, 1);
        assertEquals((float) worldPosition.x, sphereInfo.sphereExt.position.x);
        assertEquals((float) worldPosition.x, diskInfo.diskExt.position.x);
        assertEquals(72.25f, sphereInfo.sphereExt.position.y);
        assertEquals(-200.875f, diskInfo.diskExt.position.z);
        assertEquals(0.2f, diskInfo.diskExt.xRadius);
        assertEquals(0.25f, diskInfo.diskExt.volumetricRadianceScale);
    }

    @Test
    void shadowClearanceAndCandidateSelectionKeepExistingGeometry() {
        var light = new FirstPersonShadowModelPlacement.LightConstraint(0.925, 0, 0, 0.15, 0.39);
        assertEquals(0.5f, FirstPersonShadowModelPlacement.clearanceRadianceScale(Vec3d.ZERO, light), 1e-6f);
        var vectorInput = new FirstPersonShadowModelPlacement.LightConstraint(new Vec3d(0.925, 0, 0), 0.15, 0.39);
        assertEquals(0.5f, FirstPersonShadowModelPlacement.clearanceRadianceScale(Vec3d.ZERO, vectorInput), 1e-6f);
        var away = new Vec3d(-0.4, 0, 0);
        assertEquals(away, FirstPersonShadowModelPlacement.selectContinuousCandidate(Vec3d.ZERO, Vec3d.ZERO,
                List.of(light), List.of(new Vec3d(0.4, 0, 0), away)));
        assertEquals(0.62, FirstPersonShadowModelPlacement.requiredTranslation(
                new Vec3d(-0.5, 0, 0), new Vec3d(-1, 0, 0), 1.12), 1e-12);
    }

    @Test
    void firstPersonLightKeepsSurfaceLightingButHasNoVolumetricContribution() {
        LightInfoLightShaping shaping = new LightInfoLightShaping();
        shaping.direction = new Float3D(0.0f, 0.0f, 1.0f);
        LightManager.DiskBasis diskBasis = new LightManager.DiskBasis(
                new Vec3d(1.0, 0.0, 0.0),
                new Vec3d(0.0, 1.0, 0.0),
                new Vec3d(0.0, 0.0, 1.0));
        LightManager.PendingAnalyticLight heldBeacon = new LightManager.PendingAnalyticLight(
                null, new Vec3d(1.0, 2.0, 3.0), 0.15f, 0.05,
                10.0f, 20.0f, 30.0f, 0.0125f, shaping, diskBasis);

        LightManager.PendingAnalyticLight firstPerson =
                heldBeacon.withoutVolumetricContribution();

        assertEquals(0.0f, firstPerson.volumetricRadianceScale());
        assertEquals(1.0, firstPerson.x());
        assertEquals(2.0, firstPerson.y());
        assertEquals(3.0, firstPerson.z());
        assertEquals(0.15f, firstPerson.emitterRadius());
        assertEquals(0.05, firstPerson.shadowClearance());
        assertEquals(10.0f, firstPerson.radianceR());
        assertEquals(20.0f, firstPerson.radianceG());
        assertEquals(30.0f, firstPerson.radianceB());
        assertSame(shaping, firstPerson.shaping());
        assertSame(diskBasis, firstPerson.diskBasis());
    }
}

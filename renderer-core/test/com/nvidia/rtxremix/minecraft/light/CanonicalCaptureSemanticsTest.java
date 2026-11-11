/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.particle.ParticleQuadWriter;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Properties;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden semantic fixture for representative capture paths that can be proven
 * without Minecraft, a graphics context, or the native runtime.
 */
class CanonicalCaptureSemanticsTest {
    private static final Properties EXPECTED = loadFixture();

    @AfterEach
    void resetEntityCapture() {
        EntityRenderContext.get().reset();
    }

    @Test
    void nestedEntityCaptureKeepsOneRigidTransformSequence() {
        EntityRenderContext context = EntityRenderContext.get();
        context.reset();
        context.begin(true);
        context.pushBone(new Matrix4f().translation(1.0f, 0.0f, 0.0f));
        context.begin(false);
        context.pushBone(new Matrix4f().translation(2.0f, 0.0f, 0.0f));
        context.pushBone(new Matrix4f().translation(3.0f, 0.0f, 0.0f));
        context.end();

        assertTrue(context.isActive());
        assertTrue(context.isRemixPass());
        assertEquals(integer("entity.nestedBoneCount"), context.getBoneCount());
        float[] transforms = context.snapshotBoneTransforms(new float[0]);
        assertEquals(
                decimal("entity.thirdBoneTranslationX"),
                transforms[2 * 12 + 3]);
    }

    @Test
    void particleFixtureRemainsWorldSpaceAndCarriesBounds() {
        ByteBuffer vertices = ByteBuffer
                .allocate(ParticleQuadWriter.VERTEX_BYTES_PER_PARTICLE)
                .order(ByteOrder.nativeOrder());
        ParticleQuadWriter.write(
                vertices,
                0,
                1.0, 2.0, 3.0,
                1.0, 2.0, 3.0,
                0.0f, 0.0f, 1.0f, 1.0f,
                ParticleQuadWriter.basisFromCamera(0, 0, 0, 1),
                0.0f, 0.0f, 1.0f, 1.0f,
                0xFFFFFFFF,
                integerPair("particle.bounds")[0],
                integerPair("particle.bounds")[1]);

        float[] position = decimalTriplet("particle.firstPosition");
        assertEquals(position[0], vertices.getFloat(0));
        assertEquals(position[1], vertices.getFloat(4));
        assertEquals(position[2], vertices.getFloat(8));
        assertEquals(decimal("particle.normalZ"), vertices.getFloat(20));
        assertEquals(integerPair("particle.bounds")[0], vertices.getInt(36));
        assertEquals(integerPair("particle.bounds")[1], vertices.getInt(40));
    }

    @Test
    void beamAndLightEnergyStayOnTheirAuthoredPaths() {
        int luminance = 15;
        assertEquals(decimal("beam.placedIntensity"), BeaconBeam.intensity(luminance));
        assertEquals(decimal("beam.displayIntensity"), BeaconBeam.displayIntensity(luminance));
        assertEquals(decimal("beam.heldIntensity"), BeaconBeam.heldIntensity(luminance));
        assertEquals(decimal("beam.clearConeDegrees"), BeaconBeam.heldConeAngle(1.0));
        assertEquals(decimal("beam.blockedConeDegrees"), BeaconBeam.heldConeAngle(0.0));
        assertEquals(
                decimal("light.unitDiskWhitePower"),
                LightPowerPolicy.of(
                        LightShape.DISK, 0f, 0f, 1f, 1f, 1f, 1f, 1f),
                1e-5f);
    }

    private static Properties loadFixture() {
        Properties values = new Properties();
        try (InputStream input = CanonicalCaptureSemanticsTest.class
                .getResourceAsStream("/canonical-capture-semantics.properties")) {
            assertNotNull(input, "missing canonical capture fixture");
            values.load(input);
            return values;
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static int integer(String key) {
        return Integer.parseInt(EXPECTED.getProperty(key));
    }

    private static float decimal(String key) {
        return Float.parseFloat(EXPECTED.getProperty(key));
    }

    private static int[] integerPair(String key) {
        String[] values = EXPECTED.getProperty(key).split(",");
        return new int[] {
                Integer.parseInt(values[0]),
                Integer.parseInt(values[1])
        };
    }

    private static float[] decimalTriplet(String key) {
        String[] values = EXPECTED.getProperty(key).split(",");
        return new float[] {
                Float.parseFloat(values[0]),
                Float.parseFloat(values[1]),
                Float.parseFloat(values[2])
        };
    }
}

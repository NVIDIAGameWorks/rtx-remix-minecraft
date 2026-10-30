/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.world.level.dimension.DimensionType;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkyRendererDimensionSkyTest {

    private static final int BYTES_PER_TEXEL = 4 * Short.BYTES;
    /** Vanilla's end_sky vertex tint (0xFF282828) times the End's 0.15 fog factor. */
    private static final float END_SKY_LUMINANCE = 40.0f / 255.0f * 0.15f;

    @Test
    void fixedDomeFillWritesEveryTexelAsOpaqueHalfFloatRadiance() {
        ByteBuffer pixels = ByteBuffer.allocate(AtmosphericSkyGenerator.OUTPUT_BYTES);

        SkyRenderer.fillFixedDome(pixels, new float[]{0.25f, 0.5f, 1.0f});

        assertEquals(0, pixels.position());
        assertEquals(AtmosphericSkyGenerator.OUTPUT_BYTES, pixels.limit());
        assertEquals(ByteOrder.nativeOrder(), pixels.order());
        int texelCount = AtmosphericSkyGenerator.WIDTH * AtmosphericSkyGenerator.HEIGHT;
        for (int texel = 0; texel < texelCount; texel++) {
            int base = texel * BYTES_PER_TEXEL;
            assertEquals(0.25f, Float.float16ToFloat(pixels.getShort(base)));
            assertEquals(0.5f, Float.float16ToFloat(pixels.getShort(base + 2)));
            assertEquals(1.0f, Float.float16ToFloat(pixels.getShort(base + 4)));
            assertEquals(1.0f, Float.float16ToFloat(pixels.getShort(base + 6)));
        }
    }

    @Test
    void fixedDomeFillLimitsAnOversizedBufferToTheDome() {
        ByteBuffer pixels = ByteBuffer.allocate(AtmosphericSkyGenerator.OUTPUT_BYTES * 2);

        SkyRenderer.fillFixedDome(pixels, new float[]{0.0f, 0.0f, 0.0f});

        assertEquals(0, pixels.position());
        assertEquals(AtmosphericSkyGenerator.OUTPUT_BYTES, pixels.limit());
    }

    @Test
    void fixedDomeFillRejectsUndersizedBuffers() {
        ByteBuffer pixels = ByteBuffer.allocate(AtmosphericSkyGenerator.OUTPUT_BYTES - BYTES_PER_TEXEL);

        assertThrows(
                IllegalArgumentException.class,
                () -> SkyRenderer.fillFixedDome(pixels, new float[]{0.0f, 0.0f, 0.0f}));
    }

    @Test
    void endSkyHasVanillaTintLuminanceAndVanillaVioletChroma() {
        float[] dome = SkyRenderer.END_SKY_RADIANCE;

        assertEquals(END_SKY_LUMINANCE, luminance(dome), 1.0e-4f);
        assertTrue(dome[0] > dome[1], "end_sky.png is redder than it is green");
        assertTrue(dome[2] > dome[0], "end_sky.png is bluer than it is red");
    }

    @Test
    void normalSkyHasNoFixedRadiance() {
        assertNull(SkyRenderer.fixedSkyRadiance(DimensionType.Skybox.OVERWORLD));
    }

    @Test
    void endSkyPublishesItsConstantForDomeAndAmbient() {
        assertSame(
                SkyRenderer.END_SKY_RADIANCE,
                SkyRenderer.fixedSkyRadiance(DimensionType.Skybox.END));
    }

    @Test
    void skylessSkyIsBlackSoNoAmbientIsPublished() {
        assertArrayEquals(
                new float[]{0.0f, 0.0f, 0.0f},
                SkyRenderer.fixedSkyRadiance(DimensionType.Skybox.NONE));
    }

    private static float luminance(float[] radiance) {
        assertEquals(3, radiance.length);
        for (float component : radiance) {
            assertTrue(component >= 0.0f, "radiance components are non-negative");
        }
        return radiance[0] * 0.2126f + radiance[1] * 0.7152f + radiance[2] * 0.0722f;
    }
}

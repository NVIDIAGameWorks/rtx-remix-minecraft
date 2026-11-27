/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkyRendererSettingsTest {

    @Test
    void authoredDayAmbientHasNoRadianceAtFullSunlight() {
        assertEquals(0.0f, SkyRenderer.skyAmbientMultiplier(1.0f));
        assertEquals(
                SkyRenderer.DEFAULT_NIGHT_AMBIENT_BOOST,
                SkyRenderer.skyAmbientMultiplier(-1.0f));
    }

    @Test
    void mieScatteringAnisotropyDefaultsAndClampsToItsPhysicalRange() {
        float previous = SkyRenderer.getMieScatteringAnisotropy();
        try {
            SkyRenderer.setMieScatteringAnisotropy(
                    SkyRenderer.DEFAULT_MIE_SCATTERING_ANISOTROPY);
            assertEquals(
                    SkyRenderer.DEFAULT_MIE_SCATTERING_ANISOTROPY,
                    SkyRenderer.getMieScatteringAnisotropy());

            SkyRenderer.setMieScatteringAnisotropy(2.0f);
            assertEquals(
                    SkyRenderer.MAX_MIE_SCATTERING_ANISOTROPY,
                    SkyRenderer.getMieScatteringAnisotropy());

            SkyRenderer.setMieScatteringAnisotropy(-1.0f);
            assertEquals(0.0f, SkyRenderer.getMieScatteringAnisotropy());
        } finally {
            SkyRenderer.setMieScatteringAnisotropy(previous);
        }
    }
}

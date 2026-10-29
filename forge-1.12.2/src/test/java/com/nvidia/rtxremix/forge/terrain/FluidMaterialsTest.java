/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import com.nvidia.rtxremix.MaterialInfo;
import org.junit.Test;
import static org.junit.Assert.*;

public class FluidMaterialsTest {
    @Test public void waterUsesVolumeTransmissionWithItsAtlasSurface() {
        MaterialInfo water = FluidMaterials.water(1, 123);
        assertEquals(123, water.albedoTexture);
        assertNull(water.opaqueExt);
        assertNotNull(water.translucentExt);
        assertEquals(1.333f, water.translucentExt.refractiveIndex, 0);
        assertTrue(water.translucentExt.transmittanceMeasurementDistance > 0);
        assertEquals(0, water.translucentExt.thinWallThickness_hasvalue);
        assertEquals(1, water.translucentExt.useDiffuseLayer);
        assertEquals(0, water.emissiveIntensity, 0);
    }

    @Test public void lavaEmissionSamplesOnlyItsOwnAtlasUvs() {
        MaterialInfo lava = FluidMaterials.lava(2, 123);
        assertEquals(123, lava.albedoTexture);
        assertEquals(123, lava.emissiveTexture);
        assertTrue(lava.emissiveIntensity > 0);
        assertNotNull(lava.opaqueExt);
        assertNull(lava.translucentExt);
        assertEquals(0, lava.opaqueExt.blendType_hasvalue);
        assertEquals(7, lava.opaqueExt.alphaTestType);
        assertEquals(MaterialInfo.WRAP_MODE_CLAMP, lava.wrapModeU);
    }
}

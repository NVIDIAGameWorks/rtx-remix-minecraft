/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import com.nvidia.rtxremix.MaterialInfo;
import net.minecraft.util.ResourceLocation;
import org.junit.Test;
import static org.junit.Assert.*;

public class GlassMaterialsTest {
    @Test public void onlyVanillaGlassBlocksAndClosedPanesReceiveVolumeMaterials() {
        assertEquals(GlassMaterials.Kind.CLEAR, GlassMaterials.kind(new ResourceLocation("minecraft:glass")));
        assertEquals(GlassMaterials.Kind.STAINED, GlassMaterials.kind(new ResourceLocation("minecraft:stained_glass")));
        assertEquals(GlassMaterials.Kind.CLEAR, GlassMaterials.kind(new ResourceLocation("minecraft:glass_pane")));
        assertEquals(GlassMaterials.Kind.STAINED, GlassMaterials.kind(new ResourceLocation("minecraft:stained_glass_pane")));
        for (String name : new String[] {"minecraft:ice", "minecraft:slime", "mod:glass", "mod:stained_glass",
                "mod:glass_pane", "mod:stained_glass_pane"}) {
            assertNull(name, GlassMaterials.kind(new ResourceLocation(name)));
        }
        assertNull(GlassMaterials.kind(null));
    }

    @Test public void clearGlassKeepsItsFrameWithoutGreyTransmissionFromTransparentTexels() {
        MaterialInfo clear = GlassMaterials.clear(1, 123);
        assertVolume(clear);
        assertEquals(123, clear.albedoTexture);
        assertEquals(0, clear.translucentExt.transmittanceTexture);
        assertEquals(1, clear.translucentExt.useDiffuseLayer);
    }

    @Test public void stainedGlassUsesSpriteColorsForTransmissionWithoutAnOpaqueOverlay() {
        MaterialInfo stained = GlassMaterials.stained(2, 123);
        assertVolume(stained);
        assertEquals(123, stained.albedoTexture);
        assertEquals(123, stained.translucentExt.transmittanceTexture);
        assertEquals(0, stained.translucentExt.useDiffuseLayer);
    }

    private static void assertVolume(MaterialInfo material) {
        assertNull(material.opaqueExt);
        assertNotNull(material.translucentExt);
        assertEquals(0, material.translucentExt.thinWallThickness_hasvalue);
        assertEquals(1.52f, material.translucentExt.refractiveIndex, 0);
        assertEquals(0.35f, material.translucentExt.transmittanceMeasurementDistance, 0);
        assertEquals(0.97f, material.translucentExt.transmittanceColor.x, 0);
        assertEquals(0.97f, material.translucentExt.transmittanceColor.y, 0);
        assertEquals(0.97f, material.translucentExt.transmittanceColor.z, 0);
        assertEquals(MaterialInfo.WRAP_MODE_CLAMP, material.wrapModeU);
        assertEquals(MaterialInfo.WRAP_MODE_CLAMP, material.wrapModeV);
    }
}

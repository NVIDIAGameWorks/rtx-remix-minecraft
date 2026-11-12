/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import com.nvidia.rtxremix.MaterialInfo;
import org.junit.Test;
import static org.junit.Assert.*;

public class BlockOverlayMaterialTest {
    @Test public void materialHonorsInstanceBlendWithoutChangingAtlasOrTint() {
        MaterialInfo material = BlockOverlayMaterial.create(17, 23);
        assertEquals(23, material.albedoTexture);
        assertNotNull(material.opaqueExt); assertNull(material.translucentExt);
        assertEquals(1, material.opaqueExt.useDrawCallAlphaState);
        assertEquals(0, material.opaqueExt.blendType_hasvalue);
        assertEquals(1, material.opaqueExt.opacityConstant, 0);
        assertEquals(1, material.opaqueExt.albedoConstant.x, 0);
        assertEquals(1, material.opaqueExt.albedoConstant.y, 0);
        assertEquals(1, material.opaqueExt.albedoConstant.z, 0);
        assertEquals(MaterialInfo.WRAP_MODE_CLAMP, material.wrapModeU);
        assertEquals(MaterialInfo.WRAP_MODE_CLAMP, material.wrapModeV);
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.MaterialInfoTranslucentEXT;

/** Fluid-only materials keep water transmission and lava emission away from other atlas users. */
final class FluidMaterials {
    private FluidMaterials() { }

    static MaterialInfo water(long hash, long atlas) {
        return base(hash, atlas).translucentExt(MaterialInfoTranslucentEXT.builder()
                .refractiveIndex(1.333f).transmittanceColor(0.65f, 0.9f, 0.9f)
                .transmittanceMeasurementDistance(4).useDiffuseLayer(true).build()).build();
    }

    static MaterialInfo lava(long hash, long atlas) {
        return base(hash, atlas).emissiveTexture(atlas).emissiveIntensity(4)
                .emissiveColorConstant(1, 1, 1).opaqueExt(MaterialInfoOpaqueEXT.builder()
                        .albedoConstant(1, 1, 1).roughnessConstant(0.8f).metallicConstant(0)
                        .useDrawCallAlphaState(false).alphaTestType(7).blendEnabled(false).build()).build();
    }

    private static MaterialInfo.Builder base(long hash, long atlas) {
        return MaterialInfo.builder().hash(hash).albedoTexture(atlas).filterMode(0)
                .wrapMode(MaterialInfo.WRAP_MODE_CLAMP, MaterialInfo.WRAP_MODE_CLAMP);
    }
}

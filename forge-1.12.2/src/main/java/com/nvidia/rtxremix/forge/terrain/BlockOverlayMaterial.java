/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;

/** Coincident cutout faces use the instance's alpha-blended decal state. */
final class BlockOverlayMaterial {
    private BlockOverlayMaterial() { }
    static MaterialInfo create(long hash, long atlas) {
        return MaterialInfo.builder().hash(hash).albedoTexture(atlas).filterMode(0)
                .wrapMode(MaterialInfo.WRAP_MODE_CLAMP, MaterialInfo.WRAP_MODE_CLAMP)
                .opaqueExt(MaterialInfoOpaqueEXT.builder().albedoConstant(1, 1, 1)
                        .roughnessConstant(1).metallicConstant(0).useDrawCallAlphaState(true).build()).build();
    }
}

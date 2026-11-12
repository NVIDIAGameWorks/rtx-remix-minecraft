/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoTranslucentEXT;
import net.minecraft.util.ResourceLocation;

/** Vanilla glass blocks and panes use closed baked volumes, independent of their OpenGL layer. */
final class GlassMaterials {
    enum Kind { CLEAR, STAINED }

    private static final ResourceLocation CLEAR = new ResourceLocation("minecraft", "glass");
    private static final ResourceLocation STAINED = new ResourceLocation("minecraft", "stained_glass");
    private static final ResourceLocation CLEAR_PANE = new ResourceLocation("minecraft", "glass_pane");
    private static final ResourceLocation STAINED_PANE = new ResourceLocation("minecraft", "stained_glass_pane");

    private GlassMaterials() { }

    static Kind kind(ResourceLocation block) {
        // Panes have separate front/back faces and their real 1/8-block thickness.
        // Thin-wall mode on both faces would count two independent glass sheets.
        if (CLEAR.equals(block) || CLEAR_PANE.equals(block)) return Kind.CLEAR;
        if (STAINED.equals(block) || STAINED_PANE.equals(block)) return Kind.STAINED;
        return null;
    }

    static MaterialInfo clear(long hash, long atlas) {
        // Transparent texels contain grey RGB. Constant transmission avoids tinting
        // the volume grey; the alpha-weighted diffuse layer preserves its frame.
        return base(hash, atlas).translucentExt(volume()
                .useDiffuseLayer(true).build()).build();
    }

    static MaterialInfo stained(long hash, long atlas) {
        return base(hash, atlas).translucentExt(volume()
                .transmittanceTexture(atlas).useDiffuseLayer(false).build()).build();
    }

    private static MaterialInfoTranslucentEXT.Builder volume() {
        return MaterialInfoTranslucentEXT.builder().refractiveIndex(1.52f)
                .transmittanceColor(0.97f, 0.97f, 0.97f)
                .transmittanceMeasurementDistance(0.35f);
    }

    private static MaterialInfo.Builder base(long hash, long atlas) {
        return MaterialInfo.builder().hash(hash).albedoTexture(atlas).filterMode(0)
                .wrapMode(MaterialInfo.WRAP_MODE_CLAMP, MaterialInfo.WRAP_MODE_CLAMP);
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Minecraft object adapter for canonical retained entity submission. */
public final class MinecraftEntitySubmissionAdapter implements EntitySubmissionAdapter {
    @Override
    public CapturedEntityGeometry createGeometryScratch() {
        return new RemixCapturingConsumer(null, false);
    }

    @Override
    public int beaconLensKind(Object owner, Object platformSprite) {
        if (!(owner instanceof CopperBeaconBlockEntity) || platformSprite == null) {
            return BEACON_LENS_NONE;
        }
        TextureAtlasSprite sprite = (TextureAtlasSprite) platformSprite;
        if (CopperBeaconBlockEntityRenderer.LENS_SPRITE_ID.equals(
                sprite.contents().name())) {
            return BEACON_LENS_EMISSIVE;
        }
        return CopperBeaconBlockEntityRenderer.UNLIT_LENS_SPRITE_ID.equals(
                sprite.contents().name())
                ? BEACON_LENS_UNLIT
                : BEACON_LENS_NONE;
    }

    @Override
    public float beaconIntensityMultiplier(Object owner) {
        return ((CopperBeaconBlockEntity) owner).getIntensityMultiplier();
    }
}

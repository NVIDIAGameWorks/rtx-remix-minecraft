/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;


final class EntityTestAdapters {
    static final EntityTextureSource NO_TEXTURES = new EntityTextureSource() {
        @Override
        public TextureSnapshot loadTexture(EntityTextureKey textureId) {
            return null;
        }

        @Override
        public DynamicTextureSnapshot dynamicTexture(EntityTextureKey textureId) {
            return null;
        }

        @Override
        public AtlasSnapshot loadAtlas(EntityTextureKey textureId) {
            return null;
        }

        @Override
        public boolean isGlyphPage(EntityTextureKey textureId) {
            return false;
        }

        @Override
        public EndPortalEmissive pollEndPortalEmissive(EntityTextureKey textureId) {
            return null;
        }

        @Override
        public void invalidateResources() {
        }

        @Override
        public void clear() {
        }
    };

    static final EntitySubmissionAdapter NO_PLATFORM = new EntitySubmissionAdapter() {
        @Override
        public CapturedEntityGeometry createGeometryScratch() {
            return null;
        }

        @Override
        public int beaconLensKind(Object owner, Object platformSprite) {
            return BEACON_LENS_NONE;
        }

        @Override
        public float beaconIntensityMultiplier(Object owner) {
            return 0.0f;
        }
    };

    private EntityTestAdapters() {
    }
}

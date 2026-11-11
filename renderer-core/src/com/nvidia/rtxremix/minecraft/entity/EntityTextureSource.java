/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.texture.LabPBRConverter;

import java.util.List;

/**
 * Loader-facing texture capture for canonical entity materials.
 *
 * <p>The loader owns Minecraft resource and GPU-atlas access. The renderer owns
 * LabPBR conversion, Remix texture lifetime, materials, and cache invalidation.
 */
public interface EntityTextureSource {
    TextureSnapshot loadTexture(EntityTextureKey textureId);

    DynamicTextureSnapshot dynamicTexture(EntityTextureKey textureId);

    AtlasSnapshot loadAtlas(EntityTextureKey textureId);

    boolean isGlyphPage(EntityTextureKey textureId);

    EndPortalEmissive pollEndPortalEmissive(EntityTextureKey textureId);

    void invalidateResources();

    void clear();

    interface PixelImage extends LabPBRConverter.PixelImage, AutoCloseable {
        @Override
        void close();
    }

    final class TextureSnapshot implements AutoCloseable {
        public final byte[] albedoRgba;
        public final int width;
        public final int height;
        public final PixelImage normal;
        public final PixelImage specular;
        public final PixelImage subsurfaceAlbedo;

        public TextureSnapshot(
                byte[] albedoRgba,
                int width,
                int height,
                PixelImage normal,
                PixelImage specular,
                PixelImage subsurfaceAlbedo) {
            this.albedoRgba = albedoRgba;
            this.width = width;
            this.height = height;
            this.normal = normal;
            this.specular = specular;
            this.subsurfaceAlbedo = subsurfaceAlbedo;
        }

        @Override
        public void close() {
            closeImage(normal);
            closeImage(specular);
            closeImage(subsurfaceAlbedo);
        }
    }

    final class DynamicTextureSnapshot {
        public final byte[] rgba;
        public final int width;
        public final int height;
        public final long version;

        public DynamicTextureSnapshot(byte[] rgba, int width, int height, long version) {
            this.rgba = rgba;
            this.width = width;
            this.height = height;
            this.version = version;
        }
    }

    final class EndPortalEmissive {
        public final byte[] rgba;
        public final int tileSize;
        public final float emissiveIntensity;

        public EndPortalEmissive(byte[] rgba, int tileSize, float emissiveIntensity) {
            this.rgba = rgba;
            this.tileSize = tileSize;
            this.emissiveIntensity = emissiveIntensity;
        }
    }

    final class AtlasSnapshot implements AutoCloseable {
        public final byte[] albedoRgba;
        public final int width;
        public final int height;
        public final List<SpriteSnapshot> sprites;

        public AtlasSnapshot(
                byte[] albedoRgba, int width, int height, List<SpriteSnapshot> sprites) {
            this.albedoRgba = albedoRgba;
            this.width = width;
            this.height = height;
            this.sprites = sprites;
        }

        @Override
        public void close() {
            for (SpriteSnapshot sprite : sprites) {
                sprite.close();
            }
        }
    }

    final class SpriteSnapshot implements AutoCloseable {
        public final int x;
        public final int y;
        public final int width;
        public final int height;
        public final PixelImage normal;
        public final PixelImage specular;
        public final PixelImage subsurfaceAlbedo;

        public SpriteSnapshot(
                int x,
                int y,
                int width,
                int height,
                PixelImage normal,
                PixelImage specular,
                PixelImage subsurfaceAlbedo) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.normal = normal;
            this.specular = specular;
            this.subsurfaceAlbedo = subsurfaceAlbedo;
        }

        @Override
        public void close() {
            closeImage(normal);
            closeImage(specular);
            closeImage(subsurfaceAlbedo);
        }
    }

    static void closeImage(PixelImage image) {
        if (image != null) {
            image.close();
        }
    }
}

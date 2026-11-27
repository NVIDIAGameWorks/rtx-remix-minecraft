/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.minecraft.mixin.SpriteAtlasAccessor;
import com.nvidia.rtxremix.minecraft.texture.DynamicTextureCache;
import com.nvidia.rtxremix.minecraft.texture.MinecraftBlockAtlasSource;
import com.nvidia.rtxremix.minecraft.texture.LabPBRVariants;
import com.nvidia.rtxremix.minecraft.texture.NativeImagePixels;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/** Minecraft resource and atlas adapter for canonical entity material creation. */
public final class MinecraftEntityTextureSource implements EntityTextureSource {
    private LabPBRVariants variants;
    private final EndPortalSurface endPortalSurfaces = new EndPortalSurface();

    @Override
    public TextureSnapshot loadTexture(EntityTextureKey textureId) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return null;
        }
        ResourceManager resources = client.getResourceManager();
        Optional<Resource> resource = resources.getResource(minecraftId(textureId));
        if (resource.isEmpty()) {
            return null;
        }

        try (NativeImage image = NativeImage.read(resource.get().open())) {
            EntityTextureKey spriteId = RemixEntitySubmitter.deriveLabPBRSpriteId(textureId);
            return new TextureSnapshot(
                    abgrToRgba(image),
                    image.getWidth(),
                    image.getHeight(),
                    loadVariant(resources, spriteId, "_n"),
                    loadVariant(resources, spriteId, "_s"),
                    loadVariant(resources, spriteId, "_sss"));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public DynamicTextureSnapshot dynamicTexture(EntityTextureKey textureId) {
        DynamicTextureCache.Pixels pixels = DynamicTextureCache.get(minecraftId(textureId));
        return pixels == null
                ? null
                : new DynamicTextureSnapshot(
                        pixels.data(), pixels.width(), pixels.height(), pixels.version());
    }

    @Override
    public AtlasSnapshot loadAtlas(EntityTextureKey textureId) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return null;
        }
        AbstractTexture texture =
                client.getTextureManager().getTexture(minecraftId(textureId));
        if (!(texture instanceof TextureAtlas atlas)) {
            return null;
        }

        int width = MinecraftBlockAtlasSource.atlasWidth(atlas);
        int height = MinecraftBlockAtlasSource.atlasHeight(atlas);
        if (width == 0 || height == 0) {
            return null;
        }

        ResourceManager resources = client.getResourceManager();
        List<SpriteSnapshot> sprites = new ArrayList<>();
        try {
            Map<ResourceLocation, TextureAtlasSprite> atlasSprites = ((SpriteAtlasAccessor) atlas).getSprites();
            for (Map.Entry<ResourceLocation, TextureAtlasSprite> entry : atlasSprites.entrySet()) {
                TextureAtlasSprite sprite = entry.getValue();
                EntityTextureKey spriteId = entityTextureKey(entry.getKey());
                sprites.add(new SpriteSnapshot(
                        sprite.getX(),
                        sprite.getY(),
                        sprite.contents().width(),
                        sprite.contents().height(),
                        loadVariant(resources, spriteId, "_n"),
                        loadVariant(resources, spriteId, "_s"),
                        loadVariant(resources, spriteId, "_sss")));
            }
            return new AtlasSnapshot(
                    MinecraftBlockAtlasSource.stitchAtlasRgba(atlas), width, height, sprites);
        } catch (RuntimeException e) {
            for (SpriteSnapshot sprite : sprites) {
                sprite.close();
            }
            return null;
        }
    }

    @Override
    public boolean isGlyphPage(EntityTextureKey textureId) {
        return DynamicTextureCache.isGlyphPage(minecraftId(textureId));
    }

    @Override
    public EndPortalEmissive pollEndPortalEmissive(EntityTextureKey textureId) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return null;
        }
        byte[] rgba = endPortalSurfaces.poll(minecraftId(textureId), client.getResourceManager());
        return rgba == null
                ? null
                : new EndPortalEmissive(
                        rgba, EndPortalSurface.TILE_SIZE, EndPortalSurface.EMISSIVE_INTENSITY);
    }

    @Override
    public void invalidateResources() {
        variants = null;
        endPortalSurfaces.clear();
    }

    @Override
    public void clear() {
        invalidateResources();
        DynamicTextureCache.clear();
    }

    private NativeImagePixels loadVariant(
            ResourceManager resources, EntityTextureKey spriteId, String suffix) {
        if (spriteId == null) {
            return null;
        }
        if (variants == null) {
            variants = LabPBRVariants.scan(resources);
        }
        NativeImage image = variants.loadVariant(minecraftId(spriteId), suffix);
        return image == null ? null : NativeImagePixels.of(image);
    }

    private static EntityTextureKey entityTextureKey(ResourceLocation id) {
        return new EntityTextureKey(id.getNamespace(), id.getPath(), id);
    }

    private static ResourceLocation minecraftId(EntityTextureKey key) {
        Object platformKey = key.platformKey();
        return platformKey instanceof ResourceLocation
                ? (ResourceLocation) platformKey
                : ResourceLocation.fromNamespaceAndPath(key.namespace(), key.path());
    }

    private static byte[] abgrToRgba(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] rgba = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int abgr = image.getPixelRGBA(x, y);
                int index = (y * width + x) * 4;
                rgba[index] = (byte) (abgr & 0xFF);
                rgba[index + 1] = (byte) ((abgr >> 8) & 0xFF);
                rgba[index + 2] = (byte) ((abgr >> 16) & 0xFF);
                rgba[index + 3] = (byte) ((abgr >> 24) & 0xFF);
            }
        }
        return rgba;
    }
}

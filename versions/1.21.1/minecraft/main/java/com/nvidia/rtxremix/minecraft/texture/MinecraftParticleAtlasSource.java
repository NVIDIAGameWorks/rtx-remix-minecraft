/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.minecraft.mixin.SpriteAtlasAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

/** Reads the Minecraft particle atlas for the canonical particle material manager. */
public final class MinecraftParticleAtlasSource
        implements ParticleAtlasManager.ParticleAtlasSource {
    private LabPBRVariants variants;

    @Override
    public ParticleAtlasManager.ParticleAtlasData load() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getTextureManager() == null) {
            return null;
        }
        AbstractTexture texture = client.getTextureManager()
                .getTexture(TextureAtlas.LOCATION_PARTICLES);
        if (!(texture instanceof TextureAtlas atlas)) {
            return null;
        }

        SpriteAtlasAccessor access = (SpriteAtlasAccessor) atlas;
        List<ParticleAtlasManager.ParticleSpriteData> sprites =
                new ArrayList<>(access.getSprites().size());
        for (Map.Entry<ResourceLocation, TextureAtlasSprite> entry : access.getSprites().entrySet()) {
            TextureAtlasSprite sprite = entry.getValue();
            sprites.add(new ParticleAtlasManager.ParticleSpriteData(
                    entry.getKey(),
                    sprite.getX(),
                    sprite.getY(),
                    sprite.contents().width(),
                    sprite.contents().height()));
        }
        return new ParticleAtlasManager.ParticleAtlasData(
                access.getWidth(),
                access.getHeight(),
                MinecraftBlockAtlasSource.stitchAtlasRgba(atlas),
                sprites);
    }

    @Override
    public void scanVariants() {
        variants = LabPBRVariants.scan(
                Minecraft.getInstance().getResourceManager());
    }

    @Override
    public BlockTextureManager.ImageResource loadVariant(
            Object spriteId,
            String suffix) {
        if (variants == null || !(spriteId instanceof ResourceLocation id)) {
            return null;
        }
        ResourceLocation textureId = ResourceLocation.fromNamespaceAndPath(
                id.getNamespace(), "particle/" + id.getPath());
        NativeImage image = variants.loadVariant(textureId, suffix);
        return image == null ? null : NativeImagePixels.of(image);
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import com.nvidia.rtxremix.minecraft.MinecraftPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;

/** Minecraft resource adapter for the canonical sky scene. */
public final class MinecraftSkyResourceSource implements SkyResourceSource {
    private static final ResourceLocation SUN =
            ResourceLocation.withDefaultNamespace("textures/environment/sun.png");
    private static final ResourceLocation MOON =
            ResourceLocation.withDefaultNamespace("textures/environment/moon_phases.png");
    private static final String OVERCAST_TEXTURE =
            "textures/overcast_thunderstorm_bc6u.dds";

    @Override
    public String overcastTexturePath() {
        return MinecraftPlatform.services().gameDirectory()
                .resolve(OVERCAST_TEXTURE)
                .toString();
    }

    @Override
    public Image loadSunTexture() throws IOException {
        return load(SUN);
    }

    @Override
    public Image loadMoonTexture() throws IOException {
        return load(MOON);
    }

    @Override
    public float[] copyStarVertices() {
        Object renderer = Minecraft.getInstance().levelRenderer;
        if (!(renderer instanceof WorldRendererStarAccess)) {
            throw new IllegalStateException("WorldRenderer star capture is unavailable");
        }
        return ((WorldRendererStarAccess) renderer).rtxremix$copyStarVertices();
    }

    private static Image load(ResourceLocation id) throws IOException {
        Minecraft client = Minecraft.getInstance();
        net.minecraft.server.packs.resources.Resource resource =
                client.getResourceManager().getResource(id)
                        .orElseThrow(() -> new IOException("Missing texture " + id));
        try (NativeImage image = NativeImage.read(resource.open())) {
            int width = image.getWidth();
            int height = image.getHeight();
            byte[] rgba = new byte[width * height * 4];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int abgr = image.getPixelRGBA(x, y);
                    int index = (y * width + x) * 4;
                    rgba[index] = (byte) (abgr & 0xFF);
                    rgba[index + 1] = (byte) ((abgr >>> 8) & 0xFF);
                    rgba[index + 2] = (byte) ((abgr >>> 16) & 0xFF);
                    rgba[index + 3] = (byte) ((abgr >>> 24) & 0xFF);
                }
            }
            return new Image(rgba, width, height);
        }
    }
}

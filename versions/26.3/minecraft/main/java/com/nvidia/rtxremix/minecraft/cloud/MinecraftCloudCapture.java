/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import com.nvidia.rtxremix.minecraft.sky.SkyAttributes;
import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.RemixAPI;
import java.io.IOException;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;

/**
 * Translates Minecraft cloud state into the canonical cloud renderer.
 */
public final class MinecraftCloudCapture implements CloudRenderer.VanillaCloudMaskSource {
    private static final Identifier VANILLA_CLOUD_TEXTURE =
            Identifier.withDefaultNamespace("textures/environment/clouds.png");

    private final CloudRenderer renderer;
    private ClientLevel currentWorld;
    private long worldSession;

    public MinecraftCloudCapture(RemixAPI api) {
        renderer = new CloudRenderer(api, this);
    }

    public CloudRenderer renderer() {
        return renderer;
    }

    public void render(ClientLevel world, Vec3 cameraPos, float tickDelta) {
        if (world != currentWorld) {
            currentWorld = world;
            worldSession++;
        }

        Minecraft client = Minecraft.getInstance();
        int cloudColor = SkyAttributes.cloudColor(tickDelta);
        int cloudTicks =
                ((WorldRendererCloudAccess) client.levelRenderer).rtxremix$getCloudTicks();
        renderer.render(
                worldSession,
                client.options.cloudStatus().get() != CloudStatus.OFF,
                SkyAttributes.cloudHeight(tickDelta),
                ARGB.redFloat(cloudColor),
                ARGB.greenFloat(cloudColor),
                ARGB.blueFloat(cloudColor),
                cameraPos.x,
                cameraPos.z,
                world.getGameTime() + (double) tickDelta,
                cloudTicks + (double) tickDelta);
    }

    public void cleanup() {
        try {
            renderer.cleanup();
        } finally {
            currentWorld = null;
        }
    }

    @Override
    public CloudRenderer.VanillaCloudMask load() throws IOException {
        Minecraft client = Minecraft.getInstance();
        var resource = client.getResourceManager().getResource(VANILLA_CLOUD_TEXTURE)
                .orElseThrow(() -> new IOException(
                        "Missing texture " + VANILLA_CLOUD_TEXTURE));
        try (NativeImage image = NativeImage.read(resource.open())) {
            int width = image.getWidth();
            int height = image.getHeight();
            boolean[] occupancy = new boolean[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    occupancy[y * width + x] =
                            ((image.getPixelABGR(x, y) >>> 24) & 0xff) >= 128;
                }
            }
            return new CloudRenderer.VanillaCloudMask(width, height, occupancy);
        }
    }
}

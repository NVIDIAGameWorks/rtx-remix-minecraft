/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/** Minecraft resource lookup for the loader-neutral LabPBR converter. */
public final class LabPBRVariants {
    private final Map<ResourceLocation, Resource> resources;

    private LabPBRVariants(Map<ResourceLocation, Resource> resources) {
        this.resources = resources;
    }

    public static LabPBRVariants scan(ResourceManager resourceManager) {
        return new LabPBRVariants(
                resourceManager.listResources("textures", LabPBRVariants::isVariantResource));
    }

    public NativeImage loadVariant(ResourceLocation spriteId, String suffix) {
        return loadResource(resources.get(variantId(spriteId, suffix)));
    }

    public int size() {
        return resources.size();
    }

    private static boolean isVariantResource(ResourceLocation id) {
        String path = id.getPath();
        return path.endsWith("_n.png") || path.endsWith("_s.png") || path.endsWith("_sss.png");
    }

    private static NativeImage loadResource(Resource resource) {
        if (resource == null) return null;
        try (InputStream stream = resource.open()) {
            return NativeImage.read(stream);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static ResourceLocation variantId(ResourceLocation spriteId, String suffix) {
        return ResourceLocation.fromNamespaceAndPath(
                spriteId.getNamespace(), "textures/" + spriteId.getPath() + suffix + ".png");
    }
}

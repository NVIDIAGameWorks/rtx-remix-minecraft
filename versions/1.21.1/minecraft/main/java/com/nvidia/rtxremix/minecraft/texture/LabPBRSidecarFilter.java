/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.mojang.serialization.MapCodec;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.HashSet;
import java.util.Set;

/**
 * Sprite source that keeps LabPBR sidecars out of an atlas.
 *
 * <p>Vanilla stitches every texture under an atlas's source directories, so a sidecar becomes a sprite beside the
 * texture it describes. The Remix copy of an atlas is one base colour texture with a full mip chain, and a mip
 * texel coarser than a sprite averages that sprite with its neighbours: a normal map next to a base colour tints
 * it blue-violet wherever a wide ray footprint samples it. Sidecars reach Remix from their files
 * ({@link LabPBRVariants}), so an atlas needs none of them.
 *
 * <p>A sprite is a sidecar when its name is the name of another sprite in the same atlas plus a sidecar suffix,
 * the rule the variant cache applies to files. A suffixed texture with no such base stays a sprite.
 */
public final class LabPBRSidecarFilter implements SpriteSource {

    public static final LabPBRSidecarFilter INSTANCE = new LabPBRSidecarFilter();

    /** Unregistered: the filter is appended in code and never read from or written to an atlas definition. */
    private static final SpriteSourceType TYPE = new SpriteSourceType(MapCodec.unit(INSTANCE));

    private LabPBRSidecarFilter() {}

    @Override
    public void run(ResourceManager resourceManager, Output output) {
        // An Output shows its sprites only to a removeAll predicate.
        Set<ResourceLocation> sprites = new HashSet<>();
        output.removeAll(sprite -> {
            sprites.add(sprite);
            return false;
        });
        output.removeAll(sprite -> isSidecar(sprite, sprites));
    }

    private static boolean isSidecar(ResourceLocation sprite, Set<ResourceLocation> atlasSprites) {
        String path = sprite.getPath();
        for (String suffix : LabPBRVariants.VARIANT_SUFFIXES) {
            if (path.endsWith(suffix)
                    && atlasSprites.contains(sprite.withPath(path.substring(0, path.length() - suffix.length())))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public SpriteSourceType type() {
        return TYPE;
    }
}

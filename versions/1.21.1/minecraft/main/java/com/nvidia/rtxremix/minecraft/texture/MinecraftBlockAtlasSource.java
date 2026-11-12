/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.minecraft.mixin.SpriteAtlasAccessor;
import com.nvidia.rtxremix.minecraft.mixin.SpriteContentsAccessor;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Reads Minecraft's block atlas and resource-pack sidecars for the canonical manager. */
public final class MinecraftBlockAtlasSource implements BlockTextureManager.AtlasSource {
    public static final ResourceLocation BLOCK_ATLAS_ID =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png");

    private LabPBRVariants variants;

    @Override
    public void scanVariants() {
        variants = LabPBRVariants.scan(
                Minecraft.getInstance().getResourceManager());
    }

    @Override
    public BlockTextureManager.AtlasData load() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getTextureManager() == null) {
            return null;
        }
        AbstractTexture texture = client.getTextureManager().getTexture(BLOCK_ATLAS_ID);
        if (!(texture instanceof TextureAtlas atlas)) {
            return null;
        }

        SpriteAtlasAccessor access = (SpriteAtlasAccessor) atlas;
        List<BlockTextureManager.SpriteData> sprites =
                new ArrayList<>(access.getSprites().size());
        for (Map.Entry<ResourceLocation, TextureAtlasSprite> entry : access.getSprites().entrySet()) {
            sprites.add(snapshot(entry.getKey(), entry.getValue()));
        }
        return new BlockTextureManager.AtlasData(
                access.getWidth(),
                access.getHeight(),
                stitchAtlasRgba(atlas),
                sprites,
                buildLuminanceMap());
    }

    @Override
    public BlockTextureManager.ImageResource loadVariant(Object spriteId, String suffix) {
        if (variants == null || !(spriteId instanceof ResourceLocation id)) {
            return null;
        }
        NativeImage image = variants.loadVariant(id, suffix);
        return image == null ? null : NativeImagePixels.of(image);
    }

    @Override
    public void initializeAnimation(
            Map<String, AnimatedSpriteMaterialData> materialData,
            Map<Object, String> spriteNames) {
        AtlasAnimationForwarder.init(
                materialData,
                spriteNames,
                BLOCK_ATLAS_ID,
                MemoryUtil::memByteBuffer);
    }

    private static BlockTextureManager.SpriteData snapshot(ResourceLocation id, TextureAtlasSprite sprite) {
        SpriteContents contents = sprite.contents();
        NativeImage image = ((SpriteContentsAccessor) contents).getImage();
        int width = contents.width();
        int height = contents.height();
        BitSet transparentPixels = scanTransparency(image, width, height);
        SpriteContents.AnimatedTexture animation =
                ((SpriteContentsAccessor) contents).getAnimation();
        BlockTextureManager.AnimationData animationData =
                animation == null ? null : snapshotAnimation(animation);
        return new BlockTextureManager.SpriteData(
                id,
                id.toString(),
                sprite.getU0(),
                sprite.getV0(),
                sprite.getU1(),
                sprite.getV1(),
                sprite.getX(),
                sprite.getY(),
                width,
                height,
                transparentPixels,
                animationData);
    }

    private static BlockTextureManager.AnimationData snapshotAnimation(
            SpriteContents.AnimatedTexture animation) {
        List<SpriteContents.FrameInfo> frames = animation.frames;
        int[] frameIndices = new int[frames.size()];
        int[] frameTimes = new int[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            SpriteContents.FrameInfo frame = frames.get(i);
            frameIndices[i] = frame.index;
            frameTimes[i] = frame.time;
        }
        return new BlockTextureManager.AnimationData(frameIndices, frameTimes);
    }

    private static BitSet scanTransparency(NativeImage image, int width, int height) {
        if (image == null) {
            return null;
        }
        BitSet transparent = null;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (((image.getPixelRGBA(x, y) >>> 24) & 0xff) < 255) {
                    if (transparent == null) {
                        transparent = new BitSet(width * height);
                    }
                    transparent.set(y * width + x);
                }
            }
        }
        return transparent;
    }

    public static byte[] stitchAtlasRgba(TextureAtlas atlas) {
        SpriteAtlasAccessor access = (SpriteAtlasAccessor) atlas;
        int width = access.getWidth();
        int height = access.getHeight();
        byte[] pixels = new byte[width * height * 4];
        for (TextureAtlasSprite sprite : access.getSprites().values()) {
            NativeImage image = ((SpriteContentsAccessor) sprite.contents()).getImage();
            if (image == null) {
                continue;
            }
            int spriteX = sprite.getX();
            int spriteY = sprite.getY();
            int spriteWidth = sprite.contents().width();
            int spriteHeight = sprite.contents().height();
            for (int y = 0; y < spriteHeight; y++) {
                for (int x = 0; x < spriteWidth; x++) {
                    int abgr = image.getPixelRGBA(x, y);
                    int index = ((spriteY + y) * width + spriteX + x) * 4;
                    pixels[index] = (byte) (abgr & 0xff);
                    pixels[index + 1] = (byte) ((abgr >>> 8) & 0xff);
                    pixels[index + 2] = (byte) ((abgr >>> 16) & 0xff);
                    pixels[index + 3] = (byte) ((abgr >>> 24) & 0xff);
                }
            }
        }
        return pixels;
    }

    public static int atlasWidth(TextureAtlas atlas) {
        return ((SpriteAtlasAccessor) atlas).getWidth();
    }

    public static int atlasHeight(TextureAtlas atlas) {
        return ((SpriteAtlasAccessor) atlas).getHeight();
    }

    private static Map<String, Integer> buildLuminanceMap() {
        Map<String, Integer> luminance = new HashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            int maximum = 0;
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                maximum = Math.max(maximum, state.getLightEmission());
            }
            if (maximum > 0) {
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
                luminance.merge(
                        id.getNamespace() + ":block/" + id.getPath(),
                        maximum,
                        Math::max);
            }
        }
        return luminance;
    }

}

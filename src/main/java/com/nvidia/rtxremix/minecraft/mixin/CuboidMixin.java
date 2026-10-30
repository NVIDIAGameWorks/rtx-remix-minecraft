/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.RemixCapturingConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SpriteCoordinateExpander;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts {@code ModelPart.Cuboid.renderCuboid()} so a cuboid reaches the Remix
 * capture as the immutable object it is rather than as expanded vertices.
 *
 * <p>When the active {@link VertexConsumer} is a {@link RemixCapturingConsumer}
 * inside a Remix entity pass, directly or behind a {@link SpriteCoordinateExpander},
 * the cuboid's sides, sprite, folded tint and pose are handed to the consumer, which
 * registers the pivot in the {@link EntityRenderContext} and records a rigid part
 * keyed by the cuboid's identity; the vanilla vertex write is cancelled.
 */
@Mixin(ModelPart.Cube.class)
public class CuboidMixin {

    @Shadow
    @Final
    private ModelPart.Polygon[] polygons;

    @Inject(method = "compile", at = @At("HEAD"), cancellable = true)
    private void rtxremix$onRenderCuboid(PoseStack.Pose entry, VertexConsumer vertexConsumer,
                                          int light, int overlay, int color,
                                          CallbackInfo ci) {
        // Sprite-atlas models (chests, beds, banners, shields, pots) draw through a
        // wrapper whose only behaviour is remapping UVs into the sprite's rectangle.
        // The capture reproduces that remap when it rebuilds the cuboid, so the
        // wrapper is looked through and the sprite travels with the part: one cuboid
        // is baked once and painted with many sprites, so the sprite is part of the
        // part's identity. A wrapper around anything else, such as the glint union
        // ItemRenderer builds, is left to vanilla.
        VertexConsumer target = vertexConsumer;
        TextureAtlasSprite sprite = null;
        if (target instanceof SpriteCoordinateExpander wrapped) {
            sprite = wrapped.sprite;
            target = wrapped.delegate;
        }
        if (!(target instanceof RemixCapturingConsumer consumer)) {
            return;
        }

        EntityRenderContext ctx = EntityRenderContext.get();
        if (!ctx.isRemixPass()) {
            return;
        }

        // The consumer owns pivot registration and the part record so it can tag
        // the part with the cuboid it came from. That tag has to be exact: submit
        // keys the mesh by this cuboid's identity, and a part wrongly attributed
        // would reuse another cuboid's geometry.
        // Folded before capture: the tint is an instance property downstream, so a
        // damage or priming flash is a per-instance update, never a new mesh.
        consumer.acceptCuboid(this.polygons,
                RemixCapturingConsumer.applyEntityOverlay(color, overlay),
                entry.pose(),
                sprite);

        ci.cancel();
    }
}

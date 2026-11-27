/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The submit-node collector Remix capture hands to vanilla renderers.
 *
 * <p>Since 26.x vanilla does not draw while it walks entities, block entities and
 * held items: renderers record submit nodes, and feature renderers evaluate them
 * later in batches sorted by render type. Capture needs the opposite, one owner's
 * geometry at a time with clear begin and end, so this collector evaluates every
 * submit immediately, the way vanilla's feature renderers would, into the
 * capturing consumers of a {@link RemixVertexConsumerProvider}. Vanilla never
 * sees these submits, so a model is still evaluated exactly once.
 *
 * <p>Calling order is paint order, which keeps a banner's coplanar pattern
 * layers in their decal order without honouring {@link #order(int)}.
 *
 * <p>Not captured: shadows (Remix traces real ones), crumbling overlays (the
 * block damage pass draws the crack), shape outlines, particles drawn as
 * submits, debug gizmos, and for now the burning-entity flame, the lead and
 * moving pistons' blocks (see port notes).
 */
public final class RemixSubmitCollector implements SubmitNodeCollector {

    private static final Direction[] DIRECTIONS = Direction.values();

    private final RemixVertexConsumerProvider provider;
    private final QuadInstance quadInstance = new QuadInstance();
    // The capture's own stack type, so ModelPartMixin applies its pooled part transform.
    private final RecyclingMatrixStack modelPoseStack = new RecyclingMatrixStack();
    private final GlyphCapture glyphCapture = new GlyphCapture();

    public RemixSubmitCollector(RemixVertexConsumerProvider provider) {
        this.provider = provider;
    }

    public RemixVertexConsumerProvider provider() {
        return provider;
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return this;
    }

    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
    }

    @Override
    public void submitNameTag(PoseStack poseStack, @Nullable Vec3 nameTagAttachment, int offset, Component name,
                              boolean seeThrough, int lightCoords, CameraRenderState camera) {
        if (nameTagAttachment == null) {
            return;
        }
        // Vanilla's placement; the see-through copy is drawn over the whole scene and
        // has no place in a traced one, so only the depth-tested text is captured.
        Minecraft minecraft = Minecraft.getInstance();
        poseStack.pushPose();
        poseStack.translate(nameTagAttachment.x, nameTagAttachment.y + 0.5, nameTagAttachment.z);
        poseStack.rotate(camera.orientation);
        poseStack.scale(0.025F, -0.025F, 0.025F);
        float x = -minecraft.font.width(name) / 2.0F;
        float backgroundAlpha = minecraft.gameRenderer.gameRenderState().optionsRenderState.getBackgroundOpacity(0.25F);
        float textAlpha = Math.max((backgroundAlpha + 0.75F) * 0.5F, 0.5F);
        int textColor = seeThrough ? -1 : ARGB.color(textAlpha, -1);
        captureText(poseStack.last().pose(), x, offset, name.getVisualOrderText(), false,
                Font.DisplayMode.NORMAL, lightCoords, textColor, 0, 0);
        poseStack.popPose();
    }

    @Override
    public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string, boolean dropShadow,
                           Font.DisplayMode displayMode, int lightCoords, int color, int backgroundColor,
                           int outlineColor) {
        captureText(poseStack.last().pose(), x, y, string, dropShadow, displayMode, lightCoords, color,
                backgroundColor, outlineColor);
    }

    @Override
    public void submitTextBackground(PoseStack poseStack, float x0, float y0, float x1, float y1, int color,
                                     Font.DisplayMode displayMode, int lightCoords) {
        // An untextured screen-facing quad: the provider has nothing to capture it under.
    }

    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
    }

    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
    }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
                                int lightCoords, int overlayCoords, int tintedColor, @Nullable UvMapping uvMapping,
                                int outlineColor) {
        // ModelFeatureRenderer.prepareModel, against the submit's own pose.
        VertexConsumer buffer = provider.getBuffer(renderType);
        if (uvMapping != null) {
            buffer = uvMapping.wrap(buffer);
        }
        modelPoseStack.last().set(poseStack.last());
        model.setupAnim(state);
        model.renderToBuffer(modelPoseStack, buffer, lightCoords, overlayCoords, tintedColor);
    }

    @Override
    public <S> void submitCrumblingOverlay(Model<? super S> model, S state, PoseStack poseStack,
                                           RenderType renderType, int lightCoords, int overlayCoords,
                                           int tintedColor, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
    }

    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState,
                                  int outlineColor) {
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts,
                                 int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {
        // BlockModelFeatureRenderer.putPartQuads with an untinted base.
        VertexConsumer buffer = provider.getBuffer(renderType);
        quadInstance.setLightCoords(lightCoords);
        quadInstance.setOverlayCoords(overlayCoords);
        PoseStack.Pose pose = poseStack.last();
        for (BlockStateModelPart part : parts) {
            for (Direction direction : DIRECTIONS) {
                putTintedQuads(pose, part.getQuads(direction), tintLayers, buffer);
            }
            putTintedQuads(pose, part.getQuads(null), tintLayers, buffer);
        }
    }

    private void putTintedQuads(PoseStack.Pose pose, List<BakedQuad> quads, int[] tintLayers, VertexConsumer buffer) {
        for (BakedQuad quad : quads) {
            int tintIndex = quad.materialInfo().tintIndex();
            boolean useTintLayer = tintIndex != -1 && tintIndex < tintLayers.length;
            quadInstance.setColor(useTintLayer ? tintLayers[tintIndex] : -1);
            buffer.putBakedQuad(pose, quad, quadInstance);
        }
    }

    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress,
                                         boolean isBlockTranslucent) {
    }

    @Override
    public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color,
                                   float width, boolean afterTerrain) {
    }

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords,
                           int overlayCoords, int outlineColor, int[] tintLayers, ItemQuads quads,
                           ItemStackRenderState.FoilType foilType) {
        // ItemFeatureRenderer.prepareMainSubmit. The glint is its own layer; its scrolling
        // decal UVs are not reproduced, the layer carries the quad's own UVs.
        quadInstance.setLightCoords(lightCoords);
        quadInstance.setOverlayCoords(overlayCoords);
        PoseStack.Pose pose = poseStack.last();
        for (BakedQuad quad : quads.all()) {
            BakedQuad.MaterialInfo material = quad.materialInfo();
            quadInstance.setColor(material.isTinted() ? layerColor(tintLayers, material.tintIndex()) : -1);
            provider.getBuffer(material.itemRenderType()).putBakedQuad(pose, quad, quadInstance);
            if (foilType != ItemStackRenderState.FoilType.NONE) {
                RenderType glint = foilType == ItemStackRenderState.FoilType.SPECIAL
                        ? material.itemGlintSpecialRenderType()
                        : material.itemGlintRenderType();
                provider.getBuffer(glint).putBakedQuad(pose, quad, quadInstance);
            }
        }
    }

    private static int layerColor(int[] layers, int layer) {
        return layer >= 0 && layer < layers.length ? layers[layer] : -1;
    }

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
                                     SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
        customGeometryRenderer.render(poseStack.last(), provider.getBuffer(renderType));
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState particles) {
    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {
    }

    /** Fabric's custom submit phases draw mod geometry Remix has no capture for. */
    @Override
    public <T extends SubmitNode> void submitCustom(SubmitRenderPhase<T> phase, T node) {
    }

    // ---- text ----

    private void captureText(Matrix4f pose, float x, float y, FormattedCharSequence string, boolean dropShadow,
                             Font.DisplayMode displayMode, int lightCoords, int color, int backgroundColor,
                             int outlineColor) {
        // TextFeatureRenderer.renderText: an outlined string (glowing sign text) is the
        // outline pass under the text itself.
        Font font = Minecraft.getInstance().font;
        glyphCapture.pose.set(pose);
        glyphCapture.lightCoords = lightCoords;
        if (outlineColor == 0) {
            glyphCapture.displayMode = displayMode;
            font.prepareText(string, x, y, color, dropShadow, false, backgroundColor).visit(glyphCapture);
        } else {
            glyphCapture.displayMode = Font.DisplayMode.NORMAL;
            font.prepare8xTextOutline(string, x, y, outlineColor).visit(glyphCapture);
            glyphCapture.displayMode = Font.DisplayMode.POLYGON_OFFSET;
            font.prepareText(string, x, y, color, false, false, 0).visit(glyphCapture);
        }
    }

    private final class GlyphCapture implements Font.GlyphVisitor {
        private final Matrix4f pose = new Matrix4f();
        private int lightCoords;
        private Font.DisplayMode displayMode = Font.DisplayMode.NORMAL;

        @Override
        public void acceptRenderable(TextRenderable renderable) {
            renderable.render(pose, provider.getBuffer(renderable.renderType(displayMode)), lightCoords, false);
        }
    }
}

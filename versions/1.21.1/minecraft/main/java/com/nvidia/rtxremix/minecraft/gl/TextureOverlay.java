/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws the Remix output texture as a full-screen, fully opaque quad on
 * Minecraft's main framebuffer, after the world and first-person model have
 * been submitted and before the screen overlays and HUD.
 *
 * <p>Render thread only. It goes through {@code RenderSystem} and
 * {@code Tessellator} rather than raw GL so the surrounding vanilla render
 * state stays valid.
 */
public class TextureOverlay {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(TextureOverlay.class);
    
    private final Minecraft client;
    
    public TextureOverlay() {
        this.client = Minecraft.getInstance();
        LOGGER.info("TextureOverlay initialized");
    }
    
    /**
     * Renders a texture as a full-screen overlay.
     * 
     * @param textureId The GL texture ID to render as full-screen overlay
     */
    public void render(int textureId) {
        if (textureId <= 0) {
            return;
        }
        
        if (client == null || client.getWindow() == null) {
            return;
        }
        
        try {
            renderTextureOverlay(textureId);
        } catch (Exception e) {
            LOGGER.error("Error rendering texture overlay", e);
        }
    }
    
    private void renderTextureOverlay(int texId) {
        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();

        if (client.getMainRenderTarget() != null) {
            client.getMainRenderTarget().bindWrite(true);
        }

        Matrix4f savedProjection = RenderSystem.getProjectionMatrix();

        // Opaque: the Remix image replaces vanilla's output rather than blending
        // with it, and it neither tests nor writes depth.
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();

        Matrix4f projMatrix = new Matrix4f().setOrtho(
            0.0f, (float) width, (float) height, 0.0f, -1000.0f, 1000.0f
        );
        RenderSystem.setProjectionMatrix(projMatrix, VertexSorting.ORTHOGRAPHIC_Z);

        RenderSystem.getModelViewStack().pushMatrix();
        RenderSystem.getModelViewStack().identity();
        RenderSystem.applyModelViewMatrix();

        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, texId);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        // Standard GL UVs, with (0,0) at the top left: the interop blit already
        // corrects the Vulkan image's orientation.
        Matrix4f identity = new Matrix4f();
        BufferBuilder bufferBuilder = Tesselator.getInstance()
            .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);

        bufferBuilder.addVertex(identity, 0,     0,      0).setUv(0.0f, 0.0f);
        bufferBuilder.addVertex(identity, 0,     height, 0).setUv(0.0f, 1.0f);
        bufferBuilder.addVertex(identity, width, height, 0).setUv(1.0f, 1.0f);
        bufferBuilder.addVertex(identity, width, 0,      0).setUv(1.0f, 0.0f);

        BufferUploader.drawWithShader(bufferBuilder.buildOrThrow());

        RenderSystem.getModelViewStack().popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(savedProjection, VertexSorting.ORTHOGRAPHIC_Z);

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        // Blend is left disabled; the HUD enables it when it needs it.
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }
    
    /**
     * Cleans up resources.
     */
    public void shutdown() {
        LOGGER.info("TextureOverlay shutdown");
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies the Remix output texture, full screen and fully opaque, onto
 * Minecraft's main render target, after the world and first-person model have
 * been submitted and before the screen overlays and HUD.
 *
 * <p>Render thread only, and OpenGL backend only: it blits with direct state
 * access so the surrounding vanilla render state stays valid.
 */
public class TextureOverlay {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(TextureOverlay.class);
    
    private final Minecraft client;

    /** Framebuffers the blit reads the Remix image from and writes the main target through. */
    private int readFramebuffer;
    private int drawFramebuffer;
    
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
    
    /**
     * Since 26.x vanilla draws through its own GL backend, which caches the GL
     * state it set. A direct-state-access blit changes no binding, so that cache
     * stays truthful, and it replaces the old textured full-screen quad.
     */
    private void renderTextureOverlay(int texId) {
        RenderTarget target = client.gameRenderer.mainRenderTarget();
        if (target == null || !(target.getColorTexture() instanceof GlTexture destination)) {
            return;
        }
        if (readFramebuffer == 0) {
            readFramebuffer = GL45.glCreateFramebuffers();
            drawFramebuffer = GL45.glCreateFramebuffers();
        }

        int sourceWidth = GL45.glGetTextureLevelParameteri(texId, 0, GL11.GL_TEXTURE_WIDTH);
        int sourceHeight = GL45.glGetTextureLevelParameteri(texId, 0, GL11.GL_TEXTURE_HEIGHT);
        GL45.glNamedFramebufferTexture(readFramebuffer, GL30.GL_COLOR_ATTACHMENT0, texId, 0);
        GL45.glNamedFramebufferTexture(drawFramebuffer, GL30.GL_COLOR_ATTACHMENT0, destination.glId(), 0);

        // Opaque: the Remix image replaces vanilla's output rather than blending
        // with it. The destination rows are flipped because the interop blit
        // already stores the image top row first.
        GlStateManager._disableScissorTest();
        GL45.glBlitNamedFramebuffer(
                readFramebuffer, drawFramebuffer,
                0, 0, sourceWidth, sourceHeight,
                0, target.height, target.width, 0,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
    }
    
    /**
     * Cleans up resources.
     */
    public void shutdown() {
        if (readFramebuffer != 0) {
            GL30.glDeleteFramebuffers(readFramebuffer);
            GL30.glDeleteFramebuffers(drawFramebuffer);
            readFramebuffer = 0;
            drawFramebuffer = 0;
        }
        LOGGER.info("TextureOverlay shutdown");
    }
}

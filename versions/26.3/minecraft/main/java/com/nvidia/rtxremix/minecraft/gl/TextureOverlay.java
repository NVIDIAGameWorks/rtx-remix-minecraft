/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composites the Remix output texture, full screen, onto Minecraft's main
 * render target, after the world and first-person model have been submitted
 * and before the screen overlays and HUD.
 *
 * <p>Texels with zero alpha are not drawn. That is how {@link GlInterop} marks
 * a frame Remix has not written, and vanilla's frame then stays as it is.
 * Since 26.x there is no textured full-screen quad that discards them, so the
 * image is first copied into a render target of the mod's own and then
 * blended onto the main target with vanilla's render-target blit, whose
 * blend keeps the destination where the source alpha is zero and replaces it
 * where the source is opaque.
 *
 * <p>Render thread only, and OpenGL backend only: the copy uses direct state
 * access and the blend goes through vanilla's render passes, so the
 * surrounding vanilla render state stays valid.
 */
public class TextureOverlay {

    private static final Logger LOGGER = LoggerFactory.getLogger(TextureOverlay.class);

    private final Minecraft client;

    /** Framebuffers the copy reads the Remix image from and writes the staging target through. */
    private int readFramebuffer;
    private int drawFramebuffer;
    /** The Remix image in the main target's size and orientation, blended from there. */
    private TextureTarget staging;

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
        RenderTarget target = client.gameRenderer.mainRenderTarget();
        if (target == null || target.width <= 0 || target.height <= 0) {
            return;
        }
        if (staging == null) {
            staging = new TextureTarget("RTX Remix output", target.width, target.height,
                    GpuFormat.RGBA8_UNORM, null);
        } else if (staging.width != target.width || staging.height != target.height) {
            staging.resize(target.width, target.height);
        }
        if (!(staging.getColorTexture() instanceof GlTexture destination)) {
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

        // A direct-state-access blit changes no binding vanilla's GL backend has
        // cached. The destination rows are flipped because the interop blit
        // already stores the image top row first; the alpha comes along, so an
        // unwritten frame stays transparent.
        GlStateManager._disableScissorTest();
        GL45.glBlitNamedFramebuffer(
                readFramebuffer, drawFramebuffer,
                0, 0, sourceWidth, sourceHeight,
                0, staging.height, staging.width, 0,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);

        // Vanilla's entity-outline blit blends colour by source alpha and keeps
        // the destination alpha: opaque Remix texels replace vanilla's, and
        // the zero-alpha texels of an unwritten frame leave it untouched.
        staging.blitAndBlendToTexture(target.getColorTextureView(), target.getDepthTextureView());
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
        if (staging != null) {
            staging.destroyBuffers();
            staging = null;
        }
        LOGGER.info("TextureOverlay shutdown");
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;
import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TextureOverlay} blends the Remix output onto the main target with
 * vanilla's render-target blit and relies on it to leave the destination where
 * the source alpha is zero, which is how {@link GlInterop} marks a frame Remix
 * has not written. These fail if a later Minecraft version changes that blit.
 */
class TextureOverlayBlendTest {

    private static final String BLIT_SCREEN = "/assets/minecraft/shaders/core/blit_screen.fsh";
    private static final Pattern PASSES_THE_TEXEL_THROUGH = Pattern.compile(
            "fragColor\\s*=\\s*texture\\s*\\(\\s*InSampler\\s*,\\s*texCoord\\s*\\)\\s*;");

    @Test
    void renderTargetBlitBlendsColourBySourceAlphaAndKeepsDestinationAlpha() {
        BlendFunction sourceOver = new BlendFunction(
                BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA,
                BlendFactor.ZERO, BlendFactor.ONE);
        ColorTargetState target = RenderPipelines.ENTITY_OUTLINE_BLIT.getColorTargetStates().get(0);
        assertEquals(Optional.of(sourceOver), target.blendFunction());
    }

    @Test
    void renderTargetBlitShaderKeepsTheSourceAlpha() throws IOException {
        try (InputStream in = TextureOverlayBlendTest.class.getResourceAsStream(BLIT_SCREEN)) {
            assertNotNull(in, BLIT_SCREEN + " is not in the Minecraft client");
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(PASSES_THE_TEXEL_THROUGH.matcher(source).find(),
                    "blit_screen no longer passes the sampled texel through unchanged");
        }
    }
}

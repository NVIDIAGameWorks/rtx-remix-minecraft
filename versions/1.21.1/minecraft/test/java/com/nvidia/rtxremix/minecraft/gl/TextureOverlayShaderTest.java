/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureOverlayShaderTest {

    private static final String POSITION_TEX = "/assets/minecraft/shaders/core/position_tex.fsh";
    private static final Pattern DISCARDS_ZERO_ALPHA = Pattern.compile(
            "if\\s*\\(\\s*color\\.a\\s*==\\s*0\\.0\\s*\\)\\s*\\{?\\s*discard\\s*;");

    /**
     * {@link TextureOverlay} draws the Remix output with vanilla's position_tex
     * shader and relies on it to skip texels whose alpha is zero, which is how
     * {@link GlInterop} marks a frame Remix has not written.
     */
    @Test
    void positionTexDiscardsTexelsWithZeroAlpha() throws IOException {
        try (InputStream in = TextureOverlayShaderTest.class.getResourceAsStream(POSITION_TEX)) {
            assertNotNull(in, POSITION_TEX + " is not in the Minecraft client");
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(DISCARDS_ZERO_ALPHA.matcher(source).find(),
                    "position_tex no longer discards texels whose alpha is zero");
        }
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RainRefractionShaderTest {

    private static final String RAIN_REFRACTION =
            "/assets/rtx-remix-renderer/shaders/core/rain_refraction.fsh";
    private static final Pattern DISCARDS_UNWRITTEN_FRAME = Pattern.compile(
            "vec4\\s+(\\w+)\\s*=\\s*texture\\(\\s*Sampler1\\s*,\\s*screenUv\\s*\\)\\s*;"
                    + "\\s*if\\s*\\(\\s*\\1\\.a\\s*==\\s*0\\.0\\s*\\)\\s*\\{?\\s*discard\\s*;");

    /**
     * Rain refracts the Remix image bound as {@code Sampler1}. Zero alpha there
     * marks a frame Remix has not written, which holds the previous host frame,
     * so the shader must not draw from it.
     */
    @Test
    void rainIsNotDrawnFromAFrameRemixHasNotWritten() throws IOException {
        try (InputStream in = RainRefractionShaderTest.class.getResourceAsStream(RAIN_REFRACTION)) {
            assertNotNull(in, RAIN_REFRACTION + " is not on the classpath");
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(DISCARDS_UNWRITTEN_FRAME.matcher(source).find(),
                    "rain_refraction no longer discards where the Remix image has zero alpha");
        }
    }
}

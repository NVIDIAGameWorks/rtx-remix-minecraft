/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nvidia.rtxremix.minecraft.light.LightManager.SkyLightState;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.renderer.ShaderInstance;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;

/**
 * Renders the atmospheric sky panorama and its ambient statistics with compute
 * shaders, and reads the result back as a half-float RGBA image plus the mean
 * radiance of the upper hemisphere.
 *
 * <p>Render thread only: it owns GL programs, textures and a readback buffer,
 * and every entry point assumes the GL context is current.
 */
final class AtmosphericSkyGenerator {

    static final int WIDTH = 256;
    static final int HEIGHT = 128;
    static final int UPPER_HEIGHT = HEIGHT / 2;
    static final int OUTPUT_BYTES =
            WIDTH * HEIGHT * 4 * Short.BYTES;
    static final int READBACK_BYTES =
            WIDTH * (HEIGHT + 1) * 4 * Short.BYTES;

    static final int LUT_WIDTH = 1024;
    static final int LUT_HEIGHT = 256;
    private static final int PASS_BUILD_LUT = 0;
    private static final int PASS_SINGLE_SCATTERING = 1;
    private static final int PASS_REDUCE_AND_COMPOSE = 2;
    private static final int READBACK_RING_SIZE = 3;
    // The render boundary owns texture unit 0 and an unbound pixel-pack buffer;
    // dedicated units avoid synchronous binding queries on every sky update.
    private static final int FINAL_SKY_TEXTURE_UNIT = 14;
    private static final int TRANSMITTANCE_TEXTURE_UNIT = 15;
    private static final String SHADER_RESOURCE =
            "/assets/rtx-remix-renderer/shaders/sky_atmosphere.comp";

    private final float[] sunDirection = new float[3];
    private final float[] moonDirection = new float[3];
    private final int width;
    private final int upperHeight;
    private final int outputBytes;
    private final int readbackBytes;
    private final int[] readbackBuffers =
            new int[READBACK_RING_SIZE];
    private final ByteBuffer[] mappedReadbacks =
            new ByteBuffer[READBACK_RING_SIZE];
    private final long[] readbackFences =
            new long[READBACK_RING_SIZE];
    private final ByteBuffer skyParameters =
            BufferUtils.createByteBuffer(5 * 4 * Float.BYTES);
    /** Last parameter block actually dispatched, for skipping byte-identical regenerations. */
    private final ByteBuffer publishedSkyParameters =
            BufferUtils.createByteBuffer(5 * 4 * Float.BYTES);
    private boolean hasPublishedSkyParameters;
    private int readbackWriteIndex;
    private int readbackReadIndex;
    private int pendingReadbacks;

    private int program;
    private int transmittanceLut;
    private int upperSingleTexture;
    private int upperViewTexture;
    private int finalSkyTexture;
    private int skyParametersBuffer;
    private int passLocation;
    private int transmittanceLutLocation;

    AtmosphericSkyGenerator() {
        this(WIDTH, HEIGHT);
    }

    AtmosphericSkyGenerator(int width, int height) {
        if (!GL.getCapabilities().OpenGL44) {
            throw new IllegalStateException(
                    "Procedural sky generation requires OpenGL 4.4");
        }
        if (width < 8 || height < 4 || (height & 1) != 0) {
            throw new IllegalArgumentException(
                    "Sky resolution requires width >= 8 and an even height >= 4");
        }
        this.width = width;
        upperHeight = height / 2;
        outputBytes = width * height * 4 * Short.BYTES;
        readbackBytes = width * (height + 1) * 4 * Short.BYTES;

        program = createProgram(loadShaderSource(width, height));
        passLocation = uniformLocation("Pass");
        transmittanceLutLocation =
                uniformLocation("TransmittanceLut");

        transmittanceLut = createTexture(
                LUT_WIDTH,
                LUT_HEIGHT,
                GL30.GL_RGBA32F,
                GL11.GL_LINEAR);
        upperSingleTexture = createTexture(
                width,
                upperHeight,
                GL30.GL_RGBA32F,
                GL11.GL_NEAREST);
        upperViewTexture = createTexture(
                width,
                upperHeight,
                GL30.GL_RGBA32F,
                GL11.GL_NEAREST);
        finalSkyTexture = createTexture(
                width,
                height + 1,
                GL30.GL_RGBA16F,
                GL11.GL_NEAREST);
        createSkyParametersBuffer();
        createReadbackBuffers();

        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        try {
            GL20.glUseProgram(program);
            GL20.glUniform1i(
                    transmittanceLutLocation,
                    TRANSMITTANCE_TEXTURE_UNIT);
            bindImages();
            GL20.glUniform1i(passLocation, PASS_BUILD_LUT);
            GL43.glDispatchCompute(
                    divideRoundUp(LUT_WIDTH, 8),
                    divideRoundUp(LUT_HEIGHT, 8),
                    1);
            GL42.glMemoryBarrier(
                    GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                            | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        } finally {
            unbindImages();
            GL20.glUseProgram(previousProgram);
        }
        checkGlError("initialize atmospheric sky generator");
    }

    /**
     * Regenerates the atmospheric sky, or skips the work when nothing that feeds it changed.
     *
     * @param publishedOutputValid whether the caller still holds a live texture and dome light
     *     built from a previous call. When false the sky is regenerated regardless of the
     *     parameters, which is what recovers a world rejoin or a dimension whose time never
     *     advances; without it a byte-constant parameter block would skip forever and the dome
     *     would stay black.
     * @return the completed readback, or null when there is nothing new to publish
     */
    ByteBuffer generate(
            SkyLightState lightState,
            float[] upperMean,
            float mieScatteringAnisotropy,
            boolean publishedOutputValid) {
        if (upperMean.length < 3) {
            throw new IllegalArgumentException(
                    "Upper-mean output requires three floats");
        }
        if (!Float.isFinite(mieScatteringAnisotropy)
                || mieScatteringAnisotropy < 0.0f
                || mieScatteringAnisotropy >= 1.0f) {
            throw new IllegalArgumentException(
                    "Mie scattering anisotropy must be in [0, 1)");
        }

        boolean parametersChanged;
        try (ProfileScope ignored =
                ProfileScope.begin("Sky.ProceduralGeneration.Parameters")) {
            setSkyTextureDirection(
                    sunDirection,
                    lightState.sunPosition());
            setSkyTextureDirection(
                    moonDirection,
                    lightState.moonPosition());
            normalize(sunDirection);
            normalize(moonDirection);
            parametersChanged = fillSkyParameters(
                    lightState, mieScatteringAnisotropy);
        }

        // The parameters derive from the sky angle, which is tick-quantised, so most frames
        // reproduce the previous block byte for byte and their dispatch and readback would
        // recompute an identical image. Draining a readback still in flight is deliberate: the
        // result of the last dispatch that did matter arrives some frames later, and dropping
        // it here would leave the texture and the atmospheric mean one change behind forever.
        // It reads a persistently mapped buffer through a sync object and needs no GL state.
        if (!parametersChanged && publishedOutputValid) {
            try (ProfileScope ignored =
                    ProfileScope.begin("Sky.ProceduralGeneration.Readback")) {
                return consumeCompletedReadback(upperMean);
            }
        }

        ShaderInstance previousProgram;
        try (ProfileScope ignored =
                ProfileScope.begin("Sky.ProceduralGeneration.StateSave")) {
            previousProgram = RenderSystem.isOnRenderThread()
                    ? RenderSystem.getShader()
                    : null;
        }
        try {
            try (ProfileScope ignored =
                    ProfileScope.begin(
                            "Sky.ProceduralGeneration.Setup")) {
                GL20.glUseProgram(program);
                GL13.glActiveTexture(
                        GL13.GL_TEXTURE0
                                + TRANSMITTANCE_TEXTURE_UNIT);
                GL11.glBindTexture(
                        GL11.GL_TEXTURE_2D,
                        transmittanceLut);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                bindImages();
                uploadSkyParameters();
            }

            try (ProfileScope ignored =
                    ProfileScope.begin("Sky.ProceduralGeneration.Dispatch")) {
                dispatch(PASS_SINGLE_SCATTERING, width, upperHeight);
                GL42.glMemoryBarrier(
                        GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
                dispatch(PASS_REDUCE_AND_COMPOSE, 1, 1);
                GL42.glMemoryBarrier(
                        GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                                | GL42.GL_TEXTURE_UPDATE_BARRIER_BIT);
            }
            markSkyParametersDispatched();

            ByteBuffer completedReadback;
            try (ProfileScope ignored =
                    ProfileScope.begin("Sky.ProceduralGeneration.Readback")) {
                completedReadback =
                        consumeCompletedReadback(upperMean);
                enqueueReadback();
            }
            return completedReadback;
        } finally {
            unbindImages();
            GL15.glBindBuffer(
                    GL21.GL_PIXEL_PACK_BUFFER,
                    0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            if (previousProgram != null) {
                previousProgram.apply();
            } else {
                GL20.glUseProgram(0);
            }
        }
    }

    void readTransmittanceLut(ByteBuffer destination) {
        int requiredBytes =
                LUT_WIDTH * LUT_HEIGHT * 4 * Float.BYTES;
        if (destination.capacity() < requiredBytes) {
            throw new IllegalArgumentException(
                    "LUT readback buffer is smaller than " + requiredBytes);
        }
        int previousTexture =
                GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            destination.clear();
            destination.limit(requiredBytes);
            GL11.glBindTexture(
                    GL11.GL_TEXTURE_2D,
                    transmittanceLut);
            GL11.glGetTexImage(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL11.GL_RGBA,
                    GL11.GL_FLOAT,
                    destination);
            destination.position(0);
        } finally {
            GL11.glBindTexture(
                    GL11.GL_TEXTURE_2D,
                    previousTexture);
        }
    }

    void destroy() {
        deleteTexture(transmittanceLut);
        deleteTexture(upperSingleTexture);
        deleteTexture(upperViewTexture);
        deleteTexture(finalSkyTexture);
        destroyReadbackBuffers();
        if (skyParametersBuffer != 0) {
            GL15.glDeleteBuffers(skyParametersBuffer);
            skyParametersBuffer = 0;
        }
        transmittanceLut = 0;
        upperSingleTexture = 0;
        upperViewTexture = 0;
        finalSkyTexture = 0;
        if (program != 0) {
            GL20.glDeleteProgram(program);
            program = 0;
        }
        hasPublishedSkyParameters = false;
    }

    private void createSkyParametersBuffer() {
        skyParametersBuffer = GL15.glGenBuffers();
        GL15.glBindBuffer(
                GL31.GL_UNIFORM_BUFFER,
                skyParametersBuffer);
        GL15.glBufferData(
                GL31.GL_UNIFORM_BUFFER,
                skyParameters.capacity(),
                GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
    }

    private void createReadbackBuffers() {
        int previousBuffer = GL11.glGetInteger(
                GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            for (int index = 0;
                    index < readbackBuffers.length;
                    index++) {
                int buffer = GL15.glGenBuffers();
                readbackBuffers[index] = buffer;
                GL15.glBindBuffer(
                        GL21.GL_PIXEL_PACK_BUFFER,
                        buffer);
                GL44.glBufferStorage(
                        GL21.GL_PIXEL_PACK_BUFFER,
                        readbackBytes,
                        GL30.GL_MAP_READ_BIT
                                | GL44.GL_MAP_PERSISTENT_BIT
                                | GL44.GL_MAP_COHERENT_BIT);
                ByteBuffer mapped = GL30.glMapBufferRange(
                        GL21.GL_PIXEL_PACK_BUFFER,
                        0L,
                        readbackBytes,
                        GL30.GL_MAP_READ_BIT
                                | GL44.GL_MAP_PERSISTENT_BIT
                                | GL44.GL_MAP_COHERENT_BIT,
                        null);
                if (mapped == null) {
                    throw new IllegalStateException(
                            "Failed to persistently map atmospheric sky readback");
                }
                mappedReadbacks[index] = mapped;
            }
        } finally {
            GL15.glBindBuffer(
                    GL21.GL_PIXEL_PACK_BUFFER,
                    previousBuffer);
        }
    }

    private ByteBuffer consumeCompletedReadback(
            float[] upperMean) {
        if (pendingReadbacks == 0) {
            return null;
        }
        long fence = readbackFences[readbackReadIndex];
        int status = GL32.glClientWaitSync(fence, 0, 0L);
        if (status != GL32.GL_ALREADY_SIGNALED
                && status != GL32.GL_CONDITION_SATISFIED) {
            return null;
        }

        GL32.glDeleteSync(fence);
        readbackFences[readbackReadIndex] = 0L;
        ByteBuffer mapped = mappedReadbacks[readbackReadIndex];
        mapped.position(0);
        mapped.limit(readbackBytes);

        upperMean[0] = halfToFloat(
                mapped.getShort(outputBytes));
        upperMean[1] = halfToFloat(
                mapped.getShort(outputBytes + Short.BYTES));
        upperMean[2] = halfToFloat(
                mapped.getShort(
                        outputBytes + 2 * Short.BYTES));
        mapped.position(0);
        mapped.limit(outputBytes);
        readbackReadIndex =
                (readbackReadIndex + 1) % READBACK_RING_SIZE;
        pendingReadbacks--;
        return mapped;
    }

    private void enqueueReadback() {
        if (pendingReadbacks == READBACK_RING_SIZE) {
            return;
        }
        GL15.glBindBuffer(
                GL21.GL_PIXEL_PACK_BUFFER,
                readbackBuffers[readbackWriteIndex]);
        GL13.glActiveTexture(
                GL13.GL_TEXTURE0 + FINAL_SKY_TEXTURE_UNIT);
        GL11.glBindTexture(
                GL11.GL_TEXTURE_2D,
                finalSkyTexture);
        GL11.glGetTexImage(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA,
                GL30.GL_HALF_FLOAT,
                0L);
        readbackFences[readbackWriteIndex] =
                GL32.glFenceSync(
                        GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,
                        0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        readbackWriteIndex =
                (readbackWriteIndex + 1) % READBACK_RING_SIZE;
        pendingReadbacks++;
    }

    private void destroyReadbackBuffers() {
        for (int index = 0;
                index < readbackBuffers.length;
                index++) {
            if (readbackFences[index] != 0L) {
                GL32.glDeleteSync(readbackFences[index]);
                readbackFences[index] = 0L;
            }
            if (readbackBuffers[index] != 0) {
                GL15.glBindBuffer(
                        GL21.GL_PIXEL_PACK_BUFFER,
                        readbackBuffers[index]);
                GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                GL15.glDeleteBuffers(readbackBuffers[index]);
                readbackBuffers[index] = 0;
                mappedReadbacks[index] = null;
            }
        }
        pendingReadbacks = 0;
        readbackReadIndex = 0;
        readbackWriteIndex = 0;
    }

    /**
     * Fills the parameter block on the CPU and reports whether it differs from the block the
     * last dispatch ran on. Touches no GL state, so it is safe to call before deciding whether
     * to do any GL work at all.
     */
    private boolean fillSkyParameters(
            SkyLightState lightState,
            float mieScatteringAnisotropy) {
        putVectorAndScalar(
                0,
                sunDirection,
                lightState.sunAngularRadius());
        putVectorAndScalar(
                16,
                moonDirection,
                lightState.moonAngularRadius());
        putVectorAndScalar(
                32,
                lightState.sunSourceRadiance(),
                atmosphereScatteringScale(
                        lightState.sunPosition()[1]));
        putVectorAndScalar(
                48,
                lightState.moonSourceRadiance(),
                0.0f);
        skyParameters.putFloat(64, mieScatteringAnisotropy);
        skyParameters.putFloat(68, 0.0f);
        skyParameters.putFloat(72, 0.0f);
        skyParameters.putFloat(76, 0.0f);

        return !hasPublishedSkyParameters || skyParameters.mismatch(publishedSkyParameters) >= 0;
    }

    /** Records the block the dispatch just ran on, so the next identical block can skip it. */
    private void markSkyParametersDispatched() {
        publishedSkyParameters.clear();
        publishedSkyParameters.put(skyParameters.duplicate());
        publishedSkyParameters.clear();
        hasPublishedSkyParameters = true;
    }

    private void uploadSkyParameters() {
        skyParameters.position(0);
        skyParameters.limit(skyParameters.capacity());
        GL15.glBindBuffer(
                GL31.GL_UNIFORM_BUFFER,
                skyParametersBuffer);
        GL15.glBufferSubData(
                GL31.GL_UNIFORM_BUFFER,
                0L,
                skyParameters);
        GL30.glBindBufferBase(
                GL31.GL_UNIFORM_BUFFER,
                0,
                skyParametersBuffer);
    }

    private void putVectorAndScalar(
            int offset,
            float[] vector,
            float scalar) {
        skyParameters.putFloat(offset, vector[0]);
        skyParameters.putFloat(offset + Float.BYTES, vector[1]);
        skyParameters.putFloat(offset + 2 * Float.BYTES, vector[2]);
        skyParameters.putFloat(offset + 3 * Float.BYTES, scalar);
    }

    private void dispatch(int pass, int width, int height) {
        GL20.glUniform1i(passLocation, pass);
        GL43.glDispatchCompute(
                divideRoundUp(width, 8),
                divideRoundUp(height, 8),
                1);
    }

    private void bindImages() {
        GL42.glBindImageTexture(
                0,
                transmittanceLut,
                0,
                false,
                0,
                GL15.GL_READ_WRITE,
                GL30.GL_RGBA32F);
        GL42.glBindImageTexture(
                1,
                upperSingleTexture,
                0,
                false,
                0,
                GL15.GL_READ_WRITE,
                GL30.GL_RGBA32F);
        GL42.glBindImageTexture(
                2,
                upperViewTexture,
                0,
                false,
                0,
                GL15.GL_READ_WRITE,
                GL30.GL_RGBA32F);
        GL42.glBindImageTexture(
                3,
                finalSkyTexture,
                0,
                false,
                0,
                GL15.GL_READ_WRITE,
                GL30.GL_RGBA16F);
    }

    private static void unbindImages() {
        for (int unit = 0; unit <= 3; unit++) {
            GL42.glBindImageTexture(
                    unit,
                    0,
                    0,
                    false,
                    0,
                    GL15.GL_READ_WRITE,
                    GL30.GL_RGBA32F);
        }
    }

    private static int createTexture(
            int width,
            int height,
            int internalFormat,
            int filter) {
        int previousTexture =
                GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int texture = GL11.glGenTextures();
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(
                    GL11.GL_TEXTURE_2D,
                    GL11.GL_TEXTURE_MIN_FILTER,
                    filter);
            GL11.glTexParameteri(
                    GL11.GL_TEXTURE_2D,
                    GL11.GL_TEXTURE_MAG_FILTER,
                    filter);
            GL11.glTexParameteri(
                    GL11.GL_TEXTURE_2D,
                    GL11.GL_TEXTURE_WRAP_S,
                    GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(
                    GL11.GL_TEXTURE_2D,
                    GL11.GL_TEXTURE_WRAP_T,
                    GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    internalFormat,
                    width,
                    height,
                    0,
                    GL11.GL_RGBA,
                    GL11.GL_FLOAT,
                    (ByteBuffer) null);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        }
        return texture;
    }

    private static int createProgram(String source) {
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS)
                == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException(
                    "Failed to compile atmospheric sky shader: " + log);
        }

        int linkedProgram = GL20.glCreateProgram();
        try {
            GL20.glAttachShader(linkedProgram, shader);
            GL20.glLinkProgram(linkedProgram);
            if (GL20.glGetProgrami(linkedProgram, GL20.GL_LINK_STATUS)
                    == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(linkedProgram);
                GL20.glDeleteProgram(linkedProgram);
                throw new IllegalStateException(
                        "Failed to link atmospheric sky shader: " + log);
            }
            return linkedProgram;
        } finally {
            GL20.glDeleteShader(shader);
        }
    }

    private int uniformLocation(String name) {
        int location = GL20.glGetUniformLocation(program, name);
        if (location < 0) {
            throw new IllegalStateException(
                    "Atmospheric sky shader is missing uniform " + name);
        }
        return location;
    }

    int outputBytes() {
        return outputBytes;
    }

    private static String loadShaderSource(int width, int height) {
        try (InputStream stream =
                AtmosphericSkyGenerator.class.getResourceAsStream(
                        SHADER_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Missing atmospheric sky shader " + SHADER_RESOURCE);
            }
            String source = new String(
                    stream.readAllBytes(),
                    StandardCharsets.UTF_8);
            return withSkyDimensions(source, width, height);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read atmospheric sky shader "
                            + SHADER_RESOURCE,
                    e);
        }
    }

    /**
     * Inserts the size defines directly below the shader's {@code #version} directive.
     * GLSL requires that directive to precede every statement, and the file opens with
     * its licence header, so the defines cannot simply follow the first line.
     */
    private static String withSkyDimensions(String source, int width, int height) {
        int versionStart = source.indexOf("#version");
        if (versionStart < 0) {
            throw new IllegalStateException(
                    "Atmospheric sky shader has no #version directive");
        }
        int versionEnd = source.indexOf('\n', versionStart) + 1;
        return source.substring(0, versionEnd)
                + "#define SKY_WIDTH " + width + "\n"
                + "#define SKY_HEIGHT " + height + "\n"
                + source.substring(versionEnd);
    }

    private static void setSkyTextureDirection(
            float[] output,
            float[] direction) {
        output[0] = -direction[0];
        output[1] = direction[1];
        output[2] = direction[2];
    }

    private static void normalize(float[] vector) {
        float length = (float) Math.sqrt(
                vector[0] * vector[0]
                        + vector[1] * vector[1]
                        + vector[2] * vector[2]);
        if (length > 0.0f) {
            vector[0] /= length;
            vector[1] /= length;
            vector[2] /= length;
        }
    }

    private static float atmosphereScatteringScale(float sunHeight) {
        float nightFactor =
                1.0f - smoothstep(-0.15f, 0.05f, sunHeight);
        return 16.0f * (1.0f - nightFactor) + 70.0f * nightFactor;
    }

    private static float smoothstep(
            float edge0,
            float edge1,
            float value) {
        float amount = Math.max(
                0.0f,
                Math.min(1.0f, (value - edge0) / (edge1 - edge0)));
        return amount * amount * (3.0f - 2.0f * amount);
    }

    private static int divideRoundUp(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static float halfToFloat(short value) {
        int bits = value & 0xffff;
        int sign = (bits & 0x8000) << 16;
        int exponent = (bits >>> 10) & 0x1f;
        int mantissa = bits & 0x03ff;
        if (exponent == 0) {
            return Float.intBitsToFloat(sign);
        }
        if (exponent == 0x1f) {
            return Float.intBitsToFloat(
                    sign | 0x7f800000 | (mantissa << 13));
        }
        return Float.intBitsToFloat(
                sign | ((exponent + 112) << 23) | (mantissa << 13));
    }

    private static void deleteTexture(int texture) {
        if (texture != 0) {
            GL11.glDeleteTextures(texture);
        }
    }

    private static void checkGlError(String operation) {
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            throw new IllegalStateException(
                    operation + " failed with OpenGL error 0x"
                            + Integer.toHexString(error));
        }
    }

}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import com.mojang.blaze3d.systems.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.nvidia.rtxremix.minecraft.RtxRemixCommonMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Defines;
import net.minecraft.client.gl.ShaderProgramKey;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws rain and snow with GL directly onto the composited Remix colour image,
 * depth-testing against the interop depth texture so weather is occluded by
 * path-traced geometry.
 *
 * <p>Render thread only, and after Remix has presented its image into GL: the
 * renderer owns its GL buffers and reads the interop textures the frame just
 * produced.
 */
public final class RemixWeatherRenderer {
    private static final float NEAR_PLANE = 0.05f;
    private static final float FAR_PLANE = 1000.0f;
    private static final Identifier RAIN_TEXTURE =
            Identifier.ofVanilla("textures/environment/rain.png");
    private static final ShaderProgramKey RAIN_SHADER = new ShaderProgramKey(
            Identifier.of(RtxRemixCommonMod.MOD_ID, "core/rain_refraction"),
            VertexFormats.POSITION_TEXTURE_COLOR_LIGHT, Defines.EMPTY);

    private final List<DeferredDraw> draws = new ArrayList<>(2);
    private final RemixWeatherVertexConsumerProvider captureProvider =
            new RemixWeatherVertexConsumerProvider();
    private Matrix4f projection;
    private Matrix4f modelView;
    private LightmapTextureManager lightmap;
    private DepthSeeder depthSeeder;
    private int capturedQuadCount;
    private int lastDrawCount;
    private int lastQuadCount;

    public void beginCaptureFrame(LightmapTextureManager lightmapTextureManager) {
        closePendingDraws();
        captureProvider.beginFrame();
        lightmap = lightmapTextureManager;
        capturedQuadCount = 0;
        lastDrawCount = 0;
        lastQuadCount = 0;
    }

    public void updateCamera(Camera camera, float fovYDegrees, float aspect) {
        Quaternionf cameraRotation = camera.getRotation().conjugate(new Quaternionf());
        modelView = new Matrix4f().rotation(cameraRotation);
        projection = new Matrix4f().perspective(
                (float) Math.toRadians(fovYDegrees),
                aspect,
                NEAR_PLANE,
                FAR_PLANE);
    }

    public RemixWeatherVertexConsumerProvider getCaptureProvider() {
        return captureProvider;
    }

    /** Takes ownership of the buffer; its allocator remains owned by the capture provider. */
    public void capture(BuiltBuffer buffer, Identifier texture) {
        if (texture == null) {
            buffer.close();
            throw new IllegalStateException("Weather draw did not bind a texture");
        }
        capturedQuadCount += buffer.getDrawParameters().vertexCount() / 4;
        draws.add(new DeferredDraw(buffer, texture, texture.equals(RAIN_TEXTURE)));
    }

    public void render(int finalColorTexture, int depthTexture) {
        if (draws.isEmpty()) {
            return;
        }
        if (depthTexture == 0) {
            closePendingDraws();
            throw new IllegalStateException("Remix depth texture is unavailable");
        }
        if (finalColorTexture == 0) {
            closePendingDraws();
            throw new IllegalStateException("Remix final-color texture is unavailable");
        }
        if (projection == null || modelView == null || lightmap == null) {
            closePendingDraws();
            throw new IllegalStateException("Weather camera state was not initialized");
        }

        MinecraftClient client = MinecraftClient.getInstance();
        client.getFramebuffer().beginWrite(true);
        try {
            if (depthSeeder == null) {
                depthSeeder = new DepthSeeder();
            }
            depthSeeder.seed(
                    depthTexture,
                    client.getWindow().getFramebufferWidth(),
                    client.getWindow().getFramebufferHeight());
            lastDrawCount = draws.size();
            lastQuadCount = capturedQuadCount;
            renderDeferredDraws(finalColorTexture);
        } catch (RuntimeException exception) {
            closePendingDraws();
            throw exception;
        }
    }

    private void renderDeferredDraws(int finalColorTexture) {
        Matrix4f savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        ProjectionType savedProjectionType = RenderSystem.getProjectionType();
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] scissorBox = readScissorBox();
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        int blendSourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int blendDestinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int blendSourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int blendDestinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int blendEquationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        int blendEquationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        boolean[] colorMask = readColorMask();
        float[] shaderColor = RenderSystem.getShaderColor().clone();

        RenderSystem.setProjectionMatrix(projection, ProjectionType.PERSPECTIVE);
        modelViewStack.pushMatrix();
        modelViewStack.set(modelView);

        try {
            lightmap.enable();
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(false);
            GL11.glColorMask(true, true, true, true);
            GL14.glBlendFuncSeparate(
                    GL11.GL_SRC_ALPHA,
                    GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE,
                    GL11.GL_ZERO);
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            renderRain(finalColorTexture);
            renderSnow();
        } finally {
            lightmap.disable();
            closePendingDraws();
            restoreCapability(GL11.GL_DEPTH_TEST, depthTest);
            restoreCapability(GL11.GL_BLEND, blend);
            restoreCapability(GL11.GL_CULL_FACE, cull);
            restoreCapability(GL11.GL_SCISSOR_TEST, scissor);
            GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            GL11.glDepthFunc(depthFunction);
            GL11.glDepthMask(depthMask);
            GL11.glColorMask(
                    colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            GL14.glBlendFuncSeparate(
                    blendSourceRgb,
                    blendDestinationRgb,
                    blendSourceAlpha,
                    blendDestinationAlpha);
            GL20.glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha);
            modelViewStack.popMatrix();
            RenderSystem.setProjectionMatrix(savedProjection, savedProjectionType);
            RenderSystem.setShaderColor(
                    shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
        }
    }

    private void renderRain(int finalColorTexture) {
        RenderSystem.setShaderTexture(1, finalColorTexture);
        renderDraws(true, RAIN_SHADER);
    }

    private void renderSnow() {
        renderDraws(false, ShaderProgramKeys.PARTICLE);
    }

    private void renderDraws(boolean rain, ShaderProgramKey shader) {
        boolean shaderBound = false;
        for (int i = 0; i < draws.size(); i++) {
            DeferredDraw draw = draws.get(i);
            if (draw == null || draw.rain() != rain) {
                continue;
            }
            if (!shaderBound) {
                // Resolve through the loader each frame so resource reloads never leave
                // a cached, closed GL program attached to the deferred weather pass.
                if (RenderSystem.setShader(shader) == null) {
                    throw new IllegalStateException("Weather shader was not loaded: " + shader.configId());
                }
                shaderBound = true;
            }
            RenderSystem.setShaderTexture(0, draw.texture());
            try {
                BufferRenderer.drawWithGlobalProgram(draw.buffer());
            } finally {
                draws.set(i, null);
            }
        }
    }

    private static void restoreCapability(int capability, boolean enabled) {
        if (enabled) GL11.glEnable(capability);
        else GL11.glDisable(capability);
    }

    private static int[] readScissorBox() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer values = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, values);
            return new int[]{values.get(0), values.get(1), values.get(2), values.get(3)};
        }
    }

    private static boolean[] readColorMask() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer values = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, values);
            return new boolean[]{
                    values.get(0) != 0,
                    values.get(1) != 0,
                    values.get(2) != 0,
                    values.get(3) != 0
            };
        }
    }

    private void closePendingDraws() {
        for (DeferredDraw draw : draws) {
            if (draw != null) {
                draw.buffer().close();
            }
        }
        draws.clear();
    }

    public int getLastDrawCount() {
        return lastDrawCount;
    }

    public int getLastQuadCount() {
        return lastQuadCount;
    }

    public void cleanup() {
        closePendingDraws();
        captureProvider.destroy();
        if (depthSeeder != null) {
            depthSeeder.destroy();
            depthSeeder = null;
        }
    }

    private record DeferredDraw(BuiltBuffer buffer, Identifier texture, boolean rain) {
    }

    private static final class DepthSeeder {
        private static final String VERTEX_SHADER = """
                #version 150
                void main() {
                    vec2 position = vec2(
                        float((gl_VertexID << 1) & 2),
                        float(gl_VertexID & 2));
                    gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
                }
                """;

        private static final String FRAGMENT_SHADER = """
                #version 150
                uniform sampler2D RemixDepth;
                uniform vec2 FramebufferSize;
                void main() {
                    vec2 uv = vec2(
                        gl_FragCoord.x / FramebufferSize.x,
                        1.0 - gl_FragCoord.y / FramebufferSize.y);
                    gl_FragDepth = texture(RemixDepth, uv).r;
                }
                """;

        private final int program;
        private final int vertexArray;
        private final int remixDepthLocation;
        private final int framebufferSizeLocation;

        private DepthSeeder() {
            int vertexShader = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fragmentShader = 0;
            int linkedProgram = 0;
            try {
                fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
                linkedProgram = GL20.glCreateProgram();
                GL20.glAttachShader(linkedProgram, vertexShader);
                GL20.glAttachShader(linkedProgram, fragmentShader);
                GL20.glLinkProgram(linkedProgram);
                if (GL20.glGetProgrami(linkedProgram, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                    String log = GL20.glGetProgramInfoLog(linkedProgram);
                    GL20.glDeleteProgram(linkedProgram);
                    linkedProgram = 0;
                    throw new IllegalStateException("Failed to link depth seeding shader: " + log);
                }
            } finally {
                GL20.glDeleteShader(vertexShader);
                if (fragmentShader != 0) {
                    GL20.glDeleteShader(fragmentShader);
                }
            }
            program = linkedProgram;
            remixDepthLocation = GL20.glGetUniformLocation(program, "RemixDepth");
            framebufferSizeLocation = GL20.glGetUniformLocation(program, "FramebufferSize");
            vertexArray = GL30.glGenVertexArrays();
        }

        private void seed(int depthTexture, int width, int height) {
            int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            boolean previousDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            int previousDepthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            boolean previousScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            int[] previousScissorBox = readScissorBox();
            boolean previousBlend = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean previousCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            boolean previousDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            boolean[] previousColorMask = readColorMask();

            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            try {
                // OpenGL suppresses depth writes entirely when the depth test is disabled.
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_ALWAYS);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDepthMask(true);
                GL11.glColorMask(false, false, false, false);
                GL20.glUseProgram(program);
                GL20.glUniform1i(remixDepthLocation, 0);
                GL20.glUniform2f(framebufferSizeLocation, width, height);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTexture);
                GL30.glBindVertexArray(vertexArray);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            } finally {
                GL30.glBindVertexArray(previousVertexArray);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
                GL13.glActiveTexture(previousActiveTexture);
                GL20.glUseProgram(previousProgram);
                GL11.glColorMask(
                        previousColorMask[0],
                        previousColorMask[1],
                        previousColorMask[2],
                        previousColorMask[3]);
                GL11.glDepthMask(previousDepthMask);
                GL11.glDepthFunc(previousDepthFunction);
                if (previousDepthTest) GL11.glEnable(GL11.GL_DEPTH_TEST);
                else GL11.glDisable(GL11.GL_DEPTH_TEST);
                if (previousScissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
                else GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glScissor(
                        previousScissorBox[0],
                        previousScissorBox[1],
                        previousScissorBox[2],
                        previousScissorBox[3]);
                if (previousBlend) GL11.glEnable(GL11.GL_BLEND);
                else GL11.glDisable(GL11.GL_BLEND);
                if (previousCull) GL11.glEnable(GL11.GL_CULL_FACE);
                else GL11.glDisable(GL11.GL_CULL_FACE);
            }
        }

        private void destroy() {
            GL30.glDeleteVertexArrays(vertexArray);
            GL20.glDeleteProgram(program);
        }

        private static int compileShader(int type, String source) {
            int shader = GL20.glCreateShader(type);
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetShaderInfoLog(shader);
                GL20.glDeleteShader(shader);
                throw new IllegalStateException("Failed to compile depth seeding shader: " + log);
            }
            return shader;
        }
    }
}

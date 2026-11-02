/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.client;

import com.nvidia.rtxremix.CameraInfo;
import com.nvidia.rtxremix.CameraInfoParameterizedEXT;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Matrix4f;

import java.nio.FloatBuffer;

/** Reads the actual world-pass camera, including third-person offsets and view bobbing. */
final class CameraCapture {
    private final FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f inverse = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final CameraInfo camera = new CameraInfo();
    private final CameraInfoParameterizedEXT parameters = new CameraInfoParameterizedEXT();

    CameraCapture() { camera.parameterizedExt = parameters; }

    CameraInfo capture(Minecraft mc, float partialTicks) {
        read(GL11.GL_MODELVIEW_MATRIX, view);
        read(GL11.GL_PROJECTION_MATRIX, projection);
        if (Matrix4f.invert(view, inverse) == null) {
            throw new IllegalStateException("World camera matrix is singular");
        }
        Entity entity = mc.getRenderViewEntity();
        if (entity == null) { throw new IllegalStateException("No world camera entity"); }
        parameters.position.x = (float) (entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks) + inverse.m30;
        parameters.position.y = (float) (entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks) + inverse.m31;
        parameters.position.z = (float) (entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks) + inverse.m32;
        parameters.forward.x = -inverse.m20;
        parameters.forward.y = -inverse.m21;
        parameters.forward.z = -inverse.m22;
        parameters.right.x = inverse.m00;
        parameters.right.y = inverse.m01;
        parameters.right.z = inverse.m02;
        parameters.up.x = inverse.m10;
        parameters.up.y = inverse.m11;
        parameters.up.z = inverse.m12;
        parameters.fovYInDegrees = (float) Math.toDegrees(2.0 * Math.atan(1.0 / projection.m11));
        parameters.aspect = (float) mc.displayWidth / Math.max(1, mc.displayHeight);
        parameters.nearPlane = 0.05f;
        parameters.farPlane = 1024.0f;
        return camera;
    }

    private void read(int matrix, Matrix4f destination) {
        buffer.clear();
        GL11.glGetFloat(matrix, buffer);
        destination.load(buffer);
    }
}

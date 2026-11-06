/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.nvidia.rtxremix.RemixException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import net.minecraft.client.Camera;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemixRendererCameraFrameTest {

    @Test
    void cameraFrameAdvancesOnlyWhenMinecraftWillRenderAWorld() {
        assertTrue(RemixRenderer.shouldAdvanceWorldRenderFrame(true, true, true));

        assertFalse(RemixRenderer.shouldAdvanceWorldRenderFrame(false, true, true));
        assertFalse(RemixRenderer.shouldAdvanceWorldRenderFrame(true, false, true));
        assertFalse(RemixRenderer.shouldAdvanceWorldRenderFrame(true, true, false));
    }

    @Test
    void cameraErrorsStayLocalToTheCameraUpdate() throws Exception {
        RemixRenderer renderer = new RemixRenderer();
        Method update = RemixRenderer.class.getDeclaredMethod("updateCamera", Camera.class, float.class);
        update.setAccessible(true);
        for (RuntimeException error : new RuntimeException[] {
                new RemixException("camera failed"), new IllegalStateException("camera failed")}) {
            Camera camera = new Camera() {
                @Override
                public Vec3 position() {
                    throw error;
                }
            };

            assertDoesNotThrow(() -> update.invoke(renderer, camera, 0.0f));
        }
    }

    @Test
    void mediumErrorsStayLocalToTheMediumUpdate() throws Exception {
        RemixRenderer renderer = new RemixRenderer();
        Method update = RemixRenderer.class.getDeclaredMethod(
                "updateCameraMedium", Camera.class, Vec3.class, float.class, float.class);
        update.setAccessible(true);
        for (RuntimeException error : new RuntimeException[] {
                new RemixException("medium failed"), new IllegalStateException("medium failed")}) {
            Camera camera = new Camera() {
                @Override
                public FogType getFluidInCamera() {
                    throw error;
                }
            };

            assertDoesNotThrow(() -> update.invoke(renderer, camera, Vec3.ZERO, 0.0f, 0.0f));
        }
    }
}

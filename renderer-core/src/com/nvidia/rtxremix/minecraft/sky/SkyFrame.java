/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

/** Loader-neutral facts needed to render one sky frame. */
public final class SkyFrame {
    public enum Type { NORMAL, END, NONE }

    public final Object worldIdentity;
    public final Type type;
    public final double cameraX;
    public final double cameraY;
    public final double cameraZ;
    public final float skyAngle;
    public final float starBrightness;
    public final int moonPhase;
    public final float rain;
    public final float thunder;
    public final SkyLightState lightState;

    public SkyFrame(
            Object worldIdentity,
            Type type,
            double cameraX,
            double cameraY,
            double cameraZ,
            float skyAngle,
            float starBrightness,
            int moonPhase,
            float rain,
            float thunder,
            SkyLightState lightState) {
        this.worldIdentity = worldIdentity;
        this.type = type;
        this.cameraX = cameraX;
        this.cameraY = cameraY;
        this.cameraZ = cameraZ;
        this.skyAngle = skyAngle;
        this.starBrightness = starBrightness;
        this.moonPhase = moonPhase;
        this.rain = rain;
        this.thunder = thunder;
        this.lightState = lightState;
    }
}

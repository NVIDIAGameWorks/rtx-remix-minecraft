/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import com.nvidia.rtxremix.Float3D;
import com.nvidia.rtxremix.LightInfo;
import com.nvidia.rtxremix.LightInfoCylinderEXT;
import com.nvidia.rtxremix.LightInfoDiskEXT;
import com.nvidia.rtxremix.LightInfoLightShaping;
import com.nvidia.rtxremix.LightInfoRectEXT;
import com.nvidia.rtxremix.LightInfoSphereEXT;

/** Builds native Remix light descriptions from canonical resolved lights. */
public final class BlockLightInfoFactory {
    private BlockLightInfoFactory() {}

    public static LightInfo build(
            LightEntry entry, float radianceR, float radianceG, float radianceB) {
        LightInfo.Builder builder = LightInfo.builder()
                .radiance(radianceR, radianceG, radianceB);
        switch (entry.shape()) {
            case SPHERE:
                LightInfoSphereEXT.Builder sphere = LightInfoSphereEXT.builder()
                        .position(entry.x(), entry.y(), entry.z())
                        .radius(entry.radius())
                        .volumetricRadianceScale(entry.volumetricRadianceScale());
                if (entry.hasShaping()) {
                    sphere.shaping(shapingOf(entry));
                }
                return builder.sphereExt(sphere.build()).build();
            case CYLINDER:
                return builder
                        .cylinderExt(LightInfoCylinderEXT.builder()
                                .position(entry.x(), entry.y(), entry.z())
                                .radius(entry.radius())
                                .axis(entry.axisX(), entry.axisY(), entry.axisZ())
                                .axisLength(entry.axisLength())
                                .volumetricRadianceScale(entry.volumetricRadianceScale())
                                .build())
                        .build();
            case RECT:
                return builder
                        .rectExt(LightInfoRectEXT.builder()
                                .position(entry.x(), entry.y(), entry.z())
                                .xAxis(entry.xAxisX(), entry.xAxisY(), entry.xAxisZ())
                                .xSize(entry.xSize())
                                .yAxis(entry.yAxisX(), entry.yAxisY(), entry.yAxisZ())
                                .ySize(entry.ySize())
                                .direction(
                                        entry.directionX(),
                                        entry.directionY(),
                                        entry.directionZ())
                                .volumetricRadianceScale(entry.volumetricRadianceScale())
                                .build())
                        .build();
            case DISK:
                return builder
                        .diskExt(LightInfoDiskEXT.builder()
                                .position(entry.x(), entry.y(), entry.z())
                                .xAxis(entry.xAxisX(), entry.xAxisY(), entry.xAxisZ())
                                .xRadius(entry.xSize())
                                .yAxis(entry.yAxisX(), entry.yAxisY(), entry.yAxisZ())
                                .yRadius(entry.ySize())
                                .direction(
                                        entry.directionX(),
                                        entry.directionY(),
                                        entry.directionZ())
                                .volumetricRadianceScale(entry.volumetricRadianceScale())
                                .build())
                        .build();
            default:
                throw new IllegalArgumentException("Unknown light shape: " + entry.shape());
        }
    }

    private static LightInfoLightShaping shapingOf(LightEntry entry) {
        LightInfoLightShaping shaping = new LightInfoLightShaping();
        shaping.direction = new Float3D(
                entry.shapingDirectionX(),
                entry.shapingDirectionY(),
                entry.shapingDirectionZ());
        shaping.coneAngleDegrees = entry.coneAngleDegrees();
        shaping.coneSoftness = entry.coneSoftness();
        shaping.focusExponent = entry.focusExponent();
        return shaping;
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.fabric;

import com.nvidia.rtxremix.minecraft.entity.RemixSubmitCollector;
import com.nvidia.rtxremix.minecraft.entity.RemixVertexConsumerProvider;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;

/** The capture collector with the submit method Fabric API adds to vanilla's collector. */
final class FabricSubmitCollector extends RemixSubmitCollector {
    FabricSubmitCollector(RemixVertexConsumerProvider provider) {
        super(provider);
    }

    /** Fabric's custom submit phases draw mod geometry Remix has no capture for. */
    @Override
    public <T extends SubmitNode> void submitCustom(SubmitRenderPhase<T> phase, T node) {
    }
}

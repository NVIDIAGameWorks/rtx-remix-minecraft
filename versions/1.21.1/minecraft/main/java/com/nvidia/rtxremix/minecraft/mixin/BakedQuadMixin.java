/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.BakedQuadStaticGeometryCache;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Arrays;
import net.minecraft.client.renderer.block.model.BakedQuad;

/** Stores the bake-time geometry digest used by Remix entity mesh pooling. */
@Mixin(BakedQuad.class)
public class BakedQuadMixin implements BakedQuadStaticGeometryCache {

    private int[] rtxremix$staticGeometrySnapshot;
    private long rtxremix$staticGeometryHash;
    private int rtxremix$staticGeometryBoundsMin;
    private int rtxremix$staticGeometryBoundsMax;

    @Override
    public boolean rtxremix$matchesStaticGeometry(int[] vertexData,
                                                   int texcoordBoundsMin,
                                                   int texcoordBoundsMax) {
        return rtxremix$staticGeometryBoundsMin == texcoordBoundsMin
                && rtxremix$staticGeometryBoundsMax == texcoordBoundsMax
                && Arrays.equals(rtxremix$staticGeometrySnapshot, vertexData);
    }

    @Override
    public long rtxremix$getStaticGeometryHash() {
        return rtxremix$staticGeometryHash;
    }

    @Override
    public void rtxremix$setStaticGeometryHash(long hash,
                                                int[] vertexData,
                                                int texcoordBoundsMin,
                                                int texcoordBoundsMax) {
        if (rtxremix$staticGeometrySnapshot == null
                || rtxremix$staticGeometrySnapshot.length != vertexData.length) {
            rtxremix$staticGeometrySnapshot = Arrays.copyOf(vertexData, vertexData.length);
        } else {
            System.arraycopy(vertexData, 0, rtxremix$staticGeometrySnapshot, 0,
                    vertexData.length);
        }
        rtxremix$staticGeometryBoundsMin = texcoordBoundsMin;
        rtxremix$staticGeometryBoundsMax = texcoordBoundsMax;
        rtxremix$staticGeometryHash = hash;
    }
}

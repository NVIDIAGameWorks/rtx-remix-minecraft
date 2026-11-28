/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nvidia.rtxremix.minecraft.asset.RemixLight;
import com.nvidia.rtxremix.minecraft.light.LightPower;
import org.junit.jupiter.api.Test;

class TerrainLightTest {

    private static TerrainLight torchAtOrigin() {
        TerrainLight torch = new TerrainLight(new RemixLight(null, null));
        torch.place(0f, 0f, 0f, LightPower.torchReference());
        return torch;
    }

    private static float threshold(int reachBlocks) {
        return TerrainLight.thresholdFor(reachBlocks, LightPower.torchReference());
    }

    @Test
    void aTorchIsDrawnInsideItsReachAndSkippedBeyondIt() {
        float threshold = threshold(48);
        assertTrue(torchAtOrigin().shouldDraw(47, 0, 0, threshold, 1));
        assertFalse(torchAtOrigin().shouldDraw(60, 0, 0, threshold, 1));
    }

    @Test
    void hysteresisKeepsADrawnLightUntilItFallsWellBeyondReach() {
        float threshold = threshold(48);
        TerrainLight torch = torchAtOrigin();
        assertTrue(torch.shouldDraw(47, 0, 0, threshold, 1));
        // 55 blocks is past 48 but inside the 1.5x band (48 * sqrt(1.5) = 58.8).
        assertTrue(torch.shouldDraw(55, 0, 0, threshold, 2));
        assertTrue(torch.drawnInFrame(2));
        assertFalse(torch.shouldDraw(60, 0, 0, threshold, 3));
        assertFalse(torch.drawnInFrame(3));
    }

    @Test
    void aFrameSkippedByTheGatesEndsTheBand() {
        float threshold = threshold(48);
        TerrainLight torch = torchAtOrigin();
        assertTrue(torch.shouldDraw(47, 0, 0, threshold, 1));
        // Frame 2 never reaches this light (its section was gate-culled).
        assertFalse(torch.shouldDraw(55, 0, 0, threshold, 3),
                "re-entry needs the full threshold, not the hysteresis band");
    }

    @Test
    void brighterLightsReachFurther() {
        float threshold = threshold(48);
        TerrainLight lightBlock = new TerrainLight(new RemixLight(null, null));
        // 3.17x a torch: reach 48 * sqrt(3.17) = 85 blocks.
        lightBlock.place(0f, 0f, 0f, LightPower.torchReference() * 3.17f);
        assertTrue(lightBlock.shouldDraw(80, 0, 0, threshold, 1));
        assertFalse(lightBlock.shouldDraw(90, 0, 0, threshold, 1));
    }

    @Test
    void aLightAtTheCameraIsWeightedAsIfOneBlockAway() {
        TerrainLight candle = new TerrainLight(new RemixLight(null, null));
        float power = 3.3f;
        candle.place(0f, 0f, 0f, power);
        float weightAtOneBlock = (float) (power / (4.0 * Math.PI));
        // The distance floor keeps a light at the camera finite: just above its
        // one-block weight it is skipped, just below it is drawn.
        assertFalse(candle.shouldDraw(0, 0, 0, weightAtOneBlock * 1.01f, 1));
        assertTrue(candle.shouldDraw(0, 0, 0, weightAtOneBlock * 0.99f, 2));
    }
}

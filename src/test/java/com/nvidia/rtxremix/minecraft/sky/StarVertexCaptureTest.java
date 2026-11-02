/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import net.minecraft.client.render.VertexConsumer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class StarVertexCaptureTest {
    @Test
    void mirrorsPositionsAcrossCapacityGrowthAndReturnsIndependentSnapshots() {
        List<String> calls = new ArrayList<>();
        StarVertexCapture capture = new StarVertexCapture(consumer(calls));
        float[] expected = new float[6000];
        for (int i = 0; i < 2000; i++) {
            expected[i * 3] = i;
            expected[i * 3 + 1] = -i;
            expected[i * 3 + 2] = i * 0.5f;
            assertSame(capture, capture.vertex(i, -i, i * 0.5f));
        }
        assertArrayEquals(expected, capture.copyVertices());
        assertEquals(2000, calls.size());
        float[] snapshot = capture.copyVertices();
        snapshot[0] = 42;
        assertArrayEquals(expected, capture.copyVertices());
    }

    @Test
    void forwardsEveryAttributeWithoutBreakingTheCaptureChain() {
        List<String> calls = new ArrayList<>();
        StarVertexCapture capture = new StarVertexCapture(consumer(calls));
        assertSame(capture, capture.vertex(1, 2, 3).color(1, 2, 3, 4)
                .texture(0, 1).overlay(2, 3).light(4, 5).normal(0, 1, 0));
        assertEquals(List.of("vertex", "color", "texture", "overlay", "light", "normal"), calls);
        assertArrayEquals(new float[]{1, 2, 3}, capture.copyVertices());
    }

    private static VertexConsumer consumer(List<String> calls) {
        return (VertexConsumer) Proxy.newProxyInstance(VertexConsumer.class.getClassLoader(),
                new Class<?>[]{VertexConsumer.class}, (proxy, method, arguments) -> {
                    calls.add(method.getName());
                    return proxy;
                });
    }
}

/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RendererFrameCoordinatorTest {
    @Test
    void runsCanonicalFrameOrder() {
        RecordingHost host = new RecordingHost();

        new RendererFrameCoordinator().render(host);

        assertEquals(Arrays.asList(
                "begin", "prepare", "enabled", "publishParticles", "startParticles",
                "camera", "lights", "terrain", "entities", "outline", "sky",
                "clouds", "atlas", "submit", "finishParticles", "end"), host.calls);
    }

    @Test
    void suspendsUnavailableCaptureAndStillEndsFrame() {
        RecordingHost host = new RecordingHost();
        host.prepared = false;

        new RendererFrameCoordinator().render(host);

        assertEquals(
                Arrays.asList("begin", "prepare", "suspend", "end"),
                host.calls);
    }

    @Test
    void suspendsDisabledCaptureAndStillEndsFrame() {
        RecordingHost host = new RecordingHost();
        host.enabled = false;

        new RendererFrameCoordinator().render(host);

        assertEquals(
                Arrays.asList("begin", "prepare", "enabled", "suspend", "end"),
                host.calls);
    }

    @Test
    void reportsFailureAndStillEndsFrame() {
        RecordingHost host = new RecordingHost();
        host.failAt = "terrain";

        new RendererFrameCoordinator().render(host);

        assertEquals(Arrays.asList(
                "begin", "prepare", "enabled", "publishParticles", "startParticles",
                "camera", "lights", "terrain", "failure", "end"), host.calls);
    }

    private static final class RecordingHost implements RendererFrameCoordinator.Host {
        private final List<String> calls = new ArrayList<>();
        private boolean prepared = true;
        private boolean enabled = true;
        private String failAt;

        private void call(String name) {
            calls.add(name);
            if (name.equals(failAt)) {
                throw new IllegalStateException(name);
            }
        }

        @Override public void beginFrame() { call("begin"); }
        @Override public boolean prepareFrame() { call("prepare"); return prepared; }
        @Override public boolean isCaptureEnabled() { call("enabled"); return enabled; }
        @Override public void suspendCapture() { call("suspend"); }
        @Override public void publishPreparedParticles() { call("publishParticles"); }
        @Override public void startParticlePreparation() { call("startParticles"); }
        @Override public void updateCamera() { call("camera"); }
        @Override public void updateLights() { call("lights"); }
        @Override public void updateTerrain() { call("terrain"); }
        @Override public void captureEntities() { call("entities"); }
        @Override public void submitBlockOutline() { call("outline"); }
        @Override public void renderSky() { call("sky"); }
        @Override public void renderClouds() { call("clouds"); }
        @Override public void flushAtlasAnimations() { call("atlas"); }
        @Override public void submitFrame() { call("submit"); }
        @Override public void finishParticlePreparation() { call("finishParticles"); }
        @Override public void reportFrameFailure(Exception exception) { call("failure"); }
        @Override public void endFrame() { call("end"); }
    }
}

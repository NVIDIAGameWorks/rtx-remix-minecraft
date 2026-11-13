/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core;

/**
 * Canonical order of one captured Remix frame.
 *
 * <p>A host owns Minecraft interception and translates live game state inside
 * these coarse phases. The coordinator owns only ordering and failure/finally
 * semantics, so loaders cannot silently grow different renderer pipelines.</p>
 */
public final class RendererFrameCoordinator {
    public interface Host {
        void beginFrame();
        boolean prepareFrame();
        boolean isCaptureEnabled();
        void suspendCapture();
        void publishPreparedParticles();
        void startParticlePreparation();
        void updateCamera();
        void updateLights();
        void updateTerrain();
        void captureEntities();
        void submitBlockOutline();
        void renderSky();
        void renderClouds();
        void flushAtlasAnimations();
        void submitFrame();
        void finishParticlePreparation();
        void reportFrameFailure(Exception exception);
        void endFrame();
    }

    public void render(Host host) {
        if (host == null) {
            throw new NullPointerException("host");
        }
        host.beginFrame();
        try {
            if (!host.prepareFrame() || !host.isCaptureEnabled()) {
                host.suspendCapture();
                return;
            }
            host.publishPreparedParticles();
            host.startParticlePreparation();
            host.updateCamera();
            host.updateLights();
            host.updateTerrain();
            host.captureEntities();
            host.submitBlockOutline();
            host.renderSky();
            host.renderClouds();
            host.flushAtlasAnimations();
            host.submitFrame();
            host.finishParticlePreparation();
        } catch (Exception exception) {
            host.reportFrameFailure(exception);
        } finally {
            host.endFrame();
        }
    }
}

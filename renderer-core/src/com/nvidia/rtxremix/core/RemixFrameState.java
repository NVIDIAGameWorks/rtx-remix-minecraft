/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core;

import com.nvidia.rtxremix.InteropInfo;
import com.nvidia.rtxremix.PresentInfo;
import com.nvidia.rtxremix.RemixAPI;

import java.util.Objects;

/**
 * Canonical submit/present state machine shared by every host.
 *
 * <p>Exactly one native frame may be pending. Hosts own their GL interop, but
 * they cannot submit over an unpresented frame or present without first
 * allocating the interop image.
 */
public final class RemixFrameState {
    private final RemixFrameBackend backend;
    private boolean submitted;

    public RemixFrameState(RemixAPI api) {
        Objects.requireNonNull(api, "api");
        this.backend = new RemixFrameBackend() {
            @Override
            public void submit() {
                api.submit(null);
            }

            @Override
            public InteropInfo interopInfo() {
                return api.dxvkGetInteropInfo();
            }

            @Override
            public void present(PresentInfo presentInfo) {
                api.present(presentInfo);
            }
        };
    }

    RemixFrameState(RemixFrameBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    public void submit() {
        if (submitted) {
            throw new IllegalStateException("Previous native frame was not presented");
        }
        submitNewFrame();
    }

    public InteropInfo submitAndGetInteropInfo() {
        submit();
        return backend.interopInfo();
    }

    public void ensureSubmitted() {
        if (!submitted) {
            submitNewFrame();
        }
    }

    public boolean isSubmitted() {
        return submitted;
    }

    public void present(PresentInfo presentInfo) {
        if (!submitted) {
            throw new IllegalStateException("No native frame is ready to present");
        }
        backend.present(Objects.requireNonNull(presentInfo, "presentInfo"));
        submitted = false;
    }

    private void submitNewFrame() {
        backend.submit();
        submitted = true;
    }
}

interface RemixFrameBackend {
    void submit();

    InteropInfo interopInfo();

    void present(PresentInfo presentInfo);
}

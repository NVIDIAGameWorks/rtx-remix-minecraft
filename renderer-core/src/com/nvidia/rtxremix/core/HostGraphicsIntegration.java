/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.StartupInfo;

/**
 * Host-owned graphics boundary around the canonical renderer.
 *
 * <p>The renderer owns scene policy and native frame ordering. The host owns
 * window discovery, graphics API capability checks, framebuffer transfer,
 * output-image acquisition, and presentation. Keeping those operations behind
 * this contract prevents an OpenGL host from becoming an assumption of the
 * canonical scene pipeline.</p>
 */
public interface HostGraphicsIntegration extends AutoCloseable {
    /**
     * Prepares the host window and returns the native startup description.
     * Called on the render thread before the Remix API is started.
     */
    StartupInfo prepareStartup();

    /**
     * Attaches host graphics resources after the native API has started.
     */
    void attach(RemixAPI api);

    /**
     * Acquires the host-visible color output for the submitted frame.
     *
     * @return a host graphics texture handle, or zero when none is available
     */
    int acquireOutputTexture();

    /**
     * Composites the host-visible output and any host render passes that must
     * share its graphics-state boundary.
     */
    void compositeOutput(int colorTexture, Runnable additionalHostPasses);

    /**
     * Transfers the host framebuffer and presents the pending native frame.
     *
     * @return true when the native frame was presented
     */
    boolean present(RemixRendererSession session);

    /** Returns the host-visible depth texture, or zero when unavailable. */
    int depthTexture();

    /** Releases host graphics resources without shutting down the native API. */
    @Override
    void close();
}

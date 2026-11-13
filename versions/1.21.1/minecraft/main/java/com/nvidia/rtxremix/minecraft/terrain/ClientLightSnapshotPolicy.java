/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

/**
 * Decides how one client light storage publishes its thread-safe snapshot in
 * {@code LightStorage.notifyChanges}.
 *
 * <p>Vanilla clones the whole section map into {@code uncachedStorage} on every pass that
 * changed a section, so worker threads can read light while the engine mutates its private
 * copy. While RTX capture is on nothing reads client light off the render thread: the
 * section builder reports every block invisible, and the Remix entity, block-entity,
 * particle and cloud paths take no light values. The clone is then pure cost, a whole-map
 * allocation whose traversal inside a ZGC relocate phase heals every stale reference it
 * copies, so the storage publishes the live map itself instead.
 *
 * <p>Invariant: while the live map is published, no thread other than the render thread
 * may read client light. The alias hands readers the engine's own hash map with its lookup
 * cache enabled, so an off-thread reader would race the engine's mutations and corrupt
 * that cache silently. The two transitions that could break this are handled explicitly:
 * {@code MinecraftRuntime.setRtxEnabled(false)} publishes a real copy before its flag flips,
 * because the rebuilds vanilla schedules afterwards read light on workers, and a build that
 * latched capture off before a toggle on keeps the storage on real copies until it has
 * finished ({@link VanillaSectionBuilds}).
 *
 * <p>One instance per storage; every call runs on the thread that owns that storage.
 */
public final class ClientLightSnapshotPolicy {
    public static final String PROPERTY = "rtxremix.light.vanillaSnapshot";

    /** Read once: a session either may alias or never does, so no storage re-derives it. */
    public static final boolean ALIAS_ALLOWED = aliasAllowedFrom(System.getProperty(PROPERTY));

    /** What notifyChanges does for one storage in one pass. */
    public enum Decision {
        /** Vanilla: clone the live map if a section changed, then notify the chunk provider. */
        VANILLA,
        /** The published snapshot is the live map: publish a real copy first, then run vanilla. */
        REPUBLISH_THEN_VANILLA,
        /** Publish the live map itself, count the notifications as skipped rebuilds, skip vanilla. */
        ALIAS_LIVE_MAP
    }

    private final boolean aliasPermitted;
    private boolean liveMapPublished;

    /**
     * @param clientEngine whether the storage belongs to the client world's engine. The
     *                     integrated server's engine shares the class and keeps vanilla
     *                     snapshots: its light packets read them off the server thread.
     * @param aliasAllowed the session-wide kill switch state, {@link #ALIAS_ALLOWED} in production
     */
    public ClientLightSnapshotPolicy(boolean clientEngine, boolean aliasAllowed) {
        this.aliasPermitted = clientEngine && aliasAllowed;
    }

    /**
     * Decides the notifyChanges pass that is starting.
     *
     * @param rtxCaptureOn         {@code MinecraftRuntime.isRtxEnabled()}, the gate the section builder latches
     * @param vanillaBuildInFlight whether a build that latched capture off is still running on a worker
     */
    public Decision onNotifyChanges(boolean rtxCaptureOn, boolean vanillaBuildInFlight) {
        if (aliasPermitted && rtxCaptureOn && !vanillaBuildInFlight) {
            liveMapPublished = true;
            return Decision.ALIAS_LIVE_MAP;
        }
        return releaseLiveMap() ? Decision.REPUBLISH_THEN_VANILLA : Decision.VANILLA;
    }

    /**
     * Marks the published snapshot as a real copy again.
     *
     * @return whether the live map was published, in which case the caller must publish a
     *         real copy now
     */
    public boolean releaseLiveMap() {
        boolean released = liveMapPublished;
        liveMapPublished = false;
        return released;
    }

    static boolean aliasAllowedFrom(String property) {
        return !Boolean.parseBoolean(property);
    }

    /** One startup log line that makes every run self-describing. */
    public static String effectiveStateLine() {
        return ALIAS_ALLOWED
                ? "Client light snapshot: live map while RTX capture is on (" + PROPERTY + "=false)"
                : "Client light snapshot: vanilla per-pass clone (" + PROPERTY + "=true)";
    }
}

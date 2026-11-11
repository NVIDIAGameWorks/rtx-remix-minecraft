/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generic singleton store for Remix assets. Generates random 64-bit hashes
 * internally, provides collision-free registration via {@link ConcurrentHashMap},
 * and a GC sweep that drains leaked assets and removes deleted entries.
 *
 * <p>No store-level mutex: {@code ConcurrentHashMap} handles all map
 * concurrency. Each asset's own {@code ReentrantReadWriteLock} serializes
 * C API calls per-asset.
 *
 * @param <T> concrete asset type extending {@link RemixAssetBase}
 */
public abstract class RemixAssetStore<T extends RemixAssetBase> {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemixAssetStore.class);
    private static final int MAX_HASH_RETRIES = 64;

    protected final ConcurrentHashMap<Long, T> assets = new ConcurrentHashMap<>();
    protected final ConcurrentLinkedQueue<LeakRecord> livingDeadAssets = new ConcurrentLinkedQueue<>();
    protected final RemixAPI api;

    protected RemixAssetStore(RemixAPI api) {
        this.api = api;
    }

    /**
     * The only way to create assets. Generates a random collision-free 64-bit
     * hash, calls the asset's package-private {@code create()}, and registers
     * it in the map.
     *
     * @return the same asset, now in ALIVE state with its hash assigned
     */
    public T create(T asset, boolean disposeData) {
        long hash = nextHash();

        int retries = 0;
        while (assets.putIfAbsent(hash, asset) != null) {
            retries++;
            if (retries > MAX_HASH_RETRIES) {
                throw new IllegalStateException("Hash collision limit exceeded after "
                        + MAX_HASH_RETRIES + " random retries");
            }
            hash = nextHash();
        }

        try {
            asset.create(api, hash, disposeData);
            asset.registerCleaner(this::reportLeak, this::reportDeletion);
        } catch (RuntimeException e) {
            assets.remove(hash);
            throw e;
        }
        return asset;
    }

    /**
     * Convenience overload: {@code disposeData} defaults to {@code true}.
     */
    public T create(T asset) {
        return create(asset, true);
    }

    private static long nextHash() {
        long h = ThreadLocalRandom.current().nextLong();
        return h != 0 ? h : 1;
    }

    /**
     * Drains leaked asset handles without a full map sweep.
     * Cheap enough to call every frame.
     */
    public void drainLeaks() {
        LeakRecord leak;
        while ((leak = livingDeadAssets.poll()) != null) {
            if (leak.handle() != 0) {
                try {
                    destroyLeakedHandle(api, leak.handle());
                } catch (RemixException e) {
                    LOGGER.warn("Failed to destroy leaked handle (hash=0x{}): {}",
                            Long.toHexString(leak.hash()), e.getMessage());
                }
            }
            assets.remove(leak.hash());
        }
    }

    /** Drains lifecycle cleanup queues. Explicitly destroyed assets remove themselves immediately. */
    public void gc() {
        drainLeaks();
    }

    /**
     * Destroys all ALIVE assets, drains leaked assets, clears the map.
     */
    public void destroyAll() {
        Object[] snapshot = assets.values().toArray();
        for (Object value : snapshot) {
            @SuppressWarnings("unchecked")
            T asset = (T) value;
            if (!asset.isDeleted()) {
                try {
                    asset.destroy(DeletionReason.DIRECT_REQUEST);
                } catch (RuntimeException e) {
                    LOGGER.warn("Error destroying asset during shutdown: {}", e.getMessage());
                }
            }
        }
        gc();
        assets.clear();
    }

    public int size() {
        return assets.size();
    }

    void reportLeak(Long hash, Long handle) {
        livingDeadAssets.add(new LeakRecord(hash, handle));
    }

    private void reportDeletion(Long hash, RemixAssetBase asset) {
        assets.remove(hash, asset);
    }

    /**
     * Subclasses provide the correct destroy call for leaked handles
     * (the asset object is already GC'd, so we only have the raw handle).
     */
    protected abstract void destroyLeakedHandle(RemixAPI api, long handle);
}

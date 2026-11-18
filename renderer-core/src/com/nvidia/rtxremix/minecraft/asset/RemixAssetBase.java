/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import com.nvidia.rtxremix.RemixAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;

/**
 * Abstract base for all stateful Remix asset types (mesh, material, texture,
 * light, instance). Owns the {@link ReentrantReadWriteLock} that guards all
 * mutable state, the phantom-reference leak detector, and the common lifecycle
 * transitions (NOT_CREATED -> ALIVE -> DELETED).
 *
 * <p>Subclasses implement {@link #doCreate}, {@link #doDestroy}, and
 * {@link #doDisposeData} to interact with the concrete Remix C API calls.
 *
 * <p>Thread safety: write lock for {@code create/update/destroy}, read lock
 * for {@code isAlive/isDeleted/getHandle/draw}. The RWLock provides
 * happens-before guarantees, so all fields are plain (no volatile).
 */
public abstract class RemixAssetBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemixAssetBase.class);
    private static final ReferenceQueue<RemixAssetBase> LEAK_QUEUE = new ReferenceQueue<>();
    private static final Set<LeakCleanup> LEAK_CLEANUPS =
            Collections.newSetFromMap(new ConcurrentHashMap<LeakCleanup, Boolean>());

    static {
        Thread reaper = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        ((LeakCleanup) LEAK_QUEUE.remove()).clean(true);
                    } catch (InterruptedException ignored) {
                        // Leak monitoring lives for the process lifetime.
                    }
                }
            }
        }, "RTX Remix asset leak reaper");
        reaper.setDaemon(true);
        reaper.start();
    }

    protected final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    protected long handle;
    protected long hash;
    protected DeletionReason deletionFlag = DeletionReason.NOT_DELETED;
    protected boolean dataDisposed;
    protected RemixAPI api;

    private final AtomicInteger cleanerDeletionOrdinal = new AtomicInteger(DeletionReason.NOT_DELETED.ordinal());
    private final AtomicLong cleanerHandleRef = new AtomicLong();
    private LeakCleanup cleanable;
    private BiConsumer<Long, RemixAssetBase> deletionReporter;

    /**
     * Registers this asset's leak detector with the shared reference queue. Called by the
     * store after the hash is known and the asset is registered in the map.
     * The cleanup action captures only atomics and the store-lookup lambda
     * (never a reference to {@code this}).
     */
    void registerCleaner(BiConsumer<Long, Long> leakReporter,
                         BiConsumer<Long, RemixAssetBase> deletionReporter) {
        rwLock.writeLock().lock();
        try {
            long capturedHash = this.hash;
            AtomicInteger delOrd = this.cleanerDeletionOrdinal;
            AtomicLong hRef = this.cleanerHandleRef;
            this.deletionReporter = deletionReporter;

            this.cleanable = new LeakCleanup(
                    this, capturedHash, delOrd, hRef, leakReporter);
            LEAK_CLEANUPS.add(this.cleanable);
            if (this.deletionFlag != DeletionReason.NOT_DELETED) {
                this.cleanable.clean(false);
                this.cleanable = null;
                reportDeletion();
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Package-private. Only the owning store calls this after claiming a hash.
     *
     * @param api         the Remix API instance
     * @param hash        the unique 64-bit hash assigned by the store
     * @param disposeData if true, native buffers are freed after creation
     */
    void create(RemixAPI api, long hash, boolean disposeData) {
        rwLock.writeLock().lock();
        try {
            if (this.handle != 0) {
                throw new IllegalStateException(typeName() + " already created (hash=0x"
                        + Long.toHexString(this.hash) + ")");
            }
            this.api = api;
            this.hash = hash;
            this.handle = doCreate(api, hash);
            cleanerHandleRef.set(this.handle);
            if (disposeData) {
                doDisposeData();
                dataDisposed = true;
            }
        } catch (RuntimeException e) {
            this.handle = 0;
            cleanerHandleRef.set(0);
            if (disposeData && !dataDisposed) {
                try {
                    doDisposeData();
                    dataDisposed = true;
                } catch (RuntimeException cleanupError) {
                    e.addSuppressed(cleanupError);
                    LOGGER.warn("Failed to dispose {} creation data after create failure",
                            typeName(), cleanupError);
                }
            }
            throw e;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void destroy() {
        destroy(DeletionReason.DIRECT_REQUEST);
    }

    public void destroy(DeletionReason reason) {
        rwLock.writeLock().lock();
        try {
            if (deletionFlag != DeletionReason.NOT_DELETED) {
                LOGGER.warn("Thread '{}' tried to destroy already-deleted {}(hash=0x{}, reason={})",
                        Thread.currentThread().getName(), typeName(),
                        Long.toHexString(hash), deletionFlag);
                return;
            }

            // Keep the asset live if the native destroy fails. Marking it
            // deleted first lets store GC remove the last Java owner while the
            // native handle is still allocated.
            if (handle != 0 && api != null) {
                doDestroy(api, handle);
            }
            handle = 0;
            cleanerHandleRef.set(0);

            deletionFlag = reason;
            cleanerDeletionOrdinal.set(reason.ordinal());
            LeakCleanup registeredCleaner = cleanable;
            cleanable = null;
            if (registeredCleaner != null) {
                registeredCleaner.clean(false);
            }
            reportDeletion();

            if (!dataDisposed) {
                doDisposeData();
                dataDisposed = true;
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void reportDeletion() {
        BiConsumer<Long, RemixAssetBase> reporter = deletionReporter;
        deletionReporter = null;
        if (reporter != null) {
            reporter.accept(hash, this);
        }
    }

    private static final class LeakCleanup extends PhantomReference<RemixAssetBase> {
        private final long hash;
        private final AtomicInteger deletionOrdinal;
        private final AtomicLong handle;
        private final BiConsumer<Long, Long> reporter;

        LeakCleanup(RemixAssetBase referent, long hash,
                    AtomicInteger deletionOrdinal, AtomicLong handle,
                    BiConsumer<Long, Long> reporter) {
            super(referent, LEAK_QUEUE);
            this.hash = hash;
            this.deletionOrdinal = deletionOrdinal;
            this.handle = handle;
            this.reporter = reporter;
        }

        void clean(boolean collected) {
            if (!LEAK_CLEANUPS.remove(this)) return;
            clear();
            if (!collected
                    || deletionOrdinal.get() != DeletionReason.NOT_DELETED.ordinal()) {
                return;
            }
            long leaked = handle.get();
            LOGGER.error("MEMORY LEAK: {}(hash=0x{}) was GC'd without destroy()!",
                    leaked != 0 ? "Asset" : "UninitialisedAsset",
                    Long.toHexString(hash));
            if (reporter != null && leaked != 0) {
                reporter.accept(hash, leaked);
            }
        }
    }

    // --- read-locked queries ---

    public boolean isDeleted() {
        rwLock.readLock().lock();
        try {
            return deletionFlag != DeletionReason.NOT_DELETED;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Lock-free deletion check for render-thread-only hot paths.
     * Only safe when caller is guaranteed to be on the same thread as
     * {@link #destroy(DeletionReason)}.
     */
    public boolean isDeletedFast() {
        return deletionFlag != DeletionReason.NOT_DELETED;
    }

    public boolean isAlive() {
        rwLock.readLock().lock();
        try {
            return handle != 0 && deletionFlag == DeletionReason.NOT_DELETED;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public long getHandle() {
        rwLock.readLock().lock();
        try {
            return handle;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Lock-free handle lookup for render-thread-only hot paths.
     * Only safe when creation, replacement, and destruction are confined to the caller's thread.
     */
    public long getHandleFast() {
        return handle;
    }

    public long getHash() {
        return hash;
    }

    public DeletionReason getDeletionFlag() {
        rwLock.readLock().lock();
        try {
            return deletionFlag;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // --- subclass hooks ---

    protected abstract long doCreate(RemixAPI api, long hash);

    protected abstract void doDestroy(RemixAPI api, long handle);

    /** Free any native buffers held for creation. Called under write lock. */
    protected abstract void doDisposeData();

    /** Replaces the native handle after an in-place recreate/update. Called under write lock. */
    protected final void replaceNativeHandle(long newHandle) {
        handle = newHandle;
        cleanerHandleRef.set(newHandle);
    }

    /** Human-readable type name for logging (e.g. "RemixMesh"). */
    protected abstract String typeName();
}

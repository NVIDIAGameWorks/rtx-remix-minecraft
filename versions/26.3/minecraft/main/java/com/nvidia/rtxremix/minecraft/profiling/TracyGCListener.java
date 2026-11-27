/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.profiling;

import com.sun.management.GarbageCollectionNotificationInfo;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

/**
 * Emits Tracy zones for every JVM garbage collection event.
 *
 * <p>Each GC pause shows up as a named zone in Tracy with the collector name
 * and cause, e.g. {@code "GC: G1 Young Generation (G1 Evacuation Pause)"}.
 * Because the JMX notification arrives <em>after</em> the collection finishes,
 * the zone is emitted as a zero-duration marker at notification time, enough
 * to see GC events on the Tracy timeline and correlate with frame stalls.
 */
public final class TracyGCListener {

    private TracyGCListener() {}

    private static final Object LOCK = new Object();
    private static final Set<NotificationEmitter> INSTALLED_EMITTERS =
            Collections.newSetFromMap(new IdentityHashMap<>());

    public static void install() {
        install(ManagementFactory.getGarbageCollectorMXBeans());
    }

    static void install(Iterable<? extends GarbageCollectorMXBean> beans) {
        synchronized (LOCK) {
            for (GarbageCollectorMXBean bean : beans) {
                if (bean instanceof NotificationEmitter emitter && !INSTALLED_EMITTERS.contains(emitter)) {
                    emitter.addNotificationListener(GC_LISTENER, null, null);
                    INSTALLED_EMITTERS.add(emitter);
                }
            }
        }
    }

    public static void uninstall() {
        synchronized (LOCK) {
            for (NotificationEmitter emitter : new ArrayList<>(INSTALLED_EMITTERS)) {
                try {
                    emitter.removeNotificationListener(GC_LISTENER);
                } catch (ListenerNotFoundException ignored) {
                    // Already removed by the JVM or another shutdown path.
                }
            }
            INSTALLED_EMITTERS.clear();
        }
    }

    private static final NotificationListener GC_LISTENER = (Notification notification, Object handback) -> {
        if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION
                .equals(notification.getType())) {
            return;
        }
        var info = GarbageCollectionNotificationInfo.from(
                (CompositeData) notification.getUserData());
        String name = "GC: " + info.getGcName() + " (" + info.getGcCause()
                + ") " + info.getGcInfo().getDuration() + "ms";
        ProfileScope.markTracyZone(name);
    };
}

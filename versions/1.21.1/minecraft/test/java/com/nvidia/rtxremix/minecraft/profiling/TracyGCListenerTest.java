/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.profiling;

import org.junit.jupiter.api.Test;

import java.lang.management.GarbageCollectorMXBean;
import java.util.ArrayList;
import java.util.List;
import javax.management.ListenerNotFoundException;
import javax.management.MBeanNotificationInfo;
import javax.management.NotificationEmitter;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.ObjectName;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TracyGCListenerTest {

    @Test
    void installIsIdempotentAndUninstallRemovesListener() {
        TestGcBean bean = new TestGcBean();

        TracyGCListener.install(List.of(bean));
        TracyGCListener.install(List.of(bean));

        assertEquals(1, bean.listenerCount());

        TracyGCListener.uninstall();

        assertEquals(0, bean.listenerCount());
        assertDoesNotThrow(TracyGCListener::uninstall);
    }

    private static final class TestGcBean implements GarbageCollectorMXBean, NotificationEmitter {
        private final List<NotificationListener> listeners = new ArrayList<>();

        int listenerCount() {
            return listeners.size();
        }

        @Override
        public void addNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback) {
            listeners.add(listener);
        }

        @Override
        public void removeNotificationListener(NotificationListener listener) throws ListenerNotFoundException {
            if (!listeners.remove(listener)) {
                throw new ListenerNotFoundException();
            }
        }

        @Override
        public void removeNotificationListener(NotificationListener listener, NotificationFilter filter, Object handback)
                throws ListenerNotFoundException {
            removeNotificationListener(listener);
        }

        @Override
        public MBeanNotificationInfo[] getNotificationInfo() {
            return new MBeanNotificationInfo[0];
        }

        @Override
        public long getCollectionCount() {
            return 0;
        }

        @Override
        public long getCollectionTime() {
            return 0;
        }

        @Override
        public String getName() {
            return "test";
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public String[] getMemoryPoolNames() {
            return new String[0];
        }

        @Override
        public ObjectName getObjectName() {
            return null;
        }
    }
}

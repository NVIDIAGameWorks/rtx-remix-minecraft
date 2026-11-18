/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SectionBuildConcurrencyTest {

    private static final String PROPERTY = "rtxremix.chunk.sectionBuilders";

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROPERTY);
    }

    @Test
    void defaultCapsLargeMachinesAtEight() {
        assertEquals(8, SectionBuildConcurrency.cap(32));
        assertEquals(8, SectionBuildConcurrency.cap(16));
    }

    @Test
    void defaultLeavesSmallMachinesAlone() {
        assertEquals(8, SectionBuildConcurrency.cap(8));
        assertEquals(4, SectionBuildConcurrency.cap(4));
    }

    @Test
    void propertyOverridesTheCapButNeverExceedsVanilla() {
        System.setProperty(PROPERTY, "4");
        assertEquals(4, SectionBuildConcurrency.cap(32));
        System.setProperty(PROPERTY, "64");
        assertEquals(32, SectionBuildConcurrency.cap(32));
        System.setProperty(PROPERTY, "0");
        assertEquals(1, SectionBuildConcurrency.cap(32));
    }
}

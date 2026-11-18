/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemixInstanceStateTest {

    @Test
    void meshChangesDirtyTheRetainedDescription() throws Exception {
        RemixMesh firstMesh = new RemixMesh(new RemixMeshSurface[0]);
        RemixMesh secondMesh = new RemixMesh(new RemixMeshSurface[0]);
        RemixInstance instance = new RemixInstance(firstMesh);

        setDirty(instance, false);
        instance.setMesh(firstMesh);
        assertFalse(isDirty(instance));

        instance.setMesh(secondMesh);
        assertTrue(isDirty(instance));
        assertSame(secondMesh, instance.getMesh());
    }

    @Test
    void unchangedImmutablePropertiesDoNotTriggerNativeUpdates() throws Exception {
        RemixInstance instance = new RemixInstance(new RemixMesh(new RemixMeshSurface[0]));
        InstanceInfoBlendEXT blend = new InstanceInfoBlendEXT();

        instance.setCategoryFlags(5).setBlendExt(blend).setDoubleSided(true);
        setDirty(instance, false);

        instance.setCategoryFlags(5).setBlendExt(blend).setDoubleSided(true);
        assertFalse(isDirty(instance));

        instance.setCategoryFlags(6);
        assertTrue(isDirty(instance));
    }

    private static boolean isDirty(RemixInstance instance) throws Exception {
        Field field = RemixInstance.class.getDeclaredField("sceneInfoDirty");
        field.setAccessible(true);
        return field.getBoolean(instance);
    }

    private static void setDirty(RemixInstance instance, boolean value) throws Exception {
        Field field = RemixInstance.class.getDeclaredField("sceneInfoDirty");
        field.setAccessible(true);
        field.setBoolean(instance, value);
    }
}

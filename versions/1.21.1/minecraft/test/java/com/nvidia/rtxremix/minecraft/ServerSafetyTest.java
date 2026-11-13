/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the client/server split for shared content reached by loader integrations.
 *
 * <p>Blocks live in synced registries, so both loader registration adapters
 * load these shared types on dedicated servers, where
 * {@code net.minecraft.client.*} does not exist. A single client reference
 * anywhere on that path turns into a {@code NoClassDefFoundError} at
 * registration time on a server, which is the one environment none of the
 * other tests and neither {@code runClient} nor a compile-only check exercise.
 *
 * <p>These tests read the compiled class files and scan the constant pool
 * directly. That is deliberately cruder than reflection: it catches references
 * in field and method <em>descriptors</em>, not just ones that happen to be
 * resolved at class-init, and it needs no Minecraft classes on the test
 * classpath.
 */
class ServerSafetyTest {

    /**
     * Shared content reachable from the loader registration adapters.
     *
     * <p>{@code LightEntry$ColorOverride} is on the list because the beacon
     * blocks hold a beam colour; note that referencing a nested type does
     * <em>not</em> load its outer class, so {@code LightEntry} itself is listed
     * separately as cheap insurance rather than out of necessity.
     */
    private static final String[] SERVER_PATH_CLASSES = {
            "com.nvidia.rtxremix.minecraft.block.SpotLightBlock",
            "com.nvidia.rtxremix.minecraft.block.CopperBeaconBlock",
            "com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity",
            "com.nvidia.rtxremix.minecraft.block.CopperBeaconAnimationMode",
            "com.nvidia.rtxremix.minecraft.block.CopperBeaconAxisState",
            "com.nvidia.rtxremix.minecraft.block.CopperBeaconPose",
            "com.nvidia.rtxremix.minecraft.block.CopperBeaconScreenData",
            "com.nvidia.rtxremix.minecraft.block.CopperBeaconScreenHandler",
            "com.nvidia.rtxremix.minecraft.block.CopperBeaconUpdatePayload",
            "com.nvidia.rtxremix.minecraft.block.CopperBeaconShapes",
            "com.nvidia.rtxremix.minecraft.block.CopperBeaconState",
            "com.nvidia.rtxremix.minecraft.light.OverridesItemLight",
            "com.nvidia.rtxremix.minecraft.light.LightEntry$ColorOverride",
            "com.nvidia.rtxremix.minecraft.light.LightEntry",
    };

    @Test
    void serverPathClassesReferenceNoClientClasses() {
        for (String className : SERVER_PATH_CLASSES) {
            assertFalse(referencesText(className, "net/minecraft/client"),
                    className + " references a net.minecraft.client class. It is loaded by the "
                            + "main entrypoint on dedicated servers, where those classes do not exist.");
        }
    }

    /**
     * The canonical client runtime cannot be loaded on a dedicated server, so
     * nothing on the shared registration path may name it.
     */
    @Test
    void serverPathClassesDoNotReferenceTheClientEntrypoint() {
        for (String className : SERVER_PATH_CLASSES) {
            assertFalse(referencesText(className, "minecraft/MinecraftClientRuntime"),
                    className + " references MinecraftClientRuntime, which is client-only. "
                            + "Move the dependency behind a loader registration adapter.");
        }
    }

    /** Sanity check: the scan finds a reference that is genuinely present. */
    @Test
    void referencesTextDetectsAKnownReference() {
        assertTrue(referencesText("com.nvidia.rtxremix.minecraft.block.CopperBeaconBlock",
                        "minecraft/block/SpotLightBlock"),
                "CopperBeaconBlock extends SpotLightBlock, so the scan should see it; "
                        + "if this fails the other assertions in this class are vacuous.");
    }

    /**
     * True if the compiled form of {@code className} contains {@code needle}
     * anywhere: constant pool entries store class and descriptor names as raw
     * modified-UTF8, so a byte search over the whole class file is sufficient.
     */
    private static boolean referencesText(String className, String needle) {
        byte[] classBytes = readClassBytes(className);
        return indexOf(classBytes, needle.getBytes(StandardCharsets.UTF_8)) >= 0;
    }

    private static byte[] readClassBytes(String className) {
        String resource = "/" + className.replace('.', '/') + ".class";
        try (InputStream in = ServerSafetyTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "Could not find compiled class " + className
                    + ". If it was renamed or removed, update SERVER_PATH_CLASSES.");
            return in.readAllBytes();
        } catch (IOException e) {
            throw new AssertionError("Failed reading " + resource, e);
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}

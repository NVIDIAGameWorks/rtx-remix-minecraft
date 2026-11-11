/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nvidia.rtxremix.minecraft.LoaderArchitectureContract;
import com.nvidia.rtxremix.minecraft.MinecraftPlatform;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricLoaderResourcesTest {
    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path FABRIC = ROOT.resolve("versions/1.21.1/fabric");
    private static final Path RESOURCES = FABRIC.resolve("src/main/resources");

    @Test
    void metadataRegistersSharedAndFabricMixinConfigs() throws Exception {
        JsonObject metadata = JsonParser.parseString(
                Files.readString(RESOURCES.resolve("fabric.mod.json"))).getAsJsonObject();

        assertEquals(
                Set.of(
                        "rtx-remix-renderer.mixins.json",
                        "rtx-remix-renderer.vanilla-section.mixins.json",
                        "rtx-remix-renderer.fabric.mixins.json"),
                strings(metadata.getAsJsonArray("mixins")));
        assertTrue(Files.isRegularFile(RESOURCES.resolve(
                        metadata.get("accessWidener").getAsString())),
                "declared access widener must exist");
    }

    @Test
    void metadataKeepsFabricEntrypointsInTheLoaderPackage() throws Exception {
        JsonObject metadata = JsonParser.parseString(
                Files.readString(RESOURCES.resolve("fabric.mod.json"))).getAsJsonObject();
        JsonObject entrypoints = metadata.getAsJsonObject("entrypoints");

        assertEquals(
                Set.of("com.nvidia.rtxremix.fabric.RtxRemixCommonMod"),
                strings(entrypoints.getAsJsonArray("main")));
        assertEquals(
                Set.of("com.nvidia.rtxremix.fabric.RtxRemixMod"),
                strings(entrypoints.getAsJsonArray("client")));
    }

    @Test
    void declaredLoaderSuppliesCanonicalServicesAndRuntimeResources() {
        assertTrue(MinecraftPlatform.Services.class.isAssignableFrom(
                FabricPlatformServices.class));
        assertEquals(
                "fabric.mod.json",
                new FabricPlatformServices().metadataResource());
        for (String resource :
                LoaderArchitectureContract.requiredRuntimeResources(
                        "fabric.mod.json")) {
            assertNotNull(
                    getClass().getClassLoader().getResource(resource),
                    "Fabric artifact is missing " + resource);
        }
    }

    @Test
    void fabricMixinConfigNamesExistingUniqueClasses() throws Exception {
        Path configPath = RESOURCES.resolve("rtx-remix-renderer.fabric.mixins.json");
        JsonObject config = JsonParser.parseString(Files.readString(configPath)).getAsJsonObject();
        assertStrictMixinDefaults(config);
        String packageName = config.get("package").getAsString();

        Set<String> names = new HashSet<>();
        for (String section : Set.of("mixins", "client", "server")) {
            JsonArray entries = config.getAsJsonArray(section);
            if (entries == null) {
                continue;
            }
            for (var entry : entries) {
                String name = entry.getAsString();
                assertTrue(names.add(name), name + " is listed more than once");
                Path source = FABRIC.resolve("src/main/java")
                        .resolve(packageName.replace('.', '/'))
                        .resolve(name + ".java");
                assertTrue(Files.isRegularFile(source),
                        name + " is declared in the Fabric mixin config but has no source");
            }
        }
        assertTrue(!names.isEmpty(), "Fabric mixin config must not be empty");
    }

    @Test
    void clientOnlyFabricMixinsAreSideScoped() throws Exception {
        JsonObject config = JsonParser.parseString(Files.readString(
                RESOURCES.resolve("rtx-remix-renderer.fabric.mixins.json")))
                .getAsJsonObject();

        assertEquals(
                Set.of(
                        "IndigoMeshFabricMixin",
                        "MultipartBakedModelFabricMixin",
                        "WeightedBakedModelFabricMixin",
                        "WindowFabricMixin"),
                strings(config.getAsJsonArray("client")));
        assertEquals(
                Set.of("CopperBeaconBlockEntityFabricMixin"),
                strings(config.getAsJsonArray("mixins")));
    }

    private static Set<String> strings(JsonArray values) {
        Set<String> result = new HashSet<>();
        values.forEach(value -> result.add(value.getAsString()));
        return result;
    }

    private static void assertStrictMixinDefaults(JsonObject config) {
        assertTrue(config.get("required").getAsBoolean());
        assertEquals("0.8", config.get("minVersion").getAsString());
        assertEquals("JAVA_21", config.get("compatibilityLevel").getAsString());
        assertEquals(
                "rtx-remix-renderer-refmap.json",
                config.get("refmap").getAsString());
        assertEquals(
                1,
                config.getAsJsonObject("injectors")
                        .get("defaultRequire").getAsInt());
    }
}

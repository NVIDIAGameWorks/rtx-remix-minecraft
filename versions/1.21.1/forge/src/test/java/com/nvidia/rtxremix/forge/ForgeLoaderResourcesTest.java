/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nvidia.rtxremix.minecraft.LoaderArchitectureContract;
import com.nvidia.rtxremix.minecraft.MinecraftPlatform;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeLoaderResourcesTest {
    private static final Path PROJECT = Path.of("").toAbsolutePath().normalize();
    private static final Path FORGE = PROJECT.resolve("versions/1.21.1/forge");
    private static final Path RESOURCES = FORGE.resolve("src/main/resources");

    @Test
    void metadataRegistersSharedAndForgeMixinConfigs() throws Exception {
        String metadata = Files.readString(RESOURCES.resolve("META-INF/mods.toml"));

        assertTrue(metadata.contains("config=\"rtx-remix-renderer.mixins.json\""),
                "Forge metadata must register the shared mixins");
        assertTrue(metadata.contains(
                        "config=\"rtx-remix-renderer.vanilla-section.mixins.json\""),
                "Forge metadata must register the vanilla section compiler adapter");
        assertTrue(metadata.contains("config=\"rtx-remix-renderer.forge.mixins.json\""),
                "Forge metadata must register the Forge mixins");
        assertTrue(Files.isRegularFile(
                        RESOURCES.resolve("META-INF/accesstransformer.cfg")),
                "the access transformer declared by the build must exist");
    }

    @Test
    void forgeMixinConfigNamesExistingUniqueClasses() throws Exception {
        JsonObject config = JsonParser.parseString(Files.readString(
                RESOURCES.resolve("rtx-remix-renderer.forge.mixins.json"))).getAsJsonObject();
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
                Path source = FORGE.resolve("src/main/java")
                        .resolve(packageName.replace('.', '/'))
                        .resolve(name + ".java");
                assertTrue(Files.isRegularFile(source),
                        name + " is declared in the Forge mixin config but has no source");
            }
        }
        assertTrue(!names.isEmpty(), "Forge mixin config must not be empty");
    }

    @Test
    void forgeMixinsAreClientSideScoped() throws Exception {
        JsonObject config = JsonParser.parseString(Files.readString(
                RESOURCES.resolve("rtx-remix-renderer.forge.mixins.json")))
                .getAsJsonObject();

        assertEquals(
                Set.of(
                        "MultipartBakedModelForgeMixin",
                        "WeightedBakedModelForgeMixin",
                        "WorldRendererForgeMixin"),
                strings(config.getAsJsonArray("client")));
        assertTrue(config.getAsJsonArray("mixins") == null);
    }

    @Test
    void declaredLoaderSuppliesCanonicalServicesAndRuntimeResources() {
        assertTrue(MinecraftPlatform.Services.class.isAssignableFrom(
                ForgePlatformServices.class));
        assertEquals(
                "META-INF/mods.toml",
                new ForgePlatformServices().metadataResource());
        for (String resource :
                LoaderArchitectureContract.requiredRuntimeResources(
                        "META-INF/mods.toml")) {
            assertNotNull(
                    getClass().getClassLoader().getResource(resource),
                    "Forge artifact is missing " + resource);
        }
    }

    @Test
    void benchmarkMetadataIsExpandedForForge() throws Exception {
        Properties metadata = new Properties();
        try (var input = getClass().getResourceAsStream("/benchmark.properties")) {
            assertNotNull(input);
            metadata.load(input);
        }
        assertFalse(metadata.getProperty("git.commit").contains("${"));
        assertFalse(metadata.getProperty("git.branch").contains("${"));
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

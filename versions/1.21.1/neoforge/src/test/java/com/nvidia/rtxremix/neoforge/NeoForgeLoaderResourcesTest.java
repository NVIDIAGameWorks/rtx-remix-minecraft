/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nvidia.rtxremix.minecraft.LoaderArchitectureContract;
import com.nvidia.rtxremix.minecraft.MinecraftPlatform;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeoForgeLoaderResourcesTest {
    private static final Path PROJECT = Path.of("").toAbsolutePath().normalize();
    private static final Path NEOFORGE =
            PROJECT.resolve("versions/1.21.1/neoforge");
    private static final Path RESOURCES = NEOFORGE.resolve("src/main/resources");
    private static final String SHARED_MIXINS = "rtx-remix-renderer.mixins.json";
    private static final String NEOFORGE_MIXINS =
            "rtx-remix-renderer.neoforge.mixins.json";

    @Test
    void metadataRegistersSharedAndNeoForgeMixinConfigs() throws Exception {
        String metadata = Files.readString(
                RESOURCES.resolve("META-INF/neoforge.mods.toml"));

        assertTrue(metadata.contains("config=\"" + SHARED_MIXINS + "\""),
                "NeoForge metadata must register the shared mixins");
        assertTrue(metadata.contains("config=\"" + NEOFORGE_MIXINS + "\""),
                "NeoForge metadata must register the NeoForge mixins");
        assertTrue(metadata.contains("modLoader=\"javafml\""));
        assertTrue(metadata.contains("modId=\"rtx_remix_renderer\""));
        Path accessTransformer =
                RESOURCES.resolve("META-INF/accesstransformer.cfg");
        assertTrue(Files.isRegularFile(accessTransformer),
                "the access transformer consumed by ModDevGradle must exist");
        assertFalse(Files.readString(accessTransformer).isBlank(),
                "the NeoForge access transformer must declare its production access");
    }

    @Test
    void sharedAndNeoForgeMixinConfigsAreRuntimeResources() {
        assertNotNull(getClass().getClassLoader().getResource(SHARED_MIXINS));
        assertNotNull(getClass().getClassLoader().getResource(NEOFORGE_MIXINS));
        assertNotNull(getClass().getClassLoader().getResource(
                "META-INF/accesstransformer.cfg"));
    }

    @Test
    void neoForgeMixinConfigNamesExistingUniqueClasses() throws Exception {
        JsonObject config = JsonParser.parseString(Files.readString(
                RESOURCES.resolve(NEOFORGE_MIXINS))).getAsJsonObject();
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
                Path source = NEOFORGE.resolve("src/main/java")
                        .resolve(packageName.replace('.', '/'))
                        .resolve(name + ".java");
                assertTrue(Files.isRegularFile(source),
                        name + " is declared in the NeoForge mixin config but has no source");
            }
        }
        assertFalse(names.isEmpty(), "NeoForge mixin config must not be empty");
    }

    @Test
    void neoForgeMixinsAreClientSideScoped() throws Exception {
        JsonObject config = JsonParser.parseString(Files.readString(
                RESOURCES.resolve(NEOFORGE_MIXINS))).getAsJsonObject();

        assertEquals(
                Set.of(
                        "ChunkRendererRegionNeoForgeMixin",
                        "SectionBuilderNeoForgeMixin",
                        "WorldRendererNeoForgeMixin"),
                strings(config.getAsJsonArray("client")));
        assertTrue(config.getAsJsonArray("mixins") == null);
        assertTrue(config.getAsJsonArray("server") == null);
    }

    @Test
    void declaredLoaderSuppliesCanonicalServicesAndRuntimeResources() {
        assertTrue(MinecraftPlatform.Services.class.isAssignableFrom(
                NeoForgePlatformServices.class));
        assertEquals("neoforge", new NeoForgePlatformServices().loaderId());
        assertEquals(
                "META-INF/neoforge.mods.toml",
                new NeoForgePlatformServices().metadataResource());
        for (String resource :
                LoaderArchitectureContract.requiredRuntimeResources(
                        "META-INF/neoforge.mods.toml")) {
            assertNotNull(
                    getClass().getClassLoader().getResource(resource),
                    "NeoForge artifact is missing " + resource);
        }
    }

    @Test
    void benchmarkMetadataIsExpandedForNeoForge() throws Exception {
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

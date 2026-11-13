/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoaderArchitectureContractTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void discoversEveryKeyedLoaderWithoutAJavaRegistry() throws Exception {
        Path manifest = writeManifest("multi-version/v1");

        LoaderArchitectureContract.Manifest discovered =
                LoaderArchitectureContract.discover(manifest);

        assertEquals("1.21.1", discovered.minecraftVersion());
        assertEquals("[1.21.1,1.21.2)", discovered.minecraftVersionRange());
        assertEquals(21, discovered.javaTarget());
        assertEquals(
                List.of("fabric", "forge", "future-loader"),
                discovered.loaders().stream()
                        .map(LoaderArchitectureContract.Loader::id)
                        .toList());
        assertEquals(
                temporaryDirectory.resolve("versions/1.21.1/future-loader").normalize(),
                discovered.loader("future-loader").modulePath());
        assertEquals(
                "META-INF/future.json",
                discovered.loader("future-loader").metadataResource());
    }

    @Test
    void rejectsAStaleManifestSchema() throws Exception {
        Path manifest = writeManifest("legacy/v1");

        assertThrows(
                IllegalStateException.class,
                () -> LoaderArchitectureContract.discover(manifest));
    }

    @Test
    void runtimeInventoryAcceptsAnUndeclaredLoaderMetadataPath() {
        assertEquals(
                List.of(
                        "rtx-remix-renderer.mixins.json",
                        "benchmark.properties",
                        "META-INF/future.json"),
                LoaderArchitectureContract.requiredRuntimeResources(
                        "META-INF/future.json"));
    }

    private Path writeManifest(String schema) throws Exception {
        Path path = temporaryDirectory.resolve("project-state.json");
        Files.writeString(path, """
                {
                  "schema": "%s",
                  "defaultMinecraftVersion": "1.21.1",
                  "versions": {
                    "1.21.1": {
                      "minecraft": {
                        "version": "1.21.1",
                        "module": "versions/1.21.1/minecraft",
                        "versionRange": "[1.21.1,1.21.2)",
                        "javaTarget": 21
                      },
                      "loaders": {
                    "fabric": {
                      "module": "versions/1.21.1/fabric",
                      "metadataFile": "fabric.mod.json"
                    },
                    "forge": {
                      "module": "versions/1.21.1/forge",
                      "metadataFile": "META-INF/mods.toml"
                    },
                    "future-loader": {
                      "module": "versions/1.21.1/future-loader",
                      "metadataFile": "META-INF/future.json"
                    }
                      }
                    }
                  }
                }
                """.formatted(schema));
        return path;
    }
}

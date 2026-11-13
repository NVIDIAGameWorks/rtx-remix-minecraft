/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemixJavaBoundaryTest {
    private static final Pattern HOST_IMPORT = Pattern.compile(
            "(?m)^\\s*import\\s+(?:static\\s+)?(?:"
                    + "net\\.(?:minecraft|fabricmc|minecraftforge|neoforged)\\."
                    + "|org\\.spongepowered\\.asm\\."
                    + "|org\\.lwjgl\\.)");
    private static final Set<String> LOADER_ATTACHMENT_TYPES = Set.of(
            "BakedQuadStaticGeometryCache.java",
            "BuiltChunkNeighbourhoodAccess.java",
            "BuiltChunkStorageSectionAccess.java",
            "ChunkBuilderBlockEntityAccess.java",
            "LightStorageSnapshotAccess.java",
            "RegionBuilderRelease.java",
            "RegionSnapshotRelease.java",
            "RemixKeyboardAccess.java",
            "RemixMouseAccess.java",
            "WorldRendererCloudAccess.java",
            "WorldRendererProfilingAccess.java",
            "WorldRendererStarAccess.java",
            "WorldRendererTerrainAccess.java");

    @Test
    void shimSourcesDoNotImportHostApis() throws Exception {
        assertHostIndependent(Path.of("rtxremix-java", "src"), "Java shim");
    }

    @Test
    void rendererCoreSourcesDoNotImportHostApis() throws Exception {
        assertHostIndependent(Path.of("renderer-core", "src"), "renderer core");
    }

    @Test
    void rendererCoreTestsDoNotImportHostApis() throws Exception {
        assertHostIndependent(Path.of("renderer-core", "test"), "renderer core tests");
    }

    @Test
    void rendererCoreDoesNotOwnLoaderAttachmentTypes() throws Exception {
        Path sourceRoot = Path.of("renderer-core", "src");
        Set<String> offenders = new TreeSet<>();
        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            sources.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(LOADER_ATTACHMENT_TYPES::contains)
                    .forEach(offenders::add);
        }

        assertEquals(Set.of(), offenders,
                "loader attachment contracts belong beside their Minecraft adapter");
    }

    private static void assertHostIndependent(Path sourceRoot, String layer) throws Exception {
        assertTrue(Files.isDirectory(sourceRoot), "missing " + layer + " sources: " + sourceRoot);
        Set<String> offenders = new TreeSet<>();
        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            var iterator = sources.filter(path -> path.toString().endsWith(".java")).iterator();
            while (iterator.hasNext()) {
                Path source = iterator.next();
                if (HOST_IMPORT.matcher(Files.readString(source)).find()) {
                    offenders.add(sourceRoot.relativize(source).toString());
                }
            }
        }

        assertEquals(Set.of(), offenders, layer + " must remain independent of host APIs");
    }
}

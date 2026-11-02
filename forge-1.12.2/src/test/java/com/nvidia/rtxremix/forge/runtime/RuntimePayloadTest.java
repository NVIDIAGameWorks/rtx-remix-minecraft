/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class RuntimePayloadTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void resolvesLaunchWrapperAndOrdinaryLoaderUrlsIncludingSpaces() throws Exception {
        Path archive = temporary.newFile("mod with spaces.jar").toPath();
        java.net.URL plain = archive.toUri().toURL();
        assertEquals(archive, RuntimePayload.archivePath(plain));
        assertEquals(archive, RuntimePayload.archivePath(new java.net.URL("jar:" + plain + "!/")));
    }

    @Test public void installsSdkAndRepairsAnyMissingDependency() throws Exception {
        Path game = temporary.newFolder().toPath();
        Path archive = archive("runtime", null, null);
        assertTrue(RuntimePayload.install(archive, game));
        assertEquals("runtime", read(game.resolve("remix-sdk/d3d9.dll")));
        assertEquals("plugin", read(game.resolve("remix-sdk/usd/plugin.json")));
        assertFalse(RuntimePayload.install(archive, game));
        Files.delete(game.resolve("remix-sdk/usd/plugin.json"));
        assertTrue(RuntimePayload.install(archive, game));
        assertEquals("plugin", read(game.resolve("remix-sdk/usd/plugin.json")));
    }

    @Test public void preservesCurrentConfigAndOtherGameFilesButReplacesOwnedFilesOnUpgrade() throws Exception {
        Path game = temporary.newFolder().toPath();
        Path archive = archive("runtime", null, null);
        RuntimePayload.install(archive, game);
        write(game.resolve("rtx.conf"), "edited");
        Files.createDirectories(game.resolve("rtx-remix/mods/mine"));
        write(game.resolve("rtx-remix/mods/mine/custom.conf"), "authored");
        assertFalse(RuntimePayload.install(archive, game));
        assertEquals("edited", read(game.resolve("rtx.conf")));
        assertTrue(RuntimePayload.install(archive("new-runtime", null, null), game));
        assertEquals("default-config", read(game.resolve("rtx.conf")));
        assertEquals("authored", read(game.resolve("rtx-remix/mods/mine/custom.conf")));
    }

    @Test public void restoresConfigWithoutReplacingTheSdk() throws Exception {
        Path game = temporary.newFolder().toPath();
        Path archive = archive("runtime", null, null);
        RuntimePayload.install(archive, game);
        write(game.resolve("remix-sdk/local-note.txt"), "keep");
        Files.delete(game.resolve("rtx.conf"));
        assertFalse(RuntimePayload.install(archive, game));
        assertEquals("default-config", read(game.resolve("rtx.conf")));
        assertEquals("keep", read(game.resolve("remix-sdk/local-note.txt")));
    }

    @Test public void corruptionFailsBeforeReplacingLiveFilesOrMarker() throws Exception {
        Path game = temporary.newFolder().toPath();
        RuntimePayload.install(archive("runtime", null, null), game);
        String marker = read(game.resolve(".rtx-remix-forge-runtime"));
        expectFailure(archive("changed", "remix-sdk/d3d9.dll", "wrong!!"), game, "checksum mismatch");
        assertEquals("runtime", read(game.resolve("remix-sdk/d3d9.dll")));
        assertEquals(marker, read(game.resolve(".rtx-remix-forge-runtime")));
        try (java.util.stream.Stream<Path> files = Files.list(game)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".rtx-remix-forge-stage-")));
        }
    }

    @Test public void rejectsTraversalAndCaseCollisions() throws Exception {
        Path game = temporary.newFolder().toPath();
        expectFailure(archive("runtime", "remix-sdk/../../outside", "escape"), game, "payload path");
        expectFailure(archive("runtime", "remix-sdk/D3D9.DLL", "duplicate"), game, "duplicate");
        assertFalse(Files.exists(game.resolve(".rtx-remix-forge-runtime")));
    }

    @Test public void unbundledJarLeavesTheDevelopmentRuntimeAlone() throws Exception {
        Path game = temporary.newFolder().toPath();
        Path archive = temporary.newFile("plain.jar").toPath();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            put(zip, "example.txt", "plain".getBytes(StandardCharsets.UTF_8));
        }
        assertFalse(RuntimePayload.install(archive, game));
        assertFalse(Files.exists(game.resolve("remix-sdk")));
    }

    private void expectFailure(Path archive, Path game, String message) throws Exception {
        try { RuntimePayload.install(archive, game); fail("Expected invalid payload to fail"); }
        catch (IOException ex) { assertTrue(ex.getMessage(), ex.getMessage().contains(message)); }
    }

    private Path archive(String runtime, String changedName, String changedBytes) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("remix-sdk/d3d9.dll", runtime);
        files.put("remix-sdk/remixbridge.dll", "bridge");
        files.put("remix-sdk/usd/plugin.json", "plugin");
        files.put("rtx.conf", "default-config");
        if (changedName != null && !files.containsKey(changedName)) { files.put(changedName, changedBytes); }
        StringBuilder inventory = new StringBuilder();
        Path archive = temporary.newFile().toPath();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Map.Entry<String, String> entry : files.entrySet()) {
                byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
                for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) { inventory.append(String.format("%02x", b)); }
                inventory.append(' ').append(bytes.length).append(' ').append(entry.getKey()).append('\n');
                if (entry.getKey().equals(changedName)) { bytes = changedBytes.getBytes(StandardCharsets.UTF_8); }
                put(zip, "native/" + entry.getKey(), bytes);
            }
            put(zip, "native/forge-runtime.sha256", inventory.toString().getBytes(StandardCharsets.UTF_8));
        }
        return archive;
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
    }
    private static void write(Path path, String text) throws IOException { Files.write(path, text.getBytes(StandardCharsets.UTF_8)); }
    private static String read(Path path) throws IOException { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
}

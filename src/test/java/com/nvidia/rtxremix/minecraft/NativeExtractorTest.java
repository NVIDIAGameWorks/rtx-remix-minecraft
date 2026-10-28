/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeExtractorTest {

    /**
     * Deletes a retired SDK before extractIfNeeded returns. On the game's
     * daemon thread the delete can still be running when JUnit removes the
     * {@code @TempDir}, and on Windows the two deleters fail each other.
     */
    private static final Executor INLINE_CLEANUP = Runnable::run;

    private static final ShippedPayloadHistory NO_HISTORY = history();

    private static final String WATER_CONF = "rtx-remix/mods/minecraft-logic/confs/water.conf";

    @ParameterizedTest
    @CsvSource({
            "aarch64, remix-sdk-arm64",
            "amd64, remix-sdk-win64",
            "x86_64, remix-sdk-win64",
            "ppc64le, remix-sdk-win64"
    })
    void detectedArchitecturesResolveToTheirSdkDirectories(String arch, String expectedDirectory) {
        assertEquals(expectedDirectory, NativeExtractor.detectPlatformSdkDir(arch));
    }

    @Test
    void buildMarkerCombinesTheVersionAndTheArch() {
        assertEquals(
            "0.9.0-202604191459-8f2ab12|remix-sdk-win64",
            NativeExtractor.buildMarker("0.9.0-202604191459-8f2ab12", "remix-sdk-win64")
        );
    }

    @Test
    void markerMatchesAcceptsAnIdenticalMarker() {
        String m = "0.9.0-202604191459-8f2ab12|remix-sdk-win64";
        assertTrue(NativeExtractor.markerMatches(m, m));
    }

    @Test
    void markerMatchesRejectsADifferentVersion() {
        assertFalse(NativeExtractor.markerMatches(
            "0.9.0-202604191459-8f2ab12|remix-sdk-win64",
            "0.9.0-202604191500-8f2ab12|remix-sdk-win64"
        ));
    }

    @Test
    void extractTreeWithoutAKeptCountReplacesExistingFiles(@TempDir Path tmp) throws IOException {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        Files.writeString(src.resolve("d3d9.dll"), "shipped");
        Files.writeString(dst.resolve("d3d9.dll"), "stale");

        AtomicInteger files = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        NativeExtractor.extractTree(src, dst, files, bytes);

        // Mod-owned payload: the shipped copy always wins.
        assertEquals("shipped", Files.readString(dst.resolve("d3d9.dll")));
        assertEquals(1, files.get());
    }

    @Test
    void extractAuthorTreePreservesAuthoredEditsAndAddsNewFiles(@TempDir Path tmp) throws IOException {
        // The gameplay conf payload is edited in place by lighting authors, and
        // every build makes the version marker stale. Re-extraction must not
        // revert their tuning, while still delivering profiles they don't have.
        Path src = Files.createDirectories(tmp.resolve("src/confs/zones"));
        Path dst = Files.createDirectories(tmp.resolve("dst/confs/zones"));
        Files.writeString(src.resolve("cave.conf"), "shipped default");
        Files.writeString(src.resolve("swamp.conf"), "newly shipped profile");
        Files.writeString(dst.resolve("cave.conf"), "author's hand-tuned values");

        AtomicInteger files = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        AtomicInteger kept = new AtomicInteger();
        NativeExtractor.extractAuthorTree(tmp.resolve("src"), tmp.resolve("dst"), "rtx-remix", NO_HISTORY,
                files, bytes, kept);

        assertEquals("author's hand-tuned values", Files.readString(dst.resolve("cave.conf")));
        assertEquals("newly shipped profile", Files.readString(dst.resolve("swamp.conf")));
        assertEquals(1, kept.get());
        assertEquals(1, files.get());
    }

    @Test
    void extractAuthorTreeRestoresADeletedFileToItsShippedDefault(@TempDir Path tmp) throws IOException {
        // The documented escape hatch: remove the file, relaunch, get the
        // shipped version back.
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path dst = Files.createDirectories(tmp.resolve("dst"));
        Files.writeString(src.resolve("water.conf"), "shipped default");

        AtomicInteger files = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        AtomicInteger kept = new AtomicInteger();
        NativeExtractor.extractAuthorTree(src, dst, "rtx-remix", NO_HISTORY, files, bytes, kept);

        assertEquals("shipped default", Files.readString(dst.resolve("water.conf")));
        assertEquals(0, kept.get());
    }

    @Test
    void aVersionChangeUpdatesAConfStillHoldingAnEarlierShippedDefault(@TempDir Path tmp) throws IOException {
        // Issue #20: a release that retunes a shipped profile must reach
        // installs where nobody touched the previous default.
        Path nativeRoot = createNativePayload(tmp);
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        Path waterConf = Files.createDirectories(gameDir.resolve("rtx-remix/mods/minecraft-logic/confs"))
                .resolve("water.conf");
        Files.writeString(waterConf, "previous release's water");
        Files.writeString(gameDir.resolve(".rtx-remix-version"), "0.9|remix-sdk-win64");

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "remix-sdk-win64",
                history(line(tmp, "previous release's water", WATER_CONF)), INLINE_CLEANUP);

        assertEquals("shipped water", Files.readString(waterConf));
    }

    @Test
    void aVersionChangeKeepsAConfThatMatchesNoShippedVersion(@TempDir Path tmp) throws IOException {
        Path nativeRoot = createNativePayload(tmp);
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        Path waterConf = Files.createDirectories(gameDir.resolve("rtx-remix/mods/minecraft-logic/confs"))
                .resolve("water.conf");
        Files.writeString(waterConf, "previous release's water, retuned by the author");
        Files.writeString(gameDir.resolve(".rtx-remix-version"), "0.9|remix-sdk-win64");

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "remix-sdk-win64",
                history(line(tmp, "previous release's water", WATER_CONF)), INLINE_CLEANUP);

        assertEquals("previous release's water, retuned by the author", Files.readString(waterConf));
    }

    @Test
    void aShippedVersionOfAnotherFileDoesNotMakeAConfReplaceable(@TempDir Path tmp) throws IOException {
        // Hashes are recorded per path: an author who pasted another profile's
        // default into water.conf has still edited water.conf.
        Path nativeRoot = createNativePayload(tmp);
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        Path waterConf = Files.createDirectories(gameDir.resolve("rtx-remix/mods/minecraft-logic/confs"))
                .resolve("water.conf");
        Files.writeString(waterConf, "previous release's cave");
        Files.writeString(gameDir.resolve(".rtx-remix-version"), "0.9|remix-sdk-win64");

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "remix-sdk-win64",
                history(line(tmp, "previous release's cave", "rtx-remix/mods/minecraft-logic/confs/zones/cave.conf")),
                INLINE_CLEANUP);

        assertEquals("previous release's cave", Files.readString(waterConf));
    }

    @Test
    void lineEndingsAloneDoNotMakeAConfAnEdit(@TempDir Path tmp) throws IOException {
        // The builder's git configuration decides the packaged line endings,
        // and editors convert them; history hashes fold CRLF to LF.
        Path nativeRoot = createNativePayload(tmp);
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        Path confs = Files.createDirectories(gameDir.resolve("rtx-remix/mods/minecraft-logic/confs"));
        Files.writeString(confs.resolve("water.conf"), "previous\r\nwater\r\n");
        Files.writeString(gameDir.resolve(".rtx-remix-version"), "0.9|remix-sdk-win64");

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "remix-sdk-win64",
                history(line(tmp, "previous\nwater\n", WATER_CONF)), INLINE_CLEANUP);

        assertEquals("shipped water", Files.readString(confs.resolve("water.conf")));
    }

    @Test
    void aConfMatchingTheCurrentDefaultIsNotRewritten(@TempDir Path tmp) throws IOException {
        Path nativeRoot = createNativePayload(tmp);
        Files.writeString(nativeRoot.resolve(WATER_CONF), "shipped\nwater\n");
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        Path waterConf = Files.createDirectories(gameDir.resolve("rtx-remix/mods/minecraft-logic/confs"))
                .resolve("water.conf");
        Files.writeString(waterConf, "shipped\r\nwater\r\n");
        Files.writeString(gameDir.resolve(".rtx-remix-version"), "0.9|remix-sdk-win64");

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "remix-sdk-win64", NO_HISTORY, INLINE_CLEANUP);

        assertEquals("shipped\r\nwater\r\n", Files.readString(waterConf));
    }

    @Test
    void matchingMarkerWithMissingRuntimeDllReextractsTheSdk(@TempDir Path tmp) throws IOException {
        Path nativeRoot = createNativePayload(tmp);
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "remix-sdk-win64", NO_HISTORY, INLINE_CLEANUP);
        Files.delete(gameDir.resolve("remix-sdk/d3d9.dll"));
        Files.writeString(gameDir.resolve("remix-sdk/runtime.dll"), "stale runtime");
        Files.writeString(gameDir.resolve("rtx.conf"), "stale defaults");

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "remix-sdk-win64", NO_HISTORY, INLINE_CLEANUP);

        assertEquals("shipped runtime", Files.readString(gameDir.resolve("remix-sdk/d3d9.dll")));
        assertEquals("shipped companion", Files.readString(gameDir.resolve("remix-sdk/runtime.dll")));
        assertEquals("shipped defaults", Files.readString(gameDir.resolve("rtx.conf")));
        assertEquals("1.0|remix-sdk-win64", Files.readString(gameDir.resolve(".rtx-remix-version")));
    }

    @Test
    void matchingMarkerWithMissingSdkDirectoryReextractsTheSdk(@TempDir Path tmp) throws IOException {
        Path nativeRoot = createNativePayload(tmp);
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        Files.writeString(gameDir.resolve(".rtx-remix-version"), "1.0|remix-sdk-win64");

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "remix-sdk-win64", NO_HISTORY, INLINE_CLEANUP);

        assertEquals("shipped runtime", Files.readString(gameDir.resolve("remix-sdk/d3d9.dll")));
        assertEquals("shipped companion", Files.readString(gameDir.resolve("remix-sdk/runtime.dll")));
    }

    @Test
    void matchingMarkerRestoresADeletedShippedConfWithoutReplacingTheSdk(@TempDir Path tmp)
            throws IOException {
        Path nativeRoot = createNativePayload(tmp);
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "remix-sdk-win64", NO_HISTORY, INLINE_CLEANUP);
        Path waterConf = gameDir.resolve("rtx-remix/mods/minecraft-logic/confs/water.conf");
        Files.delete(waterConf);
        Files.writeString(gameDir.resolve("remix-sdk/d3d9.dll"), "existing runtime");

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "remix-sdk-win64", NO_HISTORY, INLINE_CLEANUP);

        assertEquals("shipped water", Files.readString(waterConf));
        assertEquals("existing runtime", Files.readString(gameDir.resolve("remix-sdk/d3d9.dll")));
    }

    @Test
    void matchingMarkerNeverOverwritesAnEditedConf(@TempDir Path tmp) throws IOException {
        Path nativeRoot = createNativePayload(tmp);
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "remix-sdk-win64", NO_HISTORY, INLINE_CLEANUP);
        Path waterConf = gameDir.resolve("rtx-remix/mods/minecraft-logic/confs/water.conf");
        Files.writeString(waterConf, "author's water");
        Files.writeString(gameDir.resolve("rtx.conf"), "edited defaults");

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "remix-sdk-win64", NO_HISTORY, INLINE_CLEANUP);

        assertEquals("author's water", Files.readString(waterConf));
        assertEquals("edited defaults", Files.readString(gameDir.resolve("rtx.conf")));

        Files.delete(gameDir.resolve("remix-sdk/d3d9.dll"));
        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "remix-sdk-win64", NO_HISTORY, INLINE_CLEANUP);
        assertEquals("author's water", Files.readString(waterConf),
                "repairing a missing runtime must preserve author-owned files too");
    }

    @Test
    void extractionWritesTheShippedTexturesBesideOtherFilesInTheTexturesFolder(@TempDir Path tmp)
            throws IOException {
        // A JAR dropped into mods/ by a launcher has no installer to copy the
        // runtime textures, so the mod must put them where it loads them from.
        Path nativeRoot = createNativePayload(tmp);
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        Path textures = Files.createDirectories(gameDir.resolve("textures"));
        Files.writeString(textures.resolve("unrelated.png"), "someone else's file");

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "remix-sdk-win64", NO_HISTORY, INLINE_CLEANUP);

        assertEquals("shipped water normals", Files.readString(textures.resolve("water_normals_flipbook.dds")));
        assertEquals("someone else's file", Files.readString(textures.resolve("unrelated.png")));
        try (var listing = Files.list(textures)) {
            assertEquals(2, listing.count(), "no staging copies may be left behind");
        }
    }

    @Test
    void aVersionChangeReplacesAStaleTexture(@TempDir Path tmp) throws IOException {
        // Textures are mod-owned, unlike the author-owned conf payload: a
        // release that replaces a texture must reach installs that have the old one.
        Path nativeRoot = createNativePayload(tmp);
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        Path waterNormals = Files.createDirectories(gameDir.resolve("textures"))
                .resolve("water_normals_flipbook.dds");
        Files.writeString(waterNormals, "previous release's water normals");
        Files.writeString(gameDir.resolve(".rtx-remix-version"), "0.9|remix-sdk-win64");

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "remix-sdk-win64", NO_HISTORY, INLINE_CLEANUP);

        assertEquals("shipped water normals", Files.readString(waterNormals));
    }

    @Test
    void matchingMarkerRestoresADeletedTextureWithoutReplacingTheSdk(@TempDir Path tmp) throws IOException {
        Path nativeRoot = createNativePayload(tmp);
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "remix-sdk-win64", NO_HISTORY, INLINE_CLEANUP);
        Path waterNormals = gameDir.resolve("textures/water_normals_flipbook.dds");
        Files.delete(waterNormals);
        Files.writeString(gameDir.resolve("remix-sdk/d3d9.dll"), "existing runtime");

        NativeExtractor.extractIfNeeded(nativeRoot, gameDir, "1.0", "remix-sdk-win64", NO_HISTORY, INLINE_CLEANUP);

        assertEquals("shipped water normals", Files.readString(waterNormals));
        assertEquals("existing runtime", Files.readString(gameDir.resolve("remix-sdk/d3d9.dll")));
    }

    private static Path createNativePayload(Path tmp) throws IOException {
        Path nativeRoot = Files.createDirectories(tmp.resolve("native"));
        Path sdk = Files.createDirectories(nativeRoot.resolve("remix-sdk-win64"));
        Files.writeString(sdk.resolve("d3d9.dll"), "shipped runtime");
        Files.writeString(sdk.resolve("runtime.dll"), "shipped companion");
        Files.writeString(nativeRoot.resolve("rtx.conf"), "shipped defaults");
        Path confs = Files.createDirectories(nativeRoot.resolve("rtx-remix/mods/minecraft-logic/confs"));
        Files.writeString(confs.resolve("water.conf"), "shipped water");
        Path textures = Files.createDirectories(nativeRoot.resolve("textures"));
        Files.writeString(textures.resolve("water_normals_flipbook.dds"), "shipped water normals");
        return nativeRoot;
    }

    private static ShippedPayloadHistory history(String... lines) {
        try {
            return ShippedPayloadHistory.parse(List.of(lines));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A history line recording {@code content} as a shipped version of {@code path}. */
    private static String line(Path tmp, String content, String path) throws IOException {
        Path sample = Files.createTempFile(tmp, "shipped", ".conf");
        Files.writeString(sample, content);
        return ShippedPayloadHistory.contentHash(sample) + "  " + path;
    }

    @Test
    void restoreMissingRtxConfWritesTheShippedConfWhenTheLiveCopyWasDeleted(@TempDir Path tmp) throws IOException {
        // A matching version marker skips extraction, so this is the only path
        // that puts rtx.conf back after something deletes it.
        Path nativeRoot = Files.createDirectories(tmp.resolve("native"));
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        Files.writeString(nativeRoot.resolve("rtx.conf"), "rtx.showUICursor = false");

        assertTrue(NativeExtractor.restoreMissingRtxConf(nativeRoot, gameDir));
        assertEquals("rtx.showUICursor = false", Files.readString(gameDir.resolve("rtx.conf")));
    }

    @Test
    void restoreMissingRtxConfLeavesAnExistingConfAlone(@TempDir Path tmp) throws IOException {
        // Restoring runs on every version-stable launch, so it must never
        // overwrite a conf someone is editing between launches.
        Path nativeRoot = Files.createDirectories(tmp.resolve("native"));
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        Files.writeString(nativeRoot.resolve("rtx.conf"), "shipped");
        Files.writeString(gameDir.resolve("rtx.conf"), "hand tuned");

        assertFalse(NativeExtractor.restoreMissingRtxConf(nativeRoot, gameDir));
        assertEquals("hand tuned", Files.readString(gameDir.resolve("rtx.conf")));
    }

    @Test
    void restoreMissingRtxConfIsANoOpWhenTheJarCarriesNoNativePayload(@TempDir Path tmp) throws IOException {
        // Development runs load classes without a bundled native/ root.
        Path gameDir = Files.createDirectories(tmp.resolve("game"));

        assertFalse(NativeExtractor.restoreMissingRtxConf(tmp.resolve("absent-native"), gameDir));
        assertFalse(Files.exists(gameDir.resolve("rtx.conf")));
    }
}

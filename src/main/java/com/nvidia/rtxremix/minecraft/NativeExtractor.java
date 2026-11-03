/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Extracts the bundled native payload (remix-sdk, rtx.conf, the runtime
 * textures, and other directories such as rtx-remix/mods/minecraft-logic) from
 * the mod JAR to the game directory on first launch, version change, or missing
 * runtime.
 *
 * <p>Mirrors the runtime layout produced by the installer so existing native
 * path resolution works unchanged. The runtime textures land in
 * {@code textures/}, where {@code SkyRenderer} and {@code BlockTextureManager}
 * load them, so a JAR that a launcher drops into {@code mods/} needs nothing
 * beside it.
 */
public final class NativeExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(NativeExtractor.class);
    private static final String JAR_NATIVE_ROOT = "native";
    private static final String VERSION_MARKER = ".rtx-remix-version";
    private static final String RTX_CONF = "rtx.conf";
    private static final String TEXTURES_DIR = "textures";

    /** Deletes a retired SDK on a daemon thread, off the mod-init path. */
    private static final Executor RETIRED_SDK_CLEANUP = task -> {
        Thread cleanup = new Thread(task, "NativeExtractor-cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
    };

    private NativeExtractor() {}

    /**
     * Maps Java's os.arch property to the SDK subdirectory in the JAR.
     * aarch64 -> remix-sdk-arm64; anything else (amd64, x86_64, etc.) -> remix-sdk-win64.
     */
    static String detectPlatformSdkDir(String osArch) {
        return "aarch64".equals(osArch) ? "remix-sdk-arm64" : "remix-sdk-win64";
    }

    /**
     * Composes the version marker written to .rtx-remix-version.
     * Format: "{modVersion}|{archDir}" so both version bumps AND arch changes
     * invalidate the marker, triggering re-extraction.
     */
    static String buildMarker(String modVersion, String archDir) {
        return modVersion + "|" + archDir;
    }

    /** Compares two markers for byte equality, treating a missing marker as unequal. */
    static boolean markerMatches(String a, String b) {
        return a != null && a.equals(b);
    }

    /**
     * Checks whether the JAR's native/ directory contains the subdirectory
     * for the host's architecture. Used to detect single-platform JARs
     * loaded on a machine of the other architecture.
     */
    static boolean archSubdirPresent(Set<String> jarArchDirs, String detectedDir) {
        return jarArchDirs.contains(detectedDir);
    }

    /**
     * Extracts the bundled native/runtime payload if the version marker is
     * absent or stale, or the runtime is missing. Safe to call early in mod init:
     * uses only Fabric Loader APIs.
     *
     * <p>Non-SDK directories other than {@code textures/}, such as the
     * {@code rtx-remix/} gameplay conf payload, are author-owned: players and
     * map authors edit them in place. A file there is written when it is
     * missing or still holds an earlier shipped default, as
     * {@link ShippedPayloadHistory} recognises it, and is otherwise left
     * alone. The mod version string embeds a build timestamp and git hash, so
     * every new build makes the marker stale; overwriting edited files would
     * mean each new build silently reverted an author's tuned profiles.
     * Deleting an edited file restores its shipped default on the next launch.
     *
     * <p>The SDK and {@code rtx.conf} are first built in
     * {@code remix-sdk.staging/} and {@code rtx.conf.staging}, then moved over
     * their live paths. This avoids constructing the live SDK file-by-file, but
     * the multi-path update is not a rollback transaction. Those two are
     * mod-owned and are always replaced.
     * The live {@code remix-sdk/} is retired by rename to
     * {@code remix-sdk.old-<nanoTime>/} rather than deleted in-place, allowing
     * replacement even when a file is locked. Retired copies are cleaned up
     * best-effort; stragglers are retried by the next launch's startup scan.
     *
     * <p>The runtime textures are mod-owned too, but share {@code textures/}
     * with whatever else is there, so each shipped file replaces only its own
     * live copy, through a staging file beside it.
     *
     * <p>A current marker skips the SDK copy only while {@code remix-sdk/d3d9.dll}
     * is present. Missing author-owned files, {@code rtx.conf}, and runtime
     * textures are restored on every launch without overwriting existing files,
     * apart from author-owned files that still hold an earlier shipped default.
     * Recovering a small conf must not drag the whole SDK payload with it.
     *
     * <p>The version marker is written only after the copy and both staged
     * moves succeed. A failure before that point leaves the marker stale so the
     * next launch retries extraction. Auxiliary directories may already be
     * partially updated, and a failed SDK swap can leave the live SDK path
     * absent; callers must not rely on rollback to the prior payload.
     */
    public static void extractIfNeeded() {
        try {
            ModContainer container = FabricLoader.getInstance()
                    .getModContainer(RtxRemixCommonMod.MOD_ID)
                    .orElse(null);
            if (container == null) {
                LOGGER.warn("Cannot find own mod container, skipping native extraction");
                return;
            }
            extractIfNeeded(
                    container.getRootPaths().get(0).resolve(JAR_NATIVE_ROOT),
                    FabricLoader.getInstance().getGameDir(),
                    container.getMetadata().getVersion().getFriendlyString(),
                    detectPlatformSdkDir(System.getProperty("os.arch")),
                    ShippedPayloadHistory.loadBundled(),
                    RETIRED_SDK_CLEANUP);
        } catch (Exception e) {
            LOGGER.error("Native extraction failed; the mod may not function correctly. "
                    + "Relaunch to retry the staged payload update.", e);
        }
    }

    /**
     * As {@link #extractIfNeeded()}, recognising earlier shipped defaults
     * through {@code history} and deleting a retired {@code remix-sdk/}
     * through {@code retiredSdkCleanup}.
     */
    static void extractIfNeeded(Path nativeRoot, Path gameDir, String modVersion, String archDir,
                                ShippedPayloadHistory history, Executor retiredSdkCleanup) throws IOException {
        String expectedMarker = buildMarker(modVersion, archDir);
        Path versionFile = gameDir.resolve(VERSION_MARKER);
        Path remixSdkDir = gameDir.resolve("remix-sdk");

        // Reclaim retired SDKs even when the current payload needs no repair.
        cleanupRetiredSdkDirs(gameDir);
        if (!Files.isDirectory(nativeRoot)) {
            LOGGER.warn("No native/ directory in mod JAR, skipping extraction");
            return;
        }

        AtomicInteger fileCount = new AtomicInteger();
        AtomicLong totalBytes = new AtomicLong();
        AtomicInteger keptCount = new AtomicInteger();
        if (Files.exists(versionFile)) {
            String existing = Files.readString(versionFile).strip();
            if (markerMatches(existing, expectedMarker)) {
                if (Files.isRegularFile(remixSdkDir.resolve("d3d9.dll"))) {
                    if (restoreMissingRtxConf(nativeRoot, gameDir)) {
                        LOGGER.info("Restored mod-owned {} after it was deleted since the last extraction",
                                RTX_CONF);
                    }
                    extractTextures(nativeRoot, gameDir, true, fileCount, totalBytes);
                    extractAuthorPayload(nativeRoot, gameDir, history, fileCount, totalBytes, keptCount);
                    LOGGER.info("Native runtime {} already extracted; restored or updated {} payload file(s)",
                            expectedMarker, fileCount.get());
                    return;
                }
                LOGGER.info("Native runtime {} is missing, re-extracting natives", expectedMarker);
            } else {
                LOGGER.info("Marker changed ({} -> {}), re-extracting natives", existing, expectedMarker);
            }
        }

        // Single-platform JARs must fail before changing the live payload when
        // their SDK does not match the host architecture.
        Set<String> jarArchDirs;
        try (var scanStream = Files.list(nativeRoot)) {
            jarArchDirs = scanStream
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("remix-sdk-"))
                    .collect(Collectors.toSet());
        }

        if (!archSubdirPresent(jarArchDirs, archDir)) {
            LOGGER.error(
                    "This JAR contains SDK binaries for {} but the current "
                    + "machine requires {}. This is a single-platform JAR "
                    + "built for a different architecture. Use a dual-platform "
                    + "or matching single-platform JAR instead.",
                    jarArchDirs, archDir);
            return;  // No marker written; mod init will fail loudly later.
        }

        Path sdkStaging = gameDir.resolve("remix-sdk.staging");
        Path rtxConfFile = gameDir.resolve(RTX_CONF);
        Path rtxConfStaging = gameDir.resolve(RTX_CONF + ".staging");

        // Clean any leftover staging from a previously-interrupted run.
        if (Files.exists(sdkStaging)) deleteRecursively(sdkStaging);
        Files.deleteIfExists(rtxConfStaging);

        // Mod-owned files stage first and replace their live copies only once
        // every copy succeeds. Author-owned directories are repaired in place.
        try (var topStream = Files.list(nativeRoot)) {
            for (Path entry : (Iterable<Path>) topStream::iterator) {
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    if (name.equals(archDir)) {
                        extractTree(entry, sdkStaging, fileCount, totalBytes);
                    }
                } else if (RTX_CONF.equals(name)) {
                    Files.copy(entry, rtxConfStaging, StandardCopyOption.REPLACE_EXISTING);
                    fileCount.incrementAndGet();
                    totalBytes.addAndGet(Files.size(entry));
                } else {
                    Path target = gameDir.resolve(name);
                    Files.createDirectories(target.getParent() != null ? target.getParent() : gameDir);
                    Files.copy(entry, target, StandardCopyOption.REPLACE_EXISTING);
                    fileCount.incrementAndGet();
                    totalBytes.addAndGet(Files.size(entry));
                }
            }
        }
        extractTextures(nativeRoot, gameDir, false, fileCount, totalBytes);
        extractAuthorPayload(nativeRoot, gameDir, history, fileCount, totalBytes, keptCount);

        // Commit: swap staging into place. If either move fails we abort
        // without writing the marker, so next run re-extracts.
        retireAndSwap(remixSdkDir, sdkStaging, retiredSdkCleanup);

        if (Files.exists(rtxConfStaging)) {
            // This single-file replacement is the closest to atomic we get
            // on Windows (ReplaceFileW underneath).
            Files.move(rtxConfStaging, rtxConfFile, StandardCopyOption.REPLACE_EXISTING);
        }

        Files.writeString(versionFile, expectedMarker);
        LOGGER.info("Extracted {} files ({} MB) for {} to {}{}",
                fileCount.get(),
                totalBytes.get() / (1024 * 1024),
                expectedMarker,
                gameDir.toAbsolutePath(),
                keptCount.get() > 0
                        ? " (kept " + keptCount.get() + " edited file(s); delete one to restore its shipped default)"
                        : "");
    }

    private static void extractAuthorPayload(Path nativeRoot, Path gameDir, ShippedPayloadHistory history,
                                             AtomicInteger fileCount, AtomicLong totalBytes,
                                             AtomicInteger keptCount) throws IOException {
        try (var topStream = Files.list(nativeRoot)) {
            for (Path entry : (Iterable<Path>) topStream::iterator) {
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry) && !name.startsWith("remix-sdk-") && !TEXTURES_DIR.equals(name)) {
                    extractAuthorTree(entry, gameDir.resolve(name), name, history, fileCount, totalBytes, keptCount);
                }
            }
        }
    }

    /**
     * Writes the runtime textures the JAR ships into {@code <gameDir>/textures/}.
     * With {@code onlyMissing} every shipped texture without a live copy is
     * written; without it every shipped texture replaces its live copy. Other
     * files in the folder are never touched.
     */
    private static void extractTextures(Path nativeRoot, Path gameDir, boolean onlyMissing,
                                        AtomicInteger fileCount, AtomicLong totalBytes) throws IOException {
        Path bundled = nativeRoot.resolve(TEXTURES_DIR);
        if (!Files.isDirectory(bundled)) {
            return;
        }
        Path texturesDir = gameDir.resolve(TEXTURES_DIR);
        try (var stream = Files.list(bundled)) {
            for (Path texture : (Iterable<Path>) stream::iterator) {
                String name = texture.getFileName().toString();
                Path live = texturesDir.resolve(name);
                if (!Files.isRegularFile(texture) || (onlyMissing && Files.exists(live))) {
                    continue;
                }
                // Through a staging file, so an interrupted copy never leaves a
                // truncated texture that a matching marker would then keep.
                Files.createDirectories(texturesDir);
                Path staging = texturesDir.resolve(name + ".staging");
                Files.copy(texture, staging, StandardCopyOption.REPLACE_EXISTING);
                Files.move(staging, live, StandardCopyOption.REPLACE_EXISTING);
                fileCount.incrementAndGet();
                totalBytes.addAndGet(Files.size(live));
            }
        }
    }

    /**
     * Copies the bundled {@code rtx.conf} into {@code gameDir} when the live
     * copy is gone.
     *
     * <p>Unlike the author-owned {@code rtx-remix/} payload, an absent
     * {@code rtx.conf} carries no intent: it is mod-owned, so the only way to
     * change it is to edit it, and deleting it asks for the shipped default
     * back. Restoring is therefore safe, and an existing file is never touched.
     *
     * @param nativeRoot the JAR's {@code native/} root, which need not exist
     * @param gameDir    the Minecraft game directory
     * @return true when a file was written, false when the live conf was
     *         already present or the JAR carries no bundled conf
     * @throws IOException if the copy fails
     */
    static boolean restoreMissingRtxConf(Path nativeRoot, Path gameDir) throws IOException {
        Path liveConf = gameDir.resolve(RTX_CONF);
        if (Files.exists(liveConf)) {
            return false;
        }
        Path bundledConf = nativeRoot.resolve(RTX_CONF);
        if (!Files.isRegularFile(bundledConf)) {
            return false;
        }
        Files.copy(bundledConf, liveConf);
        return true;
    }

    /**
     * Copies the {@code src} tree onto {@code dst}, replacing every existing
     * file. The SDK staging directory wants exactly this: the mod owns it outright.
     */
    static void extractTree(Path src, Path dst, AtomicInteger fileCount, AtomicLong totalBytes) throws IOException {
        Files.createDirectories(dst);
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                createTargetDirectory(src, dst, dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                copyFile(file, dst.resolve(src.relativize(file).toString()), attrs, fileCount, totalBytes);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Copies the author-owned {@code src} tree onto {@code dst}, preserving edits.
     *
     * <p>Players and lighting authors edit this payload in place, and losing
     * their work to a version bump is far worse than shipping a stale default.
     * A missing file is written, so added profiles reach existing installs and
     * a deleted file returns to its shipped default. An existing file is
     * replaced only when its content is a version of that file that
     * {@code history} records as shipped, which proves nobody edited it.
     * Anything else is left untouched and counted in {@code keptCount}.
     *
     * @param historyRoot the name {@code src} has under the JAR's {@code native/}
     *                    root, which prefixes the paths {@code history} records
     */
    static void extractAuthorTree(Path src, Path dst, String historyRoot, ShippedPayloadHistory history,
                                  AtomicInteger fileCount, AtomicLong totalBytes,
                                  AtomicInteger keptCount) throws IOException {
        Files.createDirectories(dst);
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                createTargetDirectory(src, dst, dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String rel = src.relativize(file).toString().replace('\\', '/');
                Path target = dst.resolve(rel);
                if (Files.exists(target)) {
                    String liveHash = ShippedPayloadHistory.contentHash(target);
                    if (liveHash.equals(ShippedPayloadHistory.contentHash(file))) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!history.shipped(historyRoot + "/" + rel, liveHash)) {
                        keptCount.incrementAndGet();
                        return FileVisitResult.CONTINUE;
                    }
                    LOGGER.info("Updating unedited {} to its current shipped default", target);
                }
                copyFile(file, target, attrs, fileCount, totalBytes);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void createTargetDirectory(Path src, Path dst, Path dir) throws IOException {
        Path rel = src.relativize(dir);
        if (!rel.toString().isEmpty()) {
            Files.createDirectories(dst.resolve(rel.toString()));
        }
    }

    private static void copyFile(Path file, Path target, BasicFileAttributes attrs,
                                 AtomicInteger fileCount, AtomicLong totalBytes) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream in = Files.newInputStream(file)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        fileCount.incrementAndGet();
        totalBytes.addAndGet(attrs.size());
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Replaces the live {@code remix-sdk/} with {@code remix-sdk.staging/}.
     * The live dir is retired by atomic rename to {@code remix-sdk.old-<nanoTime>/}
     * before the staging is moved in: Windows allows renaming a directory
     * containing locked files (where deleting it would fail with
     * SHARING_VIOLATION), so this lets extraction proceed even under a
     * partially-released LoadLibrary handle or an open shell handle. The
     * retired copy is then deleted through {@code cleanup}; any failure there
     * is picked up by {@link #cleanupRetiredSdkDirs} on a later launch. This
     * helper does not provide rollback: cleanup starts before the staged move,
     * so a move failure can leave no live SDK directory.
     */
    private static void retireAndSwap(Path remixSdkDir, Path sdkStaging, Executor cleanup) throws IOException {
        if (Files.exists(remixSdkDir)) {
            Path retired = remixSdkDir.resolveSibling(
                    remixSdkDir.getFileName() + ".old-" + System.nanoTime());
            Files.move(remixSdkDir, retired);
            cleanup.execute(() -> deleteQuietly(retired));
        }
        Files.move(sdkStaging, remixSdkDir);
    }

    /**
     * Best-effort scan for {@code remix-sdk.old-*} dirs left behind by prior
     * runs and quietly delete any that are now unlocked. Failures are
     * intentionally swallowed: if a file is still locked this run, the next
     * run picks the same straggler up again.
     */
    private static void cleanupRetiredSdkDirs(Path gameDir) {
        try (var stream = Files.list(gameDir)) {
            stream
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("remix-sdk.old-"))
                    .forEach(NativeExtractor::deleteQuietly);
        } catch (IOException ignored) {
            // Listing the gameDir failed; very unusual, so skip cleanup this run.
        }
    }

    private static void deleteQuietly(Path root) {
        try {
            deleteRecursively(root);
        } catch (Exception e) {
            // Locked file or transient FS error; will retry on next launch.
            LOGGER.debug("Could not fully delete {}: {}", root, e.toString());
        }
    }
}

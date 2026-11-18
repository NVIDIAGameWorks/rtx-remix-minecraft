/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
public final class DirectoryRuntimePayload {
    private static final String VERSION_MARKER = ".rtx-remix-version";
    private static final String RTX_CONF = "rtx.conf";
    private static final String TEXTURES_DIR = "textures";
    private static final Executor RETIRED_SDK_CLEANUP = new Executor() {
        @Override
        public void execute(Runnable task) {
            Thread cleanup = new Thread(task, "DirectoryRuntimePayload-cleanup");
            cleanup.setDaemon(true);
            cleanup.start();
        }
    };

    private DirectoryRuntimePayload() {}

    public interface Reporter {
        Reporter NONE = new Reporter() {
            @Override public void info(String message) {}
            @Override public void warn(String message) {}
            @Override public void error(String message) {}
        };

        void info(String message);
        void warn(String message);
        void error(String message);
    }

    /**
     * Maps Java's os.arch property to the SDK subdirectory in the JAR.
     * aarch64 -> remix-sdk-arm64; anything else (amd64, x86_64, etc.) -> remix-sdk-win64.
     */
    public static String detectPlatformSdkDir(String osArch) {
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
     * uses only Java filesystem APIs after the host supplies both directories.
     *
     * <p>Non-SDK directories other than {@code textures/}, such as the
     * {@code rtx-remix/} gameplay conf payload, are author-owned. Missing files
     * and files that still match an earlier shipped default are updated;
     * authored edits are preserved. Deleting a file restores its shipped
     * default on the next launch.
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
     * textures are restored on every launch without overwriting authored edits.
     * Recovering a small conf must not drag the whole SDK payload with it.
     *
     * <p>The version marker is written only after the copy and both staged
     * moves succeed. A failure before that point leaves the marker stale so the
     * next launch retries extraction. Auxiliary directories may already be
     * partially updated, and a failed SDK swap can leave the live SDK path
     * absent; callers must not rely on rollback to the prior payload.
     */
    public static void install(Path nativeRoot, Path gameDir, String modVersion, String archDir,
                               ShippedPayloadHistory history, Reporter reporter)
            throws IOException {
        install(nativeRoot, gameDir, modVersion, archDir, history, reporter, RETIRED_SDK_CLEANUP);
    }

    static void install(
            Path nativeRoot,
            Path gameDir,
            String modVersion,
            String archDir,
            ShippedPayloadHistory history,
            Reporter reporter,
            Executor retiredSdkCleanup)
            throws IOException {
        String expectedMarker = buildMarker(modVersion, archDir);
        Path versionFile = gameDir.resolve(VERSION_MARKER);
        Path remixSdkDir = gameDir.resolve("remix-sdk");

        // Reclaim retired SDKs even when the current payload needs no repair.
        cleanupRetiredSdkDirs(gameDir);
        if (!Files.isDirectory(nativeRoot)) {
            reporter.warn("No native/ directory in mod archive, skipping extraction");
            return;
        }

        AtomicInteger fileCount = new AtomicInteger();
        AtomicLong totalBytes = new AtomicLong();
        AtomicInteger keptCount = new AtomicInteger();
        if (Files.exists(versionFile)) {
            String existing = new String(Files.readAllBytes(versionFile), StandardCharsets.UTF_8).trim();
            if (markerMatches(existing, expectedMarker)) {
                if (Files.isRegularFile(remixSdkDir.resolve("d3d9.dll"))) {
                    if (restoreMissingRtxConf(nativeRoot, gameDir)) {
                        reporter.info("Restored mod-owned " + RTX_CONF
                                + " after it was deleted since the last extraction");
                    }
                    extractTextures(nativeRoot, gameDir, true, fileCount, totalBytes);
                    extractAuthorPayload(nativeRoot, gameDir, history, reporter,
                            fileCount, totalBytes, keptCount);
                    reporter.info("Native runtime " + expectedMarker + " already extracted; restored or updated "
                            + fileCount.get() + " payload file(s)");
                    return;
                }
                reporter.info("Native runtime " + expectedMarker + " is missing, re-extracting natives");
            } else {
                reporter.info("Marker changed (" + existing + " -> " + expectedMarker
                        + "), re-extracting natives");
            }
        }

        // Single-platform JARs must fail before changing the live payload when
        // their SDK does not match the host architecture.
        Set<String> jarArchDirs;
        try (Stream<Path> scanStream = Files.list(nativeRoot)) {
            jarArchDirs = scanStream
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("remix-sdk-"))
                    .collect(Collectors.toSet());
        }

        if (!archSubdirPresent(jarArchDirs, archDir)) {
            reporter.error(
                    "This archive contains SDK binaries for " + jarArchDirs + " but the current "
                    + "machine requires " + archDir + ". This is a single-platform archive "
                    + "built for a different architecture. Use a dual-platform "
                    + "or matching single-platform archive instead.");
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
        try (Stream<Path> topStream = Files.list(nativeRoot)) {
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
        extractAuthorPayload(nativeRoot, gameDir, history, reporter, fileCount, totalBytes, keptCount);

        // Commit: swap staging into place. If either move fails we abort
        // without writing the marker, so next run re-extracts.
        retireAndSwap(remixSdkDir, sdkStaging, retiredSdkCleanup, reporter);

        if (Files.exists(rtxConfStaging)) {
            // This single-file replacement is the closest to atomic we get
            // on Windows (ReplaceFileW underneath).
            retrying("Moving " + rtxConfStaging + " to " + rtxConfFile,
                    () -> Files.move(rtxConfStaging, rtxConfFile, StandardCopyOption.REPLACE_EXISTING),
                    Thread::sleep, RENAME_RETRY_DELAYS_MS, reporter);
        }

        Files.write(versionFile, expectedMarker.getBytes(StandardCharsets.UTF_8));
        reporter.info("Extracted " + fileCount.get() + " files ("
                + totalBytes.get() / (1024 * 1024) + " MB) for " + expectedMarker + " to "
                + gameDir.toAbsolutePath()
                + (keptCount.get() > 0
                        ? " (kept " + keptCount.get() + " edited file(s); delete one to restore its shipped default)"
                        : ""));
    }

    private static void extractAuthorPayload(
            Path nativeRoot,
            Path gameDir,
            ShippedPayloadHistory history,
            Reporter reporter,
            AtomicInteger fileCount,
            AtomicLong totalBytes,
            AtomicInteger keptCount) throws IOException {
        try (Stream<Path> topStream = Files.list(nativeRoot)) {
            for (Path entry : (Iterable<Path>) topStream::iterator) {
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry) && !name.startsWith("remix-sdk-") && !TEXTURES_DIR.equals(name)) {
                    extractAuthorTree(entry, gameDir.resolve(name), name, history, reporter,
                            fileCount, totalBytes, keptCount);
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
        try (Stream<Path> stream = Files.list(bundled)) {
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

    static void extractTree(Path src, Path dst, AtomicInteger fileCount, AtomicLong totalBytes) throws IOException {
        Files.createDirectories(dst);
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
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

    static void extractAuthorTree(
            Path src,
            Path dst,
            String historyRoot,
            ShippedPayloadHistory history,
            Reporter reporter,
            AtomicInteger fileCount,
            AtomicLong totalBytes,
            AtomicInteger keptCount) throws IOException {
        Files.createDirectories(dst);
        Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
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
                    reporter.info("Updating unedited " + target + " to its current shipped default");
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

    private static void copyFile(
            Path file,
            Path target,
            BasicFileAttributes attrs,
            AtomicInteger fileCount,
            AtomicLong totalBytes) throws IOException {
        Files.createDirectories(target.getParent());
        try (InputStream in = Files.newInputStream(file)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        fileCount.incrementAndGet();
        totalBytes.addAndGet(attrs.size());
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
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
     * retired copy is then deleted through {@code cleanup}; any failure there is
     * picked up by {@link #cleanupRetiredSdkDirs} on a later launch. This
     * helper does not provide rollback: cleanup starts before the staged move,
     * so a move failure can leave no live SDK directory.
     */
    private static void retireAndSwap(
            Path remixSdkDir, Path sdkStaging, Executor cleanup, Reporter reporter) throws IOException {
        if (Files.exists(remixSdkDir)) {
            Path retired = remixSdkDir.resolveSibling(
                    remixSdkDir.getFileName() + ".old-" + System.nanoTime());
            retrying("Retiring " + remixSdkDir, () -> Files.move(remixSdkDir, retired),
                    Thread::sleep, RENAME_RETRY_DELAYS_MS, reporter);
            cleanup.execute(() -> deleteQuietly(retired));
        }
        retrying("Moving " + sdkStaging + " to " + remixSdkDir,
                () -> Files.move(sdkStaging, remixSdkDir),
                Thread::sleep, RENAME_RETRY_DELAYS_MS, reporter);
    }

    static final long[] RENAME_RETRY_DELAYS_MS =
            {50, 100, 200, 400, 800, 1000, 1500, 2000, 2000, 2000};

    @FunctionalInterface
    interface FileOperation {
        void run() throws IOException;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    static void retrying(
            String description,
            FileOperation operation,
            Sleeper sleeper,
            long[] delaysMs) throws IOException {
        retrying(description, operation, sleeper, delaysMs, Reporter.NONE);
    }

    static void retrying(
            String description,
            FileOperation operation,
            Sleeper sleeper,
            long[] delaysMs,
            Reporter reporter) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                operation.run();
                if (attempt > 0) {
                    reporter.info(description + " succeeded after " + attempt
                            + (attempt == 1 ? " retry" : " retries"));
                }
                return;
            } catch (NoSuchFileException | FileAlreadyExistsException e) {
                throw e;
            } catch (IOException e) {
                if (attempt >= delaysMs.length) {
                    throw e;
                }
                if (attempt == 0) {
                    reporter.warn(description + " failed (" + e + "); retrying for up to "
                            + ((totalMillis(delaysMs) + 999) / 1000)
                            + " s, since another program, usually an antivirus scan, "
                            + "may still have the new files open");
                }
                try {
                    sleeper.sleep(delaysMs[attempt]);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    e.addSuppressed(interrupted);
                    throw e;
                }
            }
        }
    }

    private static long totalMillis(long[] delaysMs) {
        long total = 0;
        for (long delay : delaysMs) {
            total += delay;
        }
        return total;
    }

    /**
     * Best-effort scan for {@code remix-sdk.old-*} dirs left behind by prior
     * runs and quietly delete any that are now unlocked. Failures are
     * intentionally swallowed: if a file is still locked this run, the next
     * run picks the same straggler up again.
     */
    private static void cleanupRetiredSdkDirs(Path gameDir) {
        try (Stream<Path> stream = Files.list(gameDir)) {
            stream
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("remix-sdk.old-"))
                    .forEach(DirectoryRuntimePayload::deleteQuietly);
        } catch (IOException ignored) {
            // Listing the gameDir failed; very unusual, so skip cleanup this run.
        }
    }

    private static void deleteQuietly(Path root) {
        try {
            deleteRecursively(root);
        } catch (Exception ignored) {
            // Locked file or transient FS error; will retry on next launch.
        }
    }
}

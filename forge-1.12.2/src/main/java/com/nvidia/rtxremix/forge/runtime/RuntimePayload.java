/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.runtime;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Checked, staged extraction of the Forge package's mod-owned native files. */
public final class RuntimePayload {
    private static final String INVENTORY = "native/forge-runtime.sha256";
    private static final String MARKER = ".rtx-remix-forge-runtime";
    private static final LinkOption[] NO_LINKS = {LinkOption.NOFOLLOW_LINKS};

    private RuntimePayload() {}

    /** LaunchWrapper reports jar:file URLs; ordinary JVM loaders report file URLs. */
    static Path archivePath(java.net.URL location) throws IOException {
        if ("jar".equals(location.getProtocol())) {
            location = ((java.net.JarURLConnection) location.openConnection()).getJarFileURL();
        }
        if (!"file".equals(location.getProtocol())) { throw new IOException("The mod archive must be a local file"); }
        try { return java.nio.file.Paths.get(location.toURI()); }
        catch (java.net.URISyntaxException ex) { throw new IOException("Invalid mod archive location", ex); }
    }

    /** Lightweight development JARs have no inventory and leave the staged SDK alone. */
    public static boolean install(Path archive, Path gameDirectory) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry inventoryEntry = zip.getEntry(INVENTORY);
            if (inventoryEntry == null) { return false; }
            if (!System.getProperty("os.name").startsWith("Windows") ||
                    !("amd64".equals(System.getProperty("os.arch")) || "x86_64".equals(System.getProperty("os.arch")))) {
                throw new IOException("This Forge package requires Windows and a 64-bit x86 Java 8 runtime.");
            }
            byte[] inventory = readInventory(zip, inventoryEntry);
            List<Entry> entries = parseInventory(inventory);
            String fingerprint = hex(digest().digest(inventory));
            Path game = gameDirectory.toRealPath();
            Path marker = checkedTarget(game, MARKER);
            boolean current = Files.isRegularFile(marker, NO_LINKS) && Files.size(marker) == 64 &&
                    fingerprint.equals(new String(Files.readAllBytes(marker), StandardCharsets.US_ASCII));
            for (Entry entry : entries) {
                ZipEntry member = zip.getEntry("native/" + entry.name);
                if (member == null || member.getSize() != entry.size) {
                    throw new IOException("Incomplete native package: " + entry.name);
                }
                Path target = checkedTarget(game, entry.name);
                if (entry.name.startsWith("remix-sdk/") &&
                        (!Files.isRegularFile(target, NO_LINKS) || Files.size(target) != entry.size)) {
                    current = false;
                }
            }
            if (current) {
                Path conf = checkedTarget(game, "rtx.conf");
                if (!Files.exists(conf, NO_LINKS)) { restoreConfig(zip, entries, game); }
                return false;
            }
            replaceRuntime(zip, entries, game, marker, fingerprint);
            return true;
        }
    }

    private static byte[] readInventory(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream stream = zip.getInputStream(entry); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (out.size() + read > 1024 * 1024) { throw new IOException("Native inventory is too large"); }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static List<Entry> parseInventory(byte[] bytes) throws IOException {
        List<Entry> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(" ", 3);
                if (parts.length != 3 || !parts[0].matches("[0-9a-f]{64}") || !parts[1].matches("[0-9]+")) {
                    throw new IOException("Invalid native inventory entry");
                }
                String name = parts[2];
                if ((!name.startsWith("remix-sdk/") && !name.equals("rtx.conf")) ||
                        name.contains("\\") || name.contains(":") || name.contains("//") ||
                        name.startsWith("/") || name.endsWith("/")) {
                    throw new IOException("Invalid native payload path: " + name);
                }
                for (String segment : name.split("/")) {
                    if (segment.equals(".") || segment.equals("..") || segment.endsWith(".") || segment.endsWith(" ")) {
                        throw new IOException("Invalid native payload path: " + name);
                    }
                }
                long size;
                try { size = Long.parseLong(parts[1]); }
                catch (NumberFormatException ex) { throw new IOException("Invalid native payload size", ex); }
                if (!names.add(name.toLowerCase(java.util.Locale.ROOT)) || result.size() >= 8192 || size > 2L * 1024 * 1024 * 1024) {
                    throw new IOException("Invalid or duplicate native payload: " + name);
                }
                result.add(new Entry(name, size, parts[0]));
            }
        }
        if (!names.contains("remix-sdk/d3d9.dll") || !names.contains("remix-sdk/remixbridge.dll") || !names.contains("rtx.conf")) {
            throw new IOException("Native inventory omits the runtime, JNI bridge, or configuration");
        }
        return result;
    }

    private static void replaceRuntime(ZipFile zip, List<Entry> entries, Path game, Path marker,
            String fingerprint) throws IOException {
        Path staging = Files.createTempDirectory(game, ".rtx-remix-forge-stage-");
        Path runtime = checkedTarget(game, "remix-sdk");
        Path previous = staging.resolve("previous-sdk");
        try {
            for (Entry entry : entries) { copyVerified(zip, entry, staging.resolve(entry.name)); }
            boolean hadRuntime = Files.exists(runtime, NO_LINKS);
            if (hadRuntime) { Files.move(runtime, previous); }
            try { Files.move(staging.resolve("remix-sdk"), runtime); }
            catch (IOException ex) {
                if (hadRuntime) {
                    try { Files.move(previous, runtime); }
                    catch (IOException rollback) { ex.addSuppressed(rollback); }
                }
                throw ex;
            }
            moveReplacing(staging.resolve("rtx.conf"), checkedTarget(game, "rtx.conf"));
            Path stagedMarker = staging.resolve("marker");
            Files.write(stagedMarker, fingerprint.getBytes(StandardCharsets.US_ASCII));
            moveReplacing(stagedMarker, marker);
        } finally {
            // Never discard the only surviving previous SDK if a rollback failed.
            if (Files.exists(runtime, NO_LINKS) || !Files.exists(previous, NO_LINKS)) { deleteTree(staging); }
        }
    }

    private static void restoreConfig(ZipFile zip, List<Entry> entries, Path game) throws IOException {
        Path temporary = Files.createTempFile(game, ".rtx-remix-forge-conf-", ".tmp");
        try {
            for (Entry entry : entries) {
                if (entry.name.equals("rtx.conf")) { copyVerified(zip, entry, temporary); break; }
            }
            moveReplacing(temporary, checkedTarget(game, "rtx.conf"));
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void copyVerified(ZipFile zip, Entry entry, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        MessageDigest digest = digest();
        long bytes = 0;
        try (InputStream stream = zip.getInputStream(zip.getEntry("native/" + entry.name));
                java.io.OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                bytes += count;
                if (bytes > entry.size) { throw new IOException("Native payload exceeds inventory: " + entry.name); }
                digest.update(buffer, 0, count);
                out.write(buffer, 0, count);
            }
        }
        if (bytes != entry.size || !entry.hash.equals(hex(digest.digest()))) {
            throw new IOException("Native payload checksum mismatch: " + entry.name);
        }
    }

    private static Path checkedTarget(Path game, String name) throws IOException {
        Path target = game.resolve(name).normalize();
        if (!target.startsWith(game)) { throw new IOException("Native path escapes the game directory"); }
        Path walk = game;
        for (Path component : game.relativize(target)) {
            walk = walk.resolve(component);
            if (Files.exists(walk, NO_LINKS) && !walk.toRealPath().equals(walk)) {
                throw new IOException("Native destination must not contain links: " + walk);
            }
        }
        return target;
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static void deleteTree(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                if (!dir.toRealPath().equals(dir)) {
                    Files.delete(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) { throw error; }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
    }

    private static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            chars[i * 2] = digits[(bytes[i] >>> 4) & 15];
            chars[i * 2 + 1] = digits[bytes[i] & 15];
        }
        return new String(chars);
    }

    private static final class Entry {
        final String name;
        final long size;
        final String hash;
        Entry(String name, long size, String hash) { this.name = name; this.size = size; this.hash = hash; }
    }
}

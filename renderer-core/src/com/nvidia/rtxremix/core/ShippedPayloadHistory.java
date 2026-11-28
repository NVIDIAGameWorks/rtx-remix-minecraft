/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every version of each author-owned {@code rtx-remix/} payload file that a
 * release has shipped, identified by content hash.
 *
 * <p>Players and map authors edit the payload in place, so extraction must
 * never overwrite their work. A live file whose content matches some shipped
 * version of that file carries no edits, though, and can take the current
 * default. An install records nothing about what it extracted, so recognising
 * such a file needs the hashes of every earlier release, not just this one.
 *
 * <p>Hashes are SHA-256 over the content with CRLF folded to LF. A builder's
 * git configuration decides the line endings packaged into the JAR, and
 * editors convert them freely; neither is an edit.
 */
public final class ShippedPayloadHistory {

    /** Classpath resource listing the shipped hashes, maintained beside the payload. */
    public static final String RESOURCE = "/rtx-remix-shipped-payload.sha256";

    private static final String SEPARATOR = "  ";
    private static final int HASH_LENGTH = 64;

    private final Map<String, Set<String>> hashesByPath;

    private ShippedPayloadHistory(Map<String, Set<String>> hashesByPath) {
        this.hashesByPath = hashesByPath;
    }

    /**
     * Loads the history bundled with the mod.
     *
     * @throws IOException if the resource is missing or malformed, which means
     *                     the JAR was packaged wrongly
     */
    public static ShippedPayloadHistory loadBundled() throws IOException {
        try (InputStream in = ShippedPayloadHistory.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing shipped payload history " + RESOURCE);
            }
            List<String> lines = new ArrayList<>();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            return parse(lines);
        }
    }

    /**
     * Parses {@code <sha256>  <path>} lines; blank lines and {@code #} comments
     * are ignored.
     *
     * @param lines the history file's lines
     * @return the parsed history
     * @throws IOException if a line is not a lowercase hex SHA-256, two spaces,
     *                     and a path
     */
    public static ShippedPayloadHistory parse(List<String> lines) throws IOException {
        Map<String, Set<String>> hashesByPath = new HashMap<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf(SEPARATOR);
            String hash = separator < 0 ? "" : trimmed.substring(0, separator);
            String path = separator < 0 ? "" : trimmed.substring(separator + SEPARATOR.length());
            if (hash.length() != HASH_LENGTH || !hash.chars().allMatch(ShippedPayloadHistory::isLowerHex)
                    || path.isEmpty()) {
                throw new IOException("Malformed shipped payload history line: " + line);
            }
            hashesByPath.computeIfAbsent(path, p -> new HashSet<>()).add(hash);
        }
        return new ShippedPayloadHistory(hashesByPath);
    }

    /**
     * @param path        the file's path under the JAR's {@code native/} root,
     *                    with {@code /} separators
     * @param contentHash the file's {@link #contentHash(Path)}
     * @return whether some release shipped exactly this content at that path
     */
    public boolean shipped(String path, String contentHash) {
        Set<String> hashes = hashesByPath.get(path);
        return hashes != null && hashes.contains(contentHash);
    }

    /**
     * Hashes a file's content with CRLF folded to LF.
     *
     * @param file the file to hash
     * @return the lowercase hex SHA-256
     * @throws IOException if the file cannot be read
     */
    public static String contentHash(Path file) throws IOException {
        byte[] content = Files.readAllBytes(file);
        byte[] folded = new byte[content.length];
        int length = 0;
        for (int i = 0; i < content.length; i++) {
            if (content[i] == '\r' && i + 1 < content.length && content[i + 1] == '\n') {
                continue;
            }
            folded[length++] = content[i];
        }
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform provides SHA-256", e);
        }
        sha256.update(folded, 0, length);
        byte[] digest = sha256.digest();
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(Character.forDigit((value >>> 4) & 0xf, 16));
            hex.append(Character.forDigit(value & 0xf, 16));
        }
        return hex.toString();
    }

    private static boolean isLowerHex(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }
}

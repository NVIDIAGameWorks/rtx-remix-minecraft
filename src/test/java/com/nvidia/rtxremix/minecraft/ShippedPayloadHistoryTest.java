/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShippedPayloadHistoryTest {

    private static final String HASH = "7e24dab633d83a952fa66fcc33e87b4727b59af8eed8587484c272029a4c68bc";

    @Test
    void theBundledHistoryRecordsEveryFileThePayloadShipsToday() throws IOException {
        // A payload edit without its history line would leave the new default
        // unrecognised, so the next release could never update it.
        ShippedPayloadHistory history = ShippedPayloadHistory.loadBundled();
        Path packaging = Path.of("packaging");
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(packaging.resolve("rtx-remix"))) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                String path = packaging.relativize(file).toString().replace('\\', '/');
                String hash = ShippedPayloadHistory.contentHash(file);
                if (!history.shipped(path, hash)) {
                    missing.add(hash + "  " + path);
                }
            }
        }
        assertEquals(List.of(), missing, "Append these lines to src/main/resources"
                + ShippedPayloadHistory.RESOURCE);
    }

    @Test
    void contentHashFoldsCrlfToLf(@TempDir Path tmp) throws IOException {
        Path crlf = tmp.resolve("crlf.conf");
        Path lf = tmp.resolve("lf.conf");
        Files.writeString(crlf, "a = 1\r\nb = 2\r\n");
        Files.writeString(lf, "a = 1\nb = 2\n");

        assertEquals(ShippedPayloadHistory.contentHash(lf), ShippedPayloadHistory.contentHash(crlf));
    }

    @Test
    void contentHashKeepsALoneCarriageReturn(@TempDir Path tmp) throws IOException {
        Path cr = tmp.resolve("cr.conf");
        Path lf = tmp.resolve("lf.conf");
        Files.writeString(cr, "a = 1\rb = 2");
        Files.writeString(lf, "a = 1\nb = 2");

        assertFalse(ShippedPayloadHistory.contentHash(lf).equals(ShippedPayloadHistory.contentHash(cr)));
    }

    @Test
    void parseIgnoresCommentsAndBlankLines() throws IOException {
        ShippedPayloadHistory history = ShippedPayloadHistory.parse(List.of(
                "# 0.1.0",
                "",
                HASH + "  rtx-remix/water.conf"));

        assertTrue(history.shipped("rtx-remix/water.conf", HASH));
        assertFalse(history.shipped("rtx-remix/cave.conf", HASH));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            HASH,
            HASH + " rtx-remix/water.conf",
            "7E24DAB633D83A952FA66FCC33E87B4727B59AF8EED8587484C272029A4C68BC  rtx-remix/water.conf",
            "7e24dab6  rtx-remix/water.conf",
    })
    void parseRejectsAMalformedLine(String line) {
        assertThrows(IOException.class, () -> ShippedPayloadHistory.parse(List.of(line)));
    }
}

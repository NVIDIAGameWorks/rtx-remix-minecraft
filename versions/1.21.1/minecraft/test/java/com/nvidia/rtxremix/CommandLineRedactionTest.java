/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandLineRedactionTest {
    private static final List<String> OPTIONS = List.of("--accessToken", "--uuid", "--xuid", "--clientId");

    @Test
    void blanksTheValueAfterEachListedOption() {
        String prefix = "C:\\Java\\bin\\javaw.exe -Xmx4G -cp a.jar net.minecraft.client.main.Main"
                + " --username Steve --version 1.21.1";
        String line = prefix + " --uuid 0123abcd --accessToken eyJhbGc.payload.sig"
                + " --clientId Q2xpZW50= --xuid 2535 --userType msa";

        CommandLineRedaction.Result result = CommandLineRedaction.redact(line, OPTIONS);

        assertEquals(prefix + " --uuid " + stars(8) + " --accessToken " + stars(19)
                + " --clientId " + stars(9) + " --xuid " + stars(4) + " --userType msa",
                result.commandLine());
        assertEquals(4, result.blankedValues());
    }

    @Test
    void keepsQuotesAndBackslashesSoTheLineSplitsTheSameWay() {
        assertRedacts(
                "\"C:\\Program Files\\Java\\bin\\javaw.exe\" \"--accessToken\" \"ey J\" next",
                "\"C:\\Program Files\\Java\\bin\\javaw.exe\" \"--accessToken\" \"****\" next", 1);
        assertRedacts("java --accessToken \"a\\\"b c\" next", "java --accessToken \"*\\\"***\" next", 1);
        assertRedacts("java --accessToken \"a\"\"b\" next", "java --accessToken \"*\"\"*\" next", 1);
    }

    @Test
    void blanksTheValueAfterAnEqualsSign() {
        assertRedacts("java --accessToken=secret --version 1.21.1", "java --accessToken=****** --version 1.21.1", 1);
        assertRedacts("java \"--uuid=ab cd\" next", "java \"--uuid=*****\" next", 1);
    }

    @Test
    void leavesEverythingElseAlone() {
        assertRedacts("java -Dx=--accessToken --accessTokens secret --username Steve --accessToken",
                "java -Dx=--accessToken --accessTokens secret --username Steve --accessToken", 0);
        assertRedacts("\"--accessToken\" secret", "\"--accessToken\" secret", 0);
    }

    private static void assertRedacts(String line, String expected, int blankedValues) {
        CommandLineRedaction.Result result = CommandLineRedaction.redact(line, OPTIONS);
        assertEquals(expected, result.commandLine());
        assertEquals(blankedValues, result.blankedValues());
    }

    private static String stars(int count) {
        return String.valueOf(CommandLineRedaction.BLANK).repeat(count);
    }
}

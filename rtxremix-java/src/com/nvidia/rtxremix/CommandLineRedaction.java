/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Blanks option values in a Windows command line without changing its length
 * or how it splits into arguments.
 *
 * <p>Arguments split the way the Microsoft C runtime splits them. The program
 * name ends at the first whitespace outside quotes. After it, whitespace outside
 * quotes separates arguments; 2n backslashes before a quote become n backslashes
 * and toggle quoting, 2n+1 become n backslashes and a literal quote, and a
 * doubled quote inside quotes is a literal quote. Blanking leaves every quote
 * and backslash where it was, so the result splits into the same arguments.
 */
final class CommandLineRedaction {
    static final char BLANK = '*';

    /** The blanked command line, and how many option values it blanked. */
    static final class Result {
        private final String commandLine;
        private final int blankedValues;

        Result(String commandLine, int blankedValues) {
            this.commandLine = commandLine;
            this.blankedValues = blankedValues;
        }

        String commandLine() { return commandLine; }
        int blankedValues() { return blankedValues; }
    }

    /** One argument: its text after unquoting, and its span in the raw command line. */
    private static final class Argument {
        private final String text;
        private final int start;
        private final int end;

        Argument(String text, int start, int end) {
            this.text = text;
            this.start = start;
            this.end = end;
        }
    }

    private CommandLineRedaction() {}

    /**
     * Blanks each value that follows one of {@code options}, either as the next
     * argument or after {@code =} in the same argument.
     *
     * @param commandLine a raw Windows command line
     * @param options     option names, matched against whole arguments
     * @return the blanked line, the same length as {@code commandLine}
     */
    static Result redact(String commandLine, Collection<String> options) {
        List<Argument> arguments = split(commandLine);
        char[] chars = commandLine.toCharArray();
        int blankedValues = 0;
        for (int i = 1; i < arguments.size(); i++) {
            Argument argument = arguments.get(i);
            if (options.contains(argument.text)) {
                if (i + 1 < arguments.size()) {
                    Argument value = arguments.get(i + 1);
                    blank(chars, value.start, value.end);
                    blankedValues++;
                }
                continue;
            }
            int separator = argument.text.indexOf('=');
            if (separator > 0 && options.contains(argument.text.substring(0, separator))) {
                // Option names hold no quotes, so the first raw '=' is the separator.
                blank(chars, commandLine.indexOf('=', argument.start) + 1, argument.end);
                blankedValues++;
            }
        }
        return new Result(new String(chars), blankedValues);
    }

    private static void blank(char[] chars, int start, int end) {
        for (int i = start; i < end; i++) {
            if (chars[i] != '"' && chars[i] != '\\') {
                chars[i] = BLANK;
            }
        }
    }

    private static List<Argument> split(String line) {
        List<Argument> arguments = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int length = line.length();
        int pos = 0;

        boolean inQuotes = false;
        while (pos < length && (inQuotes || !isBlank(line.charAt(pos)))) {
            char c = line.charAt(pos++);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else {
                text.append(c);
            }
        }
        arguments.add(new Argument(text.toString(), 0, pos));

        inQuotes = false;
        while (true) {
            while (pos < length && isBlank(line.charAt(pos))) {
                pos++;
            }
            if (pos == length) {
                return arguments;
            }
            int start = pos;
            text.setLength(0);
            while (true) {
                int backslashes = 0;
                while (pos < length && line.charAt(pos) == '\\') {
                    pos++;
                    backslashes++;
                }
                boolean literal = true;
                if (pos < length && line.charAt(pos) == '"') {
                    if (backslashes % 2 == 0) {
                        if (inQuotes && pos + 1 < length && line.charAt(pos + 1) == '"') {
                            pos++;
                        } else {
                            literal = false;
                            inQuotes = !inQuotes;
                        }
                    }
                    backslashes /= 2;
                }
                for (int i = 0; i < backslashes; i++) {
                    text.append('\\');
                }
                if (pos == length || (!inQuotes && isBlank(line.charAt(pos)))) {
                    break;
                }
                if (literal) {
                    text.append(line.charAt(pos));
                }
                pos++;
            }
            arguments.add(new Argument(text.toString(), start, pos));
        }
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t';
    }
}

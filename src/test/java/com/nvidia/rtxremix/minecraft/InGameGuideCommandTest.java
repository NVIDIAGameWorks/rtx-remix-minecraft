/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the in-game guide's command tables honest against the command tree.
 *
 * <p>A guide that documents a command nobody registers is worse than no guide:
 * the reader types it, gets an unknown-command error, and stops trusting the
 * page. Rather than re-registering the tree here, this reads the two sources
 * that build it and checks every documented path against the tree's shape, so
 * a documented subcommand has to sit under the parent it is documented under
 * rather than merely exist somewhere.
 *
 * <p>The check is one-directional on purpose. A registered command that the
 * guide leaves out is an editorial decision, not a defect.
 */
class InGameGuideCommandTest {

    private static final Path GUIDE = Path.of("docs", "IN_GAME_GUIDE.md");

    private static final List<Path> COMMAND_SOURCES = List.of(
            Path.of("src", "main", "java", "com", "nvidia", "rtxremix", "minecraft", "RtxRemixMod.java"),
            Path.of("src", "main", "java", "com", "nvidia", "rtxremix", "minecraft", "camera",
                    "CameraController.java"));

    /** The literal every documented command hangs from. */
    private static final String ROOT = "rtx";

    /** Key under which {@link #registeredCommandTree()} files the literals nothing encloses. */
    private static final String NO_PARENT = "";

    /**
     * A Brigadier literal node. Anchored on the factory call, because
     * {@code Text.literal("...")} builds chat text rather than a command node.
     */
    private static final Pattern REGISTERED_LITERAL =
            Pattern.compile("ClientCommands\\.literal\\(\"([A-Za-z0-9_-]+)\"\\)");

    /**
     * String, character, and comment text, whose parentheses are prose rather
     * than nesting.
     */
    private static final Pattern STRING_OR_COMMENT = Pattern.compile(
            "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    /**
     * A documented command path. Trailing groups stop at the first token that
     * is not a bare word, so an argument placeholder such as {@code <name>} or
     * the closing backtick of a table cell ends the path.
     */
    private static final Pattern DOCUMENTED_COMMAND = Pattern.compile(
            "/rtx(?:\\s+([A-Za-z][A-Za-z0-9_-]*))?(?:\\s+([A-Za-z][A-Za-z0-9_-]*))?");

    @Test
    void everyDocumentedCommandIsRegistered() {
        Map<String, Set<String>> children = registeredCommandTree();
        List<String> documented = documentedCommandPaths();

        assertFalse(documented.isEmpty(),
                GUIDE + " has no command rows. Either the tables lost their commands or "
                        + DOCUMENTED_COMMAND.pattern() + " no longer matches how they are written.");

        List<String> missing = new ArrayList<>();
        for (String path : documented) {
            String[] literals = path.split(" ");
            if (!isFirstLevel(children, literals[0])) {
                missing.add("/rtx " + path + ": '" + literals[0] + "' is not registered under /rtx");
            } else if (literals.length > 1
                    && !children.getOrDefault(literals[0], Set.of()).contains(literals[1])) {
                missing.add("/rtx " + path + ": '" + literals[1] + "' is not registered under '"
                        + literals[0] + "'");
            }
        }

        assertTrue(missing.isEmpty(),
                GUIDE + " documents commands the tree in " + COMMAND_SOURCES + " does not build:\n"
                        + String.join("\n", new TreeSet<>(missing)));
    }

    /**
     * A subtree of any size can be built by its own method and attached with
     * {@code .then(subtreeCommand())}, which no amount of reading the text
     * resolves, so a literal nothing encloses counts as a first-level command.
     */
    private static boolean isFirstLevel(Map<String, Set<String>> children, String literal) {
        return children.getOrDefault(ROOT, Set.of()).contains(literal)
                || children.getOrDefault(NO_PARENT, Set.of()).contains(literal);
    }

    /**
     * The literal nodes the command sources build, as children by parent name.
     * {@link #NO_PARENT} holds {@code /rtx} itself and the root of every
     * subtree built by its own method.
     */
    private static Map<String, Set<String>> registeredCommandTree() {
        Map<String, Set<String>> children = new HashMap<>();
        for (Path source : COMMAND_SOURCES) {
            String text = read(source);
            Map<Integer, String> literals = new HashMap<>();
            Matcher matcher = REGISTERED_LITERAL.matcher(text);
            while (matcher.find()) {
                literals.put(matcher.start(), matcher.group(1));
            }
            collectNesting(blankStringsAndComments(text), literals, children);
        }
        assertFalse(children.getOrDefault(ROOT, Set.of()).isEmpty(),
                "No ClientCommands.literal(\"...\") nodes under /rtx in " + COMMAND_SOURCES);
        return children;
    }

    /**
     * Walks the parenthesis nesting of one source and files each literal under
     * the innermost literal whose builder chain encloses it. Brigadier writes a
     * child as {@code literal("parent").then(literal("child"))}, so a child
     * opens deeper than its parent and closes before the parent's chain ends.
     */
    private static void collectNesting(String structure, Map<Integer, String> literals,
                                       Map<String, Set<String>> children) {
        List<String> ownerByDepth = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < structure.length(); i++) {
            String literal = literals.get(i);
            if (literal != null) {
                children.computeIfAbsent(enclosingLiteral(ownerByDepth, depth), name -> new TreeSet<>())
                        .add(literal);
                while (ownerByDepth.size() <= depth) {
                    ownerByDepth.add(null);
                }
                ownerByDepth.set(depth, literal);
                continue;
            }
            switch (structure.charAt(i)) {
                case '(' -> depth++;
                case ')' -> {
                    depth = Math.max(0, depth - 1);
                    if (ownerByDepth.size() > depth + 1) {
                        ownerByDepth.subList(depth + 1, ownerByDepth.size()).clear();
                    }
                }
                // A chain ends at the statement that returns or registers it,
                // so nothing after that semicolon is inside it.
                case ';' -> {
                    if (depth == 0) {
                        ownerByDepth.clear();
                    }
                }
                default -> { }
            }
        }
    }

    private static String enclosingLiteral(List<String> ownerByDepth, int depth) {
        for (int d = Math.min(depth, ownerByDepth.size()) - 1; d >= 0; d--) {
            if (ownerByDepth.get(d) != null) {
                return ownerByDepth.get(d);
            }
        }
        return NO_PARENT;
    }

    /** The source with string, character, and comment text replaced by spaces. */
    private static String blankStringsAndComments(String source) {
        StringBuilder structure = new StringBuilder(source);
        Matcher matcher = STRING_OR_COMMENT.matcher(source);
        while (matcher.find()) {
            for (int i = matcher.start(); i < matcher.end(); i++) {
                structure.setCharAt(i, ' ');
            }
        }
        return structure.toString();
    }

    /**
     * The first and second literal of every {@code /rtx} path in the guide's
     * tables, as a space-separated string. Prose outside the tables is ignored:
     * the tables are the reference, and restricting to them is what makes the
     * emptiness check above meaningful.
     */
    private static List<String> documentedCommandPaths() {
        List<String> paths = new ArrayList<>();
        for (String line : read(GUIDE).lines().toList()) {
            if (!line.stripLeading().startsWith("|")) {
                continue;
            }
            Matcher matcher = DOCUMENTED_COMMAND.matcher(line);
            while (matcher.find()) {
                StringBuilder path = new StringBuilder();
                for (int group = 1; group <= matcher.groupCount(); group++) {
                    if (matcher.group(group) == null) {
                        break;
                    }
                    if (path.length() > 0) {
                        path.append(' ');
                    }
                    path.append(matcher.group(group));
                }
                if (path.length() > 0) {
                    paths.add(path.toString());
                }
            }
        }
        return paths;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    path + " is not readable. Tests run from the project root; check the working directory.", e);
        }
    }
}

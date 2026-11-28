/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The folder the runtime's files belong in, and what keeps them there.
 *
 * <p>The runtime resolves {@code rtx.conf}, {@code dxvk.conf}, its
 * {@code rtx-remix/} folder, its caches, and its dump files against the
 * process working directory. A launcher chooses that directory, and it need
 * not be the home folder, the host's game directory, where the payload is
 * extracted. When the two differ, this names every location the runtime lets a
 * host move, as the environment variables it reads them from, so the packaged
 * {@code rtx.conf} loads and the logs land where a player is told to look.
 *
 * <p>When the working directory is the home folder, nothing is overridden. The
 * runtime's own lookup already lands there, and it reads an explicit location
 * through a narrower path than a relative one: a fixed-size buffer, and for
 * some variables the system code page.
 *
 * <p>{@code user.conf} and {@code imgui.ini} have no override. The runtime
 * reads and writes them in the working directory, so a {@code user.conf} in
 * the home folder that it will not read is reported instead.
 */
public final class RuntimeHome {
    /**
     * The runtime copies a variable into a 260-character buffer and treats a
     * value that does not fit as unset, without reporting it.
     */
    static final int MAX_VALUE_LENGTH = 259;

    private static final String USER_CONF = "user.conf";
    private static final String CRASH_DIR = "crash";

    private static final List<Location> LOCATIONS = List.of(
            Location.confFile("DXVK_CONFIG_FILE", "dxvk.conf"),
            Location.confFile("DXVK_RTX_CONFIG_FILE", "rtx.conf"),
            Location.directory("DEFAULT_MODS_DIR", "rtx-remix/mods"),
            Location.directory("DXVK_CAPTURE_PATH", "rtx-remix/captures"),
            Location.directory("DXVK_LOG_PATH", "rtx-remix/logs"),
            Location.directory("DXVK_STATE_CACHE_PATH", ""),
            Location.directory("DXVK_METRICS_PATH", ""),
            Location.directory("DXVK_AFTERMATH_DUMP_PATH", ""),
            Location.directory("DXVK_SCREENSHOT_PATH", "Screenshots"));

    private final Path home;
    private final Path workingDirectory;
    private final Map<String, String> environment;
    private final List<String> warnings;

    private RuntimeHome(
            Path home,
            Path workingDirectory,
            Map<String, String> environment,
            List<String> warnings) {
        this.home = home;
        this.workingDirectory = workingDirectory;
        this.environment = Collections.unmodifiableMap(environment);
        this.warnings = Collections.unmodifiableList(warnings);
    }

    /**
     * @param home             the folder the runtime's files belong in
     * @param workingDirectory the process working directory
     * @param environment      looks up a variable the process started with;
     *                         one that is already set is left as it is, so a
     *                         tool's or a player's own override wins
     */
    public static RuntimeHome resolve(
            Path home,
            Path workingDirectory,
            UnaryOperator<String> environment) {
        Path absoluteHome = home.toAbsolutePath().normalize();
        Path absoluteWorkingDirectory = workingDirectory.toAbsolutePath().normalize();
        Map<String, String> variables = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        if (!sameDirectory(absoluteHome, absoluteWorkingDirectory)) {
            for (Location location : LOCATIONS) {
                String existing = environment.apply(location.variable);
                if (existing != null && !existing.isEmpty()) {
                    continue;
                }
                String value = location.resolveIn(absoluteHome);
                String refusal = location.refusal(value);
                if (refusal != null) {
                    warnings.add(location.variable + " is left unset, so the runtime keeps "
                            + location.describe() + " in " + absoluteWorkingDirectory + ": " + refusal);
                    continue;
                }
                variables.put(location.variable, value);
            }
            if (Files.exists(absoluteHome.resolve(USER_CONF))) {
                warnings.add(absoluteHome.resolve(USER_CONF) + " is not read: the runtime reads "
                        + USER_CONF + " from the working directory, " + absoluteWorkingDirectory);
            }
        }
        return new RuntimeHome(absoluteHome, absoluteWorkingDirectory, variables, warnings);
    }

    public Path home() {
        return home;
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    /**
     * @return the variables to set before the runtime loads, in a stable
     *         order; empty when the working directory is the home folder
     */
    public Map<String, String> environment() {
        return environment;
    }

    /** @return where the JNI bridge writes its crash files */
    public Path crashDirectory() {
        return home.resolve(CRASH_DIR);
    }

    /** @return what stays in the working directory, each as one log line */
    public List<String> warnings() {
        return warnings;
    }

    private static boolean sameDirectory(Path first, Path second) {
        try {
            return Files.isSameFile(first, second);
        } catch (IOException e) {
            // A folder that cannot be opened is compared by name.
            return first.equals(second);
        }
    }

    private static final class Location {
        final String variable;
        private final String relativePath;
        private final boolean commaSeparated;

        private Location(String variable, String relativePath, boolean commaSeparated) {
            this.variable = variable;
            this.relativePath = relativePath;
            this.commaSeparated = commaSeparated;
        }

        /** A variable the runtime splits at commas into several conf files. */
        static Location confFile(String variable, String relativePath) {
            return new Location(variable, relativePath, true);
        }

        static Location directory(String variable, String relativePath) {
            return new Location(variable, relativePath, false);
        }

        String resolveIn(Path home) {
            return relativePath.isEmpty() ? home.toString() : home.resolve(relativePath).toString();
        }

        String describe() {
            return relativePath.isEmpty() ? "those files" : relativePath;
        }

        /** @return why the runtime would misread {@code value}, or null */
        String refusal(String value) {
            if (value.length() > MAX_VALUE_LENGTH) {
                return "the path is longer than the " + MAX_VALUE_LENGTH
                        + " characters the runtime reads";
            }
            if (commaSeparated && value.indexOf(',') >= 0) {
                return "the runtime splits this variable at commas, and the path has one";
            }
            return null;
        }
    }
}

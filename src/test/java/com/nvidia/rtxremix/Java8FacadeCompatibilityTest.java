/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Java8FacadeCompatibilityTest {
    @Test
    void sharedFacadeCompilesAgainstJava8(@TempDir Path output) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "The facade compatibility test requires a JDK compiler");
        List<Path> sources;
        try (var paths = Files.walk(Path.of("rtxremix-java", "src"))) {
            sources = paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted().toList();
        }
        assertFalse(sources.isEmpty(), "No shared facade sources were found");
        Path joml = Path.of(Quaternionf.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var compilerOutput = new StringWriter();
        try (var files = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            // Exclude the current-JDK facade and game classes from this independent compilation.
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of(joml));
            files.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of());
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
            boolean compiled = compiler.getTask(compilerOutput, files, null,
                    List.of("--release", "8", "-proc:none"), null,
                    files.getJavaFileObjectsFromPaths(sources)).call();
            assertTrue(compiled, () -> "Shared facade must compile against Java 8 with only JOML on its classpath:\n"
                    + compilerOutput);
        }
    }
}

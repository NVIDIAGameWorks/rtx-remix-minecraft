/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalArchitectureTest {
    private static final Path ROOT = repositoryRoot();
    private static final Path MINECRAFT = ROOT.resolve("versions/1.21.1/minecraft");
    private static final LoaderArchitectureContract.Manifest MANIFEST =
            LoaderArchitectureContract.discover(ROOT.resolve("project-state.json"));
    private static final Pattern LOADER_IMPORT = Pattern.compile(
            "(?m)^\\s*import\\s+(?:static\\s+)?net\\.(?:fabricmc|minecraftforge|neoforged)\\.");
    private static final Pattern MINECRAFT_IMPORT = Pattern.compile(
            "(?m)^\\s*import\\s+(?:static\\s+)?net\\.minecraft\\.");
    private static final Pattern HOST_GRAPHICS_IMPORT = Pattern.compile(
            "(?m)^\\s*import\\s+(?:static\\s+)?org\\.lwjgl\\.");
    private static final Pattern CANONICAL_RENDERER_CONSTRUCTION = Pattern.compile(
            "\\bnew\\s+RemixRenderer\\s*\\(");
    private static final Pattern MINECRAFT_DEPENDENCY = Pattern.compile(
            "(?m)^\\s*minecraft\\s+.*\\$\\{minecraftVersion}");

    @Test
    void sharedMinecraftSourcesDoNotImportLoaderApis() {
        assertNoMatchingImports(
                MINECRAFT.resolve("main/java"),
                LOADER_IMPORT,
                "shared Minecraft sources must not import loader APIs");
    }

    @Test
    void rendererCoreDoesNotImportMinecraft() {
        assertNoMatchingImports(
                ROOT.resolve("renderer-core/src"),
                MINECRAFT_IMPORT,
                "renderer-core may mention Minecraft in documentation, but must not import it");
        assertNoMatchingImports(
                ROOT.resolve("renderer-core/src"),
                HOST_GRAPHICS_IMPORT,
                "renderer-core must not choose a host graphics backend");
    }

    @Test
    void minecraftRendererDelegatesHostGraphicsOwnership() {
        String renderer = read(MINECRAFT.resolve(
                "main/java/com/nvidia/rtxremix/minecraft/RemixRenderer.java"));
        assertTrue(renderer.contains("HostGraphicsIntegration graphicsIntegration"));
        assertFalse(renderer.contains("GLFWNativeWin32"));
        assertFalse(renderer.contains("GL.getCapabilities()"));
        assertFalse(renderer.contains("getMainRenderTarget().frameBufferId"));
        String runtime = read(MINECRAFT.resolve(
                "main/java/com/nvidia/rtxremix/minecraft/"
                        + "MinecraftClientRuntime.java"));
        assertFalse(runtime.contains("TextureOverlay"));

        String openGlHost = read(MINECRAFT.resolve(
                "main/java/com/nvidia/rtxremix/minecraft/gl/"
                        + "MinecraftOpenGlGraphicsIntegration.java"));
        assertTrue(openGlHost.contains("implements HostGraphicsIntegration"));
        assertTrue(openGlHost.contains("getMainRenderTarget().frameBufferId"));
    }

    @Test
    void minecraftIntegrationPoliciesLiveInTheMinecraftLayer() {
        for (String relative : Set.of(
                "com/nvidia/rtxremix/minecraft/WorkerPoolPolicy.java",
                "com/nvidia/rtxremix/minecraft/terrain/ClientLightSnapshotPolicy.java",
                "com/nvidia/rtxremix/minecraft/terrain/SkyLightSurfaceScanPolicy.java",
                "com/nvidia/rtxremix/minecraft/terrain/VanillaSectionBuilds.java")) {
            assertTrue(Files.isRegularFile(MINECRAFT.resolve("main/java").resolve(relative)),
                    relative + " must be owned by shared Minecraft integration");
            assertFalse(Files.exists(ROOT.resolve("renderer-core/src").resolve(relative)),
                    relative + " must not be owned by renderer-core");
        }
    }

    @Test
    void profilingAndRenderDecisionsAreSharedAcrossLoaders() {
        String runtime = read(MINECRAFT.resolve(
                "main/java/com/nvidia/rtxremix/minecraft/MinecraftClientRuntime.java"));
        assertTrue(runtime.contains("ClientTick.END.benchmark"),
                "client tick profiling must be owned by shared Minecraft integration");

        for (Path loaderRoot : loaderSourceRoots()) {
            for (Path relative : relativeJavaSources(loaderRoot)) {
                String source = read(loaderRoot.resolve(relative));
                assertFalse(source.contains("ClientTick.END.benchmark"),
                        "loader adapters must not define profiling policy: " + relative);
                assertFalse(source.contains("renderBlockDamageInPlace("),
                        "loader adapters must delegate block-damage policy: " + relative);
                assertFalse(source.contains("isRemixWorldRendering()"),
                        "loader adapters must delegate vanilla-particle policy: " + relative);
            }
        }
    }

    @Test
    void manifestOwnsMinecraftVersionAcrossBuildAndMetadata() {
        String convention = read(ROOT.resolve("gradle/loader-module.gradle"));
        assertTrue(convention.contains("state.minecraft.version"),
                "the shared loader convention must read the manifest Minecraft version");
        assertTrue(convention.contains("state.minecraft.versionRange"));
        assertTrue(convention.contains("ext.minecraftVersion"));
        assertTrue(convention.contains("minecraftVersion: minecraftVersion"));
        assertTrue(convention.contains(
                "minecraftVersionRange: state.minecraft.versionRange"));

        String rootBuild = read(ROOT.resolve("build.gradle"));
        assertTrue(rootBuild.contains("targetState.minecraft.version"),
                "root build outputs must consume the manifest Minecraft version");

        for (LoaderArchitectureContract.Loader loader : MANIFEST.loaders()) {
            Path module = loader.modulePath();
            String build = read(module.resolve("build.gradle"));
            assertTrue(
                    build.contains(
                            "apply from: rootProject.file('gradle/loader-module.gradle')"),
                    loader.id() + " must apply the shared loader convention");
            assertTrue(
                    MINECRAFT_DEPENDENCY.matcher(build).find()
                            || build.contains("loaderState.loader.version"),
                    loader.id()
                            + " build must consume a manifest-owned Minecraft or loader version");

            Path metadata = module.resolve("src/main/resources")
                    .resolve(loader.metadataResource());
            assertTrue(Files.isRegularFile(metadata),
                    loader.id() + " metadata template is missing: " + metadata);
            String template = read(metadata);
            assertTrue(
                    template.contains("${minecraftVersion}")
                            || template.contains("${minecraftVersionRange}"),
                    loader.id() + " metadata must expand a manifest Minecraft version");
        }
    }

    @Test
    void gradleDiscoversLoaderModulesFromManifestPaths() {
        String settings = read(ROOT.resolve("settings.gradle"));
        assertTrue(settings.contains(
                "state.loaders.collect { loaderId, loader -> loader.projectPath }"));

        String convention = read(ROOT.resolve("gradle/loader-module.gradle"));
        assertTrue(convention.contains("candidate.projectPath == project.path"));
        assertTrue(convention.contains("candidate.module == modulePath"));
        assertFalse(convention.contains("state.loaders[project.name]"));

        String rootBuild = read(ROOT.resolve("build.gradle"));
        assertTrue(rootBuild.contains("\"${loader.module}/build/distributions/\""));
        assertFalse(rootBuild.contains("\"${loader.id}/build/distributions/\""));
    }

    @Test
    void releaseTasksStageEveryManifestLoaderWithoutHardcodedLoaderIds() {
        String rootBuild = read(ROOT.resolve("build.gradle"));
        assertTrue(rootBuild.contains(
                "dependsOn declaredLoaders.collect { \"${it.projectPath}:verifyPackageJar\" }"));
        assertTrue(rootBuild.contains("into \"mods/${loader.id}\""));
        assertTrue(rootBuild.contains("from(loaderArchives[loader.id])"));
        for (LoaderArchitectureContract.Loader loader : MANIFEST.loaders()) {
            assertFalse(rootBuild.contains("into 'mods/" + loader.id() + "'"),
                    "release staging must enumerate the loader manifest");
            assertFalse(rootBuild.contains("into \"mods/" + loader.id() + "\""),
                    "release staging must enumerate the loader manifest");
        }
    }

    @Test
    void loaderLayersDoNotDuplicateSharedTypesOrConstructTheRenderer() {
        Set<Path> sharedTypes = new HashSet<>();
        sharedTypes.addAll(relativeJavaSources(MINECRAFT.resolve("main/java")));
        sharedTypes.addAll(relativeJavaSources(ROOT.resolve("renderer-core/src")));

        Set<String> duplicateTypes = new TreeSet<>();
        Set<String> rendererOwners = new TreeSet<>();
        for (Path loaderRoot : loaderSourceRoots()) {
            for (Path relative : relativeJavaSources(loaderRoot)) {
                if (sharedTypes.contains(relative)) {
                    duplicateTypes.add(loaderRoot.getParent().getParent().relativize(
                            loaderRoot.resolve(relative)).toString());
                }
                if (CANONICAL_RENDERER_CONSTRUCTION.matcher(read(loaderRoot.resolve(relative))).find()) {
                    rendererOwners.add(loaderRoot.getParent().getParent().relativize(
                            loaderRoot.resolve(relative)).toString());
                }
            }
        }

        assertEquals(Set.of(), duplicateTypes,
                "loader layers must adapt shared types, not replace them");
        assertEquals(Set.of(), rendererOwners,
                "only shared Minecraft integration may construct RemixRenderer");
    }

    @Test
    void everyManifestLoaderSuppliesBootstrapServicesAndMetadata() {
        for (LoaderArchitectureContract.Loader loader : MANIFEST.loaders()) {
            Path sourceRoot = loader.modulePath().resolve("src/main/java");
            boolean suppliesServices = false;
            boolean suppliesBootstrap = false;
            for (Path relative : relativeJavaSources(sourceRoot)) {
                String source = read(sourceRoot.resolve(relative));
                suppliesServices |=
                        source.contains("implements MinecraftPlatform.Services");
                suppliesBootstrap |= source.contains("MinecraftClientRuntime");
            }
            assertTrue(suppliesServices,
                    loader.id() + " must supply MinecraftPlatform.Services");
            assertTrue(suppliesBootstrap,
                    loader.id() + " must bootstrap MinecraftClientRuntime");

            Path metadata = loader.modulePath().resolve("src/main/resources")
                    .resolve(loader.metadataResource());
            assertTrue(Files.isRegularFile(metadata),
                    loader.id() + " must supply " + loader.metadataResource());
        }
    }

    @Test
    void loaderSourcesStayInTheirLoaderPackages() {
        for (LoaderArchitectureContract.Loader loader : MANIFEST.loaders()) {
            assertLoaderPackage(
                    loader.modulePath().resolve("src/main/java"),
                    "package com.nvidia.rtxremix."
                            + loader.id().replace('-', '_'));
        }
    }

    @Test
    void frameLifecycleIsOwnedByTheSharedMinecraftHook() {
        Set<String> loaderOwners = new TreeSet<>();
        for (Path loaderRoot : loaderSourceRoots()) {
            for (Path relative : relativeJavaSources(loaderRoot)) {
                String source = read(loaderRoot.resolve(relative));
                if (source.contains("beginWorldRenderFrame(")
                        || source.contains("markFrameReady(")) {
                    loaderOwners.add(loaderRoot.getParent().getParent().relativize(
                            loaderRoot.resolve(relative)).toString());
                }
            }
        }
        assertEquals(Set.of(), loaderOwners,
                "frame start/end policy belongs in the shared render hook");

        String worldRendererMixin = read(MINECRAFT.resolve(
                "main/java/com/nvidia/rtxremix/minecraft/mixin/WorldRendererMixin.java"));
        assertTrue(worldRendererMixin.contains("MinecraftRuntime.beginWorldRenderFrame();"));
        assertTrue(worldRendererMixin.contains("MinecraftRuntime.markFrameReady();"));
    }

    @Test
    void rendererPublicationAndLoaderJoinsRespectStartupOrdering() {
        String runtime = read(MINECRAFT.resolve(
                "main/java/com/nvidia/rtxremix/minecraft/"
                        + "MinecraftClientRuntime.java"));
        int initialize = runtime.indexOf("renderer.initialize();");
        int applyPreset = runtime.indexOf("applyLaunchPreset(renderer);", initialize);
        int publish = runtime.indexOf(
                "MinecraftRuntime.publishRenderer(renderer);", applyPreset);
        assertTrue(initialize >= 0 && applyPreset > initialize && publish > applyPreset,
                "a renderer must not become globally visible before startup completes");
        assertTrue(runtime.contains("retainFailedRenderer(renderer);"));
        assertTrue(runtime.contains("pendingJoin = true;"));

        Set<String> checkedLoaders = new TreeSet<>();
        for (LoaderArchitectureContract.Loader loader : MANIFEST.loaders()) {
            Path sourceRoot = loader.modulePath().resolve("src/main/java");
            for (Path relative : relativeJavaSources(sourceRoot)) {
                String source = read(sourceRoot.resolve(relative));
                int join = source.indexOf("RUNTIME.onJoin(client);");
                if (join < 0) {
                    continue;
                }
                int start = source.lastIndexOf("startClient(client);", join);
                assertTrue(start >= 0 && start < join,
                        loader.id()
                                + " must initialize the canonical runtime before world join");
                checkedLoaders.add(loader.id());
            }
        }
        assertFalse(checkedLoaders.isEmpty(),
                "at least one event-driven loader must verify join startup ordering");
    }

    private static void assertNoMatchingImports(
            Path sourceRoot, Pattern forbidden, String message) {
        Set<String> offenders = new TreeSet<>();
        for (Path relative : relativeJavaSources(sourceRoot)) {
            if (forbidden.matcher(read(sourceRoot.resolve(relative))).find()) {
                offenders.add(relative.toString());
            }
        }
        assertEquals(Set.of(), offenders, message);
    }

    private static void assertLoaderPackage(Path sourceRoot, String packagePrefix) {
        Set<String> offenders = new TreeSet<>();
        for (Path relative : relativeJavaSources(sourceRoot)) {
            if (!read(sourceRoot.resolve(relative)).contains(packagePrefix)) {
                offenders.add(relative.toString());
            }
        }
        assertEquals(Set.of(), offenders,
                "loader-owned sources must stay in their loader package");
    }

    private static Set<Path> relativeJavaSources(Path sourceRoot) {
        assertTrue(Files.isDirectory(sourceRoot), "missing source root " + sourceRoot);
        Set<Path> sources = new HashSet<>();
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .map(sourceRoot::relativize)
                    .forEach(sources::add);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to inspect " + sourceRoot, e);
        }
        return sources;
    }

    private static List<Path> loaderSourceRoots() {
        return MANIFEST.loaders().stream()
                .map(loader -> loader.modulePath().resolve("src/main/java"))
                .toList();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + path, e);
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isRegularFile(current.resolve("project-state.json"))) {
            return current;
        }
        Path parent = current.getParent();
        if (parent != null && Files.isRegularFile(parent.resolve("project-state.json"))) {
            return parent;
        }
        throw new IllegalStateException("tests must run from the repository root or a loader module");
    }
}

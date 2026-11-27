/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Executable contract shared by every current loader adapter.
 *
 * <p>It deliberately describes only bootstrap inputs that the canonical
 * Minecraft runtime requires: platform services and resources that must be
 * visible from the production class loader. Loader event wiring remains in the
 * loader modules.</p>
 */
public final class LoaderArchitectureContract {
    public static final String MANIFEST_SCHEMA = "multi-version/v1";
    private static final List<String> SHARED_RESOURCES = List.of(
            "rtx-remix-renderer.mixins.json",
            "benchmark.properties");
    private static final Pattern LOADER_ID =
            Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    private LoaderArchitectureContract() {
    }

    /** Validates the installed service object and its production resources. */
    public static void validateInstalled(MinecraftPlatform.Services services) {
        Objects.requireNonNull(services, "services");
        String loaderId = requireText(services.loaderId(), "loaderId");
        requirePath(services.gameDirectory(), "gameDirectory");
        requirePath(services.configDirectory(), "configDirectory");
        requirePath(services.modRoot(), "modRoot");
        requireText(services.modVersion(), "modVersion");
        String metadataResource =
                requireResourcePath(services.metadataResource(), "metadataResource");
        for (String resource : requiredRuntimeResources(metadataResource)) {
            if (LoaderArchitectureContract.class.getClassLoader()
                    .getResource(resource) == null) {
                throw new IllegalStateException(
                        "Loader '" + loaderId
                                + "' is missing required runtime resource " + resource);
            }
        }
    }

    /**
     * Discovers the authoritative loader catalog used by generic architecture
     * checks. Loader IDs are object keys, so adding a loader requires no Java
     * registry change.
     */
    public static Manifest discover(Path manifestPath) {
        return discover(manifestPath, null);
    }

    /**
     * Discovers one Minecraft target from the repository-wide version catalog.
     * A null version selects {@code defaultMinecraftVersion}.
     */
    public static Manifest discover(Path manifestPath, String requestedVersion) {
        Objects.requireNonNull(manifestPath, "manifestPath");
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(manifestPath)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Cannot read loader manifest " + manifestPath, exception);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Invalid loader manifest " + manifestPath, exception);
        }

        String schema = requireText(root, "schema", "<root>");
        if (!MANIFEST_SCHEMA.equals(schema)) {
            throw new IllegalStateException(
                    "project-state.json schema must be '" + MANIFEST_SCHEMA
                            + "', got '" + schema + "'");
        }
        JsonObject versions = requireObject(root, "versions", "<root>");
        String selectedVersion = requestedVersion == null
                ? requireText(root, "defaultMinecraftVersion", "<root>")
                : requireText(requestedVersion, "minecraftVersion");
        JsonElement selected = versions.get(selectedVersion);
        if (selected == null || !selected.isJsonObject()) {
            throw new IllegalStateException(
                    "project-state.json does not declare Minecraft version '"
                            + selectedVersion + "'");
        }
        JsonObject version = selected.getAsJsonObject();
        JsonObject minecraft = requireObject(
                version, "minecraft", "versions." + selectedVersion);
        String minecraftModule = requireResourcePath(
                requireText(
                        minecraft,
                        "module",
                        "versions." + selectedVersion + ".minecraft"),
                "versions." + selectedVersion + ".minecraft.module");
        String expectedMinecraftModule = "versions/" + selectedVersion + "/minecraft";
        if (!minecraftModule.equals(expectedMinecraftModule)) {
            throw new IllegalStateException(
                    "project-state.json Minecraft module must be '"
                            + expectedMinecraftModule + "'");
        }
        String minecraftVersion =
                requireText(
                        minecraft,
                        "version",
                        "versions." + selectedVersion + ".minecraft");
        if (!minecraftVersion.equals(selectedVersion)) {
            throw new IllegalStateException(
                    "project-state.json version key and minecraft.version disagree");
        }
        String minecraftVersionRange =
                requireText(
                        minecraft,
                        "versionRange",
                        "versions." + minecraftVersion + ".minecraft");
        int javaTarget = requirePositiveInteger(
                minecraft,
                "javaTarget",
                "versions." + minecraftVersion + ".minecraft");
        JsonObject rawLoaders = requireObject(
                version, "loaders", "versions." + minecraftVersion);
        if (rawLoaders.isEmpty()) {
            throw new IllegalStateException(
                    "project-state.json versions." + minecraftVersion
                            + ".loaders must declare at least one loader");
        }

        Path repositoryRoot = manifestPath.toAbsolutePath().normalize().getParent();
        List<Loader> loaders = new ArrayList<Loader>(rawLoaders.size());
        for (var entry : rawLoaders.entrySet()) {
            String id = entry.getKey();
            if (!LOADER_ID.matcher(id).matches()) {
                throw new IllegalStateException(
                        "project-state.json loader key '" + id
                                + "' must be a lowercase slug");
            }
            if (!entry.getValue().isJsonObject()) {
                throw new IllegalStateException(
                        "project-state.json versions." + minecraftVersion
                                + ".loaders." + id + " must be an object");
            }
            JsonObject loader = entry.getValue().getAsJsonObject();
            String module = requireResourcePath(
                    requireText(loader, "module", "loaders." + id),
                    "loaders." + id + ".module");
            String expectedModule = "versions/" + minecraftVersion + "/" + id;
            if (!module.equals(expectedModule)) {
                throw new IllegalStateException(
                        "project-state.json loader module must be '"
                                + expectedModule + "'");
            }
            String metadata = requireResourcePath(
                    requireText(loader, "metadataFile", "loaders." + id),
                    "loaders." + id + ".metadataFile");
            loaders.add(new Loader(
                    id,
                    repositoryRoot.resolve(module).normalize(),
                    metadata));
        }
        return new Manifest(
                minecraftVersion,
                minecraftVersionRange,
                javaTarget,
                List.copyOf(loaders));
    }

    /** Resource inventory that one loader artifact must carry at runtime. */
    public static List<String> requiredRuntimeResources(String metadataResource) {
        String metadata =
                requireResourcePath(metadataResource, "metadataResource");
        ArrayList<String> result =
                new ArrayList<String>(SHARED_RESOURCES.size() + 1);
        result.addAll(SHARED_RESOURCES);
        result.add(metadata);
        return List.copyOf(result);
    }

    private static JsonObject requireObject(
            JsonObject owner, String key, String field) {
        JsonElement value = owner.get(key);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalStateException(
                    "project-state.json " + field + "." + key
                            + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static String requireText(
            JsonObject owner, String key, String field) {
        JsonElement value = owner.get(key);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalStateException(
                    "project-state.json " + field + "." + key
                            + " must be a non-empty string");
        }
        return requireText(value.getAsString(), field + "." + key);
    }

    private static int requirePositiveInteger(
            JsonObject owner, String key, String field) {
        JsonElement value = owner.get(key);
        try {
            int result = value == null ? 0 : value.getAsInt();
            if (result > 0) {
                return result;
            }
        } catch (RuntimeException ignored) {
            // The field-specific error below is more useful.
        }
        throw new IllegalStateException(
                "project-state.json " + field + "." + key
                        + " must be a positive integer");
    }

    private static Path requirePath(Path value, String name) {
        return Objects.requireNonNull(value, name);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Minecraft platform service '" + name + "' is blank");
        }
        return value;
    }

    private static String requireResourcePath(String value, String name) {
        String normalized = requireText(value, name).replace('\\', '/');
        Path path = Path.of(normalized);
        if (path.isAbsolute() || path.normalize().startsWith("..")) {
            throw new IllegalStateException(name + " must be repository-relative");
        }
        return normalized;
    }

    public record Loader(String id, Path modulePath, String metadataResource) {
    }

    public record Manifest(
            String minecraftVersion,
            String minecraftVersionRange,
            int javaTarget,
            List<Loader> loaders) {
        public Loader loader(String id) {
            for (Loader loader : loaders) {
                if (loader.id().equals(id)) {
                    return loader;
                }
            }
            throw new IllegalArgumentException(
                    "project-state.json does not declare loader '" + id + "'");
        }
    }
}

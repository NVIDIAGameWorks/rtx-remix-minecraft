# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import copy

import pytest

from rtx_tools.loader_registry import ManifestError, parse_manifest


def version() -> dict:
    return {
        "supportTier": "primary",
        "defaultLoader": "fabric",
        "minecraft": {
            "version": "1.21.1",
            "module": "versions/1.21.1/minecraft",
            "versionRange": "[1.21.1,1.21.2)",
            "javaTarget": 21,
            "dataVersion": 3955,
            "hostGraphicsBackend": "opengl",
        },
        "loaders": {
            "fabric": {
                "displayName": "Fabric",
                "module": "versions/1.21.1/fabric",
                "projectPath": ":versions:1.21.1:fabric",
                "provider": "fabric",
                "supportTier": "active",
                "gradlePlugin": {"id": "fabric-loom", "version": "1.9.2"},
                "developmentArtifact": {
                    "directory": "versions/1.21.1/fabric/build/libs",
                    "pattern": "rtx-remix-renderer-*.jar",
                },
                "installedJar": "rtx-remix-renderer-fabric.jar",
                "builtinResourcePack": "fabric",
                "gradle": {
                    "build": ":versions:1.21.1:fabric:build",
                    "test": ":versions:1.21.1:fabric:test",
                    "package": ":versions:1.21.1:fabric:package",
                    "prepare": [":versions:1.21.1:fabric:stageFabricApi"],
                },
                "prism": {
                    "uid": "net.fabricmc.fabric-loader",
                    "name": "Fabric Loader",
                    "version": "0.16.5",
                },
                "loader": {
                    "coordinate": "net.fabricmc:fabric-loader",
                    "version": "0.16.5",
                    "versionRange": ">=0.16.5",
                },
                "api": {
                    "coordinate": "net.fabricmc.fabric-api:fabric-api",
                    "version": "0.116.10+1.21.1",
                    "versionRange": ">=0.116.10+1.21.1",
                },
                "metadataFile": "fabric.mod.json",
                "archiveBaseName": "rtx-remix-renderer",
                "packageSourceTask": "remapJar",
            },
            "example": {
                "displayName": "Example Loader",
                "module": "versions/1.21.1/example",
                "projectPath": ":versions:1.21.1:example",
                "provider": "example",
                "supportTier": "experimental",
                "gradlePlugin": {"id": "example-loader", "version": "1.0.0"},
                "developmentArtifact": {
                    "directory": "versions/1.21.1/example/out",
                    "pattern": "*-runnable.jar",
                },
                "installedJar": "example-loader.jar",
                "builtinResourcePack": "example_resources",
                "gradle": {
                    "build": ":versions:1.21.1:example:assemble",
                    "test": ":versions:1.21.1:example:check",
                    "package": ":versions:1.21.1:example:bundle",
                },
                "prism": {
                    "uid": "org.example.loader",
                    "name": "Example",
                    "version": "1.0.0",
                },
                "loader": {
                    "coordinate": "org.example:loader",
                    "version": "1.0.0",
                    "versionRange": ">=1.0.0",
                },
                "metadataFile": "META-INF/example.toml",
                "archiveBaseName": "rtx-remix-renderer-example",
                "packageSourceTask": "bundleJar",
            },
        },
    }


def manifest() -> dict:
    old = version()
    maintenance = copy.deepcopy(old)
    maintenance["supportTier"] = "maintenance"
    maintenance["minecraft"]["version"] = "1.20.1"
    maintenance["minecraft"]["module"] = "versions/1.20.1/minecraft"
    maintenance["minecraft"]["versionRange"] = "[1.20.1,1.20.2)"
    for loader_id, loader in maintenance["loaders"].items():
        old_module = loader["module"]
        loader["module"] = f"versions/1.20.1/{loader_id}"
        loader["projectPath"] = f":versions:1.20.1:{loader_id}"
        loader["developmentArtifact"]["directory"] = loader["developmentArtifact"]["directory"].replace(
            old_module, loader["module"], 1
        )
        for task_name in ("build", "test", "package"):
            loader["gradle"][task_name] = loader["gradle"][task_name].replace(
                ":versions:1.21.1:", ":versions:1.20.1:", 1
            )
        loader["gradle"]["prepare"] = [
            task.replace(":versions:1.21.1:", ":versions:1.20.1:", 1) for task in loader["gradle"].get("prepare", [])
        ]
    return {
        "schema": "multi-version/v1",
        "defaultMinecraftVersion": "1.21.1",
        "mod": {
            "id": "rtx-remix-renderer",
            "group": "com.nvidia.rtxremix",
            "version": "0.1.1",
            "displayName": "RTX Remix for Minecraft",
        },
        "versions": {"1.21.1": old, "1.20.1": maintenance},
    }


def test_registry_preserves_manifest_order_and_declarative_behavior():
    registry = parse_manifest(manifest())
    assert registry.ids == ("fabric", "example")
    assert registry.default_id == "fabric"
    assert registry.minecraft_version == "1.21.1"
    assert registry.java_target == 21
    assert registry.available_versions == ("1.21.1", "1.20.1")
    assert registry.default_version == "1.21.1"
    example = registry.get(" EXAMPLE ")
    assert example.display_name == "Example Loader"
    assert example.development_jar_pattern == "*-runnable.jar"
    assert example.build_task == ":versions:1.21.1:example:assemble"
    assert example.prepare_tasks == ()
    assert example.prism.uid == "org.example.loader"
    assert example.coordinate == "org.example:loader"


def test_registry_selects_explicit_then_environment_then_default(monkeypatch):
    value = manifest()
    monkeypatch.setenv("RTXREMIX_MINECRAFT_VERSION", "1.20.1")
    assert parse_manifest(value).minecraft_version == "1.20.1"
    assert parse_manifest(value, minecraft_version="1.21.1").minecraft_version == "1.21.1"
    monkeypatch.delenv("RTXREMIX_MINECRAFT_VERSION")
    assert parse_manifest(value).minecraft_version == "1.21.1"


def test_registry_rejects_unknown_version():
    with pytest.raises(ManifestError, match="Unknown Minecraft version '1.19.4'"):
        parse_manifest(manifest(), minecraft_version="1.19.4")


@pytest.mark.parametrize(
    ("mutate", "message"),
    [
        (lambda value: value.update(schema="other/v1"), "schema must be 'multi-version/v1'"),
        (lambda value: value.update(versions=[]), "field 'versions' must be an object"),
        (
            lambda value: value["versions"]["1.21.1"].update(defaultLoader="missing"),
            "is not present in loaders",
        ),
        (
            lambda value: value["versions"]["1.21.1"]["loaders"]["fabric"]["developmentArtifact"].update(
                directory="../outside"
            ),
            "must be a repository-relative path",
        ),
        (
            lambda value: value["versions"]["1.21.1"]["loaders"]["fabric"]["gradle"].update(prepare=":fabric:prepare"),
            "must be an array of strings",
        ),
        (
            lambda value: value["versions"]["1.21.1"]["loaders"]["fabric"].update(projectPath=":wrong"),
            "projectPath.*must match module",
        ),
        (
            lambda value: value["versions"]["1.21.1"]["loaders"]["fabric"]["prism"].update(version="different"),
            "prism.version.*must match loader.version",
        ),
        (
            lambda value: value["versions"]["1.21.1"]["loaders"]["example"].update(
                installedJar="rtx-remix-renderer-fabric.jar"
            ),
            "installedJar values must be unique",
        ),
    ],
)
def test_registry_validation_names_the_bad_field(mutate, message):
    value = copy.deepcopy(manifest())
    mutate(value)
    with pytest.raises(ManifestError, match=message):
        parse_manifest(value)

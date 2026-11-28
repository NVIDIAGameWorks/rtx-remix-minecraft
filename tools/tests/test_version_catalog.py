# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import json

import pytest

from rtx_tools import version_catalog
from rtx_tools.loader_registry import ManifestError


def valid_version() -> dict:
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
                    "pattern": "*.jar",
                },
                "installedJar": "rtx-remix-renderer-fabric.jar",
                "builtinResourcePack": "fabric",
                "gradle": {
                    "build": ":versions:1.21.1:fabric:build",
                    "test": ":versions:1.21.1:fabric:test",
                    "package": ":versions:1.21.1:fabric:package",
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
                "metadataFile": "fabric.mod.json",
                "archiveBaseName": "rtx-remix-renderer",
                "packageSourceTask": "remapJar",
            }
        },
    }


def valid_manifest() -> dict:
    return {
        "schema": "multi-version/v1",
        "defaultMinecraftVersion": "1.21.1",
        "mod": {
            "id": "rtx-remix-renderer",
            "group": "com.nvidia.rtxremix",
            "version": "0.1.1",
            "displayName": "RTX Remix for Minecraft",
        },
        "versions": {"1.21.1": valid_version()},
    }


def test_version_catalog_reports_current_checkout_versions(tmp_path):
    manifest = valid_manifest()
    second = json.loads(json.dumps(manifest["versions"]["1.21.1"]))
    second["supportTier"] = "maintenance"
    second["minecraft"]["version"] = "1.20.1"
    second["minecraft"]["module"] = "versions/1.20.1/minecraft"
    second["minecraft"]["versionRange"] = "[1.20.1,1.20.2)"
    second["loaders"]["fabric"]["module"] = "versions/1.20.1/fabric"
    second["loaders"]["fabric"]["projectPath"] = ":versions:1.20.1:fabric"
    second["loaders"]["fabric"]["developmentArtifact"]["directory"] = "versions/1.20.1/fabric/build/libs"
    second["loaders"]["fabric"]["gradle"] = {
        "build": ":versions:1.20.1:fabric:build",
        "test": ":versions:1.20.1:fabric:test",
        "package": ":versions:1.20.1:fabric:package",
    }
    manifest["versions"]["1.20.1"] = second
    (tmp_path / "project-state.json").write_text(json.dumps(manifest), encoding="utf-8")

    value = version_catalog.report(tmp_path)

    assert value["schema"] == "version-catalog-report/v1"
    assert value["catalogSchema"] == "multi-version/v1"
    assert value["defaultMinecraftVersion"] == "1.21.1"
    assert value["mod"] == {
        "id": "rtx-remix-renderer",
        "version": "0.1.1",
        "displayName": "RTX Remix for Minecraft",
    }
    assert value["versions"][0] == {
        "version": "1.21.1",
        "supportTier": "primary",
        "versionRange": "[1.21.1,1.21.2)",
        "javaTarget": 21,
        "dataVersion": 3955,
        "hostGraphicsBackend": "opengl",
        "defaultLoader": "fabric",
        "loaders": [
            {
                "id": "fabric",
                "displayName": "Fabric",
                "supportTier": "active",
                "coordinate": "net.fabricmc:fabric-loader",
                "version": "0.16.5",
                "versionRange": ">=0.16.5",
            }
        ],
    }
    assert value["versions"][1]["version"] == "1.20.1"
    assert value["versions"][1]["supportTier"] == "maintenance"


def test_version_catalog_validates_every_version(tmp_path):
    value = valid_manifest()
    invalid = json.loads(json.dumps(value["versions"]["1.21.1"]))
    invalid["minecraft"]["version"] = "1.20.1"
    invalid["loaders"] = {}
    value["versions"]["1.20.1"] = invalid
    (tmp_path / "project-state.json").write_text(json.dumps(value), encoding="utf-8")

    with pytest.raises(ManifestError, match="at least one loader"):
        version_catalog.report(tmp_path)

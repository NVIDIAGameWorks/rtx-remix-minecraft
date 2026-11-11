# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Report the Minecraft versions and loaders in the current checkout."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from .loader_registry import MANIFEST_NAME, SCHEMA, parse_manifest


def _entry(value: Any, version: str, *, source: str) -> dict[str, Any]:
    registry = parse_manifest(value, source=source, minecraft_version=version)
    return {
        "version": registry.minecraft_version,
        "supportTier": registry.support_tier,
        "versionRange": registry.minecraft_version_range,
        "javaTarget": registry.java_target,
        "dataVersion": registry.minecraft_data_version,
        "hostGraphicsBackend": registry.host_graphics_backend,
        "defaultLoader": registry.default_id,
        "loaders": [
            {
                "id": loader.id,
                "displayName": loader.display_name,
                "supportTier": loader.support_tier,
                "coordinate": loader.coordinate,
                "version": loader.version,
                "versionRange": loader.version_range,
                **(
                    {
                        "api": {
                            "coordinate": loader.api_coordinate,
                            "version": loader.api_version,
                            "versionRange": loader.api_version_range,
                        }
                    }
                    if loader.api_coordinate is not None
                    else {}
                ),
            }
            for loader in registry.loaders
        ],
    }


def report(project_dir: Path) -> dict[str, Any]:
    path = project_dir / MANIFEST_NAME
    value = json.loads(path.read_text(encoding="utf-8-sig"))
    default = parse_manifest(value, source=str(path))
    return {
        "schema": "version-catalog-report/v1",
        "catalogSchema": SCHEMA,
        "mod": {
            "id": default.mod_id,
            "version": default.mod_version,
            "displayName": default.mod_display_name,
        },
        "defaultMinecraftVersion": default.default_version,
        "versions": [_entry(value, version, source=str(path)) for version in default.available_versions],
    }


def print_report(value: dict[str, Any]) -> None:
    print(
        f"Version catalog: {value['mod']['displayName']} {value['mod']['version']} "
        f"(default Minecraft {value['defaultMinecraftVersion']})"
    )
    for entry in value["versions"]:
        loader_pins = ", ".join(f"{loader['id']} {loader['version']}" for loader in entry["loaders"])
        default = " (default)" if entry["version"] == value["defaultMinecraftVersion"] else ""
        print(
            f"  Minecraft {entry['version']}{default}: {entry['supportTier']}; "
            f"Java {entry['javaTarget']}; {entry['hostGraphicsBackend']}; "
            f"{loader_pins}; default loader {entry['defaultLoader']}"
        )

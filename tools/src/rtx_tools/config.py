# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Shared defaults and machine-local configuration."""

from __future__ import annotations

import copy
import json
import os
from pathlib import Path
from typing import Any

from . import loader_registry
from .benchmark import util


def merge_config(base: dict, override: dict) -> dict:
    merged = copy.deepcopy(base)
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(merged.get(key), dict):
            merged[key] = merge_config(merged[key], value)
        else:
            merged[key] = copy.deepcopy(value)
    return merged


# Keys that describe one pinned package. A layer naming a different world or
# art pack starts that pin afresh rather than inheriting them from below.
PIN_KEYS = {"world": ("version", "camera", "package"), "resourcePack": ("version", "package")}


def merge_layer(base: dict, layer: dict) -> dict:
    """Merge one configuration layer over another, keeping each fixture pin whole."""
    base = dict(base)
    for key, pin_keys in PIN_KEYS.items():
        pin, selection = base.get(key), layer.get(key)
        if not isinstance(pin, dict) or not isinstance(selection, dict):
            continue
        # Names compare stripped, as the pin lookups read them.
        if "name" in selection and str(selection["name"] or "").strip() != str(pin.get("name") or "").strip():
            base[key] = {setting: value for setting, value in pin.items() if setting not in pin_keys}
    return merge_config(base, layer)


def local_config_path() -> Path:
    return util.SHARED_CONFIG_PATH.with_name("config.local.json")


def _read_config(path: Path, *, optional: bool = False) -> dict:
    if optional and not path.exists():
        return {}
    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError) as exc:
        raise RuntimeError(f"Cannot read configuration {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise RuntimeError(f"Configuration {path} must contain a JSON object")
    return value


def load_config() -> dict:
    """Merge shared, benchmark, then machine-local configuration."""
    local = _read_config(local_config_path(), optional=True)
    config = merge_layer(
        _read_config(util.SHARED_CONFIG_PATH),
        _read_config(util.BENCHMARK_DIR / "config.json"),
    )
    return merge_layer(config, local)


def update_local_config(override: dict) -> None:
    path = local_config_path()
    util.write_json(path, merge_config(_read_config(path, optional=True), override))


def require_setting(config: dict, key: str) -> str:
    value: Any = config
    for part in key.split("."):
        value = value.get(part) if isinstance(value, dict) else None
    if not isinstance(value, str) or not value.strip():
        raise RuntimeError(f"Missing setting '{key}'. Set it in tools/config.local.json.")
    return value.strip()


_RUNTIME_MOD_LOADER: str | None = None


def set_runtime_mod_loader(value: str | None) -> None:
    """Set the explicit CLI loader for this process without changing configuration files."""
    global _RUNTIME_MOD_LOADER
    _RUNTIME_MOD_LOADER = value


def mod_loader(config: dict) -> str:
    """The selected loader: explicit CLI, environment, merged config, then the manifest default."""
    registry = loader_registry.load_registry()
    value = (
        str(
            _RUNTIME_MOD_LOADER
            if _RUNTIME_MOD_LOADER is not None
            else os.environ.get("RTXREMIX_MOD_LOADER", config.get("modLoader", registry.default_id))
        )
        .strip()
        .lower()
    )
    try:
        return registry.get(value).id
    except loader_registry.ManifestError as exc:
        raise RuntimeError(f"modLoader must name a loader from project-state.json, got {value!r}: {exc}") from exc

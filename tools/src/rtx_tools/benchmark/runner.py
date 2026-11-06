# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

from __future__ import annotations

import copy
import datetime as dt
import json
import locale
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from collections.abc import Iterator, Mapping
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import IO, Any, cast

from .. import client
from .. import config as tools_config
from .. import sdk as runtime_sdk
from . import catalog, gitmeta, gpu_telemetry, machine, tracy, util

PROCESS_HANDLES: dict[int, tuple[IO[str], ...]] = {}
BENCHMARK_SAVE_NAME = "rtx-benchmark"
BENCHMARK_STATUS_FILE = "benchmark_status.json"
BENCHMARK_RTX_CONFIG_PREFIX = "benchmark-rtx-"
RTX_CONFIG_PATHS_ENV = "DXVK_RTX_CONFIG_FILE"
ASYNC_COMPILATION_ENV = "RTX_ENABLE_ASYNC_COMPILATION"
BENCHMARK_BLOCKING_OPTIONS = {
    "rtx.initializer.asyncShaderFinalizing",
    "rtx.shader.enableAsyncCompilation",
}
BENCHMARK_RTX_CONFIG = """\
# Temporary runner-owned overrides for measured benchmark processes only.
rtx.initializer.asyncShaderFinalizing = false
rtx.shader.enableAsyncCompilation = false
rtx.dlfg.enable = false
"""
DEFAULT_BENCHMARK_CAMERA = "simple"
WORLD_DIR_NAME = "world"
CAMERAS_DIR_NAME = "cameras"
CAMERA_PATH_VERSION = 1
CAMERA_PATH_INTERPOLATION = "catmull-rom"
CAMERA_PATH_ROOT_FIELDS = {"version", "interpolation", "waypoints"}
CAMERA_PATH_WAYPOINT_FIELDS = {"timeMs", "x", "y", "z", "yaw", "pitch", "timeOfDay"}
CAMERA_PATH_REQUIRED_WAYPOINT_FIELDS = {"timeMs", "x", "y", "z", "yaw", "pitch"}
CAMERA_PATH_MAX_DURATION_MS = (2**63 - 1) // 1_000_000
RESOURCEPACKS_DIR_NAME = "resourcepacks"
# A quality preset names one of Remix's graphics tiers, which the mod writes to
# rtx.graphicsPreset for that session and nothing else, so the preset never
# changes the scene the harness pins in options.txt and rtxremix.json. Custom
# passes no preset. Display order, cheapest tier first, matches the mod's.
CUSTOM_PRESET = "Custom"
PRESET_NAMES = ("low", "medium", "high", "ultra", CUSTOM_PRESET)
# The mod's settings file under <gameDir>/config/, and the only format version
# RtxSettingsStore reads; it ignores a file of any other version.
RTX_SETTINGS_FILE = "rtxremix.json"
RTX_SETTINGS_VERSION = 2
# Not a mod setting: older harness versions pinned the preset under this id, and
# the mod warns about an unknown id on every load until something removes it.
STALE_RTX_PRESET_SETTING_ID = "preset"
WORLD_CONFIG_FILE = "config.json"
LEGACY_WORLD_OPTIONS_FILE = "world-options.json"
WORLD_CONFIG_IGNORED_KEYS = {"world", "defaultWorld"}
WORLD_PACKAGE_PREFIX = "rtx_remix_minecraft_world_"
ARCH_ALIASES = {
    "win64": "win64",
    "x64": "win64",
    "x86_64": "win64",
    "amd64": "win64",
    "arm64": "arm64",
    "aarch64": "arm64",
}
FAILURE_SIGNATURES = (
    ("VK_ERROR_DEVICE_LOST", "DeviceLost"),
    ("EXCEPTION_ACCESS_VIOLATION", "AccessViolation"),
    ("A fatal error has been detected by the Java Runtime Environment", "JvmCrash"),
    # JNI/native ABI mismatch between the runtime and rtxremix-java.
    ("java.lang.UnsatisfiedLinkError", "NativeLinkError"),
    ("---- Minecraft Crash Report ----", "MinecraftCrash"),
    # BenchmarkController emits this when a parsed auto trigger cannot start.
    # Treat it as terminal instead of polling for benchmark.json until timeout.
    ("Cannot start benchmark:", "BenchmarkStartupRejected"),
)
NTSTATUS_RE = re.compile(r"NTSTATUS\s+(0x[0-9A-Fa-f]{8})")
PROCESS_EXIT_RE = re.compile(r"finished with non-zero exit value\s+(-?\d+)")
NTSTATUS_FAILURE_REASONS = {
    "0xC0000005": "AccessViolation",
    "0xC0000374": "NativeHeapCorruption",
}
DEFAULT_WINDOWS_CRASH_DUMP_DIR = Path(r"C:\CrashDumps")
CRASH_DUMP_DIR_ENV = "BENCHMARK_CRASH_DUMP_DIR"
CRASH_DUMP_WAIT_ENV = "BENCHMARK_CRASH_DUMP_WAIT_SECONDS"
CONTINUE_AFTER_FAILURE_ENV = "BENCHMARK_CONTINUE_AFTER_RUN_FAILURE"
# Live-stall snapshot, kept beside the kill-time diagnostics so a run that
# recovers on its own still leaves evidence of the stall behind.
STALL_PREVIEW_DIR = "diagnostics-preview"
THREAD_DUMP_SPACING_SECONDS = 3
# Below this, a frame rendered nothing. An absolute number on purpose: this used
# to be derived from render.maxFps as "just above the cap", but 260 is
# Minecraft's Unlimited sentinel rather than a 260 fps cap, so there was no cap
# for real frames to sit above. A path-traced frame at benchmark settings is
# 10 ms or more, so 3.5 ms is far below anything legitimate and far above the
# near-zero frames drawn under the world-load overlay.
DEGENERATE_FRAME_FLOOR_MS = 3.5


@dataclass(frozen=True)
class BenchmarkSelection:
    world: str
    camera: str
    world_dir: Path
    world_path: Path
    cameras_dir: Path
    camera_path: Path
    version: str | None = None


@dataclass(frozen=True)
class ResourcePackSource:
    name: str
    file_name: str
    source_path: Path


def normalize_runtime_arch(value: object) -> str | None:
    text = str(value or "").strip().lower().replace("-", "_")
    return ARCH_ALIASES.get(text)


def normalize_arch(value: object, label: str = "architecture") -> str:
    arch = normalize_runtime_arch(value)
    if arch:
        return arch
    raise RuntimeError(f"invalid {label}: {value!r}; expected win64 or arm64")


def java_executable() -> str:
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        executable = "java.exe" if os.name == "nt" else "java"
        candidate = Path(java_home) / "bin" / executable
        if candidate.exists():
            return str(candidate)
    return "java"


def java_runtime_arch() -> str | None:
    try:
        result = subprocess.run(
            [java_executable(), "-XshowSettings:properties", "-version"],
            capture_output=True,
            check=False,
            text=True,
            encoding=locale.getencoding(),
            errors="replace",
            timeout=15,
        )
    except OSError, subprocess.TimeoutExpired:
        return None

    output = f"{result.stdout}\n{result.stderr}"
    match = re.search(r"(?m)^\s*os\.arch\s*=\s*(\S+)\s*$", output)
    return match.group(1) if match else None


def windows_process_arch() -> str | None:
    return os.environ.get("PROCESSOR_ARCHITEW6432") or os.environ.get("PROCESSOR_ARCHITECTURE")


def host_arch(override: object = None) -> str:
    if override:
        return normalize_arch(override)
    env_override = os.environ.get("BENCHMARK_ARCH")
    if env_override:
        return normalize_arch(env_override, "BENCHMARK_ARCH")
    return normalize_runtime_arch(java_runtime_arch()) or normalize_runtime_arch(windows_process_arch()) or "win64"


def verify_jvm_matches_arch(arch: str) -> None:
    """Fail fast when the JVM cannot load native binaries for `arch`.

    The SDK and JNI bridge must match the JVM that loads them. A wrong-arch
    JDK (e.g. an x64 Adoptium install on an ARM64 runner, which Adoptium
    silently provides because it ships no Windows-ARM64 build) would otherwise
    surface as the emulated client crashing mid-run with no actionable signal.
    Skips when the JVM arch cannot be determined; later steps fail on their
    own with clearer errors for a missing JVM.
    """
    jvm_raw = java_runtime_arch()
    if jvm_raw is None:
        return
    jvm_arch = normalize_runtime_arch(jvm_raw)
    if jvm_arch is None or jvm_arch == arch:
        return
    raise RuntimeError(
        f"benchmark arch {arch} does not match the JVM architecture {jvm_raw} "
        f"reported by {java_executable()}; the native SDK and JNI bridge must "
        "match the JVM. On ARM64 runners install Microsoft Build of OpenJDK 21 "
        "(ARM64) and remove any x64 Adoptium JDK or stale JAVA_HOME; see "
        "tools/docs/BENCHMARKING.md."
    )


def timestamp_id() -> str:
    return dt.datetime.now().strftime("%Y%m%d_%H%M%S")


def unique_result_dir(run_id: str) -> Path:
    base = util.RESULTS_DIR / run_id
    path = base
    suffix = 2
    while path.exists():
        path = util.RESULTS_DIR / f"{run_id}_{suffix}"
        suffix += 1
    return path


_RESOURCE_PACK_OVERRIDE: str | None = None
_PRESET_OVERRIDE: str | None = None


def set_runtime_resource_pack_override(spec: str | None) -> None:
    global _RESOURCE_PACK_OVERRIDE
    _RESOURCE_PACK_OVERRIDE = spec


def set_runtime_preset_override(name: str | None) -> None:
    global _PRESET_OVERRIDE
    _PRESET_OVERRIDE = name


def explicit_resource_pack_version() -> bool:
    """Whether --resource-pack asked for its own <name>@<version> rather than the configured pin."""
    return _RESOURCE_PACK_OVERRIDE is not None and "@" in _RESOURCE_PACK_OVERRIDE


def _apply_resource_pack_spec(config: dict, value: str | None) -> None:
    if value is None:
        return
    rp = config.get("resourcePack")
    if not isinstance(rp, dict):
        rp = config["resourcePack"] = {}
    text = value.strip()
    if text.lower() == "none":
        rp["mode"] = "none"
    elif text.lower().startswith("local:"):
        rp["mode"] = "local"
        rp["localPath"] = text[len("local:") :]
    elif text.lower() == "pinned":
        rp["mode"] = "pinned"
    elif "@" in text:
        name, _, version = text.partition("@")
        rp["package"] = resource_pack_package(name, config)
        rp["mode"] = "pinned"
        rp["name"] = name
        rp["version"] = validate_resource_pack_version(version)
    else:
        raise RuntimeError(
            f"invalid resource-pack spec: {value!r} (use none | local:<path> | <name>@<version> | pinned)"
        )


def load_config() -> dict:
    return apply_runtime_overrides(tools_config.load_config())


def apply_runtime_overrides(config: dict) -> dict:
    merged = copy.deepcopy(config)
    if _RESOURCE_PACK_OVERRIDE is not None:
        _apply_resource_pack_spec(merged, _RESOURCE_PACK_OVERRIDE)
    if _PRESET_OVERRIDE is not None:
        merged.setdefault("render", {})["preset"] = _PRESET_OVERRIDE
    return merged


def benchmark_worlds_dir() -> Path:
    return util.ASSETS_DIR / "worlds"


def world_package_name(world: str, config: dict | None = None) -> str:
    world = normalize_benchmark_name(world, "world")
    config = load_config() if config is None else config
    pin = configured_world(config)
    if pin.get("name") == world and pin.get("package"):
        return str(pin["package"])
    return f"{WORLD_PACKAGE_PREFIX}{world}"


def configured_world(config: dict) -> dict:
    """The pinned benchmark world object {name, version, camera} from config, or {}.

    Tolerates a plain string (the resolved name written back at run time). The
    name comes back stripped, as configuration layers compare it when they merge."""
    world = config.get("world")
    if isinstance(world, dict):
        name = world.get("name")
        return {**world, "name": name.strip()} if isinstance(name, str) else world
    if isinstance(world, str) and world.strip():
        return {"name": world.strip()}
    return {}


def configured_world_name(config: dict) -> str:
    return str(configured_world(config).get("name") or "")


def world_pin_version(config: dict, world: str) -> str | None:
    pin = configured_world(config)
    if pin.get("name") == world.strip():
        version = pin.get("version")
        if version:
            return str(version)
    return None


def world_pin_camera(config: dict, world: str) -> str | None:
    pin = configured_world(config)
    if pin.get("name") == world.strip():
        camera = pin.get("camera")
        return str(camera) if camera else None
    return None


def build_world_pull_project_xml(world: str, version: str, dest_dir: Path) -> str:
    package = world_package_name(world)
    copy_path = str(dest_dir.resolve()).replace("\\", "/")
    return (
        '<project toolsVersion="7.6">\n'
        f'  <dependency name="{package}" copyPath="{copy_path}">\n'
        f'    <package name="{package}" version="{version}" />\n'
        "  </dependency>\n"
        "</project>\n"
    )


def packman_cmd_path() -> Path:
    path = util.PROJECT_DIR / "packman" / "packman.cmd"
    if not path.is_file():
        raise RuntimeError(f"Packman helper is missing from the checkout: {path}. Restore the tracked file.")
    return path


def run_packman(args: list[str], *, capture: bool = False, check: bool = True) -> subprocess.CompletedProcess[str]:
    return util.run(
        [str(packman_cmd_path()), *args],
        capture=capture,
        check=check,
        encoding=locale.getencoding(),
        errors="replace",
    )


WORLD_VERSION_RE = re.compile(r"^v\d+\.\d+\.\d+$")
# Dir/label parsing tolerates a missing v prefix: worlds are published v-strict
# by our tooling, but art packs are published by the art team as bare X.Y.Z.
PACK_VERSION_RE = re.compile(r"^v?\d+\.\d+\.\d+$")
WORLD_DIR_VERSION_RE = re.compile(r"^(?P<name>.+)_(?P<version>v?\d+\.\d+\.\d+)$")


def validate_world_version(version: str) -> str:
    version = str(version or "").strip()
    if not WORLD_VERSION_RE.match(version):
        raise RuntimeError(f"world version must look like vMAJOR.MINOR.PATCH (e.g. v0.3.0), got: {version!r}")
    return version


def validate_resource_pack_version(version: str) -> str:
    """Pack versions accept both v0.1.0 (our scheme) and 0.1.0 (art team).

    The label is returned verbatim: packman pulls by exact published label."""
    version = str(version or "").strip()
    if not PACK_VERSION_RE.match(version):
        raise RuntimeError(
            f"resource pack version must look like [v]MAJOR.MINOR.PATCH (e.g. v0.1.0 or 0.1.0), got: {version!r}"
        )
    return version


def world_version_sort_key(version: str) -> tuple[int, int, int]:
    major, minor, patch = (int(part) for part in version.lstrip("v").split("."))
    return (major, minor, patch)


def parse_world_dir_name(dirname: str) -> tuple[str, str | None]:
    """Split a worlds/ dir name into (logical_name, version|None).

    A trailing _vMAJOR.MINOR.PATCH is the version; everything before it is the
    logical name (which may itself contain underscores, e.g. cyberpunk_city)."""
    match = WORLD_DIR_VERSION_RE.match(dirname)
    if match:
        return match.group("name"), match.group("version")
    return dirname, None


def world_dir_for(name: str, version: str | None) -> Path:
    """On-disk fixture dir: assets/worlds/<name>_<version>, or .../<name> when unversioned."""
    basename = f"{name}_{version}" if version else name
    return benchmark_worlds_dir() / basename


def _dir_has_world(world_dir: Path) -> bool:
    return is_minecraft_world(world_dir / WORLD_DIR_NAME) or is_minecraft_world(world_dir)


def available_world_versions(name: str) -> list[str]:
    """vMAJOR.MINOR.PATCH versions of `name` staged on disk, oldest-to-newest."""
    worlds_dir = benchmark_worlds_dir()
    if not worlds_dir.exists():
        return []
    versions = []
    for child in worlds_dir.iterdir():
        if not child.is_dir():
            continue
        child_name, version = parse_world_dir_name(child.name)
        if child_name == name and version and _dir_has_world(child):
            versions.append(version)
    return sorted(versions, key=world_version_sort_key)


def _versions_report(name: str, *, pinned: str | None, staged: list[str]) -> dict[str, Any]:
    """Pinned and locally staged versions of one fixture, newest first."""
    staged_set = set(staged)
    versions = staged_set | ({pinned} if pinned else set())
    return {
        "name": name,
        "pinned": pinned,
        "versions": [
            {
                "version": version,
                "staged": version in staged_set,
                "pinned": version == pinned,
            }
            for version in sorted(versions, key=world_version_sort_key, reverse=True)
        ],
    }


def world_versions_report(name: str) -> dict[str, Any]:
    name = normalize_benchmark_name(name, "world")
    return _versions_report(
        name,
        pinned=world_pin_version(load_config(), name),
        staged=available_world_versions(name),
    )


def resource_pack_versions_report(name: str) -> dict[str, Any]:
    name = normalize_benchmark_name(name, "resource pack")
    config = load_config()
    rp = configured_resource_pack(config)
    pinned = str(rp.get("version") or "") if str(rp.get("name") or "").strip() == name else ""
    return _versions_report(
        name,
        pinned=pinned or None,
        staged=available_resource_pack_versions(name),
    )


def locate_world_dir(name: str, version: str | None) -> Path:
    """Resolve the on-disk dir for (name, version).

    Explicit version -> <name>_<version>. No version: prefer a bare unversioned
    <name> dir (locally authored / catalog), else the newest staged versioned
    dir, else the bare path (caller reports missing)."""
    if version:
        return world_dir_for(name, version)
    bare = benchmark_worlds_dir() / name
    if _dir_has_world(bare):
        return bare
    versions = available_world_versions(name)
    if versions:
        return world_dir_for(name, versions[-1])
    return bare


FIXTURE_PULL_RETRY_DELAY_SECONDS = 3


class FixtureDownloadError(RuntimeError):
    """Packman could not download or install a fixture.

    Raised only by the pull itself, never for a configuration mistake, so an
    interactive launch can tell an unavailable art pack from a broken setting."""


def pull_fixture(project: Path, remote: str, package: str, version: str, destination: Path) -> None:
    """Require the copyPath install marker before exposing a fetched fixture."""
    marker = destination / f".{package}@{version}"
    for attempt in range(2):
        if attempt:
            # A scanner still holding the first copy would likely hold an immediate retry's too.
            time.sleep(FIXTURE_PULL_RETRY_DELAY_SECONDS)
        try:
            run_packman(["pull", str(project), "-r", remote])
        except (RuntimeError, OSError) as exc:
            raise FixtureDownloadError(str(exc)) from exc
        if marker.is_file():
            return
    raise FixtureDownloadError(
        f"Packman reported success without installing {package}@{version} at {destination}: "
        "most likely its final rename of the staged copy failed because of a transient file lock "
        "from antivirus, indexing or sync software on that drive. "
        "Please retry or exclude the folder from scanning."
    )


def fetch_world(world: str, *, version: str) -> Path:
    world = normalize_benchmark_name(world, "world")
    config = load_config()
    remote = tools_config.require_setting(config, "world.remote")
    dest_dir = world_dir_for(world, version)
    dest_dir.parent.mkdir(parents=True, exist_ok=True)
    project_xml = build_world_pull_project_xml(world, version, dest_dir)
    tmp = benchmark_worlds_dir() / f".packman-pull-{world}-{version}.tmp.xml"
    # UTF-8 without BOM: Packman rejects a BOM.
    tmp.write_text(project_xml, encoding="utf-8")
    try:
        pull_fixture(tmp, remote, world_package_name(world, config), version, dest_dir)
    finally:
        tmp.unlink(missing_ok=True)
    return dest_dir


RESOURCE_PACK_PACKAGE_PREFIX = "rtx_remix_minecraft_resourcepack_"


def resource_pack_package_name(name: str) -> str:
    return f"{RESOURCE_PACK_PACKAGE_PREFIX}{normalize_benchmark_name(name, 'resource pack')}"


def resource_pack_package(name: str, config: dict | None = None) -> str:
    """Packman package override for the configured pin, otherwise the naming convention."""
    config = load_config() if config is None else config
    rp = configured_resource_pack(config)
    override = str(rp.get("package") or "").strip()
    if override and str(rp.get("name") or "").strip() == name:
        return override
    return resource_pack_package_name(name)


def art_packs_dir() -> Path:
    return util.ASSETS_DIR / "resourcepacks"


def art_pack_dir_for(name: str, version: str) -> Path:
    name = normalize_benchmark_name(name, "resource pack")
    return art_packs_dir() / f"{name}_{version}"


def available_resource_pack_versions(name: str) -> list[str]:
    """[v]MAJOR.MINOR.PATCH versions of pack `name` staged on disk, oldest-to-newest."""
    packs_dir = art_packs_dir()
    if not packs_dir.exists():
        return []
    versions = []
    for child in packs_dir.iterdir():
        if not child.is_dir():
            continue
        child_name, version = parse_world_dir_name(child.name)
        if child_name == name and version:
            versions.append(version)
    return sorted(versions, key=world_version_sort_key)


def build_resource_pack_pull_project_xml(package: str, version: str, dest_dir: Path) -> str:
    copy_path = str(dest_dir.resolve()).replace("\\", "/")
    return (
        '<project toolsVersion="7.6">\n'
        f'  <dependency name="{package}" copyPath="{copy_path}">\n'
        f'    <package name="{package}" version="{version}" />\n'
        "  </dependency>\n"
        "</project>\n"
    )


def fetch_resource_pack(name: str, *, version: str, config: dict | None = None) -> Path:
    name = normalize_benchmark_name(name, "resource pack")
    config = load_config() if config is None else config
    remote = tools_config.require_setting(config, "resourcePack.remote")
    dest_dir = art_pack_dir_for(name, version)
    dest_dir.parent.mkdir(parents=True, exist_ok=True)
    project_xml = build_resource_pack_pull_project_xml(resource_pack_package(name, config), version, dest_dir)
    tmp = art_packs_dir() / f".packman-pull-{name}-{version}.tmp.xml"
    tmp.write_text(project_xml, encoding="utf-8")
    try:
        pull_fixture(tmp, remote, resource_pack_package(name, config), version, dest_dir)
    finally:
        tmp.unlink(missing_ok=True)
    return dest_dir


def art_pack_stage_name(config: dict) -> str:
    return normalize_resource_pack_name(configured_resource_pack(config).get("stageName") or "rtx-tools-art")


def configured_resource_pack(config: dict) -> dict:
    rp = config.get("resourcePack")
    return rp if isinstance(rp, dict) else {}


def locate_pack_root(dest: Path) -> Path:
    """Find the actual resource pack inside a packman-staged dir.

    Tolerates: pack.mcmeta at the root, a single subdir containing pack.mcmeta,
    or a single .zip. Raises if none match (package layout is an open assumption).
    """
    if (dest / "pack.mcmeta").is_file():
        return dest
    children = [c for c in dest.iterdir() if not c.name.startswith(".")] if dest.is_dir() else []
    subdirs = [c for c in children if c.is_dir() and (c / "pack.mcmeta").is_file()]
    if len(subdirs) == 1:
        return subdirs[0]
    zips = [c for c in children if c.is_file() and c.suffix.lower() == ".zip"]
    if len(zips) == 1:
        return zips[0]
    raise RuntimeError(
        f"art pack staged at {dest} has no recognizable pack layout "
        f"(expected pack.mcmeta at root, one subdir with pack.mcmeta, or one .zip)"
    )


class ArtPackNotStaged(RuntimeError):
    """A pinned art pack is configured but not present locally.

    Raised only when fetching is disabled, so read-only callers such as
    `doctor` can report the pin without downloading it."""

    def __init__(self, name: str, version: str, dest: Path) -> None:
        super().__init__(f"pinned art pack {name}@{version} is not staged at {dest}")
        self.name = name
        self.version = version
        self.dest = dest


def resolve_art_pack(
    config: dict, *, fetch: bool = True, tolerate_fetch_failure: bool = False
) -> ResourcePackSource | None:
    """Resolve the configured art pack, or None when mode is 'none'.

    With `fetch=False` a missing pinned pack raises `ArtPackNotStaged` instead
    of downloading it, so read-only callers stay read-only.

    With `tolerate_fetch_failure`, a failed download of the pinned pack
    (`FixtureDownloadError`) warns and returns None, so an interactive launch
    starts without it. Configuration mistakes still raise, and unattended runs
    leave the failure fatal so their results stay comparable."""
    rp = configured_resource_pack(config)
    mode = str(rp.get("mode") or "none").strip().lower()
    if mode == "none":
        return None
    stage_name = art_pack_stage_name(config)
    if mode == "local":
        raw = str(rp.get("localPath") or "").strip()
        if not raw:
            raise RuntimeError("resourcePack mode is 'local' but localPath is not set")
        path = util.project_path(raw)
        if not path.exists():
            raise RuntimeError(
                f"resourcePack mode is 'local' but localPath does not exist: {path} "
                f"(set as {raw!r}; {util.RELATIVE_PATH_BASE})"
            )
        root = locate_pack_root(path) if path.is_dir() else path
        return ResourcePackSource(
            name=stage_name,
            file_name=stage_name if root.is_dir() else root.name,
            source_path=root,
        )
    if mode == "pinned":
        name = normalize_resource_pack_name(tools_config.require_setting(config, "resourcePack.name"))
        version = tools_config.require_setting(config, "resourcePack.version")
        dest = art_pack_dir_for(name, version)
        if not dest.exists():
            # A malformed pin is a configuration mistake, not something a download can fix.
            validate_resource_pack_version(version)
            if not fetch:
                raise ArtPackNotStaged(name, version, dest)
            try:
                fetch_resource_pack(name, version=version, config=config)
            except FixtureDownloadError as exc:
                if not tolerate_fetch_failure:
                    raise
                print(
                    f"warning: launching without the art pack: fetching {name}@{version} failed ({exc}). "
                    f"{art_pack_retry_hint(name, version)}",
                    file=sys.stderr,
                )
                return None
        root = locate_pack_root(dest)
        return ResourcePackSource(
            name=stage_name,
            file_name=stage_name if root.is_dir() else root.name,
            source_path=root,
        )
    raise RuntimeError(f"invalid resourcePack mode: {mode!r} (expected none|local|pinned)")


def art_pack_retry_hint(name: str, version: str) -> str:
    """`resourcepack fetch NAME` fetches the configured version, so only the pin can be retried by name."""
    pin = configured_resource_pack(load_config())
    if str(pin.get("name") or "").strip() == name and str(pin.get("version") or "").strip() == version:
        return f"Retry the download with tools\\rtx.bat resourcepack fetch {name}."
    return "The next launch retries the download."


def normalize_benchmark_name(value: object, label: str, *, strip_json_suffix: bool = False) -> str:
    text = str(value or "").strip()
    if strip_json_suffix and text.lower().endswith(".json"):
        text = text[:-5]
    path = Path(text)
    if not text or path.is_absolute() or path.name != text or text in {".", ".."}:
        raise RuntimeError(f"invalid benchmark {label} name: {value!r}")
    if any(part in {"", ".", ".."} for part in path.parts):
        raise RuntimeError(f"invalid benchmark {label} name: {value!r}")
    return text


def is_minecraft_world(path: Path) -> bool:
    return path.is_dir() and (path / "level.dat").is_file()


def normalize_world_layout(world: str, version: str | None = None) -> Path:
    world = normalize_benchmark_name(world, "world")
    world_dir = locate_world_dir(world, version)
    canonical = world_dir / WORLD_DIR_NAME
    if is_minecraft_world(canonical):
        (world_dir / CAMERAS_DIR_NAME).mkdir(parents=True, exist_ok=True)
        return world_dir

    if is_minecraft_world(world_dir):
        metadata_names = {
            WORLD_DIR_NAME,
            CAMERAS_DIR_NAME,
            RESOURCEPACKS_DIR_NAME,
            WORLD_CONFIG_FILE,
            LEGACY_WORLD_OPTIONS_FILE,
        }
        raw_children = [child for child in world_dir.iterdir() if child.name not in metadata_names]
        if canonical.exists():
            raise RuntimeError(
                f"benchmark world '{world}' has both raw save files and a {WORLD_DIR_NAME}/ directory; "
                "move the save files into one layout before continuing"
            )
        canonical.mkdir(parents=True)
        for child in raw_children:
            shutil.move(str(child), str(canonical / child.name))
        (world_dir / CAMERAS_DIR_NAME).mkdir(exist_ok=True)
        return world_dir

    raise RuntimeError(
        f"benchmark world '{world}' does not contain a Minecraft save. "
        f"Expected {canonical / 'level.dat'} or {world_dir / 'level.dat'}"
    )


def _local_world_names() -> list[str]:
    worlds_dir = benchmark_worlds_dir()
    if not worlds_dir.exists():
        return []
    names = set()
    for child in worlds_dir.iterdir():
        if child.is_dir() and _dir_has_world(child):
            name, _version = parse_world_dir_name(child.name)
            names.add(name)
    return sorted(names)


def available_worlds() -> list[str]:
    config = load_config()
    names = set(_local_world_names())
    benchmark_world = configured_world_name(config)
    if benchmark_world:
        names.add(benchmark_world)
    return sorted(names)


def world_staged_version(world: str, version: str | None = None, *, config: dict | None = None) -> str | None:
    """Version recorded by packman's copyPath install marker, or None if unfetched.

    Packman writes .<package>@<version> into the directory it installs, which
    this tooling names <world>_<version>. A payload published from such a
    directory ships that marker as content, so a fetched fixture can hold
    several: the one matching the directory's own version is the install
    record and the rest are package content.

    Raises RuntimeError when several markers are present and the directory
    name cannot arbitrate between them; guessing would stage the wrong version.
    """
    world = normalize_benchmark_name(world, "world")
    world_dir = locate_world_dir(world, version)
    if not world_dir.is_dir():
        return None
    prefix = f".{world_package_name(world, config)}@"
    marked = sorted(child.name[len(prefix) :] for child in world_dir.iterdir() if child.name.startswith(prefix))
    if len(marked) <= 1:
        return marked[0] if marked else None
    _name, dir_version = parse_world_dir_name(world_dir.name)
    if dir_version in marked:
        return dir_version
    raise RuntimeError(
        f"{world_dir} holds conflicting packman install markers ({', '.join(marked)}); "
        "remove the ones that do not describe its contents"
    )


def world_status(
    world: str, *, pinned_version: str | None, version: str | None = None, config: dict | None = None
) -> dict:
    world_dir = locate_world_dir(world, version)
    staged = _dir_has_world(world_dir)
    staged_version = world_staged_version(world, version, config=config) if staged else None
    # Drift comes from packman's install marker (.<package>@<version>), not from
    # hashing: copyPath drops bookkeeping files that would skew a content hash.
    # A staged fixture with no marker is locally authored / published-from.
    modified = bool(staged and pinned_version and staged_version is not None and staged_version != pinned_version)
    return {
        "name": world,
        "pinned": pinned_version,
        "staged": staged,
        "stagedVersion": staged_version,
        "modified": modified,
    }


def resolve_world_name(config: dict, world_override: str | None = None) -> str:
    name = world_override or configured_world_name(config)
    if not name:
        raise RuntimeError(
            "No world selected. Set 'world.name' and 'world.version' in tools/config.local.json; "
            "a local save imported with tools\\rtx.bat world import needs only 'world.name'."
        )
    return normalize_benchmark_name(name, "world")


def ensure_world_staged(config: dict, world: str, *, ci: bool = False, version: str | None = None) -> None:
    world = normalize_benchmark_name(world, "world")
    pinned = world_pin_version(config, world)
    if ci:
        # CI must use the pinned benchmark world; a --version override is ignored.
        if not pinned:
            raise RuntimeError(
                f"benchmark world '{world}' is not the pinned benchmark world; CI runs must use the "
                f"pinned world (config 'world') for reproducibility. Pin it or run without --world."
            )
        target = pinned
    else:
        # An explicit --version wins; otherwise the pinned version.
        target = version or pinned
    if target:
        world_dir = world_dir_for(world, target)
        if _dir_has_world(world_dir):
            staged_version = world_staged_version(world, target, config=config)
            if staged_version is None or staged_version == target:
                # No marker -> locally authored/published-from copy, trust it.
                # Marker matches target -> correct version already staged.
                return
            print(f"Replacing staged world '{world}' ({staged_version}) with version {target} from packman.")
        fetch_world(world, version=target)
        return
    # Not CI, no pin, no explicit version: catalog / ad-hoc.
    if _dir_has_world(locate_world_dir(world, None)):
        return  # local copy present: use it as-is for ad-hoc runs
    raise RuntimeError(
        f"World '{world}' is not staged and has no pinned version. Pass --version vMAJOR.MINOR.PATCH, "
        "set 'world.version' in tools/config.local.json, or import a local world with tools\\rtx.bat world import."
    )


def available_cameras(world: str, version: str | None = None) -> list[str]:
    world_dir = normalize_world_layout(world, version)
    return available_cameras_in_dir(world_dir)


def available_cameras_in_dir(world_dir: Path) -> list[str]:
    cameras_dir = world_dir / CAMERAS_DIR_NAME
    if not cameras_dir.exists():
        return []
    return sorted(
        path.stem for path in cameras_dir.glob("*.json") if path.is_file() and not path.name.endswith(".shots.json")
    )


def camera_report(world: str, version: str | None = None) -> dict[str, Any]:
    """Camera inventory for one world version, for UI consumption.

    Cameras are files inside the world fixture, so they are only knowable once
    the world is staged; `staged: false` tells UIs to offer an import or configured
    fetch instead of raising. Reads the staged dir without normalizing its layout."""
    world = normalize_benchmark_name(world, "world")
    config = load_config()
    effective = version or world_pin_version(config, world)
    world_dir = locate_world_dir(world, effective)
    staged = _dir_has_world(world_dir)
    return {
        "world": world,
        "version": effective,
        "staged": staged,
        "cameras": available_cameras_in_dir(world_dir) if staged else [],
        "defaultCamera": world_pin_camera(config, world) or "",
    }


def _camera_path_error(camera_path: Path, detail: str) -> RuntimeError:
    return RuntimeError(
        f"benchmark camera at {camera_path} is not a valid version {CAMERA_PATH_VERSION} "
        f"time-based camera path: {detail}. Re-record it with '/rtx camera record <name>' "
        "before benchmarking"
    )


def validate_camera_path_fixture(camera_path: Path) -> dict[str, int]:
    """Fail before SDK/build/client work when a selected camera cannot load.

    CameraPathCodec remains the runtime authority. This mirrors its version 1
    schema and core CameraPath invariants so an outdated Packman fixture cannot
    occupy a benchmark runner until the outer process timeout.
    """
    try:
        root_value: Any = json.loads(camera_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise _camera_path_error(camera_path, f"invalid JSON ({exc})") from exc

    if not isinstance(root_value, dict):
        raise _camera_path_error(camera_path, "root must be an object")
    root = cast(dict[str, Any], root_value)

    waypoints_value = root.get("waypoints")
    legacy_waypoint = next(
        (
            index
            for index, waypoint in enumerate(waypoints_value if isinstance(waypoints_value, list) else [])
            if isinstance(waypoint, dict) and "frames" in waypoint
        ),
        None,
    )
    if "version" not in root or legacy_waypoint is not None:
        evidence = []
        if "version" not in root:
            evidence.append("missing root version")
        if legacy_waypoint is not None:
            evidence.append(f"waypoint {legacy_waypoint} contains legacy 'frames'")
        raise _camera_path_error(camera_path, f"legacy frame-count schema ({'; '.join(evidence)})")

    unknown_root_fields = sorted(set(root) - CAMERA_PATH_ROOT_FIELDS)
    if unknown_root_fields:
        raise _camera_path_error(
            camera_path,
            f"root contains unsupported field(s): {', '.join(unknown_root_fields)}",
        )

    version = root.get("version")
    if isinstance(version, bool) or not isinstance(version, int) or version != CAMERA_PATH_VERSION:
        raise _camera_path_error(camera_path, f"unsupported root version {version!r}")
    if root.get("interpolation") != CAMERA_PATH_INTERPOLATION:
        raise _camera_path_error(
            camera_path,
            f"unsupported interpolation {root.get('interpolation')!r}",
        )
    if not isinstance(waypoints_value, list):
        raise _camera_path_error(camera_path, "waypoints must be an array")
    if len(waypoints_value) < 2:
        raise _camera_path_error(camera_path, "camera path requires at least two waypoints")

    previous_time_ms = -1
    for index, waypoint_value in enumerate(waypoints_value):
        if not isinstance(waypoint_value, dict):
            raise _camera_path_error(camera_path, f"waypoint {index} must be an object")
        waypoint = cast(dict[str, Any], waypoint_value)

        unknown_fields = sorted(set(waypoint) - CAMERA_PATH_WAYPOINT_FIELDS)
        if unknown_fields:
            raise _camera_path_error(
                camera_path,
                f"waypoint {index} contains unsupported field(s): {', '.join(unknown_fields)}",
            )
        missing_fields = sorted(
            field for field in CAMERA_PATH_REQUIRED_WAYPOINT_FIELDS if field not in waypoint or waypoint[field] is None
        )
        if missing_fields:
            raise _camera_path_error(
                camera_path,
                f"waypoint {index} is missing required field(s): {', '.join(missing_fields)}",
            )

        time_ms = waypoint["timeMs"]
        if isinstance(time_ms, bool) or not isinstance(time_ms, int):
            raise _camera_path_error(camera_path, f"waypoint {index} timeMs must be an integer")
        if time_ms < 0:
            raise _camera_path_error(camera_path, f"waypoint {index} timeMs must be non-negative")
        if time_ms > CAMERA_PATH_MAX_DURATION_MS:
            raise _camera_path_error(
                camera_path,
                f"waypoint {index} timeMs exceeds {CAMERA_PATH_MAX_DURATION_MS}",
            )
        if index == 0 and time_ms != 0:
            raise _camera_path_error(camera_path, "first waypoint timeMs must be 0")
        if time_ms <= previous_time_ms:
            raise _camera_path_error(camera_path, "waypoint timeMs values must be strictly increasing")
        previous_time_ms = time_ms

        for field in ("x", "y", "z", "yaw", "pitch"):
            value = waypoint[field]
            try:
                finite = not isinstance(value, bool) and isinstance(value, int | float) and math.isfinite(value)
            except OverflowError:
                finite = False
            if not finite:
                raise _camera_path_error(camera_path, f"waypoint {index} {field} must be a finite number")
        for field in ("yaw", "pitch"):
            if abs(waypoint[field]) > 3.4028234663852886e38:
                raise _camera_path_error(camera_path, f"waypoint {index} {field} is outside the float range")

        if "timeOfDay" in waypoint:
            time_of_day = waypoint["timeOfDay"]
            if (
                isinstance(time_of_day, bool)
                or not isinstance(time_of_day, int)
                or time_of_day < -(2**63)
                or time_of_day > 2**63 - 1
            ):
                raise _camera_path_error(camera_path, f"waypoint {index} timeOfDay must be an integer")

    return {"waypoints": len(waypoints_value), "durationMs": previous_time_ms}


def load_json_object(path: Path, label: str) -> dict:
    value = util.read_json(path)
    if not isinstance(value, dict):
        raise RuntimeError(f"{path} must contain a JSON object for {label}")
    return value


def load_world_config(world_dir: Path) -> dict:
    config: dict = {}
    legacy_path = world_dir / LEGACY_WORLD_OPTIONS_FILE
    if legacy_path.is_file():
        config = tools_config.merge_config(config, load_json_object(legacy_path, "benchmark world options"))

    config_path = world_dir / WORLD_CONFIG_FILE
    if config_path.is_file():
        config = tools_config.merge_config(config, load_json_object(config_path, "benchmark world config"))

    for key in WORLD_CONFIG_IGNORED_KEYS:
        config.pop(key, None)
    return config


def effective_config_for_world(config: dict, world_dir: Path) -> dict:
    return apply_runtime_overrides(tools_config.merge_layer(config, load_world_config(world_dir)))


def choices_text(values: list[str]) -> str:
    return ", ".join(values) if values else "none"


def valid_java_home(path: Path) -> bool:
    return (path / "include" / "jni.h").is_file()


def detect_java_home() -> Path | None:
    configured = os.environ.get("JAVA_HOME")
    if configured and valid_java_home(Path(configured)):
        return Path(configured)

    java = shutil.which("java")
    if java:
        candidate = Path(java).resolve().parent.parent
        if valid_java_home(candidate):
            return candidate
    return None


def gradle_env() -> dict[str, str]:
    env = os.environ.copy()
    if not env.get("JAVA_HOME"):
        java_home = detect_java_home()
        if java_home:
            env["JAVA_HOME"] = str(java_home)
    return env


def rtx_conf_values(path: Path, keys: set[str]) -> dict[str, str]:
    """Read selected scalar assignments from a Remix conf file."""
    if not path.is_file():
        return {}
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw_line.split("#", 1)[0].strip()
        if "=" not in line:
            continue
        key, value = (part.strip() for part in line.split("=", 1))
        if key in keys:
            values[key] = value.rstrip(";").strip()
    return values


def validate_benchmark_user_conf() -> None:
    """Reject saved User opinions that would mask benchmark blocking values."""
    user_conf = util.RUN_DIR / "user.conf"
    values = rtx_conf_values(user_conf, BENCHMARK_BLOCKING_OPTIONS)
    conflicts = {key: value for key, value in values.items() if value.casefold() not in {"false", "0"}}
    if conflicts:
        details = ", ".join(f"{key}={value}" for key, value in sorted(conflicts.items()))
        raise RuntimeError(
            f"Benchmark shader-blocking config is masked by {user_conf}: {details}. "
            "Remove those entries from Remix User Settings/user.conf and retry."
        )


@contextmanager
def benchmark_rtx_config_env() -> Iterator[dict[str, str]]:
    """Stage startup-only RTX overrides for one measured benchmark process.

    DXVK_RTX_CONFIG_FILE replaces the default rtx.conf lookup, so the game
    directory's rtx.conf must be listed first. The temporary file is listed
    second and therefore wins only for its two shader-compilation keys.
    """
    benchmark_dir = util.RUN_DIR / "benchmark"
    benchmark_dir.mkdir(parents=True, exist_ok=True)
    temporary = tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        prefix=BENCHMARK_RTX_CONFIG_PREFIX,
        suffix=".conf",
        dir=benchmark_dir,
        delete=False,
    )
    benchmark_config = Path(temporary.name)
    try:
        with temporary:
            temporary.write(BENCHMARK_RTX_CONFIG)
        validate_benchmark_user_conf()
        env = gradle_env()
        # Environment overrides are stronger than Remix Config layers. Do not
        # let an inherited developer setting silently re-enable async compile.
        env[ASYNC_COMPILATION_ENV] = "false"
        config_paths = (
            (util.RUN_DIR / "rtx.conf").resolve(),
            benchmark_config.resolve(),
        )
        env[RTX_CONFIG_PATHS_ENV] = ",".join(str(path) for path in config_paths)
        yield env
    finally:
        unlink_retry(benchmark_config)


def resolve_camera_name(config: dict, world: str, world_dir: Path, override: str | None) -> str:
    fixture = load_world_config(world_dir)
    return normalize_benchmark_name(
        override
        or fixture.get("camera")
        or fixture.get("defaultCamera")
        or fixture.get("cameraPath")
        or world_pin_camera(config, world)
        or config.get("camera")
        or config.get("defaultCamera")
        or config.get("cameraPath")
        or DEFAULT_BENCHMARK_CAMERA,
        "camera",
        strip_json_suffix=True,
    )


def resolve_benchmark_selection(
    config: dict,
    *,
    world_override: str | None = None,
    camera_override: str | None = None,
    version_override: str | None = None,
    require_camera: bool = True,
) -> BenchmarkSelection:
    world = resolve_world_name(config, world_override)
    version = version_override or world_pin_version(config, world)
    world_dir = normalize_world_layout(world, version)
    camera = resolve_camera_name(config, world, world_dir, camera_override)

    world_path = world_dir / WORLD_DIR_NAME
    cameras_dir = world_dir / CAMERAS_DIR_NAME
    camera_path = cameras_dir / f"{camera}.json"

    if not world_path.is_dir():
        raise RuntimeError(
            f"benchmark world '{world}' does not exist at {world_path}. "
            f"Available worlds: {choices_text(available_worlds())}"
        )
    cameras_dir.mkdir(parents=True, exist_ok=True)
    if require_camera and not camera_path.is_file():
        raise RuntimeError(
            f"benchmark camera '{camera}' does not exist at {camera_path}. "
            f"Available cameras for '{world}': {choices_text(available_cameras(world, version))}"
        )
    if require_camera:
        validate_camera_path_fixture(camera_path)

    return BenchmarkSelection(
        world=world,
        camera=camera,
        world_dir=world_dir,
        world_path=world_path,
        cameras_dir=cameras_dir,
        camera_path=camera_path,
        version=version,
    )


def probe_world_layout(world: str, version: str | None = None) -> tuple[Path, Path, str]:
    world = normalize_benchmark_name(world, "world")
    world_dir = locate_world_dir(world, version)
    canonical = world_dir / WORLD_DIR_NAME
    if is_minecraft_world(canonical):
        return world_dir, canonical, "canonical"
    if is_minecraft_world(world_dir):
        return world_dir, world_dir, "raw"
    return world_dir, canonical, "missing"


class WorldNotStaged(RuntimeError):
    """The pinned benchmark world is configured but not present locally.

    A run fetches it, so read-only callers such as `doctor` can report the
    pin without treating the fixture as broken."""

    def __init__(self, message: str, world: str, version: str) -> None:
        super().__init__(message)
        self.world = world
        self.version = version


def resolve_benchmark_selection_probe(
    config: dict,
    *,
    world_override: str | None = None,
    camera_override: str | None = None,
    version_override: str | None = None,
    require_camera: bool = True,
) -> tuple[BenchmarkSelection, str]:
    world = resolve_world_name(config, world_override)
    pinned = world_pin_version(config, world)
    version = version_override or pinned
    world_dir, world_path, layout = probe_world_layout(world, version)
    if layout == "missing":
        message = (
            f"benchmark world '{world}' does not contain a Minecraft save. "
            f"Expected {world_dir / WORLD_DIR_NAME / 'level.dat'} or {world_dir / 'level.dat'}. "
            f"Available worlds: {choices_text(available_worlds())}"
        )
        if version and version == pinned:
            raise WorldNotStaged(message, world, version)
        raise RuntimeError(message)
    camera = resolve_camera_name(config, world, world_dir, camera_override)

    cameras_dir = world_dir / CAMERAS_DIR_NAME
    camera_path = cameras_dir / f"{camera}.json"
    if require_camera and not camera_path.is_file():
        raise RuntimeError(
            f"benchmark camera '{camera}' does not exist at {camera_path}. "
            f"Available cameras for '{world}': {choices_text(available_cameras_in_dir(world_dir))}"
        )
    if require_camera:
        validate_camera_path_fixture(camera_path)

    return (
        BenchmarkSelection(
            world=world,
            camera=camera,
            world_dir=world_dir,
            world_path=world_path,
            cameras_dir=cameras_dir,
            camera_path=camera_path,
            version=version,
        ),
        layout,
    )


def configure_benchmark_selection_probe(
    config: dict,
    *,
    world_override: str | None = None,
    camera_override: str | None = None,
    version_override: str | None = None,
    require_camera: bool = True,
) -> tuple[BenchmarkSelection, str]:
    selection, layout = resolve_benchmark_selection_probe(
        config,
        world_override=world_override,
        camera_override=camera_override,
        version_override=version_override,
        require_camera=require_camera,
    )
    world_version = world_staged_version(selection.world, selection.version, config=config) or selection.version or ""
    effective_config = effective_config_for_world(config, selection.world_dir)
    config.clear()
    config.update(effective_config)
    config["world"] = selection.world
    config["camera"] = selection.camera
    config["cameraPath"] = selection.camera
    config["worldVersion"] = world_version
    return selection, layout


def configure_benchmark_selection(
    config: dict,
    *,
    world_override: str | None = None,
    camera_override: str | None = None,
    version_override: str | None = None,
    require_camera: bool = True,
) -> BenchmarkSelection:
    selection = resolve_benchmark_selection(
        config,
        world_override=world_override,
        camera_override=camera_override,
        version_override=version_override,
        require_camera=require_camera,
    )
    world_version = world_staged_version(selection.world, selection.version, config=config) or selection.version or ""
    effective_config = effective_config_for_world(config, selection.world_dir)
    config.clear()
    config.update(effective_config)
    config["world"] = selection.world
    config["camera"] = selection.camera
    config["cameraPath"] = selection.camera
    config["worldVersion"] = world_version
    return selection


def config_for_run(config: dict, *, ci: bool) -> dict:
    if not ci:
        return apply_env_config_overrides(config)
    config = copy.deepcopy(config)
    profiling = config.setdefault("profiling", {})
    depth_override = os.environ.get("BENCHMARK_CI_TRACY_JAVA_MAX_DEPTH")
    if depth_override:
        profiling["tracyJavaMaxDepth"] = int(depth_override)
        profiling["tracyJavaMaxDepthReason"] = "CI override from BENCHMARK_CI_TRACY_JAVA_MAX_DEPTH."

    drain_override = os.environ.get("BENCHMARK_CI_TRACY_DRAIN_SECONDS")
    ci_drain_seconds = int(
        drain_override
        or profiling.get("ciPostResultExitDelaySeconds")
        or max(int(profiling.get("postResultExitDelaySeconds") or 0), 60)
    )
    profiling["postResultExitDelaySeconds"] = max(
        int(profiling.get("postResultExitDelaySeconds") or 0),
        ci_drain_seconds,
    )
    profiling["postResultProcessWaitSeconds"] = max(
        int(profiling.get("postResultProcessWaitSeconds") or 0),
        profiling["postResultExitDelaySeconds"] + 45,
    )
    profiling["tracyDrainWaitForIdle"] = True
    profiling["postResultExitDelayReason"] = (
        "CI keeps the Tracy client alive after the benchmark so capture can resolve native source-location strings."
    )
    apply_env_config_overrides(config)
    return config


def env_int(name: str) -> int | None:
    raw = os.environ.get(name)
    if raw is None or not raw.strip():
        return None
    value = int(raw)
    if value < 0:
        raise RuntimeError(f"{name} must be zero or greater")
    return value


def env_bool(name: str) -> bool | None:
    raw = os.environ.get(name)
    if raw is None or not raw.strip():
        return None
    value = raw.strip().lower()
    if value in {"1", "true", "yes", "on"}:
        return True
    if value in {"0", "false", "no", "off"}:
        return False
    raise RuntimeError(f"{name} must be true or false")


def apply_env_config_overrides(config: dict) -> dict:
    render = config.setdefault("render", {})
    profiling = config.setdefault("profiling", {})
    if (fullscreen := env_bool("BENCHMARK_RENDER_FULLSCREEN")) is not None:
        render["fullscreen"] = fullscreen
    if (width := env_int("BENCHMARK_RENDER_WIDTH")) is not None:
        render["overrideWidth"] = width
    if (height := env_int("BENCHMARK_RENDER_HEIGHT")) is not None:
        render["overrideHeight"] = height
    if (lap := env_bool("BENCHMARK_WARMUP_LAP")) is not None:
        config["warmupLap"] = lap
    if (stable := env_int("BENCHMARK_TRACY_DRAIN_STABLE_SECONDS")) is not None:
        profiling["tracyDrainStableSeconds"] = stable
    if (stall := env_int("BENCHMARK_HEARTBEAT_STALL_SECONDS")) is not None:
        profiling["benchmarkHeartbeatStallSeconds"] = stall
    if (startup_stall := env_int("BENCHMARK_STARTUP_STALL_SECONDS")) is not None:
        profiling["benchmarkStartupStallSeconds"] = startup_stall
    if (stall_preview := env_int("BENCHMARK_STALL_PREVIEW_SECONDS")) is not None:
        profiling["benchmarkStallPreviewSeconds"] = stall_preview
    if (poll_timeout := env_int("BENCHMARK_POLL_TIMEOUT_SECONDS")) is not None:
        config["pollTimeoutSeconds"] = poll_timeout
    if (cooldown := env_int("BENCHMARK_INTER_RUN_COOLDOWN_SECONDS")) is not None:
        config["interRunCooldownSeconds"] = cooldown
    return config


def tracy_java_depth(config: dict) -> int:
    value = (config.get("profiling") or {}).get("tracyJavaMaxDepth")
    return 6 if value is None else int(value)


def profiling_exit_delay(config: dict) -> int:
    return int((config.get("profiling") or {}).get("postResultExitDelaySeconds") or 0)


def profiling_exit_mode(config: dict) -> str:
    mode = str((config.get("profiling") or {}).get("exitMode") or "halt")
    return mode if mode in {"halt", "systemExit"} else "halt"


def exit_code_ntstatus(exit_code: int) -> str | None:
    """The NTSTATUS an exit code is, when it is a Windows error status (0xC0000000 to 0xCFFFFFFF), else None.
    Minecraft's own crash exits, -1 after it writes a crash report and -2 when it cannot, read as 0xFFFFFFFF and
    0xFFFFFFFE and are not statuses."""
    code = exit_code & 0xFFFFFFFF
    return f"0x{code:08X}" if code >> 28 == 0xC else None


def native_exit_details(log_text: str, exit_code: int | None = None) -> dict[str, object]:
    """The native exit evidence in a run's log or, when the log has no NTSTATUS, in the game process's `exit_code`,
    which is all a launcher that keeps the game's console output to itself hands over."""
    details: dict[str, object] = {}
    logged_exit_code: int | None = None
    exit_matches = list(PROCESS_EXIT_RE.finditer(log_text))
    if exit_matches:
        logged_exit_code = int(exit_matches[-1].group(1))
        details["processExitCode"] = logged_exit_code
    status_matches = list(NTSTATUS_RE.finditer(log_text))
    if status_matches:
        details["ntstatus"] = status_matches[-1].group(1).upper().replace("0X", "0x")
    elif logged_exit_code is not None:
        # Gradle reports native JVM deaths as a decimal exit value only
        # (e.g. -1073740940 == 0xC0000374); derive the NTSTATUS from it.
        # Only severity-error codes (0xC...) map; ordinary exit codes do not.
        masked = logged_exit_code & 0xFFFFFFFF
        if masked >= 0xC0000000:
            details["ntstatus"] = f"0x{masked:08X}"
    if "ntstatus" not in details and exit_code is not None and (status := exit_code_ntstatus(exit_code)):
        details["ntstatus"] = status
    return details


def classify_run_failure(log_text: str, fallback: str, exit_code: int | None = None) -> str:
    native_exit = native_exit_details(log_text, exit_code)
    if ntstatus := native_exit.get("ntstatus"):
        return NTSTATUS_FAILURE_REASONS.get(str(ntstatus), f"NativeCrash:{ntstatus}")
    for signature, reason in FAILURE_SIGNATURES:
        if signature in log_text:
            return reason
    return fallback


def is_native_crash_reason(reason: str) -> bool:
    # NativeLinkError (UnsatisfiedLinkError) is a JNI/ABI deployment mismatch,
    # not a native crash; keep it out of the crash metric.
    return reason.startswith("NativeCrash:") or reason in {
        "NativeHeapCorruption",
        "AccessViolation",
        "JvmCrash",
    }


def failure_warrants_crash_dump_wait(failure: str, log_text: str) -> bool:
    """Only failures with native-termination evidence can produce a WER dump.

    Timeout/Stalled/StartupHang force-kill the launcher ourselves; waiting the
    full BENCHMARK_CRASH_DUMP_WAIT_SECONDS for a dump that cannot appear adds
    dead time to every soft-failed run.
    """
    if is_native_crash_reason(failure):
        return True
    # A silent nonzero exit may be an unclassified native crash.
    return failure == "ProcessExited" and "processExitCode" in native_exit_details(log_text)


def continue_after_run_failure(config: dict, *, ci: bool) -> bool:
    override = env_bool(CONTINUE_AFTER_FAILURE_ENV)
    if override is not None:
        return override
    configured = config.get("continueAfterRunFailure")
    if configured is not None:
        return bool(configured)
    return ci


def log_tail_excerpt(log_text: str, *, lines: int = 80, max_chars: int = 16_000) -> str:
    """Bounded tail of a run log, shared by every failure record.

    The run log carries the dxvk runtime's log too, so this tail is what shows
    whether the runtime went silent before the harness gave up on the run."""
    tail = "\n".join(log_text.rstrip().splitlines()[-lines:])
    return tail[-max_chars:] if len(tail) > max_chars else tail


def build_failure_details(
    reason: str,
    log_text: str,
    *,
    benchmark_status: dict | None,
    launcher_exit_code: int | None,
) -> dict[str, object]:
    # crashDumps is added by save_run_artifacts once the dumps are staged
    # under run_N/crash/, so failure.json and the manifest share one list.
    details: dict[str, object] = {
        "schemaVersion": 1,
        "reason": reason,
        "launcherExitCode": launcher_exit_code,
        "benchmarkStatus": benchmark_status or {},
        "logTail": log_tail_excerpt(log_text),
    }
    details.update(native_exit_details(log_text, launcher_exit_code))
    return details


def benchmark_heartbeat_stall_seconds(config: dict) -> int:
    profiling = config.get("profiling") or {}
    value = profiling.get("benchmarkHeartbeatStallSeconds", 90)
    return max(0, int(value))


def benchmark_startup_stall_seconds(config: dict) -> int:
    profiling = config.get("profiling") or {}
    value = profiling.get("benchmarkStartupStallSeconds", 120)
    return max(0, int(value))


def startup_output_stalled(
    *, idle_seconds: float, threshold: int, has_status: bool, has_result: bool, proc_alive: bool
) -> bool:
    """True when a launched run has gone silent before the benchmark even started.

    Catches a client that hung at startup (e.g. a runtime/ABI mismatch) so the
    run fails fast instead of polling the full pollTimeout. Only applies before
    the benchmark writes any status (heartbeat-stall covers it after that)."""
    return threshold > 0 and proc_alive and not has_status and not has_result and idle_seconds >= threshold


def benchmark_stall_preview_seconds(config: dict, heartbeat_stall_seconds: int) -> int:
    """Seconds of frozen status after which a still-running run is snapshotted.

    Defaults to half the heartbeat-stall budget so the snapshot lands while the
    run is still stuck rather than after it recovers. 0 disables it."""
    profiling = config.get("profiling") or {}
    value = profiling.get("benchmarkStallPreviewSeconds")
    if value is None:
        return max(0, heartbeat_stall_seconds // 2)
    return max(0, int(value))


def stall_preview_due(*, idle_seconds: float, threshold: int, kill_threshold: int, already_captured: bool) -> bool:
    """True when a stalled-but-alive run should be snapshotted without killing it.

    Fires at most once per frozen status signature, and never in the poll tick
    where the heartbeat watchdog is about to kill the run, which captures its
    own diagnostics."""
    if threshold <= 0 or already_captured or idle_seconds < threshold:
        return False
    return kill_threshold <= 0 or idle_seconds < kill_threshold


def read_run_log_best_effort(*paths: Path) -> str:
    parts = []
    for path in paths:
        try:
            parts.append(path.read_text(encoding="utf-8", errors="replace"))
        except OSError:
            pass
    return "".join(parts)


def read_benchmark_status(path: Path) -> dict | None:
    if not path.exists():
        return None
    try:
        data = util.read_json(path)
    except OSError, ValueError:
        return None
    return data if isinstance(data, dict) else None


def benchmark_status_signature(status: dict) -> tuple[Any, ...]:
    # Deliberately excludes "sequence": that is a monotonic write counter the
    # Java side bumps on every heartbeat (~1/sec), so including it would make
    # the signature change on every poll even when the run is frozen on the
    # same frame, defeating heartbeat-stall detection. Only true progress
    # fields belong here.
    return (
        status.get("state"),
        status.get("phase"),
        status.get("currentFrame"),
        status.get("pendingFrameTimeIndex"),
    )


def describe_benchmark_status(status: dict | None) -> str:
    if not status:
        return "no heartbeat"
    state = status.get("state") or "unknown"
    phase = status.get("phase") or "unknown"
    frame = status.get("currentFrame")
    warmup = status.get("warmupFrames")
    record = status.get("recordFrames")
    terrain = status.get("terrain") if isinstance(status.get("terrain"), dict) else None
    terrain_suffix = ""
    if terrain:
        interesting = {
            "missing": terrain.get("columnsMissingGeometry"),
            "pending": terrain.get("pendingBuilds"),
            "ready": terrain.get("readyMeshes"),
            "active": terrain.get("activeInstall"),
            "queue": terrain.get("captureQueue"),
        }
        if any(value not in (None, 0) for value in interesting.values()):
            terrain_suffix = " terrain " + " ".join(f"{key}={value}" for key, value in interesting.items())
    if state == "WARMUP":
        return f"{state}/{phase} frame {frame}/{warmup}{terrain_suffix}"
    if state == "RECORDING":
        return f"{state}/{phase} frame {frame}/{record}{terrain_suffix}"
    return f"{state}/{phase} frame {frame}{terrain_suffix}"


def post_result_process_wait_seconds(config: dict) -> int:
    profiling = config.get("profiling") or {}
    minimum = max(45, profiling_exit_delay(config) + 45)
    configured = profiling.get("postResultProcessWaitSeconds")
    if configured is not None:
        return max(minimum, int(configured))
    return minimum


def inter_run_cooldown_seconds(config: dict) -> int:
    return max(0, int(config.get("interRunCooldownSeconds") or 0))


def _configured_screenshots(config: dict) -> dict[str, Any]:
    screenshots = config.get("screenshots") or {}
    if not isinstance(screenshots, dict):
        raise RuntimeError("screenshots must be a JSON object")

    count = screenshots.get("count", config.get("screenshotFrameCount", config.get("screenshotCount", 5)))
    interval = screenshots.get("interval", config.get("screenshotInterval", 0))
    frames = screenshots.get("frames", config.get("screenshotFrames", []))
    if frames is None:
        frames = []
    if not isinstance(frames, list):
        raise RuntimeError("screenshots.frames must be a JSON array of frame numbers")

    normalized_frames = []
    for frame in frames:
        value = int(frame)
        if value < 0:
            raise RuntimeError("screenshots.frames entries must be zero or greater")
        normalized_frames.append(value)

    return {
        "screenshotFrameCount": max(0, int(count or 0)),
        "screenshotInterval": max(0, int(interval or 0)),
        "screenshotFrames": sorted(set(normalized_frames)),
    }


def sdk_has_tracy(dll: Path) -> bool:
    if not dll.exists():
        return False
    return b"___tracy_emit_zone_begin_alloc" in dll.read_bytes()


def tracy_package_version(release_version: str, arch: str) -> str:
    match = re.match(rf"^(?P<prefix>.+-{re.escape(arch)})-release$", release_version)
    return f"{match.group('prefix')}-debugoptimized" if match else ""


def packman_sdk_metadata(arch: str, variant: str) -> dict[str, str]:
    manifest = util.PROJECT_DIR / "packman" / "remix-sdk.packman.xml"
    metadata = {"packmanManifest": str(manifest)}
    if not manifest.exists():
        return metadata
    try:
        root = ET.parse(manifest).getroot()
    except ET.ParseError as exc:
        metadata["packmanManifestError"] = str(exc)
        return metadata

    dependency_name = f"remix-sdk-{arch}"
    for dep in root.findall("dependency"):
        if dep.attrib.get("name") != dependency_name:
            continue
        package = dep.find("package")
        if package is None:
            return metadata
        package_name = package.attrib.get("name", "")
        release_version = package.attrib.get("version", "")
        requested_version = tracy_package_version(release_version, arch) if variant == "tracy" else release_version
        metadata.update(
            {
                "packmanDependency": dependency_name,
                "packmanPackage": package_name,
                "packmanReleaseVersion": release_version,
                "packmanRequestedVersion": requested_version,
            }
        )
        if package_name and release_version:
            metadata["packmanReleasePackage"] = f"{package_name}@{release_version}"
        if package_name and requested_version:
            metadata["packmanRequestedPackage"] = f"{package_name}@{requested_version}"
        return metadata
    return metadata


def packman_cache_probe(package_name: str, version: str) -> dict[str, Any]:
    """Read-only probe of the local packman cache for a specific package version.

    Mirrors packmanconf's root resolution (PM_PACKAGES_ROOT, else <drive>\\packman-repo
    on Windows) without creating any directories.
    """
    if not package_name or not version:
        return {}
    root = os.environ.get("PM_PACKAGES_ROOT", "")
    if not root:
        drive, _ = os.path.splitdrive(str(util.PROJECT_DIR.resolve()))
        if not drive:
            return {}
        root = drive + os.sep + "packman-repo"
    cache_dir = Path(root) / "chk" / package_name / version
    return {
        "packmanCacheDir": str(cache_dir),
        "packmanCached": cache_dir.is_dir(),
    }


def source_git_metadata(source_path: str) -> dict[str, object]:
    if not source_path:
        return {}
    path = Path(source_path)
    if not path.exists():
        return {}
    cwd = path if path.is_dir() else path.parent
    root_result = util.run(["git", "rev-parse", "--show-toplevel"], cwd=cwd, capture=True, check=False)
    if root_result.returncode != 0:
        return {}
    root = Path((root_result.stdout or "").strip())
    commit = (util.run(["git", "rev-parse", "HEAD"], cwd=root, capture=True, check=False).stdout or "").strip()
    branch = (util.run(["git", "branch", "--show-current"], cwd=root, capture=True, check=False).stdout or "").strip()
    dirty = bool((util.run(["git", "status", "--porcelain"], cwd=root, capture=True, check=False).stdout or "").strip())
    return {
        "sourceGitRoot": str(root),
        "sourceGitBranch": branch,
        "sourceGitCommit": commit,
        "sourceGitCommitShort": commit[:8],
        "sourceGitDirty": dirty,
    }


def sdk_label(metadata: dict) -> str:
    parts = [metadata.get("variant", ""), metadata.get("arch", "")]
    if metadata.get("sourceType") == "local":
        source = str(metadata.get("sourcePath") or metadata.get("source") or "local")
        source_path = Path(source.replace("\n", " ").strip()) if source else Path("local")
        source_short = "/".join(source_path.parts[-2:]) if len(source_path.parts) >= 2 else str(source_path)
        parts.extend(["local", source_short])
        if metadata.get("sourceGitCommitShort"):
            dirty = "-dirty" if metadata.get("sourceGitDirty") else ""
            parts.append(f"{metadata['sourceGitCommitShort']}{dirty}")
    elif metadata.get("packmanRequestedPackage"):
        parts.append(metadata["packmanRequestedPackage"])
    elif metadata.get("packmanRequestedVersion"):
        parts.append(metadata["packmanRequestedVersion"])

    if metadata.get("dllSha256Short"):
        parts.append(f"d3d9:{metadata['dllSha256Short']}")
    return " ".join(str(part) for part in parts if part)


def fetch_sdk(variant: str, arch: str, auto_restage: bool = True) -> Path:
    if variant not in {"tracy", "release"}:
        raise RuntimeError(f"invalid SDK variant: {variant}")
    sdk_dir = util.PROJECT_DIR / "remix-runtime" / arch
    dll = sdk_dir / "d3d9.dll"
    local_override = (sdk_dir / ".local-sdk-source").exists()
    if local_override:
        source_status = runtime_sdk.sdk_source_status(arch)
        if source_status["needsRestage"]:
            # A developer iterating on dxvk rebuilds _output and launches; the staged
            # snapshot does not follow by itself, so by default it is refreshed here.
            # Off, the launch warns and uses the staged copy: the choice for someone
            # whose _output may still be mid-build when they launch.
            if auto_restage:
                print("The local dxvk SDK source differs from the staged copy; re-staging it (sdk.autoRestage).")
                runtime_sdk.restage_local_sdk(arch)
            else:
                print(
                    "WARNING: The local dxvk SDK source differs from the staged copy. "
                    "Refresh it with: tools\\rtx.bat sdk restage "
                    "(or turn automatic re-staging back on: tools\\rtx.bat sdk auto-restage on)"
                )
        elif source_status["state"] == "local-source-missing":
            print(
                f"WARNING: The recorded local dxvk SDK source is unavailable: {source_status['sourcePath']}. "
                "The existing staged copy will be used."
            )
        if variant == "release" or sdk_has_tracy(dll):
            print(f"Using local SDK override: {sdk_dir}")
        else:
            raise RuntimeError(
                f"Local {arch} SDK override is not Tracy-enabled: {sdk_dir}. "
                "Stage a Tracy-enabled local SDK or switch back with 'tools\\rtx.bat sdk packman'."
            )
    elif variant == "tracy":
        tracy.fetch_sdk(arch)
    else:
        runtime_sdk.stage_packman_sdk(arch)
    if not dll.exists():
        raise RuntimeError(f"SDK fetch completed but d3d9.dll is missing: {dll}")
    return dll


def sdk_metadata(dll: Path, variant: str) -> dict:
    target_dir = dll.parent
    sentinel = target_dir / ".local-sdk-source"
    stat = dll.stat()
    arch = target_dir.name
    source_type = "local" if sentinel.exists() else "packman"
    source = sentinel.read_text(encoding="utf-8", errors="replace").strip() if sentinel.exists() else ""
    dll_sha = runtime_sdk.file_sha256(dll)
    metadata: dict[str, object] = {
        "contract": "tracy-debug-sdk" if variant == "tracy" else "release-sdk",
        "arch": arch,
        "variant": variant,
        "sourceType": source_type,
        "source": source,
        "sdkDir": str(target_dir),
        "sdkDirResolved": str(target_dir.resolve()),
        "dll": str(dll),
        "dllResolved": str(dll.resolve()),
        "dllSizeBytes": stat.st_size,
        "dllModifiedUtc": dt.datetime.fromtimestamp(stat.st_mtime, dt.UTC).isoformat(),
        "dllSha256": dll_sha,
        "dllSha256Short": dll_sha[:12],
        "tracyEnabled": sdk_has_tracy(dll),
    }
    metadata.update(packman_sdk_metadata(arch, variant))
    if source:
        local_source = runtime_sdk.parse_local_sdk_source(source)
        metadata.update(
            {
                "sourcePath": local_source.get("source", ""),
                "stagedAtUtc": local_source.get("staged_at_utc", ""),
                "stagedBy": local_source.get("staged_by", ""),
            }
        )
        metadata.update(source_git_metadata(local_source.get("source", "")))
    metadata["label"] = sdk_label(metadata)
    return metadata


def write_sdk_version(sdk: dict) -> None:
    """Stage the runtime identity consumed by BenchmarkController result metadata."""
    bench_dir = util.RUN_DIR / "benchmark"
    bench_dir.mkdir(parents=True, exist_ok=True)
    (bench_dir / "dxvk_version.txt").write_text(sdk_label(sdk), encoding="utf-8")


def sdk_probe(variant: str, arch: str) -> dict[str, Any]:
    if variant not in {"tracy", "release"}:
        raise RuntimeError(f"invalid SDK variant: {variant}")
    sdk_dir = util.PROJECT_DIR / "remix-runtime" / arch
    dll = sdk_dir / "d3d9.dll"
    sentinel = sdk_dir / ".local-sdk-source"
    exists = dll.exists()
    local_override = sentinel.exists()
    tracy_enabled = sdk_has_tracy(dll) if exists else False
    probe: dict[str, Any] = {
        "variant": variant,
        "arch": arch,
        "sdkDir": str(sdk_dir),
        "dll": str(dll),
        "dllExists": exists,
        "sourceType": "local" if local_override else "packman",
        "localOverride": local_override,
        "tracyEnabled": tracy_enabled,
        "wouldFetch": not local_override,
        "ready": exists and (variant == "release" or tracy_enabled),
    }
    if local_override:
        probe["source"] = sentinel.read_text(encoding="utf-8", errors="replace").strip()
    probe.update(packman_sdk_metadata(arch, variant))
    if not local_override:
        probe.update(
            packman_cache_probe(
                str(probe.get("packmanPackage") or ""),
                str(probe.get("packmanRequestedVersion") or ""),
            )
        )
    return probe


def benchmark_run_plan(args) -> dict[str, Any]:
    config = load_config()
    ci = bool(getattr(args, "ci", False))
    version_override = None if ci else getattr(args, "version", None)
    selection, world_layout = configure_benchmark_selection_probe(
        config,
        world_override=getattr(args, "world", None),
        camera_override=getattr(args, "camera", None),
        version_override=version_override,
    )
    config = config_for_run(config, ci=ci)
    if getattr(args, "strip_entities", False):
        config["stripEntitiesOnStage"] = True
    runs = getattr(args, "runs", 0) or (int(config.get("runs") or 3) if ci else 1)
    arch = host_arch()
    sdk_variant = getattr(args, "sdk_variant", "release")
    sdk = sdk_probe(sdk_variant, arch)
    build_task = "build" if ci else "package"
    resource_pack_files = resolve_resource_pack_files(config, selection)
    screenshots = _configured_screenshots(config)
    return {
        "dryRun": bool(getattr(args, "dry_run", False)),
        "world": selection.world,
        "worldVersion": str(config.get("worldVersion") or ""),
        "worldPath": str(selection.world_path),
        "worldLayout": world_layout,
        "camera": selection.camera,
        "cameraPath": str(selection.camera_path),
        "resourcePacks": configured_resource_pack_names(config),
        "resourcePackFiles": resource_pack_files,
        "screenshots": screenshots,
        "runs": runs,
        "ci": ci,
        "arch": arch,
        "sdk": sdk,
        "build": {
            "enabled": not bool(getattr(args, "no_build", False)),
            "task": build_task,
            "args": [build_task, "-x", "test", f"-Parch={arch}", "--quiet"],
        },
        "stripEntitiesOnStage": strip_entities_on_stage(config),
        "resultDirPattern": str(util.RESULTS_DIR / "<timestamp>"),
    }


def doctor_report(
    *,
    world: str | None = None,
    camera: str | None = None,
    sdk_variant: str = "release",
) -> dict[str, Any]:
    checks: list[dict[str, Any]] = []

    def add(status: str, name: str, detail: str, **extra) -> None:
        checks.append({"status": status, "name": name, "detail": detail, **extra})

    def check_resource_packs(check_config: dict, selection: BenchmarkSelection | None) -> None:
        try:
            resource_pack_files = resolve_resource_pack_files(check_config, selection) if selection else []
            rp = configured_resource_pack(check_config)
            art_mode = str(rp.get("mode") or "none").strip().lower()
            art_detail = f"art pack {art_mode}"
            art_status = "ok"
            # doctor is read-only: never let a missing pinned pack download.
            try:
                art_pack = resolve_art_pack(check_config, fetch=False)
            except ArtPackNotStaged as exc:
                art_pack = None
                art_status = "warn"
                art_detail = f"art pack {art_mode} {exc.name}@{exc.version} is not staged; a run would fetch it"
            if art_pack is not None:
                resource_pack_files = append_art_pack_files(resource_pack_files, art_pack)
                if art_mode == "pinned":
                    art_detail = f"art pack pinned {rp.get('name')}@{rp.get('version')}"
                else:
                    art_detail = f"art pack local {art_pack.source_path}"
            extra = {"worldResourcePacksPath": str(selection.world_dir / RESOURCEPACKS_DIR_NAME)} if selection else {}
            add(
                art_status,
                "resource packs",
                f"enabled={', '.join(resource_pack_files) if resource_pack_files else 'none'} | {art_detail}",
                resourcePacks=resource_pack_files,
                artPackMode=art_mode,
                **extra,
            )
        except Exception as exc:
            add("error", "resource packs", str(exc))

    def check_screenshots(check_config: dict) -> None:
        try:
            screenshots = _configured_screenshots(check_config)
            add(
                "ok",
                "screenshots",
                f"count={screenshots['screenshotFrameCount']} explicit={screenshots['screenshotFrames']}",
                **screenshots,
            )
        except Exception as exc:
            add("error", "screenshots", str(exc))

    config: dict = {}
    try:
        config = load_config()
        add("ok", "config", f"Loaded {util.BENCHMARK_DIR / 'config.json'}")
    except Exception as exc:
        add("error", "config", str(exc))

    selection = None
    world_layout = ""
    if config:
        try:
            config_for_selection = copy.deepcopy(config)
            selection, world_layout = configure_benchmark_selection_probe(
                config_for_selection,
                world_override=world,
                camera_override=camera,
            )
            add(
                "ok",
                "fixture",
                f"world={selection.world} camera={selection.camera}",
                worldPath=str(selection.world_path),
                cameraPath=str(selection.camera_path),
                worldLayout=world_layout,
            )
            if world_layout == "raw":
                add(
                    "warn",
                    "fixture layout",
                    "World uses the legacy raw-save layout; launch/run/import will normalize it into world/.",
                )
            check_resource_packs(config_for_selection, selection)
            check_screenshots(config_for_selection)
        except WorldNotStaged as exc:
            add(
                "warn",
                "fixture",
                f"pinned world {exc.world}@{exc.version} is not staged; a run would fetch it, "
                "or fetch it now with tools\\rtx.bat world fetch. Its camera, world resource packs "
                "and own config are checked once it is staged.",
            )
            # The art pack and screenshot settings need no staged world.
            check_resource_packs(config, None)
            check_screenshots(config)
        except Exception as exc:
            add("error", "fixture", str(exc))

    java_home = detect_java_home()
    java_arch = java_runtime_arch()
    process_arch = windows_process_arch()
    if java_home:
        add("ok", "java", f"JAVA_HOME={java_home}", javaArch=java_arch or "", processArch=process_arch or "")
    else:
        add("error", "java", "Could not find a Java 21-style JDK with include/jni.h.")

    launcher_description = "Client launcher"
    try:
        # A launcher that cannot even describe itself is an error check, not a doctor traceback.
        launcher = client.active()
        launcher_description = launcher.describe()
        # Read-only like the rest of doctor: a blocked check never opens a sign-in window.
        launcher.check(interactive=False)
        add("ok", "launcher", launcher_description)
    except client.LaunchBlocked as exc:
        add("fail", "launcher", f"{launcher_description}: {exc}")
    except Exception as exc:
        add("error", "launcher", f"{launcher_description}: {exc}")

    arch = ""
    try:
        arch = host_arch()
        detail = f"Detected benchmark arch {arch}"
        if os.environ.get("BENCHMARK_ARCH"):
            detail += f" from BENCHMARK_ARCH={os.environ['BENCHMARK_ARCH']}"
        add("ok", "arch", detail, javaArch=java_arch or "", processArch=process_arch or "")
    except Exception as exc:
        add("error", "arch", str(exc))

    if arch:
        try:
            sdk = sdk_probe(sdk_variant, arch)
            source_status = runtime_sdk.sdk_source_status(arch)
            dll_exists = bool(sdk.get("dllExists"))
            tracy_enabled = bool(sdk.get("tracyEnabled"))
            local_override = bool(sdk.get("localOverride"))
            if source_status["needsRestage"]:
                add(
                    "warn",
                    "sdk source",
                    "The local dxvk source differs from the staged copy; run "
                    "'tools\\rtx.bat sdk restage' before testing it, or turn automatic "
                    "re-staging back on with 'tools\\rtx.bat sdk auto-restage on'.",
                    **source_status,
                )
            elif source_status["state"] == "local-source-missing":
                add(
                    "warn",
                    "sdk source",
                    f"The recorded local dxvk source is unavailable: {source_status['sourcePath']}",
                    **source_status,
                )
            elif source_status["state"] == "local-source-unknown":
                add(
                    "warn",
                    "sdk source",
                    "The local SDK sentinel has no readable source path; repair it with "
                    "'tools\\rtx.bat sdk local PATH'.",
                    **source_status,
                )
            if sdk_variant == "tracy" and local_override and not tracy_enabled:
                add("error", "sdk", f"Local {arch} SDK override is not Tracy-enabled.", **sdk)
            elif dll_exists and (sdk_variant == "release" or tracy_enabled):
                add("ok", "sdk", f"{sdk_variant}/{arch} SDK is staged.", **sdk)
            elif sdk_variant == "tracy" and not (config.get("sdk") or {}).get("tracyRemote"):
                add(
                    "warn",
                    "sdk",
                    f"Tracy/{arch} SDK is not staged; fetching requires configured 'sdk.tracyRemote', "
                    "or stage a local Tracy-enabled SDK with 'tools\\rtx.bat sdk local --path PATH'.",
                    **sdk,
                )
            elif dll_exists and sdk_variant == "tracy" and not local_override and sdk.get("packmanRequestedPackage"):
                if "packmanCached" in sdk:
                    cache_note = (
                        " (already in the local packman cache)"
                        if sdk["packmanCached"]
                        else " (not in the local packman cache; packman would download it)"
                    )
                else:
                    cache_note = ""
                add(
                    "warn",
                    "sdk",
                    f"Staged {arch} SDK is the release variant (no Tracy symbols); "
                    f"a run would fetch {sdk['packmanRequestedPackage']}{cache_note}.",
                    **sdk,
                )
            elif dll_exists:
                add("warn", "sdk", f"{sdk_variant}/{arch} SDK is staged but Tracy symbols were not found.", **sdk)
            else:
                add("warn", "sdk", f"{sdk_variant}/{arch} SDK is not staged yet; benchmark will fetch it.", **sdk)
        except Exception as exc:
            add("error", "sdk", str(exc))

    if sdk_variant == "tracy":
        capture = util.TOOLS_DIR / "tracy-tools" / "capture" / "capture.exe"
        csvexport = util.TOOLS_DIR / "tracy-tools" / "csvexport" / "csvexport.exe"
        if capture.exists() and csvexport.exists():
            add("ok", "tracy tools", "Tracy capture and csvexport tools are staged.")
        else:
            add("warn", "tracy tools", "Tracy tools are not staged yet; benchmark will fetch them when needed.")

    return {
        "ok": not any(check["status"] in ("error", "fail") for check in checks),
        "world": selection.world if selection else "",
        "camera": selection.camera if selection else "",
        "worldLayout": world_layout,
        "sdkVariant": sdk_variant,
        "checks": checks,
    }


def tracy_tools(dll: Path, required: bool = True) -> dict | None:
    if not sdk_has_tracy(dll):
        if required:
            raise RuntimeError(f"Benchmark requires Tracy-enabled SDK, but no Tracy symbols were found in {dll}")
        return None
    tracy.fetch_tools()
    capture = util.TOOLS_DIR / "tracy-tools" / "capture" / "capture.exe"
    csvexport = util.TOOLS_DIR / "tracy-tools" / "csvexport" / "csvexport.exe"
    if not capture.exists() or not csvexport.exists():
        if required:
            raise RuntimeError("Tracy tools were not fetched correctly")
        return None
    return {"capture": capture, "csvexport": csvexport}


def copytree_replace(src: Path, dst: Path) -> None:
    if dst.exists():
        util.ensure_inside(dst, util.RUN_DIR)
        shutil.rmtree(dst)
    shutil.copytree(src, dst)


def _truthy(value: object) -> bool:
    if isinstance(value, bool):
        return value
    if value is None:
        return False
    return str(value).strip().lower() in {"1", "true", "yes", "on"}


def strip_entities_on_stage(config: dict) -> bool:
    if "stripEntitiesOnStage" in config:
        return _truthy(config.get("stripEntitiesOnStage"))
    sanitizer = config.get("worldSanitizer") or {}
    if isinstance(sanitizer, dict):
        if "stripEntitiesOnStage" in sanitizer:
            return _truthy(sanitizer.get("stripEntitiesOnStage"))
        if "stripEntities" in sanitizer:
            return _truthy(sanitizer.get("stripEntities"))
    return False


def normalize_resource_pack_name(value: object) -> str:
    text = str(value or "").strip()
    if text.lower().endswith(".zip"):
        text = text[:-4]
    return normalize_benchmark_name(text, "resource pack")


def configured_resource_pack_names(config: dict) -> list[str]:
    raw = config.get("resourcePacks", [])
    if raw is None:
        raw = []
    if not isinstance(raw, list):
        raise RuntimeError("resourcePacks must be a JSON array of resource pack names")

    names: list[str] = []
    seen: set[str] = set()
    for value in raw:
        name = normalize_resource_pack_name(value)
        if name in seen:
            raise RuntimeError(f"resourcePacks contains duplicate resource pack '{name}'")
        seen.add(name)
        names.append(name)
    return names


def discover_world_resource_packs(selection: BenchmarkSelection | None) -> dict[str, ResourcePackSource]:
    if selection is None:
        return {}
    packs_dir = selection.world_dir / RESOURCEPACKS_DIR_NAME
    if not packs_dir.exists():
        return {}
    if not packs_dir.is_dir():
        raise RuntimeError(f"{packs_dir} must be a directory")

    sources: dict[str, ResourcePackSource] = {}
    for child in sorted(packs_dir.iterdir(), key=lambda path: path.name.lower()):
        if child.is_dir():
            name = normalize_benchmark_name(child.name, "resource pack")
            file_name = child.name
        elif child.is_file() and child.suffix.lower() == ".zip":
            name = normalize_resource_pack_name(child.name)
            file_name = child.name
        else:
            continue

        if name in sources:
            raise RuntimeError(
                f"benchmark world '{selection.world}' has multiple resource packs named '{name}' under {packs_dir}"
            )
        sources[name] = ResourcePackSource(name=name, file_name=file_name, source_path=child)
    return sources


def resolve_resource_pack_files(config: dict, selection: BenchmarkSelection | None) -> list[str]:
    available = {name: source.file_name for name, source in discover_world_resource_packs(selection).items()}

    names = configured_resource_pack_names(config)
    if "rtx-remix" in names and "rtx-remix" not in available:
        names.remove("rtx-remix")
        print(
            "Ignoring retired baseline resource pack 'rtx-remix'; its textures are built into the mod.", file=sys.stderr
        )
    missing = [name for name in names if name not in available]
    if missing:
        world_label = selection.world if selection is not None else "(main menu)"
        packs_path = selection.world_dir / RESOURCEPACKS_DIR_NAME if selection is not None else "(none)"
        raise RuntimeError(
            f"resourcePacks for benchmark world '{world_label}' references missing pack(s): "
            f"{', '.join(missing)}. Put unpacked packs or .zip packs under "
            f"{packs_path}."
        )
    return [available[name] for name in names]


def remove_staged_resource_pack(root: Path, name: str) -> None:
    for candidate in (root / name, root / f"{name}.zip"):
        if not candidate.exists():
            continue
        util.ensure_inside(candidate, util.RUN_DIR)
        if candidate.is_dir():
            shutil.rmtree(candidate)
        else:
            candidate.unlink()


def copy_resource_pack(source: ResourcePackSource, root: Path) -> None:
    remove_staged_resource_pack(root, source.name)
    dst = root / source.file_name
    if source.source_path.is_dir():
        shutil.copytree(source.source_path, dst)
        if source.name == "rtx-remix":
            # Gradle's migration must not mistake a world-authored copy for the old baseline.
            (dst / ".rtx-remix-world-pack").touch()
    else:
        root.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source.source_path, dst)


def append_art_pack_files(enabled: list[str], art_pack: ResourcePackSource | None) -> list[str]:
    # The art pack goes last to override every enabled pack and the mod's built-in baseline.
    if art_pack is None:
        return list(enabled)
    return [*enabled, art_pack.file_name]


def remove_legacy_baseline_pack(root: Path) -> None:
    legacy = root / "rtx-remix"
    if (legacy / ".rtx-remix-world-pack").exists():
        return
    metadata = legacy / "pack.mcmeta"
    if not metadata.is_file() or not (legacy / "BLOCK_TEXTURES.txt").is_file():
        return
    try:
        contents = json.loads(metadata.read_text(encoding="utf-8-sig"))
    except json.JSONDecodeError, UnicodeError:
        return
    if contents != {"pack": {"pack_format": 34, "description": "RTX Remix LabPBR Development Pack"}}:
        return
    util.ensure_inside(legacy, util.RUN_DIR)
    shutil.rmtree(legacy)


def stage_resource_packs(
    config: dict,
    selection: BenchmarkSelection | None,
    run_dir: Path,
    art_pack: ResourcePackSource | None = None,
) -> list[str]:
    root = run_dir / RESOURCEPACKS_DIR_NAME
    root.mkdir(parents=True, exist_ok=True)

    world_packs = discover_world_resource_packs(selection)
    if "rtx-remix" not in world_packs:
        remove_legacy_baseline_pack(root)
    for source in world_packs.values():
        copy_resource_pack(source, root)

    enabled = resolve_resource_pack_files(config, selection)
    if art_pack is not None:
        copy_resource_pack(art_pack, root)
        enabled = append_art_pack_files(enabled, art_pack)
    return enabled


def sanitize_staged_world(config: dict, selection: BenchmarkSelection, world_dst: Path) -> list[dict[str, object]]:
    actions: list[dict[str, object]] = []

    session_lock = world_dst / "session.lock"
    if session_lock.exists():
        session_lock.unlink()
        actions.append(
            {
                "action": "removeSessionLock",
                "path": str(session_lock),
                "reason": "Staged benchmark worlds must not inherit stale locks from fixture captures.",
            }
        )

    filefix_dir = world_dst / "filefix"
    if filefix_dir.exists():
        shutil.rmtree(filefix_dir)
        actions.append(
            {
                "action": "removeFileFixScratch",
                "path": str(filefix_dir),
                "reason": "Modern Minecraft file-fixer scratch data must not be reused between staged runs.",
            }
        )

    if strip_entities_on_stage(config):
        entities_dir = world_dst / "entities"
        if entities_dir.exists():
            file_count = sum(1 for path in entities_dir.rglob("*") if path.is_file())
            byte_count = sum(path.stat().st_size for path in entities_dir.rglob("*") if path.is_file())
            shutil.rmtree(entities_dir)
            actions.append(
                {
                    "action": "stripEntities",
                    "path": str(entities_dir),
                    "filesRemoved": file_count,
                    "bytesRemoved": byte_count,
                    "reason": "Saved entity storage was removed from the staged copy only.",
                }
            )

    if actions:
        bench_dir = util.RUN_DIR / "benchmark"
        bench_dir.mkdir(parents=True, exist_ok=True)
        util.write_json(
            bench_dir / "world-sanitize.json",
            {
                "world": selection.world,
                "sourceWorldPath": str(selection.world_path),
                "stagedWorldPath": str(world_dst),
                "actions": actions,
            },
        )

    return actions


def install_fixtures(
    config: dict,
    selection: BenchmarkSelection,
    *,
    pin_display: bool = False,
    tolerate_art_pack_fetch_failure: bool = False,
) -> None:
    run_dir = util.RUN_DIR
    saves = run_dir / "saves"
    saves.mkdir(parents=True, exist_ok=True)
    world_dst = saves / BENCHMARK_SAVE_NAME
    copytree_replace(selection.world_path, world_dst)
    sanitize_staged_world(config, selection, world_dst)

    # Runtime camera paths are grouped by the exact fixture directory name so
    # multiple world versions cannot collide (for example DemoWorld_v0.8.4).
    cam_dst = run_dir / "camera-paths" / selection.world_dir.name
    copytree_replace(selection.cameras_dir, cam_dst)

    art_pack = resolve_art_pack(config, tolerate_fetch_failure=tolerate_art_pack_fetch_failure)
    resource_pack_files = stage_resource_packs(config, selection, run_dir, art_pack)

    write_options(config, resource_pack_files=resource_pack_files, pin_display=pin_display)
    write_rtx_settings(config, run_dir=run_dir)


def minecraft_fov_option_value(value: object) -> str:
    if not isinstance(value, int | float | str):
        raise RuntimeError(f"invalid benchmark FOV value: {value!r}")
    fov = float(value)
    if -1.0 <= fov <= 1.0:
        option_value = fov
    else:
        degrees = min(110.0, max(30.0, fov))
        option_value = (degrees - 70.0) / 40.0
    if option_value == 0:
        option_value = 0.0
    return f"{option_value:.12g}"


def configured_preset(config: dict) -> str:
    """The preset the run is pinned to, validated against PRESET_NAMES.

    A typo must not silently measure a different quality tier: an unrecognised
    name is the kind of one-line mistake that later reads as a real regression.
    """
    render = config.get("render") or {}
    name = str(render.get("preset") or CUSTOM_PRESET).strip() or CUSTOM_PRESET
    if name not in PRESET_NAMES:
        raise RuntimeError(f"unknown quality preset {name!r} in render.preset; available: {', '.join(PRESET_NAMES)}")
    return name


def configured_preset_value(config: dict) -> str | None:
    """The pinned preset that selects Remix's tier for this launch only, or None for Custom.

    The mod writes the tier to Remix's session settings without saving it, so a
    later launch without a preset starts from Remix's own choice again. Custom
    passes nothing.
    """
    preset = configured_preset(config)
    return None if preset == CUSTOM_PRESET else preset


def write_rtx_settings(config: dict, *, run_dir: Path | None = None) -> None:
    """Pin the mod settings in render.modSettings into <gameDir>/config/rtxremix.json.

    Settings the config does not name keep the file's values, so a developer's
    other choices survive. A file the mod would not read, another version or
    unparseable, starts over, since the mod would discard its values anyway.
    """
    pinned = (config.get("render") or {}).get("modSettings") or {}
    target = (util.RUN_DIR if run_dir is None else run_dir) / "config" / RTX_SETTINGS_FILE

    settings: dict[str, Any] = {}
    if target.is_file():
        try:
            existing = json.loads(target.read_text(encoding="utf-8"))
        except OSError, ValueError:
            existing = {}
        if isinstance(existing, dict) and existing.get("version") == RTX_SETTINGS_VERSION:
            found = existing.get("settings")
            if isinstance(found, dict):
                settings = found
    settings.pop(STALE_RTX_PRESET_SETTING_ID, None)
    settings.update(pinned)

    if not settings:
        target.unlink(missing_ok=True)
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        json.dumps({"version": RTX_SETTINGS_VERSION, "settings": settings}, indent=2) + "\n",
        encoding="utf-8",
    )


def fullscreen_resolution_option_value(width: int, height: int, *, pin_display: bool) -> str:
    """The options.txt value for fullscreenResolution.

    Unpinned writes an empty value, which Minecraft reads as the "Current"
    entry in the video settings: VideoMode.fromString fails to parse it and
    Monitor.findClosestVideoMode falls through to the monitor's own mode. A
    play launch wants that, so going fullscreen follows whichever display the
    player is actually on.

    Pinned mirrors the windowed size, fixing the display mode so two machines
    measure the same framebuffer. The suffix is refreshRate:bitDepth, so this
    pins 60 Hz as well."""
    if not pin_display:
        return ""
    if not (width and height):
        raise RuntimeError(
            "cannot pin the display without a window to match: set render.overrideWidth and render.overrideHeight"
        )
    return f"{width}x{height}@60:24"


def write_options(config: dict, *, resource_pack_files: list[str] | None = None, pin_display: bool = False) -> None:
    """Write options.txt in the game directory (util.RUN_DIR) from `config`.

    `pin_display` belongs to the measurement session rather than to config,
    because both paths load the same merged config: benchmark/config.json is
    merged for play launches too, so a config key could not tell them apart."""
    render = config.get("render") or {}
    override_width = max(0, int(render.get("overrideWidth") or 0))
    override_height = max(0, int(render.get("overrideHeight") or 0))
    fullscreen_resolution = fullscreen_resolution_option_value(override_width, override_height, pin_display=pin_display)
    if resource_pack_files is None:
        resource_pack_files = configured_resource_pack_names(config)
    # The mod's built-in baseline rides in Fabric's pack, below every file/ pack.
    # Fabric appends its pack above user packs if options.txt omits it.
    resource_pack_options = ["vanilla", "fabric", *(f"file/{file_name}" for file_name in resource_pack_files)]
    values = {
        "enableVsync": "true" if render.get("vsync") else "false",
        "fullscreen": "true" if render.get("fullscreen") else "false",
        "fov": minecraft_fov_option_value(render.get("fov", 70)),
        "graphicsMode": str(render.get("graphicsMode", 1)),
        "maxFps": str(render.get("maxFps", 260)),
        "renderDistance": str(render.get("renderDistance", 32)),
        "simulationDistance": str(render.get("simulationDistance", 12)),
        "pauseOnLostFocus": "false",
        "resourcePacks": json.dumps(resource_pack_options, separators=(",", ":")),
        "onboardAccessibility": "false",
        "tutorialStep": "none",
        "skipMultiplayerWarning": "true",
        "hideBundleTutorial": "true",
        "overrideWidth": str(override_width),
        "overrideHeight": str(override_height),
        "fullscreenResolution": fullscreen_resolution,
    }
    options = util.RUN_DIR / "options.txt"
    if options.exists():
        lines = options.read_text(encoding="utf-8", errors="replace").splitlines()
        seen = set()
        out = []
        for line in lines:
            key = line.split(":", 1)[0] if ":" in line else ""
            if key in values:
                out.append(f"{key}:{values[key]}")
                seen.add(key)
            else:
                out.append(line)
        for key, value in values.items():
            if key not in seen:
                out.append(f"{key}:{value}")
        options.write_text("\n".join(out) + "\n", encoding="utf-8")
        return

    defaults = {
        "version": "3955",
        "ao": "true",
        "biomeBlendRadius": "2",
        "prioritizeChunkUpdates": "1",
        "gamma": "0.5",
        "guiScale": "3",
        "mipmapLevels": "4",
        **values,
    }
    options.parent.mkdir(parents=True, exist_ok=True)
    options.write_text("\n".join(f"{k}:{v}" for k, v in defaults.items()) + "\n", encoding="utf-8")


def validate_benchmark_display(config: dict, benchmark_json: Path) -> None:
    """Reject results captured at a different framebuffer size than requested.

    Minecraft can silently retain its 854x480 startup window when display-mode
    setup fails, producing plausible but incomparable benchmark numbers.
    """
    render = config.get("render") or {}
    expected_width = max(0, int(render.get("overrideWidth") or 0))
    expected_height = max(0, int(render.get("overrideHeight") or 0))
    if bool(expected_width) != bool(expected_height):
        raise RuntimeError("benchmark render overrideWidth and overrideHeight must be set together")

    document = util.read_json(benchmark_json)
    metadata = document.get("metadata") or {}
    if not isinstance(metadata, dict):
        raise RuntimeError(f"{benchmark_json} has no benchmark metadata")

    if expected_width and expected_height:
        expected_resolution = f"{expected_width}x{expected_height}"
        actual_resolution = str(metadata.get("resolution") or "")
        if actual_resolution != expected_resolution:
            raise RuntimeError(
                "benchmark display validation failed: "
                f"requested {expected_resolution}, captured {actual_resolution or 'unknown'}"
            )


def degenerate_frame_report(benchmark_json: Path) -> str:
    """Detect recordings contaminated by non-rendered (near-free) frames.

    When recording starts while the world-load overlay is still up, the early
    frames render almost nothing, inflating every aggregate. More than 1% such
    frames invalidates the run. Returns an empty string when the recording
    looks sane."""
    per_frame = util.read_json(benchmark_json).get("perFrame")
    if not isinstance(per_frame, list) or not per_frame:
        return ""
    times_ms = [nanos / 1e6 for nanos in per_frame]
    cheap = sum(1 for t in times_ms if t < DEGENERATE_FRAME_FLOOR_MS)
    floor_ms = DEGENERATE_FRAME_FLOOR_MS
    if cheap * 100 <= len(times_ms):
        return ""
    return (
        f"{cheap} of {len(times_ms)} frames below the {floor_ms:.2f} ms floor "
        f"(min {min(times_ms):.2f} ms); recording likely started on a loading screen"
    )


def write_auto_trigger(
    config: dict,
    selection: BenchmarkSelection,
    exit_on_complete: bool,
    *,
    mode: str = "benchmark",
    disable_world_saving: bool | None = None,
    extra: dict[str, Any] | None = None,
) -> None:
    if disable_world_saving is None:
        disable_world_saving = mode == "benchmark"
    trigger = {
        "mode": mode,
        "warmupFrames": config.get("warmupFrames", 600),
        "warmupLap": config.get("warmupLap", False),
        "benchmarkWorld": selection.world,
        "worldVersion": str(config.get("worldVersion") or selection.version or ""),
        "cameraStorageKey": selection.world_dir.name,
        # The mod derives no fixture paths of its own, so a managed recording
        # learns where its canonical copy belongs from this absolute path.
        "cameraCanonicalDirectory": str(selection.cameras_dir),
        # Shot sets outlive world fixtures and are git-tracked, so marks made
        # in game land in the repository rather than in the staged fixture.
        "shotSetDirectory": str(shot_set_directory()),
        "camera": selection.camera,
        "cameraPath": selection.camera,
        "exitOnComplete": exit_on_complete,
        "postResultExitDelaySeconds": profiling_exit_delay(config),
        "exitMode": profiling_exit_mode(config),
        "quickPlayWorldLoad": True,
        "disableWorldSaving": disable_world_saving,
        **_configured_screenshots(config),
        **(extra or {}),
    }
    util.write_json(util.RUN_DIR / "benchmark" / "auto_benchmark.json", trigger)


def shot_set_directory() -> Path:
    """Home of the git-tracked shot sets; created so a first mark has somewhere to land."""
    directory = util.ASSETS_DIR / "shots"
    directory.mkdir(parents=True, exist_ok=True)
    return directory


def world_records() -> list[dict[str, object]]:
    config = load_config()
    records = []
    for name in available_worlds():
        pin = world_pin_version(config, name)
        status = world_status(name, pinned_version=pin, version=pin, config=config)
        cameras = available_cameras(name, pin) if status["staged"] else []
        records.append(
            {
                "name": name,
                "pinned": status["pinned"],
                "staged": status["staged"],
                "stagedVersion": status["stagedVersion"],
                "modified": status["modified"],
                "cameras": cameras,
            }
        )
    return records


def import_world(source: str, *, name: str | None = None, replace: bool = False) -> Path:
    source_path = util.project_path(source)
    if not source_path.exists():
        raise RuntimeError(f"world source does not exist: {source_path} ({util.RELATIVE_PATH_BASE})")
    source_world = source_path / WORLD_DIR_NAME if is_minecraft_world(source_path / WORLD_DIR_NAME) else source_path
    if not is_minecraft_world(source_world):
        raise RuntimeError(f"world source is not a Minecraft save: {source_path}")

    world_name = normalize_benchmark_name(name or source_path.name, "world")
    target_dir = benchmark_worlds_dir() / world_name
    target_world = target_dir / WORLD_DIR_NAME
    if source_world.resolve() == target_dir.resolve():
        return normalize_world_layout(world_name)
    if target_world.exists():
        if not replace:
            raise RuntimeError(f"benchmark world '{world_name}' already exists; pass --replace to update its world/")
        if source_world.resolve() == target_world.resolve():
            (target_dir / CAMERAS_DIR_NAME).mkdir(parents=True, exist_ok=True)
            return target_dir
        util.ensure_inside(target_world, benchmark_worlds_dir())
        shutil.rmtree(target_world)

    target_dir.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source_world, target_world)
    (target_dir / CAMERAS_DIR_NAME).mkdir(exist_ok=True)
    return target_dir


def update_world(world: str, *, source: str | None = None) -> Path:
    """Replace an existing fixture's world/ from a save, by default the staged benchmark save.

    `world` names the fixture directory itself. A name with no such directory
    raises rather than creating a second fixture beside the one meant."""
    world = normalize_benchmark_name(world, "world")
    worlds_dir = benchmark_worlds_dir()
    if not (worlds_dir / world).is_dir():
        existing = sorted(path.name for path in worlds_dir.iterdir() if path.is_dir()) if worlds_dir.is_dir() else []
        raise RuntimeError(
            f"No fixture directory '{world}' under {worlds_dir} to update. "
            f"Fixture directories: {', '.join(existing) or 'none'}. Use world import to create a fixture."
        )
    source_path = util.project_path(source) if source else util.RUN_DIR / "saves" / BENCHMARK_SAVE_NAME
    return import_world(str(source_path), name=world, replace=True)


def staged_session_lock_path() -> Path:
    return util.RUN_DIR / "saves" / BENCHMARK_SAVE_NAME / "session.lock"


def file_lock_is_held(path: Path) -> bool:
    """True when another process holds an OS lock on path (e.g. Minecraft's session.lock)."""
    if not path.is_file():
        return False
    try:
        with path.open("r+b") as handle:
            if os.name == "nt":
                import msvcrt

                try:
                    msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                except OSError:
                    return True
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl

                try:
                    fcntl.lockf(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except OSError:
                    return True
                fcntl.lockf(handle, fcntl.LOCK_UN)
    except OSError:
        return True
    return False


def java_process_command_lines(*, required: bool = False) -> list[tuple[int, str]]:
    """Snapshot of running java/javaw command lines (Windows only).

    With `required`, a failed or malformed process query raises instead of
    looking like a machine with no Java processes.
    """
    if os.name != "nt":
        return []
    script = (
        "Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='javaw.exe'\""
        " | Select-Object ProcessId,CommandLine | ConvertTo-Json -Compress"
    )
    try:
        result = util.run(
            ["powershell", "-NoProfile", "-Command", script],
            capture=True,
            check=False,
            timeout=30,
            encoding=util.console_encoding(),
        )
    except RuntimeError:
        if required:
            raise
        return []
    text = (result.stdout or "").strip()
    if result.returncode != 0:
        if required:
            raise RuntimeError(f"Java process query failed with exit code {result.returncode}")
        return []
    if not text:
        return []
    try:
        data = json.loads(text)
    except ValueError as exc:
        if required:
            raise RuntimeError("Java process query returned malformed JSON") from exc
        return []
    entries = data if isinstance(data, list) else [data]
    processes = []
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        pid = entry.get("ProcessId")
        command_line = entry.get("CommandLine")
        if isinstance(pid, int) and isinstance(command_line, str) and command_line:
            processes.append((pid, command_line))
    return processes


# Gradle daemons and workers may reference the checkout without owning the game
# directory; only Minecraft clients consume auto_benchmark.json and hold
# session.lock.
GRADLE_HELPER_MARKERS = ("org.gradle.launcher.daemon", "org.gradle.process.internal.worker")
LIVE_CLIENT_SKIP_ENV = "BENCHMARK_SKIP_LIVE_CLIENT_CHECK"


# The checkout's own directories a development client's command line names: the Prism data folder for a game Prism
# starts, and Loom's launch configuration under .gradle, the mod's classes under build, and run for Gradle's runClient.
CHECKOUT_CLIENT_DIRS = ("run-prism", ".gradle", "build", "run")


def find_checkout_java_processes(
    processes: list[tuple[int, str]],
    project_dir: Path | None = None,
    game_dir: Path | None = None,
) -> list[tuple[int, str]]:
    """The Java processes whose command lines name this checkout's client files.

    The checkout path alone is not enough: it begins the paths of a nested
    worktree and of a sibling whose name extends it. Installed Prism can keep
    the game outside the checkout, so its resolved instance is an additional
    exact ownership path.
    """
    root = str(project_dir or util.PROJECT_DIR).lower().replace("/", "\\").rstrip("\\")
    owned = re.compile(re.escape(root) + r"\\(?:" + "|".join(map(re.escape, CHECKOUT_CLIENT_DIRS)) + r")\\")
    instance = (
        None
        if game_dir is None
        else re.compile(re.escape(str(game_dir.parent).lower().replace("/", "\\").rstrip("\\")) + r"\\")
    )
    matches = []
    for pid, command_line in processes:
        normalized = command_line.lower().replace("/", "\\")
        if not owned.search(normalized) and (instance is None or not instance.search(normalized)):
            continue
        if any(marker in normalized for marker in GRADLE_HELPER_MARKERS):
            continue
        matches.append((pid, command_line))
    return matches


def detect_live_client() -> str | None:
    """Describe a Minecraft client already running from this checkout, or None."""
    lock_path = staged_session_lock_path()
    if file_lock_is_held(lock_path):
        return f"the staged benchmark world is locked ({lock_path})"
    matches = find_checkout_java_processes(java_process_command_lines(), game_dir=util.RUN_DIR)
    if matches:
        pids = ", ".join(str(pid) for pid, _ in matches)
        return f"java process(es) {pids} reference this checkout"
    return None


def ensure_no_live_client(*, ci: bool = False) -> None:
    """Abort before staging/launch when a client from this checkout is still running.

    A live client's BenchmarkController consumes benchmark/auto_benchmark.json
    in the game directory from its tick loop and the open world holds an OS
    lock on the staged save's session.lock, so a new launch dies later as an
    opaque ProcessExited / missing-benchmark.json failure instead of failing
    here with a clear cause."""
    if env_bool(LIVE_CLIENT_SKIP_ENV):
        print(f"Skipping live-client preflight ({LIVE_CLIENT_SKIP_ENV} is set).")
        return
    reason = detect_live_client()
    if not reason:
        return
    message = (
        f"another Minecraft client is running from this checkout ({reason}); "
        f"close it before benchmarking, or set {LIVE_CLIENT_SKIP_ENV}=1 to bypass this check"
    )
    if ci:
        print(f"WARNING: {message}. Continuing because CI kills leftover processes before launch.")
        return
    raise RuntimeError(message)


# A dxvk-cache left by an earlier session can crash the runtime on its
# dxvk-cs thread shortly after the world loads, so every launch starts without
# one. The cache is named after the executable: java.exe under Gradle, javaw.exe
# under Prism. The related session logs are preserved alongside the cache.
STALE_RUNTIME_STATE_FILES = ("java.dxvk-cache", "javaw.dxvk-cache", "nrc_session_log.txt", "metrics.txt")
RUN_STATE_ASIDE_DIR_NAME = "run-state-aside"
RUN_STATE_ASIDE_KEEP = 5


def park_stale_runtime_state() -> Path | None:
    """Move the previous session's runtime state files out of the game directory
    before a launch.

    Whichever of STALE_RUNTIME_STATE_FILES exist move together into
    run-state-aside/<timestamp>/ in the game directory, so the evidence
    survives, and only the newest RUN_STATE_ASIDE_KEEP sets are kept. Returns
    the new set's directory, or None when the game directory held none of the
    files."""
    present = [util.RUN_DIR / name for name in STALE_RUNTIME_STATE_FILES if (util.RUN_DIR / name).is_file()]
    if not present:
        return None
    aside = util.RUN_DIR / RUN_STATE_ASIDE_DIR_NAME
    parked = aside / timestamp_id()
    parked.mkdir(parents=True, exist_ok=True)
    for path in present:
        path.replace(parked / path.name)
    prune_run_state_aside(aside)
    names = ", ".join(path.name for path in present)
    print(f"Parked stale runtime state ({names}) under {parked}")
    return parked


def prune_run_state_aside(aside: Path) -> None:
    sets = sorted(path for path in aside.iterdir() if path.is_dir())
    for stale in sets[:-RUN_STATE_ASIDE_KEEP]:
        util.ensure_inside(stale, util.RUN_DIR)
        shutil.rmtree(stale)


def launch_client_for_selection(
    *,
    world: str | None = None,
    mode: str = "play",
    require_camera: bool = False,
    quiet: bool = False,
    sdk_variant: str = "release",
    strip_entities: bool = False,
    version: str | None = None,
) -> BenchmarkSelection:
    config = load_config()
    launcher = client.active()
    ensure_no_live_client()
    launcher.check()
    ensure_world_staged(config, resolve_world_name(config, world), version=version)
    selection = configure_benchmark_selection(
        config,
        world_override=world,
        version_override=version,
        require_camera=require_camera,
    )
    if strip_entities:
        config["stripEntitiesOnStage"] = True
    arch = host_arch()
    sdk_dll = fetch_sdk(sdk_variant, arch, auto_restage=runtime_sdk.configured_sdk_auto_restage(config))
    launcher.prepare(arch, build=True)
    # Interactive: start without a configured art pack that cannot be downloaded.
    # An explicit --resource-pack <name>@<version> is a request, so it stays fatal.
    install_fixtures(config, selection, tolerate_art_pack_fetch_failure=not explicit_resource_pack_version())
    write_sdk_version(sdk_metadata(sdk_dll, sdk_variant))
    write_auto_trigger(config, selection, exit_on_complete=False, mode=mode, disable_world_saving=False)

    park_stale_runtime_state()
    print(
        f"Launching Minecraft: world={selection.world} camera=(none) "
        f"mode={mode} sdk={sdk_variant}/{arch} ({sdk_dll.parent})"
    )
    launch_interactive(
        client.LaunchRequest(
            arch=arch,
            env=gradle_env(),
            quick_play_world=BENCHMARK_SAVE_NAME,
            preset=configured_preset_value(config),
            quiet=quiet,
        )
    )
    return selection


def launch_client_main_menu(
    *,
    sdk_variant: str = "release",
    quiet: bool = False,
) -> None:
    """Launch the client into the vanilla main menu (no quick-play world).

    Stages any art pack (none/local/pinned per config), writes options.txt, and
    starts the game through the active launcher without a quick-play world.
    """
    config = load_config()
    launcher = client.active()
    ensure_no_live_client()
    launcher.check()
    arch = host_arch()
    sdk_dll = fetch_sdk(sdk_variant, arch, auto_restage=runtime_sdk.configured_sdk_auto_restage(config))
    launcher.prepare(arch, build=True)

    # Interactive: start without a configured art pack that cannot be downloaded.
    # An explicit --resource-pack <name>@<version> is a request, so it stays fatal.
    art_pack = resolve_art_pack(config, tolerate_fetch_failure=not explicit_resource_pack_version())
    util.RUN_DIR.mkdir(parents=True, exist_ok=True)
    resource_pack_files = stage_resource_packs(config, None, util.RUN_DIR, art_pack)
    write_options(config, resource_pack_files=resource_pack_files)
    write_rtx_settings(config)

    park_stale_runtime_state()
    print(f"Launching Minecraft (main menu): sdk={sdk_variant}/{arch} ({sdk_dll.parent})")
    launch_interactive(
        client.LaunchRequest(arch=arch, env=gradle_env(), preset=configured_preset_value(config), quiet=quiet)
    )


def launch_with_resource_pack_override(
    *,
    world: str | None,
    sdk_variant: str,
    resource_pack: str | None,
    version: str | None,
    quiet: bool,
) -> None:
    if resource_pack is not None:
        set_runtime_resource_pack_override(resource_pack)
    launch_client_for_selection(
        world=world,
        mode="play",
        sdk_variant=sdk_variant,
        version=version,
        quiet=quiet,
    )


def launch_interactive(request: client.LaunchRequest) -> None:
    """Start the game through the active launcher and wait until it closes.

    Ctrl+C reaches a launcher that shares this console but not the game it
    starts, so an interrupt, or any other failure while waiting, ends the game
    before it propagates. A game that fails names the logs the launcher wrote
    for this launch.
    """
    launcher = client.active()
    started_ns = time.time_ns()
    proc = launcher.start(request)
    try:
        code = proc.wait()
    except BaseException:
        terminate_process(proc, "game client")
        raise
    if code != 0:
        logs = launcher.log_files(started_ns) or [util.RUN_DIR / "logs" / "latest.log"]
        raise RuntimeError(f"Minecraft exited with code {code}; see {', '.join(str(log) for log in logs)}")


# How long a harness waits on a game it has let exit or ended, for the launcher to finish behind it.
CLIENT_EXIT_WAIT_SECONDS = 30


@contextmanager
def started_client(request: client.LaunchRequest) -> Iterator[client.GameProcess]:
    """Start the game through the active launcher for a harness that polls it.

    Ctrl+C reaches a launcher that shares this console but not the game it
    starts, so an interrupt, or any other failure in the body, ends the game
    before it propagates. Once the body has let the game exit or ended it,
    waiting on the process lets the launcher end what it left behind, such as
    a Prism still open after its game, on every path.
    """
    proc = client.active().start(request)
    try:
        yield proc
    except BaseException:
        terminate_process(proc, "game client")
        raise
    finally:
        try:
            proc.wait(timeout=CLIENT_EXIT_WAIT_SECONDS)
        except subprocess.TimeoutExpired:
            print(f"WARNING: the game client did not exit within {CLIENT_EXIT_WAIT_SECONDS}s.")


def run_gradle_build(arch: str, ci: bool) -> None:
    task = "build" if ci else "package"
    timeout_seconds = 900 if ci else 1200
    args = [
        str(util.PROJECT_DIR / "gradlew.bat"),
        task,
        "-x",
        "test",
        f"-Parch={arch}",
        "--console=plain",
        "--quiet",
    ]
    started = time.time()
    print(f"Running Gradle {task} for arch={arch} (timeout={timeout_seconds}s)...")
    util.run(args, env=gradle_env(), timeout=timeout_seconds)
    print(f"Gradle {task} finished in {time.time() - started:.1f}s.")


def creation_flags() -> int:
    return getattr(subprocess, "CREATE_NO_WINDOW", 0)


def run_taskkill(*args: str) -> None:
    try:
        util.run(
            ["taskkill", *args],
            check=False,
            capture=True,
            timeout=15,
            encoding=util.console_encoding(),
            errors="replace",
        )
    except RuntimeError as exc:
        print(f"WARNING: taskkill did not finish: {exc}")


def remember_process_handles(proc: subprocess.Popen, *handles: IO[str]) -> None:
    PROCESS_HANDLES[id(proc)] = handles


def close_process_handles(proc: subprocess.Popen) -> None:
    for handle in PROCESS_HANDLES.pop(id(proc), ()):
        handle.close()


def terminate_process(proc: client.GameProcess, label: str, *, timeout: int = 20) -> None:
    if proc.poll() is not None:
        return
    print(f"Terminating {label}...")
    if os.name == "nt":
        run_taskkill("/pid", str(proc.pid), "/t", "/f")
        try:
            proc.wait(timeout=timeout)
            return
        except subprocess.TimeoutExpired:
            print(f"{label} did not exit after taskkill; killing the root process.")
    proc.terminate()
    try:
        proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        print(f"{label} did not exit after terminate; killing it.")
        proc.kill()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            print(f"WARNING: {label} did not exit after kill.")


def java_tool_executable(tool: str) -> str | None:
    executable = f"{tool}.exe" if os.name == "nt" else tool
    java_home = detect_java_home()
    if java_home:
        candidate = java_home / "bin" / executable
        if candidate.exists():
            return str(candidate)
    return shutil.which(executable) or shutil.which(tool)


def run_diagnostic_command(args: list[str], output: Path, *, timeout: int = 20) -> None:
    try:
        result = subprocess.run(
            args,
            capture_output=True,
            check=False,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )
        output.write_text(
            (result.stdout or "")
            + (("\n--- stderr ---\n" + result.stderr) if result.stderr else "")
            + f"\n--- exit {result.returncode} ---\n",
            encoding="utf-8",
            errors="replace",
        )
    except Exception as exc:
        output.write_text(f"diagnostic command failed: {exc}\nargs: {args}\n", encoding="utf-8", errors="replace")


def windows_process_snapshot(root_pid: int | None = None) -> tuple[str, set[int]]:
    if os.name != "nt":
        return "", set()
    root_literal = "" if root_pid is None else str(int(root_pid))
    script = rf"""
$processes = Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,Name,CommandLine
$ids = New-Object 'System.Collections.Generic.HashSet[int]'
if ('{root_literal}' -ne '') {{
  [void]$ids.Add([int]'{root_literal}')
  $changed = $true
  while ($changed) {{
    $changed = $false
    foreach ($p in $processes) {{
      if ($ids.Contains([int]$p.ParentProcessId) -and -not $ids.Contains([int]$p.ProcessId)) {{
        [void]$ids.Add([int]$p.ProcessId)
        $changed = $true
      }}
    }}
  }}
}}
$selected = if ('{root_literal}' -eq '') {{
  $processes
}} else {{
  $processes | Where-Object {{ $ids.Contains([int]$_.ProcessId) }}
}}
@($selected) | ConvertTo-Json -Compress -Depth 3
"""
    try:
        result = subprocess.run(
            ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script],
            capture_output=True,
            check=False,
            text=True,
            encoding=util.console_encoding(),
            timeout=20,
        )
    except Exception as exc:
        return f"process snapshot failed: {exc}\n", set()
    text = (result.stdout or "").strip()
    pids: set[int] = set()
    try:
        data = json.loads(text or "[]")
        if isinstance(data, dict):
            data = [data]
        for item in data if isinstance(data, list) else []:
            pids.add(int(item.get("ProcessId")))
    except Exception:
        pass
    detail = text
    if result.stderr:
        detail += "\n--- stderr ---\n" + result.stderr
    return detail + "\n", pids


def parse_jcmd_processes(text: str) -> list[tuple[int, str]]:
    processes: list[tuple[int, str]] = []
    for line in text.splitlines():
        match = re.match(r"^\s*(\d+)\s*(.*)$", line)
        if not match:
            continue
        processes.append((int(match.group(1)), match.group(2).strip()))
    return processes


def select_java_diagnostic_pids(jcmd_text: str, tree_pids: set[int]) -> list[int]:
    tokens = (
        "rtx-remix-minecraft",
        "net.fabricmc.devlaunchinjector",
        "KnotClient",
        "GradleWorkerMain",
        "minecraft",
    )
    selected: list[int] = []
    for pid, command in parse_jcmd_processes(jcmd_text):
        lower = command.lower()
        if pid in tree_pids or any(token.lower() in lower for token in tokens):
            selected.append(pid)
    return selected[:12]


def capture_hang_diagnostics(
    proc: client.GameProcess,
    output_dir: Path,
    *,
    reason: str,
    status: dict | None,
    log_text: str | None = None,
    subdir: str = "diagnostics",
    thread_dumps: int = 1,
    include_jfr: bool = True,
) -> Path:
    """Snapshot the client process for triage.

    Used both at kill time and, via stall_preview_due, while a stalled run is
    still alive. The live snapshot passes include_jfr=False and takes two spaced
    thread dumps instead: JFR.dump on a wedged JVM is the slow part, and the poll
    loop is blocked for the duration, while two dumps show whether the render
    thread is moving at all."""
    diagnostics = output_dir / subdir
    diagnostics.mkdir(parents=True, exist_ok=True)
    print(f"Capturing benchmark diagnostics to {diagnostics}...")

    # Shared keys (reason/benchmarkStatus/logTail) match build_failure_details so
    # triage tooling can read both failure records the same way.
    util.write_json(
        diagnostics / "failure.json",
        {
            "schemaVersion": 1,
            "reason": reason,
            "rootPid": proc.pid,
            "capturedAt": dt.datetime.now().astimezone().isoformat(),
            "benchmarkStatus": status or {},
            "logTail": log_tail_excerpt(log_text or ""),
        },
    )

    process_text, tree_pids = windows_process_snapshot(proc.pid)
    (diagnostics / "process-tree.json").write_text(process_text, encoding="utf-8", errors="replace")
    capture_desktop_screenshot(diagnostics / "desktop-at-failure.png", proc.pid)

    jcmd = java_tool_executable("jcmd")
    if not jcmd:
        (diagnostics / "jcmd.txt").write_text("jcmd not found on PATH/JAVA_HOME\n", encoding="utf-8")
        return diagnostics

    jcmd_list = diagnostics / "jcmd-list.txt"
    run_diagnostic_command([jcmd, "-l"], jcmd_list)
    jcmd_text = read_text_retry(jcmd_list)
    for pid in select_java_diagnostic_pids(jcmd_text, tree_pids):
        prefix = diagnostics / f"java-{pid}"
        run_diagnostic_command([jcmd, str(pid), "VM.command_line"], prefix.with_suffix(".command-line.txt"))
        for index in range(max(1, thread_dumps)):
            if index:
                time.sleep(THREAD_DUMP_SPACING_SECONDS)
            name = ".threads.txt" if index == 0 else f".threads-{index + 1}.txt"
            run_diagnostic_command([jcmd, str(pid), "Thread.print", "-l"], prefix.with_suffix(name), timeout=45)
        run_diagnostic_command([jcmd, str(pid), "GC.heap_info"], prefix.with_suffix(".heap.txt"))
        if include_jfr:
            run_diagnostic_command(
                [jcmd, str(pid), "JFR.dump", f"filename={prefix.with_suffix('.jfr')}"],
                prefix.with_suffix(".jfr-dump.txt"),
                timeout=45,
            )
    return diagnostics


def parse_byte_amount(amount: str, unit: str) -> int:
    multipliers = {
        "bytes": 1,
        "kb": 1024,
        "mb": 1024 * 1024,
        "gb": 1024 * 1024 * 1024,
    }
    return int(float(amount) * multipliers[unit.lower()])


def tracy_capture_tx_bytes(log_path: Path) -> int | None:
    if not log_path.exists():
        return None
    try:
        with log_path.open("rb") as handle:
            handle.seek(0, os.SEEK_END)
            size = handle.tell()
            handle.seek(max(0, size - 512 * 1024))
            text = handle.read().decode("utf-8", errors="replace")
    except OSError:
        return None

    text = re.sub(r"\x1b\[[0-9;?]*[ -/]*[@-~]", "", text)
    matches = re.findall(r"Tx:\s*([0-9]+(?:\.[0-9]+)?)\s*(bytes|KB|MB|GB)", text, flags=re.IGNORECASE)
    if not matches:
        return None
    amount, unit = matches[-1]
    return parse_byte_amount(amount, unit)


def tracy_drain_config(config: dict) -> dict:
    profiling = config.get("profiling") or {}
    return {
        "enabled": bool(profiling.get("tracyDrainWaitForIdle")),
        "stableSeconds": max(1, int(profiling.get("tracyDrainStableSeconds") or 30)),
        "timeoutSeconds": max(
            1, int(profiling.get("tracyDrainTimeoutSeconds") or max(1, profiling_exit_delay(config) - 10))
        ),
        "pollSeconds": max(1.0, float(profiling.get("tracyDrainPollSeconds") or 5.0)),
        "changeThresholdBytes": max(0, int(profiling.get("tracyDrainChangeThresholdBytes") or 8 * 1024)),
    }


def format_bytes(count: int | None) -> str:
    if count is None:
        return "unknown"
    value = float(count)
    for unit in ("bytes", "KB", "MB", "GB"):
        if value < 1024 or unit == "GB":
            return f"{value:.2f} {unit}" if unit != "bytes" else f"{int(value)} bytes"
        value /= 1024
    return f"{value:.2f} GB"


def wait_for_tracy_capture_idle(config: dict, log_path: Path, app_proc: client.GameProcess) -> bool:
    drain = tracy_drain_config(config)
    if not drain["enabled"]:
        return False

    print(f"Waiting for Tracy capture Tx to idle ({drain['stableSeconds']}s stable, {drain['timeoutSeconds']}s max)...")
    start = time.time()
    stable_since: float | None = None
    last_tx: int | None = None
    last_progress = start
    saw_tx = False

    while time.time() - start < drain["timeoutSeconds"]:
        if app_proc.poll() is not None:
            print("Benchmark process exited before Tracy capture reached an idle Tx state.")
            return False

        tx = tracy_capture_tx_bytes(log_path)
        now = time.time()
        if tx is not None:
            saw_tx = True
            if last_tx is None or abs(tx - last_tx) > drain["changeThresholdBytes"]:
                last_tx = tx
                stable_since = now
            elif stable_since is None:
                stable_since = now

            stable_for = now - stable_since if stable_since is not None else 0.0
            if stable_for >= drain["stableSeconds"]:
                print(f"Tracy capture Tx idle at {format_bytes(tx)} for {stable_for:.0f}s.")
                return True

            if now - last_progress >= 30:
                print(f"Tracy capture Tx {format_bytes(tx)}; idle for {stable_for:.0f}s.")
                last_progress = now
        elif saw_tx and now - last_progress >= 30:
            print("Waiting for Tracy capture progress...")
            last_progress = now

        time.sleep(drain["pollSeconds"])

    print(f"WARNING: Tracy capture Tx did not idle before {drain['timeoutSeconds']}s timeout.")
    return False


def read_text_retry(path: Path, *, attempts: int = 6, delay_seconds: float = 0.5) -> str:
    for attempt in range(attempts):
        try:
            return path.read_text(encoding="utf-8", errors="replace") if path.exists() else ""
        except OSError:
            if attempt == attempts - 1:
                raise
            time.sleep(delay_seconds)
    return ""


def unlink_retry(path: Path, *, attempts: int = 6, delay_seconds: float = 0.5) -> None:
    for attempt in range(attempts):
        try:
            path.unlink(missing_ok=True)
            return
        except OSError:
            if attempt == attempts - 1:
                print(f"WARNING: could not remove temporary file {path}; leaving it in place.")
                return
            time.sleep(delay_seconds)


def capture_desktop_screenshot(output: Path, pid: int | None = None) -> None:
    if os.name != "nt":
        return
    output.parent.mkdir(parents=True, exist_ok=True)
    output.unlink(missing_ok=True)
    script = r"""
$outputPath = $env:BENCHMARK_DESKTOP_SCREENSHOT
if (-not $outputPath) {
  Write-Error "BENCHMARK_DESKTOP_SCREENSHOT is not set"
  exit 2
}
$dpiDiagnostics = @()
try {
  Add-Type @"
using System;
using System.Runtime.InteropServices;

public static class RtxRemixCaptureNative {
  [DllImport("user32.dll", SetLastError = true)]
  public static extern bool SetProcessDpiAwarenessContext(IntPtr dpiContext);

  [DllImport("user32.dll", SetLastError = true)]
  public static extern bool SetProcessDPIAware();

  [DllImport("user32.dll", SetLastError = true)]
  public static extern IntPtr GetDC(IntPtr hWnd);

  [DllImport("user32.dll", SetLastError = true)]
  public static extern int ReleaseDC(IntPtr hWnd, IntPtr hDC);

  [DllImport("gdi32.dll", SetLastError = true)]
  public static extern IntPtr CreateCompatibleDC(IntPtr hdc);

  [DllImport("gdi32.dll", SetLastError = true)]
  public static extern IntPtr CreateCompatibleBitmap(IntPtr hdc, int width, int height);

  [DllImport("gdi32.dll", SetLastError = true)]
  public static extern IntPtr SelectObject(IntPtr hdc, IntPtr hObject);

  [DllImport("gdi32.dll", SetLastError = true)]
  public static extern bool BitBlt(
    IntPtr hdcDest,
    int xDest,
    int yDest,
    int width,
    int height,
    IntPtr hdcSrc,
    int xSrc,
    int ySrc,
    int rop
  );

  [DllImport("gdi32.dll", SetLastError = true)]
  public static extern bool DeleteDC(IntPtr hdc);

  [DllImport("gdi32.dll", SetLastError = true)]
  public static extern bool DeleteObject(IntPtr hObject);
}
"@
  if ([RtxRemixCaptureNative]::SetProcessDpiAwarenessContext([IntPtr](-4))) {
    $dpiDiagnostics += "dpiAwareness=per-monitor-v2"
  } elseif ([RtxRemixCaptureNative]::SetProcessDPIAware()) {
    $dpiDiagnostics += "dpiAwareness=system-aware fallback"
  } else {
    $dpiDiagnostics += "dpiAwareness=unchanged lastError=$([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
  }
} catch {
  $dpiDiagnostics += "dpiAwareness=unknown ($($_.Exception.Message))"
}
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
function Save-ScreenshotWithBitBlt([System.Drawing.Rectangle]$rect, [string]$path) {
  $screenDc = [RtxRemixCaptureNative]::GetDC([IntPtr]::Zero)
  if ($screenDc -eq [IntPtr]::Zero) {
    throw "GetDC failed lastError=$([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
  }
  $memoryDc = [IntPtr]::Zero
  $bitmapHandle = [IntPtr]::Zero
  $oldObject = [IntPtr]::Zero
  $image = $null
  try {
    $memoryDc = [RtxRemixCaptureNative]::CreateCompatibleDC($screenDc)
    if ($memoryDc -eq [IntPtr]::Zero) {
      throw "CreateCompatibleDC failed lastError=$([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
    }
    $bitmapHandle = [RtxRemixCaptureNative]::CreateCompatibleBitmap($screenDc, $rect.Width, $rect.Height)
    if ($bitmapHandle -eq [IntPtr]::Zero) {
      throw "CreateCompatibleBitmap failed lastError=$([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
    }
    $oldObject = [RtxRemixCaptureNative]::SelectObject($memoryDc, $bitmapHandle)
    if ($oldObject -eq [IntPtr]::Zero) {
      throw "SelectObject failed lastError=$([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
    }
    $copyRasterOperation = 0x00CC0020 -bor 0x40000000
    if (-not [RtxRemixCaptureNative]::BitBlt(
      $memoryDc,
      0,
      0,
      $rect.Width,
      $rect.Height,
      $screenDc,
      $rect.X,
      $rect.Y,
      $copyRasterOperation
    )) {
      throw "BitBlt failed lastError=$([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
    }
    $image = [System.Drawing.Image]::FromHbitmap($bitmapHandle)
    $image.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
  } finally {
    if ($image) {
      $image.Dispose()
    }
    if ($oldObject -ne [IntPtr]::Zero -and $memoryDc -ne [IntPtr]::Zero) {
      [void][RtxRemixCaptureNative]::SelectObject($memoryDc, $oldObject)
    }
    if ($bitmapHandle -ne [IntPtr]::Zero) {
      [void][RtxRemixCaptureNative]::DeleteObject($bitmapHandle)
    }
    if ($memoryDc -ne [IntPtr]::Zero) {
      [void][RtxRemixCaptureNative]::DeleteDC($memoryDc)
    }
    [void][RtxRemixCaptureNative]::ReleaseDC([IntPtr]::Zero, $screenDc)
  }
}
$targetPid = 0
if ($env:BENCHMARK_SCREENSHOT_PID) {
  [void][int]::TryParse($env:BENCHMARK_SCREENSHOT_PID, [ref]$targetPid)
}
$bounds = $null
$captureLabel = "virtual desktop"
$diagnostics = New-Object System.Collections.Generic.List[string]
foreach ($entry in $dpiDiagnostics) {
  $diagnostics.Add($entry)
}
$diagnostics.Add("userInteractive=$([System.Environment]::UserInteractive)")
$diagnostics.Add("sessionName=$($env:SESSIONNAME)")
$diagnostics.Add("username=$($env:USERNAME)")
try {
  $diagnostics.Add("processSession=$((Get-Process -Id $PID).SessionId)")
} catch {
  $diagnostics.Add("processSession=unknown ($($_.Exception.Message))")
}
$virtual = [System.Windows.Forms.SystemInformation]::VirtualScreen
$diagnostics.Add("virtualScreen=$($virtual.X),$($virtual.Y) $($virtual.Width)x$($virtual.Height)")
try {
  $screens = [System.Windows.Forms.Screen]::AllScreens | ForEach-Object {
    "$($_.DeviceName):$($_.Bounds.X),$($_.Bounds.Y) $($_.Bounds.Width)x$($_.Bounds.Height) primary=$($_.Primary)"
  }
  $diagnostics.Add("screens=$($screens -join '; ')")
} catch {
  $diagnostics.Add("screens=unknown ($($_.Exception.Message))")
}
if ($targetPid -gt 0) {
  Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class RtxRemixWin32 {
  public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

  [StructLayout(LayoutKind.Sequential)]
  public struct RECT {
    public int Left;
    public int Top;
    public int Right;
    public int Bottom;
  }

  [DllImport("user32.dll")]
  public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);

  [DllImport("user32.dll")]
  public static extern bool IsWindowVisible(IntPtr hWnd);

  [DllImport("user32.dll")]
  public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

  [DllImport("user32.dll")]
  public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);

  [DllImport("user32.dll", CharSet = CharSet.Unicode)]
  public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);

  [DllImport("user32.dll", SetLastError = true)]
  public static extern IntPtr OpenInputDesktop(uint dwFlags, bool fInherit, uint dwDesiredAccess);

  [DllImport("user32.dll", SetLastError = true)]
  public static extern bool CloseDesktop(IntPtr hDesktop);
}
"@
  $diagnostics.Add("targetPid=$targetPid")
  try {
    $targetProcess = Get-Process -Id $targetPid -ErrorAction Stop
    $diagnostics.Add("targetSession=$($targetProcess.SessionId)")
  } catch {
    $diagnostics.Add("targetSession=unknown ($($_.Exception.Message))")
  }
  try {
    $desktop = [RtxRemixWin32]::OpenInputDesktop(0, $false, 1)
    if ($desktop -eq [IntPtr]::Zero) {
      $diagnostics.Add("openInputDesktop=false lastError=$([Runtime.InteropServices.Marshal]::GetLastWin32Error())")
    } else {
      $diagnostics.Add("openInputDesktop=true")
      [void][RtxRemixWin32]::CloseDesktop($desktop)
    }
  } catch {
    $diagnostics.Add("openInputDesktop=unknown ($($_.Exception.Message))")
  }
  $pidSet = @{}
  $pidSet[$targetPid] = $true
  try {
    $allProcesses = Get-CimInstance Win32_Process
    $pending = New-Object System.Collections.Generic.Queue[int]
    $pending.Enqueue($targetPid)
    while ($pending.Count -gt 0) {
      $parentId = $pending.Dequeue()
      foreach ($child in $allProcesses | Where-Object { $_.ParentProcessId -eq $parentId }) {
        $childId = [int]$child.ProcessId
        if (-not $pidSet.ContainsKey($childId)) {
          $pidSet[$childId] = $true
          $pending.Enqueue($childId)
        }
      }
    }
  } catch {
    Write-Output "PID descendant enumeration failed: $($_.Exception.Message)"
  }

  $windows = New-Object System.Collections.Generic.List[object]
  $callback = [RtxRemixWin32+EnumWindowsProc]{
    param([IntPtr]$hwnd, [IntPtr]$lparam)
    if (-not [RtxRemixWin32]::IsWindowVisible($hwnd)) {
      return $true
    }
    $windowPid = 0
    [void][RtxRemixWin32]::GetWindowThreadProcessId($hwnd, [ref]$windowPid)
    if (-not $pidSet.ContainsKey([int]$windowPid)) {
      return $true
    }
    $rect = New-Object RtxRemixWin32+RECT
    if (-not [RtxRemixWin32]::GetWindowRect($hwnd, [ref]$rect)) {
      return $true
    }
    $width = $rect.Right - $rect.Left
    $height = $rect.Bottom - $rect.Top
    if ($width -lt 16 -or $height -lt 16) {
      return $true
    }
    $titleBuilder = New-Object System.Text.StringBuilder 512
    [void][RtxRemixWin32]::GetWindowText($hwnd, $titleBuilder, $titleBuilder.Capacity)
    $windows.Add([pscustomobject]@{
      Hwnd = $hwnd
      Pid = [int]$windowPid
      Title = $titleBuilder.ToString()
      Left = $rect.Left
      Top = $rect.Top
      Width = $width
      Height = $height
      Area = $width * $height
    })
    return $true
  }
  [void][RtxRemixWin32]::EnumWindows($callback, [IntPtr]::Zero)
  $diagnostics.Add("candidateWindows=$($windows.Count)")
  $window = $windows | Sort-Object Area -Descending | Select-Object -First 1
  if ($window) {
    $bounds = New-Object System.Drawing.Rectangle $window.Left, $window.Top, $window.Width, $window.Height
    $captureLabel = "pid=$($window.Pid) hwnd=$($window.Hwnd) title='$($window.Title)'"
    $diagnostics.Add("selectedWindow=$captureLabel at $($bounds.X),$($bounds.Y) $($bounds.Width)x$($bounds.Height)")
  }
}
if ($null -eq $bounds) {
  $bounds = [System.Windows.Forms.SystemInformation]::VirtualScreen
}
$diagnostics.Add("selectedBounds=$captureLabel at $($bounds.X),$($bounds.Y) $($bounds.Width)x$($bounds.Height)")
if ($bounds.Width -le 0 -or $bounds.Height -le 0) {
  Write-Output "Screenshot failure: no visible Windows screen bounds were available"
  Write-Output ($diagnostics -join "`n")
  exit 3
}
$bitmap = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
try {
  $graphics.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
  $bitmap.Save($outputPath, [System.Drawing.Imaging.ImageFormat]::Png)
  Write-Output "Captured $captureLabel at $($bounds.X),$($bounds.Y) $($bounds.Width)x$($bounds.Height)"
} catch {
  $copyFromScreenError = $_.Exception.Message
  $diagnostics.Add("copyFromScreen=$copyFromScreenError")
  try {
    Save-ScreenshotWithBitBlt $bounds $outputPath
    Write-Output (
      "Captured $captureLabel at $($bounds.X),$($bounds.Y) " +
      "$($bounds.Width)x$($bounds.Height) via BitBlt after CopyFromScreen failed: " +
      $copyFromScreenError
    )
  } catch {
    Write-Output (
      "Screenshot failure: CopyFromScreen failed: $copyFromScreenError; " +
      "BitBlt failed: $($_.Exception.Message)"
    )
    Write-Output ($diagnostics -join "`n")
    exit 4
  }
} finally {
  $graphics.Dispose()
  $bitmap.Dispose()
}
"""
    env = os.environ.copy()
    env["BENCHMARK_DESKTOP_SCREENSHOT"] = str(output)
    if pid:
        env["BENCHMARK_SCREENSHOT_PID"] = str(pid)
    timeout_seconds = env_int("BENCHMARK_DESKTOP_SCREENSHOT_TIMEOUT_SECONDS") or 15
    try:
        result = util.run(
            ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script],
            capture=True,
            check=False,
            env=env,
            timeout=timeout_seconds,
            encoding=util.console_encoding(),
            errors="replace",
        )
    except RuntimeError as exc:
        print(f"WARNING: desktop screenshot capture failed: {exc}")
        return
    if result.returncode != 0 or not output.exists():
        detail = (result.stderr or result.stdout or "").strip()
        print(f"WARNING: desktop screenshot capture failed: {detail}")
    else:
        detail = (result.stdout or "").strip()
        suffix = f" ({detail})" if detail else ""
        print(f"Captured desktop screenshot to {output}{suffix}")


def start_tracy_capture(tools: dict | None, output: Path, timeout_seconds: int) -> subprocess.Popen | None:
    if not tools:
        return None
    output.parent.mkdir(parents=True, exist_ok=True)
    stdout = output.with_suffix(output.suffix + ".out.log")
    stderr = output.with_suffix(output.suffix + ".err.log")
    for path in (output, stdout, stderr):
        path.unlink(missing_ok=True)
    print(f"Starting Tracy capture to {output}...")
    out_handle = stdout.open("w", encoding="utf-8", errors="replace")
    err_handle = stderr.open("w", encoding="utf-8", errors="replace")
    proc = subprocess.Popen(
        [str(tools["capture"]), "-o", str(output), "-f", "-s", str(timeout_seconds)],
        stdout=out_handle,
        stderr=err_handle,
        cwd=str(util.PROJECT_DIR),
        creationflags=creation_flags(),
    )
    remember_process_handles(proc, out_handle, err_handle)
    return proc


def stop_tracy_capture(proc: subprocess.Popen | None, timeout_seconds: int = 30) -> None:
    if not proc:
        return
    try:
        proc.wait(timeout=timeout_seconds)
    except subprocess.TimeoutExpired:
        print("Tracy capture did not exit in time; terminating it.")
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
    close_process_handles(proc)
    time.sleep(2)


def windows_crash_dump_dir() -> Path | None:
    override = os.environ.get(CRASH_DUMP_DIR_ENV, "").strip()
    if override:
        return Path(override)
    return DEFAULT_WINDOWS_CRASH_DUMP_DIR if os.name == "nt" else None


def crash_dump_snapshot(directory: Path | None) -> dict[str, tuple[int, int]]:
    if directory is None or not directory.is_dir():
        return {}
    snapshot: dict[str, tuple[int, int]] = {}
    for path in directory.iterdir():
        if not path.is_file():
            continue
        try:
            stat = path.stat()
        except OSError:
            continue
        snapshot[str(path.resolve()).lower()] = (stat.st_size, stat.st_mtime_ns)
    return snapshot


def changed_crash_dump_files(
    directory: Path | None,
    baseline: dict[str, tuple[int, int]],
    *,
    started_ns: int,
) -> list[Path]:
    if directory is None or not directory.is_dir():
        return []
    changed = []
    for path in directory.iterdir():
        if not path.is_file():
            continue
        try:
            stat = path.stat()
        except OSError:
            continue
        signature = (stat.st_size, stat.st_mtime_ns)
        key = str(path.resolve()).lower()
        # Allow a small timestamp skew because WER and Python may observe the
        # same file through clocks with slightly different precision.
        if signature != baseline.get(key) and stat.st_mtime_ns >= started_ns - 2_000_000_000:
            changed.append(path)
    return sorted(changed, key=lambda path: path.name.lower())


def collect_windows_crash_dumps(
    output_dir: Path,
    baseline: dict[str, tuple[int, int]],
    *,
    started_ns: int,
    source_dir: Path | None = None,
    wait_seconds: int | None = None,
) -> list[Path]:
    """Wait for new WER dumps to settle, then copy them into job artifacts."""
    source = source_dir if source_dir is not None else windows_crash_dump_dir()
    if source is None:
        return []
    if wait_seconds is None:
        wait_seconds = env_int(CRASH_DUMP_WAIT_ENV)
        if wait_seconds is None:
            wait_seconds = 30
    wait_seconds = max(0, int(wait_seconds))
    deadline = time.monotonic() + wait_seconds
    last_signature: tuple[tuple[str, int, int], ...] | None = None
    stable_since: float | None = None
    candidates: list[Path] = []

    while True:
        candidates = changed_crash_dump_files(source, baseline, started_ns=started_ns)
        signature_items = []
        for path in candidates:
            try:
                stat = path.stat()
            except OSError:
                continue
            signature_items.append((path.name, stat.st_size, stat.st_mtime_ns))
        signature = tuple(signature_items)
        now = time.monotonic()
        if signature and signature == last_signature:
            if stable_since is not None and now - stable_since >= 2:
                break
        else:
            last_signature = signature
            stable_since = now if signature else None
        if now >= deadline:
            break
        time.sleep(min(1.0, max(0.0, deadline - now)))

    copied = []
    for source_path in candidates:
        try:
            output_dir.mkdir(parents=True, exist_ok=True)
            destination = output_dir / source_path.name
            shutil.copy2(source_path, destination)
            copied.append(destination)
        except OSError as exc:
            print(f"WARNING: could not collect Windows crash dump {source_path}: {exc}")
    if copied:
        total_bytes = sum(path.stat().st_size for path in copied)
        print(f"Collected {len(copied)} Windows crash dump(s) ({total_bytes / (1024 * 1024):.1f} MiB).")
    else:
        print(f"No new Windows crash dumps appeared in {source} within {wait_seconds}s.")
    return copied


def kill_leftovers() -> None:
    # Do not terminate WerFault.exe here. It may still be writing the dump that
    # explains why a just-finished Java process exited.
    images = ["capture.exe", "javaw.exe", "java.exe"]
    for image in images:
        run_taskkill("/f", "/im", image)


def wait_before_next_run(config: dict, completed_run: int, next_run: int) -> None:
    cooldown = inter_run_cooldown_seconds(config)
    if not cooldown:
        return
    print(f"Waiting {cooldown}s before run {next_run} after run {completed_run}...")
    time.sleep(cooldown)


def run_client_once(
    config: dict,
    selection: BenchmarkSelection,
    tools: dict | None,
    run_log: Path,
    exit_on_complete: bool,
    arch: str,
) -> dict:
    bench_results = util.RUN_DIR / "benchmark" / "results"
    bench_results.mkdir(parents=True, exist_ok=True)
    bench_screenshots = util.RUN_DIR / "benchmark" / "screenshots"
    bench_screenshots.mkdir(parents=True, exist_ok=True)
    for old in bench_results.glob("*.json"):
        old.unlink(missing_ok=True)
    for old in bench_results.glob("*.png"):
        old.unlink(missing_ok=True)
    for old in bench_screenshots.glob("*.png"):
        old.unlink(missing_ok=True)

    install_fixtures(config, selection, pin_display=True)
    write_auto_trigger(config, selection, exit_on_complete=exit_on_complete)

    jfr_out = bench_results / "profile.jfr"
    tracy_out = bench_results / "profile.tracy"
    status_out = bench_results / BENCHMARK_STATUS_FILE
    diagnostics_dir = bench_results / "diagnostics"
    preview_dir = bench_results / STALL_PREVIEW_DIR
    crash_dir = bench_results / "crash"
    jfr_out.unlink(missing_ok=True)
    tracy_out.unlink(missing_ok=True)
    status_out.unlink(missing_ok=True)
    (bench_results / f"{BENCHMARK_STATUS_FILE}.tmp").unlink(missing_ok=True)
    if diagnostics_dir.exists():
        shutil.rmtree(diagnostics_dir, ignore_errors=True)
    if preview_dir.exists():
        shutil.rmtree(preview_dir, ignore_errors=True)
    if crash_dir.exists():
        shutil.rmtree(crash_dir, ignore_errors=True)

    poll_timeout = int(config.get("pollTimeoutSeconds", 600))
    poll_interval = int(config.get("pollIntervalSeconds", 5))
    # Written beside the run log, not into bench_results: the poll loop treats
    # any other JSON file there as the benchmark result.
    gpu_samples_out = run_log.parent / f"{run_log.stem}.gpu_samples.json"
    gpu_samples_out.unlink(missing_ok=True)
    gpu_sampler = gpu_telemetry.Sampler()
    crash_dump_source = windows_crash_dump_dir()
    crash_dump_before = crash_dump_snapshot(crash_dump_source)
    process_started_ns = time.time_ns()
    kill_leftovers()
    park_stale_runtime_state()
    tracy_proc = start_tracy_capture(tools, tracy_out, poll_timeout)

    launcher = client.active()
    run_log.parent.mkdir(parents=True, exist_ok=True)
    stdout_path = run_log.with_suffix(run_log.suffix + ".stdout")
    stderr_path = run_log.with_suffix(run_log.suffix + ".stderr")
    print("Launching Minecraft benchmark...")
    result_file = None
    failure = ""
    diagnostics_path: Path | None = None
    preview_path: Path | None = None
    last_status: dict | None = None
    proc: client.GameProcess | None = None
    try:
        with (
            benchmark_rtx_config_env() as benchmark_env,
            stdout_path.open("w", encoding="utf-8", errors="replace") as out,
            stderr_path.open("w", encoding="utf-8", errors="replace") as err,
            started_client(
                client.LaunchRequest(
                    arch=arch,
                    env=benchmark_env,
                    stdout=out,
                    stderr=err,
                    quick_play_world=BENCHMARK_SAVE_NAME,
                    preset=configured_preset_value(config),
                    jfr_path=jfr_out,
                    tracy_java_max_depth=tracy_java_depth(config),
                    quiet=True,
                    startup_seconds=benchmark_startup_stall_seconds(config),
                )
            ) as proc,
        ):
            last_status_signature: tuple[Any, ...] | None = None
            last_status_change: float | None = None
            heartbeat_stall_seconds = benchmark_heartbeat_stall_seconds(config)
            startup_stall_seconds = benchmark_startup_stall_seconds(config)
            stall_preview_seconds = benchmark_stall_preview_seconds(config, heartbeat_stall_seconds)
            stall_preview_captured = False
            startup_log_size = -1
            start = time.time()
            last_progress = start
            last_log_growth = start
            while time.time() - start < poll_timeout:
                time.sleep(poll_interval)
                matches = list(bench_results.glob("*.json"))
                matches = [path for path in matches if path.name != BENCHMARK_STATUS_FILE]
                if matches:
                    result_file = matches[0]
                    break
                now = time.time()
                # Fail fast on a logged fatal error (JVM crash / native ABI mismatch)
                # rather than polling the full timeout.
                log_text = read_run_log_best_effort(stdout_path, stderr_path, *launcher.log_files(process_started_ns))
                if fatal := classify_run_failure(log_text, ""):
                    failure = fatal
                    diagnostics_path = capture_hang_diagnostics(
                        proc, bench_results, reason=failure, status=last_status, log_text=log_text
                    )
                    break
                status = read_benchmark_status(status_out)
                gpu_sampler.record(status)
                if status:
                    signature = benchmark_status_signature(status)
                    if signature != last_status_signature:
                        last_status = status
                        last_status_signature = signature
                        last_status_change = now
                        stall_preview_captured = False
                    elif (
                        heartbeat_stall_seconds > 0
                        and last_status_change is not None
                        and now - last_status_change >= heartbeat_stall_seconds
                    ):
                        failure = f"Stalled:{describe_benchmark_status(status)}"
                        diagnostics_path = capture_hang_diagnostics(
                            proc,
                            bench_results,
                            reason=failure,
                            status=status,
                            log_text=log_text,
                        )
                        break
                    elif last_status_change is not None and stall_preview_due(
                        idle_seconds=now - last_status_change,
                        threshold=stall_preview_seconds,
                        kill_threshold=heartbeat_stall_seconds,
                        already_captured=stall_preview_captured,
                    ):
                        # Snapshot the client while it is still stuck. The kill-time
                        # capture regularly lands after the block clears, which makes
                        # its thread dump useless for telling a hang from a slow frame.
                        stall_preview_captured = True
                        print(
                            f"Benchmark status frozen for {int(now - last_status_change)}s "
                            f"({describe_benchmark_status(status)}); capturing a live snapshot..."
                        )
                        preview_path = capture_hang_diagnostics(
                            proc,
                            bench_results,
                            reason=f"StallPreview:{describe_benchmark_status(status)}",
                            status=status,
                            log_text=log_text,
                            subdir=STALL_PREVIEW_DIR,
                            thread_dumps=2,
                            include_jfr=False,
                        )
                else:
                    # Before the benchmark writes any status: fail fast if the client
                    # launched but its log went silent (hung at startup, e.g. an ABI
                    # mismatch) instead of waiting out the full timeout.
                    if len(log_text) != startup_log_size:
                        startup_log_size = len(log_text)
                        last_log_growth = now
                    elif startup_output_stalled(
                        idle_seconds=now - last_log_growth,
                        threshold=startup_stall_seconds,
                        has_status=False,
                        has_result=False,
                        proc_alive=proc.poll() is None,
                    ):
                        failure = "StartupHang"
                        diagnostics_path = capture_hang_diagnostics(
                            proc, bench_results, reason=failure, status=None, log_text=log_text
                        )
                        break
                if proc.poll() is not None:
                    time.sleep(5)
                    matches = list(bench_results.glob("*.json"))
                    matches = [path for path in matches if path.name != BENCHMARK_STATUS_FILE]
                    if matches:
                        result_file = matches[0]
                    else:
                        failure = "ProcessExited"
                    break
                if time.time() - last_progress >= 30:
                    elapsed = int(time.time() - start)
                    suffix = f"; {describe_benchmark_status(last_status)}" if last_status else ""
                    print(f"Minecraft benchmark still running after {elapsed}s{suffix}...")
                    last_progress = time.time()
            if not result_file and not failure:
                failure = "Timeout"

            if result_file:
                wait_seconds = post_result_process_wait_seconds(config)
                tracy_log = tracy_out.with_suffix(tracy_out.suffix + ".out.log")
                if tracy_proc and wait_for_tracy_capture_idle(config, tracy_log, proc):
                    terminate_process(proc, "game client")
                else:
                    try:
                        proc.wait(timeout=wait_seconds)
                    except subprocess.TimeoutExpired:
                        print(f"Minecraft completed but the game client did not exit after {wait_seconds}s.")
                        terminate_process(proc, "game client")
            else:
                if proc.poll() is None and diagnostics_path is None:
                    diagnostics_path = capture_hang_diagnostics(
                        proc,
                        bench_results,
                        reason=failure,
                        status=last_status,
                    )
                terminate_process(proc, "game client")
    except client.GameExitedEarly as exited:
        # The game came and went before the launcher could follow it. Its logs, read below with the launcher's
        # other files, classify the run, which then ends like any other failed run.
        print(f"Minecraft exited as it started: {exited}")
        failure = "ProcessExited"

    stdout_text = read_text_retry(stdout_path)
    stderr_text = read_text_retry(stderr_path)
    combined_log = stdout_text + stderr_text + read_run_log_best_effort(*launcher.log_files(process_started_ns))
    run_log.write_text(combined_log, encoding="utf-8", errors="replace")
    unlink_retry(stdout_path)
    unlink_retry(stderr_path)

    stop_tracy_capture(tracy_proc)
    gpu_sampler.write(gpu_samples_out)
    # None when the game exited before the launcher could follow it: there is no exit code to read.
    launcher_exit_code = proc.poll() if proc is not None else None
    failure = classify_run_failure(combined_log, failure, launcher_exit_code)
    crash_dumps = []
    if failure:
        # Collect even when benchmark.json was written: teardown crashes
        # happen after the result lands and their WER dump is the evidence
        # that explains them. Soft failures take a single snapshot instead of
        # waiting the full deadline for a dump that cannot appear.
        crash_dumps = collect_windows_crash_dumps(
            crash_dir,
            crash_dump_before,
            started_ns=process_started_ns,
            source_dir=crash_dump_source,
            wait_seconds=None if failure_warrants_crash_dump_wait(failure, combined_log) else 0,
        )
    kill_leftovers()
    if proc is not None:
        launcher_exit_code = proc.poll()
    return {
        "resultFile": result_file,
        "jfrOut": jfr_out,
        "tracyOut": tracy_out,
        "statusOut": status_out,
        "gpuSamplesOut": gpu_samples_out if gpu_sampler.samples else None,
        "diagnosticsDir": diagnostics_path,
        "previewDiagnosticsDir": preview_path,
        "crashDir": crash_dir if crash_dumps else None,
        "failureReason": failure,
        "failureDetails": build_failure_details(
            failure,
            combined_log,
            benchmark_status=last_status,
            launcher_exit_code=launcher_exit_code,
        )
        if failure
        else None,
        "launcherExitCode": launcher_exit_code,
    }


def export_tracy_csv(tools: dict | None, tracy_file: Path, csv_file: Path) -> None:
    if not tools or not tracy_file.exists():
        return
    err = csv_file.with_suffix(csv_file.suffix + ".err.tmp")
    csv_file.unlink(missing_ok=True)
    err.unlink(missing_ok=True)
    with (
        csv_file.open("w", encoding="utf-8", errors="replace") as out,
        err.open("w", encoding="utf-8", errors="replace") as err_handle,
    ):
        proc = subprocess.run(
            [str(tools["csvexport"]), "-s", "\t", str(tracy_file)],
            stdout=out,
            stderr=err_handle,
            cwd=str(util.PROJECT_DIR),
        )
    if proc.returncode != 0 or not csv_file.exists() or csv_file.stat().st_size == 0:
        csv_file.unlink(missing_ok=True)
        print(f"WARNING: Tracy CSV export failed for {tracy_file}")
    else:
        err.unlink(missing_ok=True)


def save_run_artifacts(result: dict, result_dir: Path, run_number: int, tools: dict | None) -> Path:
    per_run = result_dir / f"run_{run_number}"
    per_run.mkdir(parents=True, exist_ok=True)
    result_file = result.get("resultFile")
    if result_file and Path(result_file).exists():
        result_path = Path(result_file)
        shutil.copy2(result_path, per_run / "benchmark.json")
        for artifact in sorted(result_path.parent.glob("*.png")):
            shutil.copy2(artifact, per_run / artifact.name)
        screenshots_dir = result_path.parent.parent / "screenshots"
        if screenshots_dir.is_dir():
            for artifact in sorted(screenshots_dir.glob("*.png")):
                shutil.copy2(artifact, per_run / artifact.name)
    for key, name in (("jfrOut", "profile.jfr"), ("tracyOut", "profile.tracy")):
        src = result.get(key)
        if src and Path(src).exists():
            shutil.copy2(src, per_run / name)
        if src and name == "profile.tracy":
            for suffix in (".out.log", ".err.log"):
                log_src = Path(str(src) + suffix)
                if log_src.exists():
                    shutil.copy2(log_src, per_run / f"profile.tracy{suffix}")
    status_file = result.get("statusOut")
    if status_file and Path(status_file).exists():
        shutil.copy2(status_file, per_run / BENCHMARK_STATUS_FILE)
    gpu_samples = result.get("gpuSamplesOut")
    if gpu_samples and Path(gpu_samples).exists():
        shutil.move(str(gpu_samples), per_run / "gpu_samples.json")
    diagnostics_dir = result.get("diagnosticsDir")
    if diagnostics_dir and Path(diagnostics_dir).is_dir():
        shutil.copytree(diagnostics_dir, per_run / "diagnostics", dirs_exist_ok=True)
    # Kept even when the run went on to pass: a recovered stall is the cheapest
    # evidence we get for this failure class, and it never fails a job.
    preview_dir = result.get("previewDiagnosticsDir")
    if preview_dir and Path(preview_dir).is_dir():
        shutil.copytree(preview_dir, per_run / STALL_PREVIEW_DIR, dirs_exist_ok=True)
    crash_dir = result.get("crashDir")
    if crash_dir and Path(crash_dir).is_dir():
        shutil.copytree(crash_dir, per_run / "crash", dirs_exist_ok=True)
    log_file = result.get("runLog")
    if log_file and Path(log_file).exists():
        shutil.copy2(log_file, per_run / "runClient.log")
        if Path(log_file).parent == result_dir:
            Path(log_file).unlink(missing_ok=True)
    if result.get("failureReason"):
        (per_run / "failure.txt").write_text(str(result["failureReason"]) + "\n", encoding="utf-8")
        details = copy.deepcopy(result.get("failureDetails") or {})
        details.setdefault("schemaVersion", 1)
        details.setdefault("reason", str(result["failureReason"]))
        details["run"] = run_number
        if (per_run / "crash").is_dir():
            dump_names = [f"crash/{path.name}" for path in sorted((per_run / "crash").iterdir()) if path.is_file()]
            details["crashDumps"] = dump_names
            # Propagate so manifest_failure_entry (built later from
            # result["failureDetails"]) agrees with failure.json.
            failure_details = result.get("failureDetails")
            if isinstance(failure_details, dict):
                failure_details["crashDumps"] = dump_names
        util.write_json(per_run / "failure.json", details)

    export_tracy_csv(tools, per_run / "profile.tracy", per_run / "profile.csv")
    return per_run


def generate_profile_data(per_run: Path, run_number: int, *, tracy_required: bool = True) -> None:
    benchmark_json = per_run / "benchmark.json"
    if not benchmark_json.exists():
        return
    print(f"Generating profile-data.json for run {run_number}...")
    args = [
        sys.executable,
        "-m",
        "rtx_tools.profiler.generate_profile_data",
        "--benchmark",
        str(benchmark_json),
        "--jfr",
        str(per_run / "profile.jfr"),
        "--tracy",
        str(per_run / "profile.tracy"),
        "--run",
        str(run_number),
        "--out",
        str(per_run / "profile-data.json"),
    ]
    if not tracy_required:
        args.append("--tracy-optional")
    if (per_run / "profile.csv").exists():
        args += ["--tracy-csv", str(per_run / "profile.csv")]
    util.run(args)
    print(f"Generated {per_run / 'profile-data.json'}")


def aggregate(result_dir: Path) -> dict:
    aggregate_path = result_dir / "aggregate.json"
    print("Aggregating benchmark profile data...")
    util.run(
        [
            sys.executable,
            "-m",
            "rtx_tools.profiler.aggregate_profile_data",
            "--input-dir",
            str(result_dir),
            "--out",
            str(aggregate_path),
        ]
    )
    print(f"Generated {aggregate_path}")
    return util.read_json(aggregate_path)


def benchmark_system_from_results(result_dir: Path) -> dict:
    for benchmark_json in sorted(result_dir.glob("run_*/benchmark.json")):
        try:
            system = util.read_json(benchmark_json).get("system") or {}
        except OSError, ValueError:
            continue
        if isinstance(system, dict):
            system = dict(system)
            system["sourceRun"] = benchmark_json.parent.name
            return system
    return {}


def attach_benchmark_system(machine_info: dict, result_dir: Path) -> None:
    system = benchmark_system_from_results(result_dir)
    if not system:
        return
    machine_info["benchmarkSystem"] = system
    benchmark_gpu = machine.benchmark_gpu_from_system(system, machine_info.get("selectedGpu") or {})
    if benchmark_gpu:
        machine_info["benchmarkGpu"] = benchmark_gpu


def attach_machine_health(machine_info: dict, result_dir: Path) -> list[str]:
    """Record GPU telemetry and the environment's runner expectations on machine_info.

    Returns the off-spec reasons so the caller can warn; the CI report reads
    the same list from the manifest to withhold a verdict.
    """
    telemetry = gpu_telemetry.summarize_result_dir(result_dir)
    if telemetry:
        machine_info["gpuTelemetry"] = telemetry
    spec = machine.runner_expectations()
    reasons = machine.off_spec_reasons(machine_info, spec)
    machine_info["health"] = {"spec": spec or None, "reasons": reasons}
    return reasons


def artifact_manifest(result_dir: Path) -> dict:
    runs = []
    for per_run in sorted(result_dir.glob("run_*")):
        if not per_run.is_dir():
            continue
        run_num = per_run.name.split("_", 1)[-1]
        item: dict[str, Any] = {"run": int(run_num) if run_num.isdigit() else run_num}
        for key, filename in {
            "benchmark": "benchmark.json",
            "profileData": "profile-data.json",
            "tracy": "profile.tracy",
            "gpuSamples": "gpu_samples.json",
            "tracyCsv": "profile.csv",
            "jfr": "profile.jfr",
            "log": "runClient.log",
            "status": BENCHMARK_STATUS_FILE,
            "failure": "failure.txt",
            "failureDetails": "failure.json",
            "tracyOutLog": "profile.tracy.out.log",
            "tracyErrLog": "profile.tracy.err.log",
        }.items():
            if (per_run / filename).exists():
                item[key] = f"{per_run.name}/{filename}"
        if (per_run / "diagnostics").is_dir():
            item["diagnostics"] = f"{per_run.name}/diagnostics"
        if (per_run / STALL_PREVIEW_DIR).is_dir():
            item["diagnosticsPreview"] = f"{per_run.name}/{STALL_PREVIEW_DIR}"
        if (per_run / "crash").is_dir():
            item["crash"] = f"{per_run.name}/crash"
        screenshots = [f"{per_run.name}/{path.name}" for path in sorted(per_run.glob("*.png")) if path.is_file()]
        if screenshots:
            item["screenshots"] = screenshots
        runs.append(item)
    artifacts = {"manifest": "manifest.json", "runs": runs}
    if (result_dir / "aggregate.json").exists():
        artifacts["aggregate"] = "aggregate.json"
    return artifacts


def metric_name(prefix: str, name: str, suffix: str = "") -> str:
    safe = "".join(ch if ch.isalnum() else "_" for ch in name).strip("_")
    return f"{prefix}_{safe}{suffix}"


def write_metrics_file(aggregate_doc: dict | None, outcome: dict | None = None) -> None:
    aggregate_doc = aggregate_doc or {}
    outcome = outcome or {"status": "passed"}
    frame = aggregate_doc.get("frame") or {}
    failure_items = outcome.get("failures")
    failures = failure_items if isinstance(failure_items, list) else []
    native_crash_count = sum(
        1
        for failure in failures
        if isinstance(failure, dict) and is_native_crash_reason(str(failure.get("reason") or ""))
    )
    lines = [
        "# HELP benchmark_success Whether every requested benchmark run completed successfully",
        "# TYPE benchmark_success gauge",
        f"benchmark_success {1 if outcome.get('status') == 'passed' else 0}",
        "# HELP benchmark_runs_requested Number of benchmark runs requested",
        "# TYPE benchmark_runs_requested gauge",
        f"benchmark_runs_requested {int(outcome.get('requestedRuns') or 0)}",
        "# HELP benchmark_runs_attempted Number of benchmark client launches attempted",
        "# TYPE benchmark_runs_attempted gauge",
        f"benchmark_runs_attempted {int(outcome.get('attemptedRuns') or 0)}",
        "# HELP benchmark_runs_completed Number of benchmark runs that produced results",
        "# TYPE benchmark_runs_completed gauge",
        f"benchmark_runs_completed {int(outcome.get('completedRuns') or 0)}",
        "# HELP benchmark_runs_failed Number of benchmark runs that failed",
        "# TYPE benchmark_runs_failed gauge",
        f"benchmark_runs_failed {int(outcome.get('failedRuns') or 0)}",
        "# HELP benchmark_native_crashes Number of runs terminated by a classified native crash",
        "# TYPE benchmark_native_crashes gauge",
        f"benchmark_native_crashes {native_crash_count}",
    ]
    if frame:
        lines.extend(
            [
                "# HELP frame_time_avg_ms Average frame time in milliseconds",
                "# TYPE frame_time_avg_ms gauge",
                f"frame_time_avg_ms {float(frame.get('avgMs') or 0.0)}",
                "# HELP frame_time_p1_ms 99th percentile frame time in milliseconds",
                "# TYPE frame_time_p1_ms gauge",
                f"frame_time_p1_ms {float(frame.get('p99Ms') or frame.get('p1Ms') or 0.0)}",
                "# HELP fps_avg Average frames per second",
                "# TYPE fps_avg gauge",
            ]
        )
        avg_ms = float(frame.get("avgMs") or 0.0)
        lines.append(f"fps_avg {round(1000.0 / avg_ms, 3) if avg_ms else 0.0}")
        lines.extend(
            [
                "# HELP frame_time_stddev_ms Standard deviation of frame time in milliseconds",
                "# TYPE frame_time_stddev_ms gauge",
                f"frame_time_stddev_ms {float(frame.get('stddevMs') or 0.0)}",
                "# HELP frame_time_cv_pct Coefficient of variation (stddev/mean) as percentage",
                "# TYPE frame_time_cv_pct gauge",
                f"frame_time_cv_pct {float(frame.get('cvPct') or 0.0)}",
            ]
        )
        for name, value in (frame.get("sections") or {}).items():
            lines.append(f"{metric_name('section', name, '_avg_ms')} {float((value or {}).get('avgMs') or 0.0)}")
        for name, value in (frame.get("counters") or {}).items():
            lines.append(f"{metric_name('counter', name, '_avg')} {float((value or {}).get('avg') or 0.0)}")
    lines.append("# EOF")
    (util.PROJECT_DIR / "metrics.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_manifest(
    result_dir: Path,
    *,
    run_id: str,
    started: dt.datetime,
    git: dict,
    machine_info: dict,
    sdk: dict,
    config: dict,
    summary: dict,
    notes: str,
    tags: list[str],
    source: dict,
    outcome: dict | None = None,
) -> dict:
    manifest = {
        "schemaVersion": 1,
        "kind": "benchmark-run",
        "id": run_id,
        "timestamp": {
            "local": started.astimezone().isoformat(),
            "utc": started.astimezone(dt.UTC).isoformat(),
        },
        "source": source,
        "launcher": client.active().name,
        "ci": machine_info.get("ci") or {},
        "git": git,
        "machine": machine_info,
        "sdk": sdk,
        "config": config,
        "summary": summary,
        "outcome": outcome or {"status": "passed"},
        "artifacts": artifact_manifest(result_dir),
        "notes": notes,
        "tags": tags,
    }
    util.write_json(result_dir / "manifest.json", manifest)
    return manifest


def manifest_failure_entry(run_number: int | None, details: Mapping[str, object]) -> dict[str, object]:
    entry: dict[str, object] = {"reason": str(details.get("reason") or "UnknownFailure")}
    if run_number is not None:
        entry["run"] = run_number
        entry["artifact"] = f"run_{run_number}/failure.json"
    for key in ("ntstatus", "processExitCode", "launcherExitCode", "benchmarkStatus", "crashDumps", "message"):
        value = details.get(key)
        if value not in (None, "", [], {}):
            entry[key] = value
    return entry


def run_benchmark(args) -> Path | None:
    if getattr(args, "dry_run", False):
        plan = benchmark_run_plan(args)
        print("Dry run: no SDK fetch, build, launch, capture, or files will be written.")
        print(json.dumps(plan, indent=2))
        return None

    config = load_config()
    launcher = client.active()
    ensure_no_live_client(ci=args.ci)
    # A CI job has no one to sign in, so its check never opens a window.
    launcher.check(interactive=not args.ci)
    version_override = None if args.ci else getattr(args, "version", None)
    ensure_world_staged(
        config,
        resolve_world_name(config, getattr(args, "world", None)),
        ci=args.ci,
        version=version_override,
    )
    selection = configure_benchmark_selection(
        config,
        world_override=getattr(args, "world", None),
        camera_override=getattr(args, "camera", None),
        version_override=version_override,
    )
    # configure_benchmark_selection set config["worldVersion"] from the staged
    # marker / pin; config_for_run preserves it for the manifest + dashboard.
    config = config_for_run(config, ci=args.ci)
    if getattr(args, "strip_entities", False):
        config["stripEntitiesOnStage"] = True
    runs = args.runs or (int(config.get("runs") or 3) if args.ci else 1)
    arch = host_arch(getattr(args, "arch", None))
    verify_jvm_matches_arch(arch)
    started = dt.datetime.now().astimezone()
    run_id = timestamp_id()
    result_dir = unique_result_dir(run_id)
    result_dir.mkdir(parents=True, exist_ok=False)

    git = gitmeta.current_git()
    if git.get("dirty"):
        git["dirtyPatch"] = "dirty.patch" if gitmeta.write_dirty_patch(result_dir / "dirty.patch") else ""
    machine_info = machine.collect_machine()
    runner_spec = machine.runner_expectations()
    if preflight_reasons := machine.off_spec_reasons(machine_info, runner_spec):
        print("WARNING: this box is off-spec for benchmarking: " + "; ".join(preflight_reasons))

    print(f"Benchmark run: {run_id}")
    print(f"Target: world={selection.world} camera={selection.camera}")
    print(f"Branch: {git.get('branch')} {git.get('commitShort')}{' dirty' if git.get('dirty') else ''}")

    sdk_dll = fetch_sdk(args.sdk_variant, arch, auto_restage=runtime_sdk.configured_sdk_auto_restage(config))
    tools = tracy_tools(sdk_dll, required=args.sdk_variant == "tracy")
    sdk = sdk_metadata(sdk_dll, args.sdk_variant)
    write_sdk_version(sdk)

    if not args.no_build:
        run_gradle_build(arch, ci=args.ci)
    # The jar is built, by the step above or by the caller of --no-build; this installs it into the game directory.
    client.active().prepare(arch, build=False)

    run_failures: list[dict[str, object]] = []
    post_run_failures: list[dict[str, object]] = []
    attempted_runs = 0
    completed_runs = 0
    fatal_error: Exception | None = None
    keep_running_after_failure = continue_after_run_failure(config, ci=args.ci)
    kill_leftovers()
    try:
        for i in range(1, runs + 1):
            print(f"\n=== Run {i} of {runs} ===")
            attempted_runs += 1
            run_log = result_dir / f"run{i}-runClient.log"
            try:
                result = run_client_once(config, selection, tools, run_log, exit_on_complete=True, arch=arch)
                result["runLog"] = run_log
                per_run = save_run_artifacts(result, result_dir, i, tools)
                if not result.get("resultFile"):
                    details = copy.deepcopy(result.get("failureDetails") or {})
                    details.setdefault("reason", str(result.get("failureReason") or "ProcessExited"))
                    run_failures.append(manifest_failure_entry(i, details))
                    print(f"ERROR: Benchmark run {i} did not produce benchmark.json ({details['reason']})")
                    if i < runs and keep_running_after_failure:
                        wait_before_next_run(config, i, i + 1)
                        continue
                    break
                validate_benchmark_display(config, per_run / "benchmark.json")
                degenerate = degenerate_frame_report(per_run / "benchmark.json")
                if degenerate:
                    details = {
                        "schemaVersion": 1,
                        "run": i,
                        "reason": "DegenerateFrames",
                        "message": degenerate,
                    }
                    if not (per_run / "failure.json").exists():
                        util.write_json(per_run / "failure.json", details)
                    if not (per_run / "failure.txt").exists():
                        (per_run / "failure.txt").write_text(f"DegenerateFrames: {degenerate}\n", encoding="utf-8")
                    run_failures.append(manifest_failure_entry(i, details))
                    print(f"ERROR: Benchmark run {i} recorded degenerate frames: {degenerate}")
                    if i < runs and keep_running_after_failure:
                        wait_before_next_run(config, i, i + 1)
                        continue
                    break
                completed_runs += 1
                if result.get("failureReason"):
                    # The measurement is valid (benchmark.json landed), but the
                    # client failed afterwards - typically a native teardown
                    # crash. Surface it in the manifest instead of leaving a
                    # failure.json inside a run the outcome calls clean.
                    details = copy.deepcopy(result.get("failureDetails") or {})
                    details.setdefault("reason", str(result["failureReason"]))
                    post_run_failures.append(manifest_failure_entry(i, details))
                    print(
                        f"WARNING: run {i} completed but the client failed during "
                        f"teardown ({details['reason']}); see run_{i}/failure.json"
                    )
                generate_profile_data(per_run, i, tracy_required=bool(tools))
                if i < runs:
                    wait_before_next_run(config, i, i + 1)
            except Exception as exc:
                fatal_error = exc
                per_run = result_dir / f"run_{i}"
                per_run.mkdir(parents=True, exist_ok=True)
                details = {
                    "schemaVersion": 1,
                    "run": i,
                    "reason": "HarnessError",
                    "message": f"{type(exc).__name__}: {exc}",
                }
                if not (per_run / "failure.json").exists():
                    util.write_json(per_run / "failure.json", details)
                if not (per_run / "failure.txt").exists():
                    (per_run / "failure.txt").write_text(details["message"] + "\n", encoding="utf-8")
                run_failures.append(manifest_failure_entry(i, details))
                break
    finally:
        kill_leftovers()

    aggregate_doc: dict = {}
    if any(result_dir.glob("run_*/profile-data.json")):
        try:
            aggregate_doc = aggregate(result_dir)
        except Exception as exc:
            fatal_error = fatal_error or exc
            run_failures.append(
                manifest_failure_entry(
                    None,
                    {
                        "reason": "FinalizationError",
                        "message": f"{type(exc).__name__}: {exc}",
                    },
                )
            )

    passed = not run_failures and completed_runs == runs
    outcome = {
        "status": "passed" if passed else "failed",
        "requestedRuns": runs,
        "attemptedRuns": attempted_runs,
        "completedRuns": completed_runs,
        "failedRuns": len([failure for failure in run_failures if failure.get("run") is not None]),
        "failures": run_failures,
    }
    if post_run_failures:
        outcome["postCompletionFailures"] = post_run_failures
    write_metrics_file(aggregate_doc, outcome)
    summary = catalog.summarize_aggregate(result_dir / "aggregate.json") if aggregate_doc else {}
    attach_benchmark_system(machine_info, result_dir)
    if off_spec := attach_machine_health(machine_info, result_dir):
        print("WARNING: this box was off-spec during the benchmark: " + "; ".join(off_spec))
    write_manifest(
        result_dir,
        run_id=run_id,
        started=started,
        git=git,
        machine_info=machine_info,
        sdk=sdk,
        config=config,
        summary=summary,
        notes=args.note or "",
        tags=list(args.tag or []),
        source={"type": "ci" if args.ci else "local"},
        outcome=outcome,
    )
    catalog.write_dashboard(default_b=run_id if passed else "")
    if args.open:
        util.open_in_browser(util.RESULTS_DIR / "index.html")
    print(f"\nArtifacts: {result_dir}")
    print(f"Dashboard: {util.RESULTS_DIR / 'index.html'}")
    if not passed:
        reasons = ", ".join(str(failure.get("reason") or "UnknownFailure") for failure in run_failures)
        if fatal_error is not None:
            raise fatal_error
        raise RuntimeError(
            f"Benchmark soak failed: {outcome['failedRuns']} of {attempted_runs} attempted run(s) failed ({reasons})"
        )
    return result_dir

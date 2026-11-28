# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Typed loader catalog read from the repository's project-state manifest."""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .benchmark import util

SCHEMA = "multi-version/v1"
MANIFEST_NAME = "project-state.json"
PROJECT_STATE_PATH = util.PROJECT_DIR / MANIFEST_NAME


class ManifestError(RuntimeError):
    """A project-state manifest is missing or does not satisfy the tooling contract."""


@dataclass(frozen=True)
class PrismComponent:
    uid: str
    name: str
    version: str


@dataclass(frozen=True)
class Loader:
    id: str
    display_name: str
    module: str
    gradle_project_path: str
    provider: str
    development_jar_dir: str
    development_jar_pattern: str
    installed_jar: str
    builtin_resource_pack: str
    build_task: str
    test_task: str
    package_task: str
    prepare_tasks: tuple[str, ...]
    prism: PrismComponent
    support_tier: str
    coordinate: str
    version: str
    version_range: str
    api_coordinate: str | None
    api_version: str | None
    api_version_range: str | None
    gradle_plugin_version: str
    gradle_plugin_id: str
    metadata_file: str
    archive_base_name: str
    package_source_task: str

    def release_archive_base_name(self, mod_id: str, minecraft_version: str) -> str:
        return f"{mod_id}-{self.id}-mc{minecraft_version}"

    def project_path(self, project_dir: Path, value: str) -> Path:
        return project_dir.joinpath(*Path(value).parts)


@dataclass(frozen=True)
class LoaderRegistry:
    loaders: tuple[Loader, ...]
    default_id: str
    support_tier: str
    mod_id: str
    mod_group: str
    mod_version: str
    mod_display_name: str
    minecraft_version: str
    minecraft_module: str
    minecraft_version_range: str
    java_target: int
    minecraft_data_version: int
    host_graphics_backend: str
    available_versions: tuple[str, ...]
    default_version: str

    @property
    def ids(self) -> tuple[str, ...]:
        return tuple(loader.id for loader in self.loaders)

    @property
    def target_arguments(self) -> tuple[str, str]:
        return (f"-PmcVersion={self.minecraft_version}", f"-Ploader={self.default_id}")

    def gradle_arguments(self, loader_id: str) -> tuple[str, str]:
        loader = self.get(loader_id)
        return (f"-PmcVersion={self.minecraft_version}", f"-Ploader={loader.id}")

    def get(self, loader_id: str) -> Loader:
        normalized = loader_id.strip().lower()
        for loader in self.loaders:
            if loader.id == normalized:
                return loader
        choices = ", ".join(self.ids)
        raise ManifestError(f"Unknown mod loader {loader_id!r}; project-state.json declares: {choices}")


def _object(value: Any, field: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ManifestError(f"project-state.json field '{field}' must be an object")
    return value


def _string(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ManifestError(f"project-state.json field '{field}' must be a non-empty string")
    return value.strip()


def _relative(value: Any, field: str) -> str:
    text = _string(value, field).replace("\\", "/")
    path = Path(text)
    if path.is_absolute() or ".." in path.parts:
        raise ManifestError(f"project-state.json field '{field}' must be a repository-relative path")
    return text


def _strings(value: Any, field: str) -> tuple[str, ...]:
    if value is None:
        return ()
    if not isinstance(value, list):
        raise ManifestError(f"project-state.json field '{field}' must be an array of strings")
    return tuple(_string(item, f"{field}[{index}]") for index, item in enumerate(value))


def _integer(value: Any, field: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise ManifestError(f"project-state.json field '{field}' must be a positive integer")
    return value


def _choice(value: Any, field: str, choices: set[str]) -> str:
    text = _string(value, field)
    if text not in choices:
        raise ManifestError(f"project-state.json field '{field}' must be one of: {', '.join(sorted(choices))}")
    return text


def _loader(loader_id: str, value: Any, *, version_id: str, version_field: str) -> Loader:
    field = f"{version_field}.loaders.{loader_id}"
    data = _object(value, field)
    artifact = _object(data.get("developmentArtifact"), f"{field}.developmentArtifact")
    gradle = _object(data.get("gradle"), f"{field}.gradle")
    prism = _object(data.get("prism"), f"{field}.prism")
    runtime = data.get("loader")
    runtime = _object(runtime, f"{field}.loader")
    api_value = data.get("api")
    api = _object(api_value, f"{field}.api") if api_value is not None else None
    gradle_plugin = _object(data.get("gradlePlugin"), f"{field}.gradlePlugin")
    normalized = loader_id.strip().lower()
    if normalized != loader_id or re.fullmatch(r"[a-z][a-z0-9-]*", normalized) is None:
        raise ManifestError(f"project-state.json loader key {loader_id!r} must be a lowercase slug")
    declared_id = data.get("id")
    if declared_id is not None and declared_id != loader_id:
        raise ManifestError(f"project-state.json loader key {loader_id!r} disagrees with entry id {declared_id!r}")
    module = _relative(data.get("module"), f"{field}.module")
    expected_module = f"versions/{version_id}/{loader_id}"
    if module != expected_module:
        raise ManifestError(f"project-state.json field '{field}.module' must be {expected_module!r}")
    project_path = _string(data.get("projectPath"), f"{field}.projectPath")
    expected_project_path = ":" + module.replace("/", ":")
    if project_path != expected_project_path:
        raise ManifestError(f"project-state.json field '{field}.projectPath' must match module {module!r}")
    provider = _string(data.get("provider"), f"{field}.provider")
    if re.fullmatch(r"[a-z][a-z0-9-]*", provider) is None:
        raise ManifestError(f"project-state.json field '{field}.provider' must be a lowercase slug")
    development_directory = _relative(artifact.get("directory"), f"{field}.developmentArtifact.directory")
    if not development_directory.startswith(f"{module}/"):
        raise ManifestError(
            f"project-state.json field '{field}.developmentArtifact.directory' must be under module {module!r}"
        )
    build_task = _string(gradle.get("build"), f"{field}.gradle.build")
    test_task = _string(gradle.get("test"), f"{field}.gradle.test")
    package_task = _string(gradle.get("package"), f"{field}.gradle.package")
    prepare_tasks = _strings(gradle.get("prepare"), f"{field}.gradle.prepare")
    for name, task in (
        ("build", build_task),
        ("test", test_task),
        ("package", package_task),
        *((f"prepare[{index}]", task) for index, task in enumerate(prepare_tasks)),
    ):
        if not task.startswith(f"{project_path}:"):
            raise ManifestError(f"project-state.json field '{field}.gradle.{name}' must belong to {project_path!r}")
    loader_version = _string(runtime.get("version"), f"{field}.loader.version")
    prism_version = _string(prism.get("version"), f"{field}.prism.version")
    if prism_version != loader_version:
        raise ManifestError(f"project-state.json field '{field}.prism.version' must match loader.version")
    installed_jar = _string(data.get("installedJar"), f"{field}.installedJar")
    if Path(installed_jar).name != installed_jar or not installed_jar.lower().endswith(".jar"):
        raise ManifestError(f"project-state.json field '{field}.installedJar' must be a JAR file name without a path")
    return Loader(
        id=normalized,
        display_name=_string(data.get("displayName"), f"{field}.displayName"),
        module=module,
        gradle_project_path=project_path,
        provider=provider,
        development_jar_dir=development_directory,
        development_jar_pattern=_string(artifact.get("pattern"), f"{field}.developmentArtifact.pattern"),
        installed_jar=installed_jar,
        builtin_resource_pack=_string(data.get("builtinResourcePack"), f"{field}.builtinResourcePack"),
        build_task=build_task,
        test_task=test_task,
        package_task=package_task,
        prepare_tasks=prepare_tasks,
        prism=PrismComponent(
            uid=_string(prism.get("uid"), f"{field}.prism.uid"),
            name=_string(prism.get("name"), f"{field}.prism.name"),
            version=prism_version,
        ),
        support_tier=_choice(
            data.get("supportTier"),
            f"{field}.supportTier",
            {"active", "maintenance", "experimental", "archived"},
        ),
        coordinate=_string(runtime.get("coordinate"), f"{field}.loader.coordinate"),
        version=loader_version,
        version_range=_string(runtime.get("versionRange"), f"{field}.loader.versionRange"),
        api_coordinate=_string(api.get("coordinate"), f"{field}.api.coordinate") if api is not None else None,
        api_version=_string(api.get("version"), f"{field}.api.version") if api is not None else None,
        api_version_range=_string(api.get("versionRange"), f"{field}.api.versionRange") if api is not None else None,
        gradle_plugin_version=_string(gradle_plugin.get("version"), f"{field}.gradlePlugin.version"),
        gradle_plugin_id=_string(gradle_plugin.get("id"), f"{field}.gradlePlugin.id"),
        metadata_file=_string(data.get("metadataFile"), f"{field}.metadataFile"),
        archive_base_name=_string(data.get("archiveBaseName"), f"{field}.archiveBaseName"),
        package_source_task=_string(data.get("packageSourceTask"), f"{field}.packageSourceTask"),
    )


def _version_registry(
    root: dict[str, Any],
    version_id: str,
    value: Any,
    *,
    available_versions: tuple[str, ...],
    default_version: str,
    source: str,
) -> LoaderRegistry:
    field = f"versions.{version_id}"
    version = _object(value, field)
    raw_loaders = _object(version.get("loaders"), f"{field}.loaders")
    if not raw_loaders:
        raise ManifestError(f"{source} field '{field}.loaders' must declare at least one loader")
    loaders = tuple(
        _loader(loader_id, data, version_id=version_id, version_field=field) for loader_id, data in raw_loaders.items()
    )
    default_id = _string(version.get("defaultLoader"), f"{field}.defaultLoader")
    mod = _object(root.get("mod"), "mod")
    minecraft = _object(version.get("minecraft"), f"{field}.minecraft")
    mod_id = _string(mod.get("id"), "mod.id")
    mod_group = _string(mod.get("group"), "mod.group")
    mod_version = _string(mod.get("version"), "mod.version")
    if re.fullmatch(r"[a-z][a-z0-9-]*", mod_id) is None:
        raise ManifestError("project-state.json field 'mod.id' must be a lowercase slug")
    if re.fullmatch(r"[A-Za-z0-9_.-]+", mod_group) is None:
        raise ManifestError("project-state.json field 'mod.group' is malformed")
    if re.fullmatch(r"\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?", mod_version) is None:
        raise ManifestError("project-state.json field 'mod.version' is malformed")
    minecraft_version = _string(minecraft.get("version"), f"{field}.minecraft.version")
    if minecraft_version != version_id:
        raise ManifestError(
            f"project-state.json field '{field}.minecraft.version' must match version key {version_id!r}"
        )
    if re.fullmatch(r"\d+\.\d+(?:\.\d+)?(?:[-+][0-9A-Za-z.-]+)?", minecraft_version) is None:
        raise ManifestError(f"project-state.json field '{field}.minecraft.version' is malformed")
    minecraft_module = _relative(minecraft.get("module"), f"{field}.minecraft.module")
    expected_minecraft_module = f"versions/{version_id}/minecraft"
    if minecraft_module != expected_minecraft_module:
        raise ManifestError(
            f"project-state.json field '{field}.minecraft.module' must be {expected_minecraft_module!r}"
        )
    java_target = _integer(minecraft.get("javaTarget"), f"{field}.minecraft.javaTarget")
    if java_target < 8:
        raise ManifestError(f"project-state.json field '{field}.minecraft.javaTarget' must be at least 8")
    host_graphics_backend = _string(minecraft.get("hostGraphicsBackend"), f"{field}.minecraft.hostGraphicsBackend")
    if re.fullmatch(r"[a-z][a-z0-9-]*", host_graphics_backend) is None:
        raise ManifestError(
            f"project-state.json field '{field}.minecraft.hostGraphicsBackend' must be a lowercase slug"
        )
    registry = LoaderRegistry(
        loaders=loaders,
        default_id=default_id,
        support_tier=_choice(
            version.get("supportTier"),
            f"{field}.supportTier",
            {"primary", "active", "maintenance", "archived"},
        ),
        mod_id=mod_id,
        mod_group=mod_group,
        mod_version=mod_version,
        mod_display_name=_string(mod.get("displayName"), "mod.displayName"),
        minecraft_version=minecraft_version,
        minecraft_module=minecraft_module,
        minecraft_version_range=_string(minecraft.get("versionRange"), f"{field}.minecraft.versionRange"),
        java_target=java_target,
        minecraft_data_version=_integer(minecraft.get("dataVersion"), f"{field}.minecraft.dataVersion"),
        host_graphics_backend=host_graphics_backend,
        available_versions=available_versions,
        default_version=default_version,
    )
    if default_id not in registry.ids:
        raise ManifestError(f"{source} {field}.defaultLoader {default_id!r} is not present in loaders")
    if len({loader.module for loader in loaders}) != len(loaders):
        raise ManifestError(f"{source} {field} loader module values must be unique")
    if len({loader.gradle_project_path for loader in loaders}) != len(loaders):
        raise ManifestError(f"{source} {field} loader projectPath values must be unique")
    installed_jars = [loader.installed_jar.lower() for loader in loaders]
    if len(set(installed_jars)) != len(installed_jars):
        raise ManifestError(f"{source} {field} loader installedJar values must be unique")
    return registry


def parse_manifest(
    value: Any,
    *,
    source: str = MANIFEST_NAME,
    minecraft_version: str | None = None,
) -> LoaderRegistry:
    root = _object(value, "<root>")
    schema = root.get("schema")
    if schema != SCHEMA:
        raise ManifestError(f"{source} schema must be {SCHEMA!r}, got {schema!r}")
    versions = _object(root.get("versions"), "versions")
    if not versions:
        raise ManifestError(f"{source} field 'versions' must declare at least one Minecraft version")
    available_versions = tuple(versions)
    for version_id in available_versions:
        if (
            not isinstance(version_id, str)
            or re.fullmatch(r"\d+\.\d+(?:\.\d+)?(?:[-+][0-9A-Za-z.-]+)?", version_id) is None
        ):
            raise ManifestError(f"project-state.json version key {version_id!r} is malformed")
    default_version = _string(root.get("defaultMinecraftVersion"), "defaultMinecraftVersion")
    if default_version not in versions:
        raise ManifestError(f"{source} defaultMinecraftVersion {default_version!r} is not present in versions")

    registries = {
        version_id: _version_registry(
            root,
            version_id,
            version_value,
            available_versions=available_versions,
            default_version=default_version,
            source=source,
        )
        for version_id, version_value in versions.items()
    }
    selected = (
        minecraft_version
        if minecraft_version is not None
        else os.environ.get("RTXREMIX_MINECRAFT_VERSION", default_version)
    ).strip()
    if selected not in registries:
        choices = ", ".join(available_versions)
        raise ManifestError(f"Unknown Minecraft version {selected!r}; project-state.json declares: {choices}")
    return registries[selected]


def load_registry(
    project_dir: Path | None = None,
    minecraft_version: str | None = None,
) -> LoaderRegistry:
    path = PROJECT_STATE_PATH if project_dir is None else project_dir / MANIFEST_NAME
    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"))
    except FileNotFoundError as exc:
        raise ManifestError(f"Missing {path}; this checkout has no {SCHEMA} project catalog") from exc
    except (OSError, ValueError) as exc:
        raise ManifestError(f"Cannot read {path}: {exc}") from exc
    return parse_manifest(value, source=str(path), minecraft_version=minecraft_version)

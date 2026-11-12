# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Install a built development mod into an existing loader game directory."""

from __future__ import annotations

import os
import shutil
from pathlib import Path

from . import loader_registry
from .benchmark import util

# The runtime textures the mod loads from <gameDir>/textures/. build.gradle checks the same files after every pull.
RUNTIME_TEXTURE_FILES = ("overcast_thunderstorm_bc6u.dds",)
OBSOLETE_RUNTIME_TEXTURE_FILES = ("water_normals_flipbook.dds",)


def development_mod_jar(project_dir: Path, mod_loader: str) -> Path | None:
    """The newest runnable development JAR for the selected loader."""
    registry_dir = project_dir if (project_dir / loader_registry.MANIFEST_NAME).is_file() else None
    loader = loader_registry.load_registry(registry_dir).get(mod_loader)
    directory = loader.project_path(project_dir, loader.development_jar_dir)
    pattern = loader.development_jar_pattern
    jars = [jar for jar in directory.glob(pattern) if jar.is_file() and "-sources" not in jar.name.lower()]
    return max(jars, key=lambda jar: jar.stat().st_mtime_ns, default=None)


def runtime_source(project_dir: Path, arch: str) -> Path:
    """The runtime directory install uses, with relative overrides anchored at the checkout."""
    return (project_dir / (os.environ.get("REMIX_BUILD") or f"remix-runtime/{arch}")).absolute()


def _stage_runtime(source: Path, bridge: Path, destination: Path, arch: str) -> None:
    # A game directory may point at a shared SDK cache. Detach the link before
    # reading its marker so even an unchanged SDK receives a private bridge copy.
    if destination.is_junction():
        destination.rmdir()
    elif destination.is_symlink():
        destination.unlink()

    marker = destination / ".install-marker"
    stamp = (source / "d3d9.dll").stat()
    marker_value = f"{arch};{source};{stamp.st_size} {stamp.st_mtime_ns}"
    current = marker.read_bytes().split(b"\n", 1)[0].rstrip(b"\r") if marker.is_file() else b""
    if not os.environ.get("REMIX_FORCE_SDK_COPY") and current == marker_value.encode("utf-8"):
        print(f"remix-sdk already staged for {arch} from {source}; skipping SDK copy.")
    else:
        if destination.exists():
            shutil.rmtree(destination)
        shutil.copytree(source, destination)
        marker.write_text(marker_value + "\n", encoding="utf-8")
    shutil.copy2(bridge, destination / "remixbridge.dll")


def install_development_build(
    project_dir: Path,
    *,
    game_dir: Path | None = None,
    arch: str | None = None,
    mod_loader: str | None = None,
    mod_jar: Path | None = None,
) -> int:
    """Stage built artifacts, returning 1 on missing prerequisites or a failed migration.

    Relative game and REMIX_BUILD paths are anchored at the checkout. This does
    not build the mod, install Fabric, enable user packs, or launch the game.
    Filesystem failures propagate to the CLI's error handler.
    """
    selected_arch = arch or os.environ.get("REMIX_ARCH")
    if not selected_arch:
        selected_arch = "arm64" if os.environ.get("PROCESSOR_ARCHITECTURE", "").lower() == "arm64" else "win64"
    if selected_arch.lower() not in {"win64", "arm64"}:
        print(f'ERROR: REMIX_ARCH must be win64 or arm64, got "{selected_arch}".')
        return 1
    selected_arch = selected_arch.lower()
    registry = loader_registry.load_registry()
    selected_loader = mod_loader or registry.default_id
    loader = registry.get(selected_loader)
    target_args = " ".join(registry.gradle_arguments(loader.id))

    if game_dir is None:
        appdata = os.environ.get("APPDATA")
        if not appdata:
            print("ERROR: APPDATA is not set; pass --game-dir DIR.")
            return 1
        game_dir = Path(appdata) / ".minecraft"
    game_dir = (project_dir / game_dir).absolute()
    source = runtime_source(project_dir, selected_arch)
    bridge = project_dir / "build" / "native" / selected_arch / "remixbridge.dll"
    if not (source / "d3d9.dll").is_file():
        print(f'ERROR: d3d9.dll not found in "{source}".')
        print(f"       Stage one with tools\\rtx.bat sdk local <runtime dir> --arch {selected_arch}.")
        return 1
    if not bridge.is_file():
        task = "compileNativeArm64" if selected_arch == "arm64" else "compileNativeWin64"
        print(f'ERROR: JNI bridge not found at "{bridge}".')
        print(f"       Build it with .\\gradlew.bat {task} {target_args}.")
        return 1
    if mod_jar is None:
        mod_jar = development_mod_jar(project_dir, selected_loader)
    if mod_jar is None:
        print(
            f"ERROR: no {loader.display_name} mod jar matching "
            f"{loader.development_jar_dir}/{loader.development_jar_pattern}."
        )
        return 1
    textures = project_dir / "remix-runtime" / "textures"
    if not textures.is_dir():
        print(f'ERROR: runtime textures not found at "{textures}".')
        print(f"       Pull them with .\\gradlew.bat fetchRuntimeTextures {target_args}.")
        return 1
    unusable = [
        name
        for name in RUNTIME_TEXTURE_FILES
        if not (textures / name).is_file() or (textures / name).stat().st_size == 0
    ]
    if unusable:
        print(f'ERROR: runtime textures at "{textures}" are missing or empty: {", ".join(unusable)}.')
        print(
            f"       Run .\\gradlew.bat fetchRuntimeTextures {target_args}; "
            "when it reports the same files, delete the folder it"
        )
        print("       names and run it again, and Packman downloads the package afresh.")
        return 1

    mods = game_dir / "mods"
    mods.mkdir(parents=True, exist_ok=True)
    installed_name = loader.installed_jar
    target = mods / installed_name
    staged = target.with_name(target.name + ".new")
    staged.unlink(missing_ok=True)
    shutil.copy2(mod_jar, staged)
    previous = [jar for jar in mods.glob("rtx-remix-renderer*.jar") if jar.is_file()]
    rollbacks: list[tuple[Path, Path]] = []
    installed = False
    try:
        for index, jar in enumerate(previous):
            rollback = jar.with_name(f"{jar.name}.rollback-{index}")
            rollback.unlink(missing_ok=True)
            jar.replace(rollback)
            rollbacks.append((jar, rollback))
        staged.replace(target)
        installed = True
    except OSError:
        if installed:
            target.unlink(missing_ok=True)
        for original, rollback in reversed(rollbacks):
            if rollback.exists():
                rollback.replace(original)
        staged.unlink(missing_ok=True)
        raise
    for _, rollback in rollbacks:
        rollback.unlink(missing_ok=True)
    if loader.provider == "fabric" and not any(mods.glob("fabric-api-*.jar")):
        print(f"WARNING: no fabric-api jar in {mods}.")
        requirement = loader.api_version_range or loader.api_version or "(version declared in project-state.json)"
        print(f"         The mod requires fabric-api {requirement} (https://modrinth.com/mod/fabric-api).")

    _stage_runtime(source, bridge, game_dir / "remix-sdk", selected_arch)
    packaging = project_dir / "packaging"
    shutil.copy2(packaging / "rtx.conf", game_dir / "rtx.conf")
    # Shipped profiles are refreshed; extra author-created files stay intact.
    shutil.copytree(packaging / "rtx-remix", game_dir / "rtx-remix", dirs_exist_ok=True)
    for name in OBSOLETE_RUNTIME_TEXTURE_FILES:
        obsolete = game_dir / "textures" / name
        if obsolete.is_file():
            obsolete.unlink()
            print(f"Removed obsolete runtime texture {obsolete}")
    shutil.copytree(textures, game_dir / "textures", dirs_exist_ok=True)
    migration = util.run(
        [
            "powershell",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            packaging / "migrate_resource_packs.ps1",
            "-McDir",
            game_dir,
            "-LegacyPackName",
            "rtx-remix-labpbr",
            "-ModLoader",
            loader.display_name,
        ],
        cwd=project_dir,
        check=False,
    )
    if migration.returncode != 0:
        print(f"ERROR: resource-pack migration failed ({migration.returncode}).")
        return 1
    print(f"Mod jar: {mod_jar}")
    print(f"Runtime: {source} [{selected_arch}]")
    print(f"Installed to {game_dir}")
    return 0

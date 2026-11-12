# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import os
import subprocess
from pathlib import Path
from unittest.mock import Mock

import pytest

from rtx_tools import cli, install, loader_registry
from rtx_tools.benchmark import util

pytestmark = pytest.mark.usefixtures("isolated_tools_config")
PROJECT_STATE = loader_registry.load_registry()
FABRIC_API_VERSION = PROJECT_STATE.get("fabric").api_version
assert FABRIC_API_VERSION is not None


def write_file(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)


@pytest.fixture
def migration(monkeypatch: pytest.MonkeyPatch) -> Mock:
    run = Mock(return_value=subprocess.CompletedProcess(args=[], returncode=0))
    monkeypatch.setattr(util, "run", run)
    return run


@pytest.fixture
def installation(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, migration: Mock) -> tuple[Path, Path]:
    project = tmp_path / "checkout"
    game = tmp_path / "existing game"
    (game / "mods").mkdir(parents=True)
    monkeypatch.setattr(util, "PROJECT_DIR", project)
    monkeypatch.setattr(util, "load_env_file", lambda: None)
    for name in ("REMIX_ARCH", "REMIX_BUILD", "REMIX_FORCE_SDK_COPY"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("PROCESSOR_ARCHITECTURE", "AMD64")
    monkeypatch.setenv("APPDATA", str(tmp_path / "appdata"))

    fabric_dir = PROJECT_STATE.get("fabric").development_jar_dir
    forge_dir = PROJECT_STATE.get("forge").development_jar_dir
    neoforge_dir = PROJECT_STATE.get("neoforge").development_jar_dir
    files = {
        f"{fabric_dir}/rtx-remix-renderer-0.1.jar": b"newest mod",
        f"{fabric_dir}/rtx-remix-renderer-9.9.jar": b"older mod",
        f"{fabric_dir}/rtx-remix-renderer-9.9-SOURCES.jar": b"sources only",
        f"{forge_dir}/rtx-remix-renderer-forge-0.1-all.jar": b"forge mod",
        f"{neoforge_dir}/rtx-remix-renderer-neoforge-0.1.jar": b"neoforge mod",
        "packaging/rtx.conf": b"runtime defaults",
        "packaging/rtx-remix/mods/minecraft-logic/weather/rain.conf": b"shipped rain",
        "remix-runtime/textures/overcast_thunderstorm_bc6u.dds": b"overcast texture",
    }
    for arch in ("win64", "arm64"):
        files[f"remix-runtime/{arch}/d3d9.dll"] = f"{arch} runtime".encode()
        files[f"remix-runtime/{arch}/dxvk.conf"] = b"runtime config"
        files[f"remix-runtime/{arch}/usd/plugins/plugin.dll"] = b"USD plugin"
        files[f"remix-runtime/{arch}/remixbridge.dll"] = b"old bundled bridge"
        files[f"build/native/{arch}/remixbridge.dll"] = f"{arch} bridge".encode()
    for name, content in files.items():
        write_file(project / name, content)
    jars = project / fabric_dir
    for name, timestamp in (
        ("rtx-remix-renderer-9.9.jar", 1_000),
        ("rtx-remix-renderer-0.1.jar", 2_000),
        ("rtx-remix-renderer-9.9-SOURCES.jar", 3_000),
    ):
        os.utime(jars / name, (timestamp, timestamp))
    return project, game


def run_install(game: Path, *args: str) -> int:
    return cli.main(["install", "--game-dir", str(game), *args])


@pytest.mark.parametrize("mod_loader", PROJECT_STATE.ids)
def test_development_mod_jar_selects_the_loader_and_excludes_sources(
    tmp_path: Path,
    mod_loader: str,
):
    loader = PROJECT_STATE.get(mod_loader)
    old = tmp_path / loader.development_jar_dir / loader.development_jar_pattern.replace("*", "old")
    selected = tmp_path / loader.development_jar_dir / loader.development_jar_pattern.replace("*", "selected")
    sources = tmp_path / loader.development_jar_dir / "rtx-remix-renderer-sources.jar"
    for path, timestamp in ((old, 1_000), (selected, 2_000), (sources, 3_000)):
        write_file(path, path.name.encode())
        os.utime(path, (timestamp, timestamp))

    assert install.development_mod_jar(tmp_path, mod_loader) == selected


def test_development_mod_jar_never_selects_newer_thin_forge_artifact(
    tmp_path: Path,
):
    directory = PROJECT_STATE.get("forge").development_jar_dir
    runnable = tmp_path / directory / "rtx-remix-renderer-forge-older-all.jar"
    thin = tmp_path / directory / "rtx-remix-renderer-forge-newer.jar"
    for path, timestamp in ((runnable, 1_000), (thin, 2_000)):
        write_file(path, path.name.encode())
        os.utime(path, (timestamp, timestamp))

    assert install.development_mod_jar(tmp_path, "forge") == runnable


@pytest.mark.parametrize("arch", ["win64", "arm64"])
def test_install_stages_complete_development_payload(installation, capsys, arch):
    project, game = installation
    existing = {
        "mods/rtx-remix-renderer-old.jar": b"old mod",
        f"mods/fabric-api-{FABRIC_API_VERSION}.jar": b"fabric api",
        "mods/another-mod.jar": b"another mod",
        "remix-sdk/stale.dll": b"stale SDK",
        "rtx-remix/mods/minecraft-logic/weather/rain.conf": b"edited shipped rain",
        "rtx-remix/mods/custom/zones/castle.conf": b"author zone",
        "textures/water_normals_flipbook.dds": b"obsolete shipped water normals",
        "textures/custom.dds": b"author texture",
        "resourcepacks/rtx-remix-labpbr/custom.txt": b"custom pack file",
        "options.txt": b"unchanged settings",
    }
    for name, content in existing.items():
        write_file(game / name, content)
    (project / f"remix-runtime/{arch}/usd/empty").mkdir()

    assert run_install(game, "--arch", arch) == 0

    expected = {
        "mods/rtx-remix-renderer-fabric.jar": b"newest mod",
        f"mods/fabric-api-{FABRIC_API_VERSION}.jar": b"fabric api",
        "mods/another-mod.jar": b"another mod",
        "remix-sdk/d3d9.dll": f"{arch} runtime".encode(),
        "remix-sdk/dxvk.conf": b"runtime config",
        "remix-sdk/usd/plugins/plugin.dll": b"USD plugin",
        "remix-sdk/remixbridge.dll": f"{arch} bridge".encode(),
        "rtx.conf": b"runtime defaults",
        "rtx-remix/mods/minecraft-logic/weather/rain.conf": b"shipped rain",
        "rtx-remix/mods/custom/zones/castle.conf": b"author zone",
        "textures/overcast_thunderstorm_bc6u.dds": b"overcast texture",
        "textures/custom.dds": b"author texture",
        "resourcepacks/rtx-remix-labpbr/custom.txt": b"custom pack file",
        "options.txt": b"unchanged settings",
    }
    actual = {path.relative_to(game).as_posix(): path.read_bytes() for path in game.rglob("*") if path.is_file()}
    marker = actual.pop("remix-sdk/.install-marker")
    assert marker.decode("utf-8").startswith(f"{arch};")
    assert actual == expected
    assert (game / "remix-sdk/usd/empty").is_dir()
    assert (project / f"remix-runtime/{arch}/remixbridge.dll").read_bytes() == b"old bundled bridge"
    output = capsys.readouterr().out
    assert "WARNING" not in output
    assert "Mod jar:" in output
    assert f"[{arch}]" in output
    assert f"Installed to {game}" in output
    assert not (game / "resourcepacks/rtx-remix-labpbr/pack.mcmeta").exists()


def test_install_invokes_resource_pack_migration(installation, migration):
    project, game = installation

    assert run_install(game) == 0

    migration.assert_called_once_with(
        [
            "powershell",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            project / "packaging" / "migrate_resource_packs.ps1",
            "-McDir",
            game,
            "-LegacyPackName",
            "rtx-remix-labpbr",
            "-ModLoader",
            "Fabric",
        ],
        cwd=project,
        check=False,
    )
    assert not (game / "resourcepacks").exists()


@pytest.mark.parametrize(
    ("loader_id", "installed_jar", "content"),
    [
        ("forge", "rtx-remix-renderer-forge.jar", b"forge mod"),
        ("neoforge", "rtx-remix-renderer-neoforge.jar", b"neoforge mod"),
    ],
)
def test_install_selects_non_fabric_artifact_and_migration_pack(
    installation, migration, capsys, loader_id, installed_jar, content
):
    _, game = installation

    assert run_install(game, "--loader", loader_id) == 0

    loader = PROJECT_STATE.get(loader_id)
    assert (game / "mods" / installed_jar).read_bytes() == content
    assert migration.call_args.args[0][-2:] == ["-ModLoader", loader.display_name]
    assert "fabric-api" not in capsys.readouterr().out


@pytest.mark.parametrize(
    ("cli_loader", "env_loader", "configured_loader", "expected"),
    [
        ("neoforge", "forge", "fabric", "neoforge"),
        ("forge", "fabric", "fabric", "forge"),
        (None, "neoforge", "fabric", "neoforge"),
        (None, "forge", "fabric", "forge"),
        (None, None, "neoforge", "neoforge"),
        (None, None, "forge", "forge"),
        (None, None, None, "fabric"),
    ],
)
def test_install_uses_shared_loader_precedence(
    installation,
    isolated_tools_config,
    monkeypatch,
    cli_loader,
    env_loader,
    configured_loader,
    expected,
):
    _, game = installation
    if env_loader is None:
        monkeypatch.delenv("RTXREMIX_MOD_LOADER", raising=False)
    else:
        monkeypatch.setenv("RTXREMIX_MOD_LOADER", env_loader)
    if configured_loader is not None:
        (isolated_tools_config.with_name("config.local.json")).write_text(
            f'{{"modLoader":"{configured_loader}"}}',
            encoding="utf-8",
        )
    stage = Mock(return_value=0)
    monkeypatch.setattr(install, "install_development_build", stage)
    args = ["install", "--game-dir", str(game)]
    if cli_loader is not None:
        args += ["--loader", cli_loader]

    assert cli.main(args) == 0

    assert stage.call_args.kwargs["mod_loader"] == expected


@pytest.mark.parametrize(
    ("loader_args", "expected"),
    [(["-ModLoader", loader.display_name], loader.builtin_resource_pack) for loader in PROJECT_STATE.loaders],
)
def test_resource_pack_migration_selects_loader_builtin(tmp_path, loader_args, expected):
    game = tmp_path / "game"
    game.mkdir()
    (game / "options.txt").write_text(
        'resourcePacks:["file/user-pack","fabric","mod_resources","vanilla"]\n',
        encoding="utf-8",
    )

    subprocess.run(
        [
            "powershell",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(util.PROJECT_DIR / "packaging/migrate_resource_packs.ps1"),
            "-McDir",
            str(game),
            "-LegacyPackName",
            "rtx-remix-labpbr",
            *loader_args,
        ],
        check=True,
    )

    assert (game / "options.txt").read_text(encoding="utf-8") == (
        f'resourcePacks:["vanilla","{expected}","file/user-pack"]\n'
    )


@pytest.mark.parametrize("exit_code", [1, 7])
def test_install_reports_resource_pack_migration_failure(installation, migration, capsys, exit_code):
    _, game = installation
    migration.return_value.returncode = exit_code

    assert run_install(game) == 1

    migration.assert_called_once()
    output = capsys.readouterr().out
    assert f"ERROR: resource-pack migration failed ({exit_code})." in output
    assert "Installed to" not in output


def test_install_warns_about_missing_fabric_api_without_installing_it(installation, capsys):
    _, game = installation

    assert run_install(game) == 0

    output = capsys.readouterr().out
    assert f"WARNING: no fabric-api jar in {game / 'mods'}." in output
    assert (
        f"fabric-api {PROJECT_STATE.get('fabric').api_version_range} (https://modrinth.com/mod/fabric-api)"
    ) in output
    assert list((game / "mods").glob("fabric-api-*.jar")) == []


@pytest.mark.parametrize("arch", ["win64", "arm64"])
@pytest.mark.parametrize("missing", ["runtime", "bridge", "mod", "textures", "texture file", "empty texture"])
def test_install_checks_prerequisites_before_changing_game(installation, migration, capsys, arch, missing):
    project, game = installation
    write_file(game / "mods/rtx-remix-renderer.jar", b"previous install")
    if missing == "runtime":
        (project / f"remix-runtime/{arch}/d3d9.dll").unlink()
    elif missing == "bridge":
        (project / f"build/native/{arch}/remixbridge.dll").unlink()
    elif missing == "textures":
        for texture in (project / "remix-runtime/textures").iterdir():
            texture.unlink()
        (project / "remix-runtime/textures").rmdir()
    elif missing == "texture file":
        (project / "remix-runtime/textures/overcast_thunderstorm_bc6u.dds").unlink()
    elif missing == "empty texture":
        (project / "remix-runtime/textures/overcast_thunderstorm_bc6u.dds").write_bytes(b"")
    else:
        for jar in (project / PROJECT_STATE.get("fabric").development_jar_dir).glob("*.jar"):
            if "-sources" not in jar.name.lower():
                jar.unlink()

    assert run_install(game, "--arch", arch) == 1
    migration.assert_not_called()

    assert (game / "mods/rtx-remix-renderer.jar").read_bytes() == b"previous install"
    assert sorted(path.name for path in game.iterdir()) == ["mods"]
    output = capsys.readouterr().out
    if missing == "runtime":
        assert "ERROR: d3d9.dll not found" in output
        assert f"--arch {arch}" in output
        assert "sdk local" in output
    elif missing == "bridge":
        assert "ERROR: JNI bridge not found" in output
        task = "compileNativeArm64" if arch == "arm64" else "compileNativeWin64"
        target_args = " ".join(PROJECT_STATE.gradle_arguments("fabric"))
        assert f"Build it with .\\gradlew.bat {task} {target_args}." in output
    elif missing == "textures":
        assert "ERROR: runtime textures not found" in output
        assert "fetchRuntimeTextures" in output
    elif missing == "texture file":
        assert "overcast_thunderstorm_bc6u.dds" in output
        assert "fetchRuntimeTextures" in output
    elif missing == "empty texture":
        assert "overcast_thunderstorm_bc6u.dds" in output
        assert "fetchRuntimeTextures" in output
    else:
        loader = PROJECT_STATE.get("fabric")
        assert (
            f"ERROR: no {loader.display_name} mod jar matching "
            f"{loader.development_jar_dir}/{loader.development_jar_pattern}."
        ) in output


@pytest.mark.parametrize(
    ("override", "processor", "expected"),
    [(None, "AMD64", "win64"), (None, "Arm64", "arm64"), ("ARM64", "AMD64", "arm64"), ("WIN64", "ARM64", "win64")],
)
def test_install_keeps_environment_and_host_arch_defaults(installation, monkeypatch, override, processor, expected):
    _, game = installation
    monkeypatch.setenv("PROCESSOR_ARCHITECTURE", processor)
    if override:
        monkeypatch.setenv("REMIX_ARCH", override)

    assert run_install(game) == 0
    assert (game / "remix-sdk/remixbridge.dll").read_bytes() == f"{expected} bridge".encode()


def test_install_explicit_arch_overrides_environment(installation, monkeypatch):
    _, game = installation
    monkeypatch.setenv("REMIX_ARCH", "invalid")

    assert run_install(game, "--arch", "arm64") == 0
    assert (game / "remix-sdk/remixbridge.dll").read_bytes() == b"arm64 bridge"


def test_install_rejects_invalid_environment_arch(installation, monkeypatch, capsys):
    _, game = installation
    monkeypatch.setenv("REMIX_ARCH", "x86")

    assert run_install(game) == 1
    assert 'ERROR: REMIX_ARCH must be win64 or arm64, got "x86".' in capsys.readouterr().out
    assert not (game / "remix-sdk").exists()


def test_install_defaults_to_appdata_minecraft(installation, monkeypatch, tmp_path):
    appdata = tmp_path / "Roaming"
    monkeypatch.setenv("APPDATA", str(appdata))

    assert cli.main(["install"]) == 0
    assert (appdata / ".minecraft/mods/rtx-remix-renderer-fabric.jar").read_bytes() == b"newest mod"


def test_install_requires_game_dir_when_appdata_is_unset(installation, monkeypatch, capsys):
    monkeypatch.delenv("APPDATA")

    assert cli.main(["install"]) == 1
    assert "APPDATA is not set; pass --game-dir DIR." in capsys.readouterr().out


def test_install_resolves_relative_paths_from_checkout(installation, migration, monkeypatch, tmp_path):
    project, _ = installation
    write_file(project / "local SDK/d3d9.dll", b"custom runtime")
    monkeypatch.setenv("REMIX_BUILD", "local SDK")
    monkeypatch.chdir(tmp_path)

    assert run_install(Path("another game")) == 0
    assert (project / "another game/remix-sdk/d3d9.dll").read_bytes() == b"custom runtime"
    assert migration.call_args.args[0][7] == project / "another game"


def test_install_accepts_absolute_custom_runtime(installation, monkeypatch, tmp_path):
    _, game = installation
    source = tmp_path / "custom SDK"
    write_file(source / "d3d9.dll", b"custom runtime")
    write_file(source / "usd/custom.dll", b"custom plugin")
    monkeypatch.setenv("REMIX_BUILD", str(source))

    assert run_install(game) == 0
    assert (game / "remix-sdk/d3d9.dll").read_bytes() == b"custom runtime"
    assert (game / "remix-sdk/usd/custom.dll").read_bytes() == b"custom plugin"


def test_install_skips_unchanged_runtime_but_refreshes_bridge_and_payload(installation, capsys):
    project, game = installation
    assert run_install(game) == 0
    marker = (game / "remix-sdk/.install-marker").read_bytes()
    write_file(game / "remix-sdk/keep-on-skip.dll", b"not recopied")
    write_file(project / "build/native/win64/remixbridge.dll", b"rebuilt bridge")
    write_file(project / "packaging/rtx.conf", b"updated defaults")

    assert run_install(game) == 0

    assert (game / "remix-sdk/keep-on-skip.dll").read_bytes() == b"not recopied"
    assert (game / "remix-sdk/.install-marker").read_bytes() == marker
    assert (game / "remix-sdk/remixbridge.dll").read_bytes() == b"rebuilt bridge"
    assert (game / "rtx.conf").read_bytes() == b"updated defaults"
    assert "skipping SDK copy." in capsys.readouterr().out


@pytest.mark.parametrize("force", ["1", "0"])
def test_install_force_copy_recreates_runtime(installation, monkeypatch, force):
    _, game = installation
    assert run_install(game) == 0
    write_file(game / "remix-sdk/stale.dll", b"remove on recopy")
    monkeypatch.setenv("REMIX_FORCE_SDK_COPY", force)

    assert run_install(game) == 0
    assert not (game / "remix-sdk/stale.dll").exists()


@pytest.mark.parametrize("changed", ["size", "timestamp", "source", "arch"])
def test_install_recreates_runtime_when_marker_changes(installation, monkeypatch, changed):
    project, game = installation
    assert run_install(game) == 0
    write_file(game / "remix-sdk/stale.dll", b"remove on recopy")
    source = project / "remix-runtime/win64/d3d9.dll"
    args = []
    if changed == "size":
        source.write_bytes(b"larger rebuilt runtime")
    elif changed == "timestamp":
        stamp = source.stat()
        os.utime(source, ns=(stamp.st_atime_ns, stamp.st_mtime_ns + 120_000_000_000))
    elif changed == "source":
        custom = project / "custom runtime"
        write_file(custom / "d3d9.dll", source.read_bytes())
        stamp = source.stat()
        os.utime(custom / "d3d9.dll", ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
        monkeypatch.setenv("REMIX_BUILD", str(custom))
    else:
        args = ["--arch", "arm64"]

    assert run_install(game, *args) == 0
    assert not (game / "remix-sdk/stale.dll").exists()


@pytest.mark.skipif(os.name != "nt", reason="Windows runtime junctions")
def test_install_detaches_game_sdk_junction_without_mutating_cache(installation, tmp_path):
    _, game = installation
    assert run_install(game) == 0
    sdk = game / "remix-sdk"
    cache = tmp_path / "shared cache"
    sdk.rename(cache)
    original = {path.relative_to(cache): path.read_bytes() for path in cache.rglob("*") if path.is_file()}
    subprocess.run(["cmd", "/d", "/c", "mklink", "/J", str(sdk), str(cache)], check=True, capture_output=True)

    assert run_install(game) == 0

    assert not sdk.is_junction()
    assert (sdk / "remixbridge.dll").read_bytes() == b"win64 bridge"
    assert {path.relative_to(cache): path.read_bytes() for path in cache.rglob("*") if path.is_file()} == original

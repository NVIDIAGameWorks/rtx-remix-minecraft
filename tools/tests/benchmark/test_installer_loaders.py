# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Catalog-driven loader behavior owned by packaging/installer.ps1."""

import json
import os
import shutil
import subprocess
from copy import deepcopy
from pathlib import Path

import pytest

from rtx_tools import loader_registry
from rtx_tools.benchmark import util

pytestmark = pytest.mark.skipif(os.name != "nt", reason="Windows PowerShell runs the installer")

INSTALLER = util.PROJECT_DIR / "packaging" / "installer.ps1"
PROJECT_STATE = loader_registry.load_registry()
MC_VERSION = PROJECT_STATE.minecraft_version
FABRIC_VERSION = PROJECT_STATE.get("fabric").version
FORGE_VERSION = PROJECT_STATE.get("forge").version
NEOFORGE_VERSION = PROJECT_STATE.get("neoforge").version
FABRIC_PROFILE = f"fabric-loader-{FABRIC_VERSION}-{MC_VERSION}"
FORGE_PROFILE = f"{MC_VERSION}-forge-{FORGE_VERSION}"
NEOFORGE_PROFILE = f"neoforge-{NEOFORGE_VERSION}"

HARNESS = r"""
param([string]$Installer, [string]$Loaded, [string]$Action, [string]$Root, [string]$Path,
      [string]$Loader, [string]$Launcher, [string]$JavaExe)
$ast = [System.Management.Automation.Language.Parser]::ParseFile($Installer, [ref]$null, [ref]$null)
$entryPoint = $ast.EndBlock.Statements[-1]
$start = $ast.ParamBlock.Extent.EndOffset
$body = $ast.Extent.Text.Substring($start, $entryPoint.Extent.StartOffset - $start)
[IO.File]::WriteAllText($Loaded, $body, [Text.UTF8Encoding]::new($true))
. $Loaded
switch ($Action) {
    'Dist' {
        $script:SCRIPT_DIR = $Root
        $env:RTXREMIX_PROJECT_STATE = Join-Path $Root 'project-state.json'
        Initialize-LoaderCatalog
        Split-Path -Leaf (Get-DistModJar -ModLoader $Loader)
    }
    'PrismSwitch' {
        Initialize-PrismInstance -InstanceDir $Path -ModLoader $Loader
    }
    'PreserveLoader' {
        $detected = [pscustomobject]@{ ModLoaders = @{ $Launcher = $Loader } }
        Select-ModLoader -Mode Upgrade -Launcher $Launcher -DetectedState $detected
    }
    'RemoveApi' {
        Remove-FabricApiForNonFabric -McDir $Path -Launcher $Launcher -ModLoader $Loader
    }
    'LoaderProfile' {
        Ensure-MsLoaderProfile -ModLoader $Loader
    }
    'InstallLoader' {
        function Invoke-VerifiedDownload {
            param([string]$Url, [string]$Destination, [string]$ExpectedSha256, [string]$ChecksumUrl)
            [IO.File]::WriteAllText($Destination, 'installer')
            Write-Host "Download: $Url"
            Write-Host "Checksum: $ChecksumUrl"
        }
        Initialize-Session
        Initialize-MicrosoftLoaderInstaller -JavaExe $JavaExe -ModLoader $Loader
    }
    'ResolveJava' {
        $script:MANAGED_JDK_DIR = $Root
        function Get-Command { [pscustomobject]@{ Source = $JavaExe } }
        function Get-JavaMajor { param([string]$Candidate) if ($Candidate -eq $JavaExe) { 17 } else { 21 } }
        Resolve-JavaForLoaderInstaller -ModLoader $Loader
    }
    'ProfileDir' {
        Get-MsProfileGameDir -ModLoader $Loader
    }
    'PrismInstanceDir' {
        Get-PrismInstanceDir -DataDir $Root
    }
    'UninstallBoth' {
        function Get-DetectedState {
            [pscustomobject]@{
                ModInstalled = @{ Prism = 'fabric.jar'; Microsoft = 'forge.jar' }
                ModLoaders = @{ Prism = 'Fabric'; Microsoft = 'Forge' }
                PrismDataDir = $Root
            }
        }
        function Select-LauncherForUninstall { 'Both' }
        function Get-InstalledModVersion {
            param([string]$McDir, [string]$ModLoader)
            "rtx-remix-renderer-$($ModLoader.ToLowerInvariant()).jar"
        }
        function Remove-ModArtifacts {
            param([string]$McDir, [string]$Launcher, [string]$ModLoader)
            Write-Host "Removed: $Launcher/$ModLoader"
        }
        Invoke-Uninstall -Unattended -AcknowledgeDataLoss
    }
    'RemoveOppositeProfile' {
        Remove-OtherManagedMsProfiles -SelectedModLoader $Loader
    }
    'ArtifactLoader' {
        Get-ModArtifactLoader -FileName $Path
    }
    'Version' {
        function Invoke-Menu {
            param([string]$Title, [array]$Items, [string]$Selected)
            Write-Host "Asked: $Title"
            return $Items[0].Value
        }
        $null = Select-MinecraftVersion
        Write-Host "Selected: $script:MC_VERSION"
        Write-Host "Available: $($script:AVAILABLE_MINECRAFT_VERSIONS -join ',')"
    }
    default { throw "Unknown action: $Action" }
}
"""


def run_harness(tmp_path: Path, *arguments: str, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    harness = tmp_path / "harness.ps1"
    harness.write_text(HARNESS, encoding="ascii")
    return subprocess.run(
        [
            "powershell",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness),
            "-Installer",
            str(INSTALLER),
            "-Loaded",
            str(tmp_path / "installer-functions.ps1"),
            *arguments,
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
        env=env,
    )


def multi_version_release(tmp_path: Path, *packaged_versions: str) -> tuple[Path, dict[str, str]]:
    state = json.loads((util.PROJECT_DIR / "project-state.json").read_text(encoding="utf-8"))
    second = deepcopy(state["versions"]["1.21.1"])
    second["minecraft"]["version"] = "1.20.1"
    second["minecraft"]["module"] = "versions/1.20.1/minecraft"
    for loader_id, loader in second["loaders"].items():
        loader["module"] = f"versions/1.20.1/{loader_id}"
    state["versions"]["1.20.1"] = second
    manifest = tmp_path / "project-state.json"
    manifest.write_text(json.dumps(state), encoding="utf-8")
    for version in packaged_versions:
        for loader_id in state["versions"][version]["loaders"]:
            (tmp_path / "mods" / version / loader_id).mkdir(parents=True, exist_ok=True)
    return manifest, {**os.environ, "RTXREMIX_PROJECT_STATE": str(manifest)}


def test_unattended_uninstall_accepts_both_launchers_without_one_loader():
    text = INSTALLER.read_text(encoding="utf-8-sig")
    assert "[ValidateSet('Prism', 'Microsoft', 'Both')]" in text
    assert "-not ($Mode -eq 'Uninstall' -and $Launcher -eq 'Both')" in text
    assert "$Launcher -eq 'Both' -and $Mode -ne 'Uninstall'" in text


def test_release_jar_is_selected_from_the_requested_loader_directory(tmp_path: Path):
    root = tmp_path / "release"
    root.mkdir()
    shutil.copy2(util.PROJECT_DIR / "project-state.json", root / "project-state.json")
    for loader, name in (
        ("fabric", "rtx-remix-renderer-fabric.jar"),
        ("forge", "rtx-remix-renderer-forge-all.jar"),
        ("neoforge", "rtx-remix-renderer-neoforge.jar"),
    ):
        directory = root / "mods" / loader
        directory.mkdir(parents=True)
        (directory / name).touch()

    for loader, expected in (
        ("Fabric", "rtx-remix-renderer-fabric.jar"),
        ("Forge", "rtx-remix-renderer-forge-all.jar"),
        ("NeoForge", "rtx-remix-renderer-neoforge.jar"),
    ):
        result = run_harness(tmp_path, "-Action", "Dist", "-Root", str(root), "-Loader", loader)
        assert result.returncode == 0, result.stdout + result.stderr
        assert result.stdout.strip() == expected


def test_prism_instance_directory_follows_launcher_configuration(tmp_path: Path):
    data_dir = tmp_path / "prism"
    data_dir.mkdir()
    custom = tmp_path / "custom-instances"
    (data_dir / "prismlauncher.cfg").write_text(
        f'InstanceDir="{custom}"\n',
        encoding="utf-8",
    )

    result = run_harness(
        tmp_path,
        "-Action",
        "PrismInstanceDir",
        "-Root",
        str(data_dir),
    )

    assert result.returncode == 0, result.stdout + result.stderr
    assert Path(result.stdout.strip()) == custom / "NVIDIA RTX Remix for Minecraft"


@pytest.mark.parametrize(
    ("loader", "uid", "version"),
    [
        ("Forge", "net.minecraftforge", FORGE_VERSION),
        ("NeoForge", "net.neoforged", NEOFORGE_VERSION),
    ],
)
def test_prism_switch_replaces_only_the_loader_component(tmp_path: Path, loader: str, uid: str, version: str):
    instance = tmp_path / "instance"
    instance.mkdir()
    (instance / "instance.cfg").write_text("InstanceType=OneSix\r\n", encoding="utf-8")
    pack = {
        "components": [
            {"uid": "net.minecraft", "version": MC_VERSION, "important": True},
            {"uid": "net.fabricmc.fabric-loader", "version": "old"},
            {"uid": "net.minecraftforge", "version": "old"},
            {"uid": "net.neoforged", "version": "old"},
            {"uid": "example.unrelated", "version": "keep"},
        ],
        "formatVersion": 1,
    }
    (instance / "mmc-pack.json").write_text(json.dumps(pack), encoding="utf-8")

    result = run_harness(tmp_path, "-Action", "PrismSwitch", "-Path", str(instance), "-Loader", loader)

    assert result.returncode == 0, result.stdout + result.stderr
    written = json.loads((instance / "mmc-pack.json").read_text(encoding="utf-8-sig"))
    components = {component["uid"]: component for component in written["components"]}
    assert "net.fabricmc.fabric-loader" not in components
    assert set(components).isdisjoint({"net.minecraftforge", "net.neoforged"} - {uid})
    assert components[uid]["version"] == version
    assert components["example.unrelated"]["version"] == "keep"
    assert (instance / "mmc-pack.json.bak").exists()


@pytest.mark.parametrize("loader", [loader.display_name for loader in PROJECT_STATE.loaders])
def test_upgrade_preserves_the_installed_loader(tmp_path: Path, loader: str):
    result = run_harness(
        tmp_path,
        "-Action",
        "PreserveLoader",
        "-Launcher",
        "Prism",
        "-Loader",
        loader,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout.splitlines()[-1] == loader


@pytest.mark.parametrize("loader", ["Forge", "NeoForge"])
def test_switching_prism_to_non_fabric_removes_fabric_api(tmp_path: Path, loader: str):
    mods = tmp_path / "game" / "mods"
    mods.mkdir(parents=True)
    (mods / "fabric-api-old.jar").touch()
    (mods / "unrelated.jar").touch()

    result = run_harness(
        tmp_path,
        "-Action",
        "RemoveApi",
        "-Path",
        str(tmp_path / "game"),
        "-Launcher",
        "Prism",
        "-Loader",
        loader,
    )

    assert result.returncode == 0, result.stdout + result.stderr
    assert not (mods / "fabric-api-old.jar").exists()
    assert (mods / "unrelated.jar").exists()


@pytest.mark.parametrize("loader", ["Forge", "NeoForge"])
def test_non_fabric_in_shared_microsoft_mods_does_not_remove_fabric_api(tmp_path: Path, loader: str):
    mods = tmp_path / "game" / "mods"
    mods.mkdir(parents=True)
    (mods / "fabric-api-old.jar").touch()

    result = run_harness(
        tmp_path,
        "-Action",
        "RemoveApi",
        "-Path",
        str(tmp_path / "game"),
        "-Launcher",
        "Microsoft",
        "-Loader",
        loader,
    )

    assert result.returncode == 0, result.stdout + result.stderr
    assert (mods / "fabric-api-old.jar").exists()


@pytest.mark.parametrize("filename", ["launcher_profiles.json", "launcher_profiles_microsoft_store.json"])
@pytest.mark.parametrize(
    ("loader", "profile_key", "version_id"),
    [
        ("Forge", f"rtx-remix-forge-{MC_VERSION}", FORGE_PROFILE),
        ("NeoForge", f"rtx-remix-neoforge-{MC_VERSION}", NEOFORGE_PROFILE),
    ],
)
def test_microsoft_installer_profile_is_created_and_visible(
    tmp_path: Path, filename: str, loader: str, profile_key: str, version_id: str
):
    app_data = tmp_path / "AppData"
    profiles = app_data / ".minecraft" / filename
    profiles.parent.mkdir(parents=True)
    profiles.write_text(json.dumps({"profiles": {}, "version": 3}), encoding="utf-8")
    env = {**os.environ, "APPDATA": str(app_data)}
    harness = tmp_path / "harness.ps1"
    harness.write_text(HARNESS, encoding="ascii")

    result = subprocess.run(
        [
            "powershell",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness),
            "-Installer",
            str(INSTALLER),
            "-Loaded",
            str(tmp_path / "installer-functions.ps1"),
            "-Action",
            "LoaderProfile",
            "-Loader",
            loader,
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
        env=env,
    )

    assert result.returncode == 0, result.stdout + result.stderr
    profile = json.loads(profiles.read_text(encoding="utf-8"))["profiles"][profile_key]
    assert profile["lastVersionId"] == version_id
    assert profile["name"] == f"NVIDIA RTX Remix for Minecraft ({loader})"
    assert profile["type"] == "custom"


@pytest.mark.parametrize(
    ("loader", "version_id"),
    [("Forge", FORGE_PROFILE), ("NeoForge", NEOFORGE_PROFILE)],
)
def test_existing_installer_profile_game_directory_is_used(tmp_path: Path, loader: str, version_id: str):
    app_data = tmp_path / "AppData"
    game_dir = tmp_path / "custom-forge-game"
    profiles = app_data / ".minecraft/launcher_profiles.json"
    profiles.parent.mkdir(parents=True)
    profiles.write_text(
        json.dumps(
            {
                "profiles": {
                    "custom-forge": {
                        "lastVersionId": version_id,
                        "gameDir": str(game_dir),
                    }
                }
            }
        ),
        encoding="utf-8",
    )
    env = {**os.environ, "APPDATA": str(app_data)}
    harness = tmp_path / "harness.ps1"
    harness.write_text(HARNESS, encoding="ascii")

    result = subprocess.run(
        [
            "powershell",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness),
            "-Installer",
            str(INSTALLER),
            "-Loaded",
            str(tmp_path / "installer-functions.ps1"),
            "-Action",
            "ProfileDir",
            "-Loader",
            loader,
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
        env=env,
    )

    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout.strip() == str(game_dir)


@pytest.mark.parametrize(
    ("loader", "version_id", "artifact", "url_version", "installer_arg"),
    [
        (
            "Forge",
            FORGE_PROFILE,
            f"forge-{MC_VERSION}-{FORGE_VERSION}-installer.jar",
            f"{MC_VERSION}-{FORGE_VERSION}",
            "--installClient",
        ),
        (
            "NeoForge",
            NEOFORGE_PROFILE,
            f"neoforge-{NEOFORGE_VERSION}-installer.jar",
            NEOFORGE_VERSION,
            "--install-client",
        ),
    ],
)
def test_microsoft_loader_downloads_verifies_and_runs_the_pinned_installer(
    tmp_path: Path,
    loader: str,
    version_id: str,
    artifact: str,
    url_version: str,
    installer_arg: str,
):
    app_data = tmp_path / "AppData"
    (app_data / ".minecraft").mkdir(parents=True)
    java = tmp_path / "java.cmd"
    java.write_text(
        f'@if not "%3"=="{installer_arg}" exit /b 9\r\n'
        f'@mkdir "%APPDATA%\\.minecraft\\versions\\{version_id}"\r\n'
        "@exit /b 0\r\n",
        encoding="ascii",
    )
    env = {**os.environ, "APPDATA": str(app_data)}
    harness = tmp_path / "harness.ps1"
    harness.write_text(HARNESS, encoding="ascii")

    result = subprocess.run(
        [
            "powershell",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness),
            "-Installer",
            str(INSTALLER),
            "-Loaded",
            str(tmp_path / "installer-functions.ps1"),
            "-Action",
            "InstallLoader",
            "-Loader",
            loader,
            "-JavaExe",
            str(java),
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
        env=env,
    )

    assert result.returncode == 0, result.stdout + result.stderr
    assert f"/{url_version}/{artifact}" in result.stdout
    assert f"/{url_version}/{artifact}.sha256" in result.stdout
    assert (app_data / f".minecraft/versions/{version_id}").is_dir()


@pytest.mark.parametrize("loader", ["Forge", "NeoForge"])
def test_installer_loader_skips_old_java_and_uses_java_21(tmp_path: Path, loader: str):
    java17 = tmp_path / "java17.exe"
    java17.touch()
    managed = tmp_path / "managed"
    java21 = managed / "bin/java.exe"
    java21.parent.mkdir(parents=True)
    java21.touch()

    result = run_harness(
        tmp_path,
        "-Action",
        "ResolveJava",
        "-Root",
        str(managed),
        "-JavaExe",
        str(java17),
        "-Loader",
        loader,
    )

    assert result.returncode == 0, result.stdout + result.stderr
    assert str(java21) in result.stdout
    assert "java17" not in result.stdout


def test_uninstall_both_uses_each_launchers_detected_loader(tmp_path: Path):
    app_data = tmp_path / "AppData"
    (app_data / ".minecraft").mkdir(parents=True)
    env = {**os.environ, "APPDATA": str(app_data)}
    harness = tmp_path / "harness.ps1"
    harness.write_text(HARNESS, encoding="ascii")

    result = subprocess.run(
        [
            "powershell",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness),
            "-Installer",
            str(INSTALLER),
            "-Loaded",
            str(tmp_path / "installer-functions.ps1"),
            "-Action",
            "UninstallBoth",
            "-Root",
            str(tmp_path / "Prism"),
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
        env=env,
    )

    assert result.returncode == 0, result.stdout + result.stderr
    assert "Removed: Prism/Fabric" in result.stdout
    assert "Removed: Microsoft/Forge" in result.stdout


@pytest.mark.parametrize(
    ("selected", "selected_key"),
    [
        ("Forge", f"rtx-remix-forge-{MC_VERSION}"),
        ("NeoForge", f"rtx-remix-neoforge-{MC_VERSION}"),
    ],
)
def test_loader_switch_removes_only_other_managed_microsoft_profiles(tmp_path: Path, selected: str, selected_key: str):
    app_data = tmp_path / "AppData"
    profiles = app_data / ".minecraft/launcher_profiles.json"
    profiles.parent.mkdir(parents=True)
    profiles.write_text(
        json.dumps(
            {
                "profiles": {
                    f"fabric-loader-{MC_VERSION}": {
                        "name": "NVIDIA RTX Remix for Minecraft",
                        "lastVersionId": FABRIC_PROFILE,
                    },
                    "custom-fabric": {
                        "name": "My Fabric",
                        "lastVersionId": FABRIC_PROFILE,
                    },
                    f"rtx-remix-forge-{MC_VERSION}": {
                        "name": "NVIDIA RTX Remix for Minecraft (Forge)",
                        "lastVersionId": FORGE_PROFILE,
                    },
                    f"rtx-remix-neoforge-{MC_VERSION}": {
                        "name": "NVIDIA RTX Remix for Minecraft (NeoForge)",
                        "lastVersionId": NEOFORGE_PROFILE,
                    },
                }
            }
        ),
        encoding="utf-8",
    )
    env = {**os.environ, "APPDATA": str(app_data)}
    harness = tmp_path / "harness.ps1"
    harness.write_text(HARNESS, encoding="ascii")

    result = subprocess.run(
        [
            "powershell",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(harness),
            "-Installer",
            str(INSTALLER),
            "-Loaded",
            str(tmp_path / "installer-functions.ps1"),
            "-Action",
            "RemoveOppositeProfile",
            "-Loader",
            selected,
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
        env=env,
    )

    assert result.returncode == 0, result.stdout + result.stderr
    written = json.loads(profiles.read_text(encoding="utf-8"))["profiles"]
    assert f"fabric-loader-{MC_VERSION}" not in written
    assert "custom-fabric" in written
    assert selected_key in written
    other_key = f"rtx-remix-neoforge-{MC_VERSION}" if selected == "Forge" else f"rtx-remix-forge-{MC_VERSION}"
    assert other_key not in written


@pytest.mark.parametrize(
    ("filename", "loader"),
    [
        ("rtx-remix-renderer-0.1.0.jar", "Fabric"),
        ("rtx-remix-renderer-forge-0.1.0-all.jar", "Forge"),
        ("rtx-remix-renderer-neoforge-0.1.0.jar", "NeoForge"),
    ],
)
def test_legacy_loader_artifact_inference_handles_all_loaders(tmp_path: Path, filename: str, loader: str):
    result = run_harness(tmp_path, "-Action", "ArtifactLoader", "-Path", filename)
    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout.strip() == loader


def test_migration_call_supports_the_loader_contract():
    text = INSTALLER.read_text(encoding="utf-8-sig")
    assert "-ModLoader $ModLoader" in text
    assert "-MinecraftVersion $script:MC_VERSION" in text
    assert "BuiltInPackName" not in text


def test_unattended_mode_requires_an_explicit_loader():
    result = subprocess.run(
        [
            "powershell",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(INSTALLER),
            "-Mode",
            "Install",
            "-Unattended",
            "-MinecraftVersion",
            MC_VERSION,
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    assert result.returncode == 2
    assert "-ModLoader Fabric|Forge|NeoForge is required" in result.stderr


def test_unattended_mode_requires_an_explicit_minecraft_version():
    result = subprocess.run(
        [
            "powershell",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(INSTALLER),
            "-Mode",
            "Install",
            "-Unattended",
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    assert result.returncode == 2
    assert f"-MinecraftVersion {MC_VERSION} is required" in result.stderr


def test_only_versions_with_staged_mods_are_available(tmp_path: Path):
    _, env = multi_version_release(tmp_path, "1.21.1")
    result = run_harness(tmp_path, "-Action", "Version", env=env)
    assert result.returncode == 0, result.stdout + result.stderr
    assert "Asked:" not in result.stdout
    assert "Selected: 1.21.1" in result.stdout
    assert "Available: 1.21.1" in result.stdout
    assert "1.20.1" not in result.stdout


def test_interactive_version_choice_is_shown_for_multiple_packaged_versions(tmp_path: Path):
    _, env = multi_version_release(tmp_path, "1.21.1", "1.20.1")
    result = run_harness(tmp_path, "-Action", "Version", env=env)
    assert result.returncode == 0, result.stdout + result.stderr
    assert "Asked:" in result.stdout
    assert "Which Minecraft version" in result.stdout
    assert "Selected: 1.21.1" in result.stdout
    assert "Available: 1.21.1,1.20.1" in result.stdout


def test_single_target_bundle_with_full_catalog_uses_jar_version(tmp_path: Path):
    _, env = multi_version_release(tmp_path)
    directory = tmp_path / "mods" / "fabric"
    directory.mkdir(parents=True)
    (directory / "rtx-remix-renderer-fabric-mc1.20.1-build.jar").touch()
    result = run_harness(tmp_path, "-Action", "Version", env=env)
    assert result.returncode == 0, result.stdout + result.stderr
    assert "Asked:" not in result.stdout
    assert "Selected: 1.20.1" in result.stdout
    assert "Available: 1.20.1" in result.stdout

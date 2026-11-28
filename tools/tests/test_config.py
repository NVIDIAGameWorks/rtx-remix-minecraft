# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import json
import os
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest

from rtx_tools import config, launcher, loader_registry, sdk
from rtx_tools.benchmark import runner, util

PROJECT_STATE = loader_registry.load_registry()


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


@pytest.fixture
def config_tree(tmp_path, monkeypatch):
    tools = tmp_path / "tools"
    shared = tools / "config.json"
    benchmark = tools / "benchmark"
    write_json(shared, {"value": "shared", "render": {"preset": "low", "width": 100}})
    write_json(benchmark / "config.json", {"value": "benchmark", "runs": 3})
    monkeypatch.setattr(util, "SHARED_CONFIG_PATH", shared)
    monkeypatch.setattr(util, "BENCHMARK_DIR", benchmark)
    runner.set_runtime_resource_pack_override(None)
    runner.set_runtime_preset_override(None)
    yield tools
    runner.set_runtime_resource_pack_override(None)
    runner.set_runtime_preset_override(None)


def test_shared_and_benchmark_defaults_merge(config_tree):
    merged = config.load_config()
    assert merged["value"] == "benchmark"
    assert merged["render"] == {"preset": "low", "width": 100}


def test_local_world_and_cli_precedence(config_tree, tmp_path):
    write_json(config_tree / "config.local.json", {"value": "local", "render": {"width": 200}})
    world = tmp_path / "world"
    write_json(world / "config.json", {"value": "world", "render": {"preset": "medium"}})
    merged = runner.load_config()
    assert merged["value"] == "local"
    assert merged["render"] == {"preset": "low", "width": 200}
    runner.set_runtime_preset_override("ultra")
    runner.set_runtime_resource_pack_override("none")
    write_json(
        world / "config.json", {"value": "world", "render": {"preset": "medium"}, "resourcePack": {"mode": "pinned"}}
    )
    effective = runner.effective_config_for_world(runner.load_config(), world)
    assert effective["value"] == "world"
    assert effective["render"]["preset"] == "ultra"
    assert effective["resourcePack"]["mode"] == "none"


def test_local_updates_preserve_tracked_defaults(config_tree):
    original = util.SHARED_CONFIG_PATH.read_bytes()
    config.update_local_config({"sdk": {"autoRestage": False}})
    config.update_local_config({"world": {"name": "DemoWorld", "version": "v1.2.3"}})
    assert util.SHARED_CONFIG_PATH.read_bytes() == original
    assert json.loads((config_tree / "config.local.json").read_text()) == {
        "sdk": {"autoRestage": False},
        "world": {"name": "DemoWorld", "version": "v1.2.3"},
    }


def test_unconfigured_remote_names_missing_setting(config_tree):
    with pytest.raises(RuntimeError, match=r"Missing setting 'world.remote'.*tools/config.local.json"):
        config.require_setting(config.load_config(), "world.remote")


def test_mod_loader_defaults_to_fabric_and_normalizes_config(monkeypatch):
    monkeypatch.delenv("RTXREMIX_MOD_LOADER", raising=False)
    assert config.mod_loader({}) == "fabric"
    for loader_id in PROJECT_STATE.ids:
        assert config.mod_loader({"modLoader": f" {loader_id.upper()} "}) == loader_id


def test_mod_loader_environment_override_wins_and_is_validated(monkeypatch):
    monkeypatch.setenv("RTXREMIX_MOD_LOADER", "FORGE")
    assert config.mod_loader({"modLoader": "fabric"}) == "forge"
    monkeypatch.setenv("RTXREMIX_MOD_LOADER", "quilt")
    with pytest.raises(RuntimeError, match="modLoader must name a loader from project-state.json"):
        config.mod_loader({})


def test_explicit_runtime_loader_wins_without_changing_environment_or_config(monkeypatch):
    monkeypatch.setenv("RTXREMIX_MOD_LOADER", "fabric")
    merged = {"modLoader": "fabric"}
    config.set_runtime_mod_loader("forge")
    try:
        assert config.mod_loader(merged) == "forge"
        assert merged == {"modLoader": "fabric"}
        assert os.environ["RTXREMIX_MOD_LOADER"] == "fabric"
    finally:
        config.set_runtime_mod_loader(None)


@pytest.mark.parametrize("fixture", ["world", "resourcePack"])
def test_fixture_pulls_use_mod_remotes_without_project_import(config_tree, tmp_path, monkeypatch, fixture):
    write_json(
        config_tree / "config.local.json",
        {
            fixture: {"name": "ExampleFixture", "package": "example_package", "remote": "example-remote"},
        },
    )
    monkeypatch.setattr(util, "ASSETS_DIR", tmp_path / "assets")
    bootstrap = tmp_path / "packman.cmd"
    monkeypatch.setattr(runner, "packman_cmd_path", lambda: bootstrap)
    commands = []

    def invoke(args, **kwargs):
        commands.append(list(args))
        project = ET.parse(args[2]).getroot()
        assert project.find("import") is None
        dependency = project.find("dependency")
        assert dependency is not None
        destination = Path(dependency.attrib["copyPath"])
        destination.mkdir()
        (destination / ".example_package@v1.0.0").touch()
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(util, "run", invoke)
    fetch = runner.fetch_world if fixture == "world" else runner.fetch_resource_pack
    destination = fetch("ExampleFixture", version="v1.0.0")
    assert (destination / ".example_package@v1.0.0").is_file()
    assert len(commands) == 1
    assert commands[0][-2:] == ["-r", "example-remote"]


def test_sdk_pull_uses_mod_bootstrap(config_tree, tmp_path, monkeypatch):
    monkeypatch.setattr(util, "PROJECT_DIR", tmp_path)
    bootstrap = tmp_path / "packman.cmd"
    monkeypatch.setattr(sdk, "packman_cmd", lambda: bootstrap)
    dependency = ET.fromstring('<dependency name="runtime"><package name="example" version="1" /></dependency>')

    def invoke(args, **kwargs):
        assert args[0] == str(bootstrap)
        assert "-r" not in args
        assert ET.parse(args[2]).getroot().find("import") is None
        assert "env" not in kwargs
        return subprocess.CompletedProcess(args, 0)

    monkeypatch.setattr(util, "run", invoke)
    sdk.pull_packman_dependency(dependency, tmp_path)


@pytest.mark.parametrize(
    ("spec", "mode"), [("none", "none"), ("local:C:/art", "local"), ("Art@1.0.0", "pinned"), ("pinned", "pinned")]
)
def test_resource_pack_override_replaces_a_null_local_pack(config_tree, spec, mode):
    write_json(config_tree / "config.local.json", {"resourcePack": None})
    runner.set_runtime_resource_pack_override(spec)
    assert runner.load_config()["resourcePack"]["mode"] == mode


def test_launcher_does_not_promote_default_pack_to_cli_override():
    config = {"resourcePack": {"mode": "none"}}
    command = launcher.build_command(config, mode="play", world="DemoWorld")
    assert "--resource-pack" not in command
    command = launcher.build_command(config, mode="play", world="DemoWorld", resource_pack="none")
    assert command[-2:] == ["--resource-pack", "none"]


@pytest.mark.parametrize("name, package", [("Art", "example_art"), ("Other", "rtx_remix_minecraft_resourcepack_Other")])
def test_package_override_only_applies_to_matching_pin(config_tree, name, package):
    write_json(config_tree / "config.local.json", {"resourcePack": {"name": "Art", "package": "example_art"}})
    assert runner.resource_pack_package(name, runner.load_config()) == package
    runner.set_runtime_resource_pack_override("Third@1.0.0")
    assert runner.resource_pack_package("Third", runner.load_config()) == "rtx_remix_minecraft_resourcepack_Third"


@pytest.mark.parametrize("camera_key", ["camera", "defaultCamera", "cameraPath"])
def test_world_camera_precedes_global_pin_and_cli_precedes_world(config_tree, tmp_path, monkeypatch, camera_key):
    assets = tmp_path / "assets"
    world_dir = assets / "worlds" / "DemoWorld_v1.0.0"
    save = world_dir / "world"
    save.mkdir(parents=True)
    (save / "level.dat").write_bytes(b"")
    write_json(world_dir / "config.json", {camera_key: "fixture"})
    monkeypatch.setattr(util, "ASSETS_DIR", assets)
    config = {"world": {"name": "DemoWorld", "version": "v1.0.0", "camera": "global"}}
    for override, expected in [(None, "fixture"), ("cli", "cli")]:
        selected = runner.resolve_benchmark_selection(config, camera_override=override, require_camera=False)
        probed, _ = runner.resolve_benchmark_selection_probe(config, camera_override=override, require_camera=False)
        assert selected.camera == expected
        assert probed.camera == expected

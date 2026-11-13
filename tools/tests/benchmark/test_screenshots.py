# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

import pytest
from conftest import FakeLauncher, FakeProcess
from PIL import Image

from rtx_tools import client, loader_registry
from rtx_tools.benchmark import runner, screenshots, util

PROJECT_STATE = loader_registry.load_registry()


def shot(name, **overrides):
    base = {
        "name": name,
        "x": 10.0,
        "y": 70.0,
        "z": -3.5,
        "yaw": 90.0,
        "pitch": -10.0,
        "timeOfDay": 13000,
        "weather": "clear",
        "rtx": True,
        "proceduralClouds": False,
    }
    base.update(overrides)
    return base


class ShotSetTests(unittest.TestCase):
    def test_missing_file_is_an_empty_set_and_other_versions_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "docs.shots.json"
            self.assertEqual(screenshots.load_shot_set(path), [])
            path.write_text(json.dumps({"version": 2, "shots": []}), encoding="utf-8")
            with self.assertRaises(RuntimeError):
                screenshots.load_shot_set(path)
            path.write_text(json.dumps({"version": 1, "shots": [shot("a"), shot("b")]}), encoding="utf-8")
            self.assertEqual([s["name"] for s in screenshots.load_shot_set(path)], ["a", "b"])

    def test_a_shot_name_is_a_single_file_name_before_any_file_is_touched(self):
        for bad in ("../outside", "a/b", "a\\b", "C:\\x", "CON", "con.png", "lpt1", ".", "..", "", " x", 7, None):
            self.assertFalse(screenshots.is_shot_name(bad), repr(bad))
            with tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / "docs.shots.json"
                path.write_text(json.dumps({"version": 1, "shots": [shot(bad)]}), encoding="utf-8")
                with self.assertRaises(RuntimeError, msg=repr(bad)):
                    screenshots.load_shot_set(path)
        for good in ("rapids", "village-night", "copper_beacon.v2", "A1"):
            self.assertTrue(screenshots.is_shot_name(good), good)

    def test_select_keeps_set_order_and_names_every_unknown_shot(self):
        shots = [shot("rapids"), shot("village-night"), shot("thunderstorm")]
        self.assertEqual(
            [s["name"] for s in screenshots.select_shots(shots, ["thunderstorm", "rapids"])],
            ["rapids", "thunderstorm"],
        )
        self.assertEqual(screenshots.select_shots(shots, []), shots)
        with self.assertRaises(RuntimeError) as ctx:
            screenshots.select_shots(shots, ["rapids", "lighthouse", "beacon"])
        self.assertIn("lighthouse, beacon", str(ctx.exception))

    def test_shot_sets_are_not_listed_as_cameras(self):
        with tempfile.TemporaryDirectory() as tmp:
            cameras = Path(tmp) / runner.CAMERAS_DIR_NAME
            cameras.mkdir()
            (cameras / "benchmark.json").write_text("{}", encoding="utf-8")
            (cameras / "docs.shots.json").write_text("{}", encoding="utf-8")
            self.assertEqual(runner.available_cameras_in_dir(Path(tmp)), ["benchmark"])


class ExportTests(unittest.TestCase):
    def test_export_fits_the_longest_edge_and_never_upscales(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "wide.png"
            Image.new("RGBA", (1920, 1080), (200, 30, 30, 255)).save(source)
            destination = Path(tmp) / "out" / "wide.jpg"
            screenshots.export_jpeg(source, destination, max_edge=1600)
            with Image.open(destination) as image:
                self.assertEqual(image.format, "JPEG")
                self.assertEqual(image.size, (1600, 900))

            small = Path(tmp) / "small.png"
            Image.new("RGB", (800, 600)).save(small)
            small_out = Path(tmp) / "small.jpg"
            screenshots.export_jpeg(small, small_out, max_edge=1600)
            with Image.open(small_out) as image:
                self.assertEqual(image.size, (800, 600))


class ReadmeTests(unittest.TestCase):
    def test_enabled_pack_names_start_with_builtin_baseline(self):
        cases = (
            ({}, ["fabric"]),
            ({"mode": "pinned", "name": "texture", "version": "0.55.0"}, ["fabric", "texture 0.55.0"]),
            ({"mode": "local"}, ["fabric", "a local art pack"]),
        )
        for resource_pack, expected in cases:
            with self.subTest(resource_pack=resource_pack):
                self.assertEqual(screenshots.enabled_pack_names({"resourcePack": resource_pack}), expected)

    def test_enabled_pack_names_identify_loader_builtin_resources(self):
        for loader in PROJECT_STATE.loaders:
            with self.subTest(loader=loader.id):
                self.assertEqual(
                    screenshots.enabled_pack_names({"modLoader": loader.id, "resourcePack": {"mode": "none"}}),
                    [loader.builtin_resource_pack],
                )

    def test_provenance_lists_every_shot_with_its_scene(self):
        text = screenshots.provenance_text(
            world_version="0.11.0",
            pack_names=["fabric", "texture 0.55.0"],
            width=1920,
            height=1080,
            shots=[
                shot("village-night", timeOfDay=18000),
                shot("thunderstorm", weather="thunder"),
                shot("clouds-dusk", proceduralClouds=True),
                shot("rtx-off", rtx=False),
            ],
        )
        self.assertIn("version 0.11.0", text)
        self.assertIn("`fabric` and `texture 0.55.0`", text)
        self.assertIn("1920x1080", text)
        self.assertIn("| `village-night.jpg` | 18000 | clear | classic | on |", text)
        self.assertIn("| `thunderstorm.jpg` | 13000 | thunderstorm | classic | on |", text)
        self.assertIn("| `clouds-dusk.jpg` | 13000 | clear | procedural | on |", text)
        self.assertIn("| `rtx-off.jpg` | 13000 | clear | classic | off |", text)
        self.assertTrue(text.startswith(screenshots.PROVENANCE_BEGIN))
        self.assertTrue(text.endswith(screenshots.PROVENANCE_END))

    def test_update_replaces_only_the_generated_section(self):
        hand_written = "# Documentation screenshots\n\nIntro kept by hand.\n\n"
        trailer = "\n\n`settings-menu.jpg` is a picture of a menu and is retaken by hand.\n"
        old = hand_written + screenshots.PROVENANCE_BEGIN + "\nold\n" + screenshots.PROVENANCE_END + trailer
        new_section = screenshots.PROVENANCE_BEGIN + "\nnew\n" + screenshots.PROVENANCE_END

        updated = screenshots.update_readme(old, new_section)

        self.assertEqual(updated, hand_written + new_section + trailer)
        self.assertNotIn("old", updated)

    def test_update_appends_when_no_section_exists(self):
        section = screenshots.PROVENANCE_BEGIN + "\nbody\n" + screenshots.PROVENANCE_END
        self.assertEqual(screenshots.update_readme("# Title\n", section), "# Title\n\n" + section + "\n")
        self.assertEqual(screenshots.update_readme("", section), section + "\n")


class TriggerTests(unittest.TestCase):
    def test_trigger_names_the_shot_directory_and_mode_specific_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            original_run_dir = screenshots.util.RUN_DIR
            screenshots.util.RUN_DIR = Path(tmp)
            try:
                selection = runner.BenchmarkSelection(
                    world="demo",
                    camera="benchmark",
                    world_dir=Path(tmp) / "demo_v1",
                    world_path=Path(tmp) / "demo_v1" / "world",
                    cameras_dir=Path(tmp) / "demo_v1" / "cameras",
                    camera_path=Path(tmp) / "demo_v1" / "cameras" / "benchmark.json",
                    version="v1",
                )
                config = {"screenshots": {"settleFrames": 90, "quietFrames": 10}}
                screenshots.write_stills_trigger(config, selection, ["rapids"])
                trigger = json.loads((Path(tmp) / "benchmark" / "auto_benchmark.json").read_text(encoding="utf-8"))
            finally:
                screenshots.util.RUN_DIR = original_run_dir

        self.assertEqual(trigger["mode"], "stills")
        self.assertEqual(trigger["shotSet"], "docs")
        self.assertEqual(trigger["shots"], ["rapids"])
        self.assertEqual(trigger["settleFrames"], 90)
        self.assertEqual(trigger["quietFrames"], 10)
        self.assertNotIn("maxSettleFrames", trigger)
        self.assertTrue(trigger["exitOnComplete"])
        self.assertTrue(trigger["disableWorldSaving"])
        self.assertEqual(Path(trigger["shotSetDirectory"]), screenshots.SHOTS_DIR)


def stills_selection(root: Path) -> runner.BenchmarkSelection:
    return runner.BenchmarkSelection(
        world="demo",
        camera="benchmark",
        world_dir=root / "demo_v1",
        world_path=root / "demo_v1" / "world",
        cameras_dir=root / "demo_v1" / "cameras",
        camera_path=root / "demo_v1" / "cameras" / "benchmark.json",
        version="v1",
    )


def stub_capture_staging(monkeypatch: pytest.MonkeyPatch) -> Mock:
    """Stub what capture does around the launch; returns the SDK fetch."""
    for name in ("ensure_no_live_client", "install_fixtures", "kill_leftovers", "park_stale_runtime_state"):
        monkeypatch.setattr(runner, name, Mock())
    fetch_sdk = Mock(return_value=util.PROJECT_DIR / "d3d9.dll")
    monkeypatch.setattr(runner, "fetch_sdk", fetch_sdk)
    monkeypatch.setattr(runner, "host_arch", Mock(return_value="win64"))
    monkeypatch.setattr(runner, "gradle_env", Mock(return_value={"PLAY": "1"}))
    monkeypatch.setattr(screenshots, "write_stills_trigger", Mock())
    monkeypatch.setattr(screenshots.time, "sleep", Mock())
    return fetch_sdk


class ExitedGame(FakeProcess):
    def __init__(self) -> None:
        self.waits: list[float | None] = []

    def wait(self, timeout: float | None = None) -> int:
        self.waits.append(timeout)
        return 0


def test_stills_log_follows_the_launchers_game_directory(fake_launcher: FakeLauncher):
    assert screenshots.stills_log() == fake_launcher.game_dir / "benchmark" / "stills.log"


def test_capture_checks_the_launcher_before_staging(
    fake_launcher: FakeLauncher, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    fetch_sdk = stub_capture_staging(monkeypatch)
    fake_launcher.block = client.LaunchBlocked("no account")
    with pytest.raises(client.LaunchBlocked):
        screenshots.capture({}, stills_selection(tmp_path), ["rapids"], sdk_variant="release", timeout_seconds=60)
    fetch_sdk.assert_not_called()
    assert fake_launcher.prepared == []


def test_refresh_checks_the_launcher_before_staging_the_world(
    fake_launcher: FakeLauncher, monkeypatch: pytest.MonkeyPatch
):
    stage_world = Mock()
    monkeypatch.setattr(runner, "load_config", Mock(return_value={}))
    monkeypatch.setattr(screenshots, "load_shot_set", Mock(return_value=[shot("rapids")]))
    monkeypatch.setattr(runner, "resolve_world_name", Mock(return_value="demo"))
    monkeypatch.setattr(runner, "ensure_world_staged", stage_world)
    fake_launcher.block = client.LaunchBlocked("no account")
    with pytest.raises(client.LaunchBlocked):
        screenshots.refresh([])
    # A signed-out user is stopped before the pinned world downloads.
    stage_world.assert_not_called()
    assert fake_launcher.sign_in_opened


def test_capture_launches_the_stills_through_the_launcher(
    fake_launcher: FakeLauncher, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    stub_capture_staging(monkeypatch)
    png = util.RUN_DIR / "benchmark" / "screenshots" / "rapids.png"
    status = util.RUN_DIR / "benchmark" / "results" / runner.BENCHMARK_STATUS_FILE
    start = fake_launcher.start

    def finish_the_stills(request: client.LaunchRequest) -> FakeProcess:
        png.write_bytes(b"png")
        util.write_json(status, {"phase": screenshots.STILLS_COMPLETE_PHASE})
        return start(request)

    monkeypatch.setattr(fake_launcher, "start", Mock(side_effect=finish_the_stills))
    config = {"render": {"preset": "high"}}
    captured = screenshots.capture(
        config, stills_selection(tmp_path), ["rapids"], sdk_variant="release", timeout_seconds=60
    )

    assert captured == {"rapids": png}
    assert fake_launcher.prepared == [("win64", True)]
    [request] = fake_launcher.requests
    assert request.arch == "win64"
    assert request.quick_play_world == runner.BENCHMARK_SAVE_NAME
    assert request.preset == "high"
    assert request.jfr_path is None and request.tracy_java_max_depth is None
    assert request.quiet and request.env == {"PLAY": "1"}
    assert request.stdout is not None and request.stdout is request.stderr
    assert Path(request.stdout.name) == screenshots.stills_log()


def test_capture_waits_on_an_exited_game_so_its_launcher_is_ended(
    fake_launcher: FakeLauncher, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    stub_capture_staging(monkeypatch)
    game = ExitedGame()
    monkeypatch.setattr(fake_launcher, "start", Mock(return_value=game))
    with pytest.raises(RuntimeError, match="ProcessExited"):
        screenshots.capture({}, stills_selection(tmp_path), ["rapids"], sdk_variant="release", timeout_seconds=60)
    assert game.waits == [30]


def test_capture_ends_the_game_on_ctrl_c(fake_launcher: FakeLauncher, tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    stub_capture_staging(monkeypatch)
    monkeypatch.setattr(screenshots.time, "sleep", Mock(side_effect=KeyboardInterrupt))
    terminate = Mock()
    monkeypatch.setattr(runner, "terminate_process", terminate)
    game = FakeProcess()
    monkeypatch.setattr(fake_launcher, "start", Mock(return_value=game))
    with pytest.raises(KeyboardInterrupt):
        screenshots.capture({}, stills_selection(tmp_path), ["rapids"], sdk_variant="release", timeout_seconds=60)
    terminate.assert_called_once_with(game, "game client")


if __name__ == "__main__":
    unittest.main()

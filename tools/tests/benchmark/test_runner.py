# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import datetime as dt
import hashlib
import inspect
import io
import json
import locale
import os
import subprocess
import sys
import tempfile
import unittest
from contextlib import ExitStack, chdir, redirect_stderr, redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from typing import Any, cast
from unittest.mock import Mock, call, patch

import pytest
from conftest import FakeLauncher, FakeProcess

from rtx_tools import cli, client
from rtx_tools.benchmark import runner, util

pytestmark = pytest.mark.usefixtures("isolated_tools_config")

VALID_CAMERA_PATH_JSON = (
    '{"version":1,"interpolation":"catmull-rom","waypoints":['
    '{"timeMs":0,"x":0,"y":64,"z":0,"yaw":0,"pitch":0},'
    '{"timeMs":1000,"x":1,"y":64,"z":0,"yaw":10,"pitch":0}]}'
)


def options_value(options: str, key: str) -> str | None:
    """The value written for `key`, or None when the key is absent.

    An empty value is meaningful in options.txt (Minecraft reads an empty
    fullscreenResolution as "Current"), so substring assertions cannot tell
    "absent" from "present and empty"."""
    for line in options.splitlines():
        name, separator, value = line.partition(":")
        if separator and name == key:
            return value
    return None


class RunnerTests(unittest.TestCase):
    @pytest.fixture(autouse=True)
    def _launcher(self, fake_launcher: FakeLauncher) -> None:
        self.launcher = fake_launcher

    def test_benchmark_rtx_config_env_stages_ordered_override_and_cleans_up(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_dir = Path(tmp) / "run"
            baseline = run_dir / "rtx.conf"
            baseline.parent.mkdir(parents=True)
            baseline.write_text("rtx.sceneScale = 0.01\n", encoding="utf-8")

            with (
                patch.object(runner.util, "RUN_DIR", run_dir),
                patch.object(
                    runner,
                    "gradle_env",
                    return_value={
                        runner.RTX_CONFIG_PATHS_ENV: "inherited.conf",
                        runner.ASYNC_COMPILATION_ENV: "true",
                        "KEEP": "yes",
                    },
                ),
            ):
                with runner.benchmark_rtx_config_env() as env:
                    temporary = Path(env[runner.RTX_CONFIG_PATHS_ENV].split(",")[1])
                    self.assertTrue(temporary.is_file())
                    self.assertTrue(temporary.name.startswith(runner.BENCHMARK_RTX_CONFIG_PREFIX))
                    self.assertEqual(
                        temporary.read_text(encoding="utf-8"),
                        runner.BENCHMARK_RTX_CONFIG,
                    )
                    self.assertEqual(env["KEEP"], "yes")
                    self.assertEqual(env[runner.ASYNC_COMPILATION_ENV], "false")
                    self.assertEqual(
                        env[runner.RTX_CONFIG_PATHS_ENV].split(","),
                        [str(baseline.resolve()), str(temporary.resolve())],
                    )

                self.assertFalse(temporary.exists())

    def test_benchmark_rtx_config_env_uses_unique_live_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_dir = Path(tmp) / "run"
            run_dir.mkdir()
            (run_dir / "rtx.conf").write_text("rtx.sceneScale = 0.01\n", encoding="utf-8")
            with patch.object(runner.util, "RUN_DIR", run_dir):
                with runner.benchmark_rtx_config_env() as first_env:
                    first = Path(first_env[runner.RTX_CONFIG_PATHS_ENV].split(",")[1])
                    with runner.benchmark_rtx_config_env() as second_env:
                        second = Path(second_env[runner.RTX_CONFIG_PATHS_ENV].split(",")[1])
                        self.assertNotEqual(first, second)
                        self.assertTrue(first.exists())
                        self.assertTrue(second.exists())
                    self.assertTrue(first.exists())
                    self.assertFalse(second.exists())
                self.assertFalse(first.exists())

    def test_benchmark_rtx_config_env_rejects_conflicting_user_setting_and_cleans_up(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_dir = Path(tmp) / "run"
            run_dir.mkdir()
            (run_dir / "user.conf").write_text("rtx.shader.enableAsyncCompilation = true\n", encoding="utf-8")
            with patch.object(runner.util, "RUN_DIR", run_dir):
                with self.assertRaisesRegex(RuntimeError, "masked by"):
                    with runner.benchmark_rtx_config_env():
                        self.fail("conflicting User Settings must prevent launch")
            self.assertEqual([], list((run_dir / "benchmark").glob("benchmark-rtx-*.conf")))

    def test_run_client_once_launches_with_live_temporary_config(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run_dir = root / "run"
            run_dir.mkdir()
            (run_dir / "rtx.conf").write_text("rtx.sceneScale = 0.01\n", encoding="utf-8")
            seen: dict[str, Path] = {}
            start = self.launcher.start

            def checked_start(request: client.LaunchRequest) -> FakeProcess:
                temporary = Path(request.env[runner.RTX_CONFIG_PATHS_ENV].split(",")[1])
                self.assertTrue(temporary.is_file())
                self.assertEqual("false", request.env[runner.ASYNC_COMPILATION_ENV])
                seen["temporary"] = temporary
                return start(request)

            config = {"pollTimeoutSeconds": 0, "render": {"preset": "high"}}
            with (
                patch.object(runner.util, "RUN_DIR", run_dir),
                patch.object(runner.util, "PROJECT_DIR", root),
                patch.object(runner, "install_fixtures"),
                patch.object(runner, "write_auto_trigger"),
                patch.object(runner, "windows_crash_dump_dir", return_value=root / "crash-source"),
                patch.object(runner, "crash_dump_snapshot", return_value=set()),
                patch.object(runner, "kill_leftovers"),
                patch.object(runner, "start_tracy_capture", return_value=None),
                patch.object(runner, "collect_windows_crash_dumps", return_value=[]),
                patch.object(self.launcher, "start", side_effect=checked_start),
            ):
                selection = runner.BenchmarkSelection(
                    world="DemoWorld",
                    camera="simple",
                    world_dir=root / "fixture",
                    world_path=root / "fixture" / "world",
                    cameras_dir=root / "fixture" / "cameras",
                    camera_path=root / "fixture" / "cameras" / "simple.json",
                )
                result = runner.run_client_once(config, selection, None, root / "runClient.log", True, "win64")

            self.assertEqual("Timeout", result["failureReason"])
            self.assertFalse(seen["temporary"].exists())
            [request] = self.launcher.requests
            self.assertEqual(request.arch, "win64")
            self.assertEqual(request.quick_play_world, runner.BENCHMARK_SAVE_NAME)
            self.assertEqual(request.jfr_path, run_dir / "benchmark" / "results" / "profile.jfr")
            self.assertEqual(request.tracy_java_max_depth, runner.tracy_java_depth(config))
            self.assertEqual(request.preset, "high")
            self.assertTrue(request.quiet)
            assert request.stdout is not None and request.stderr is not None
            self.assertTrue(request.stdout.name.endswith("runClient.log.stdout"))
            self.assertTrue(request.stderr.name.endswith("runClient.log.stderr"))

    def test_host_arch_prefers_java_runtime_over_windows_process_arch(self):
        with (
            patch.dict(os.environ, {"PROCESSOR_ARCHITECTURE": "AMD64"}, clear=True),
            patch.object(runner, "java_runtime_arch", return_value="aarch64"),
        ):
            self.assertEqual(runner.host_arch(), "arm64")

    def test_host_arch_falls_back_to_windows_process_arch(self):
        with (
            patch.dict(os.environ, {"PROCESSOR_ARCHITECTURE": "ARM64"}, clear=True),
            patch.object(runner, "java_runtime_arch", return_value=None),
        ):
            self.assertEqual(runner.host_arch(), "arm64")

    def test_host_arch_allows_explicit_override(self):
        with (
            patch.dict(os.environ, {"BENCHMARK_ARCH": "win64", "PROCESSOR_ARCHITECTURE": "ARM64"}, clear=True),
            patch.object(runner, "java_runtime_arch", return_value="aarch64") as java_runtime_arch,
        ):
            self.assertEqual(runner.host_arch(), "win64")

        java_runtime_arch.assert_not_called()

    def test_host_arch_rejects_invalid_explicit_override(self):
        with patch.dict(os.environ, {"BENCHMARK_ARCH": "sparc"}, clear=True):
            with self.assertRaisesRegex(RuntimeError, "invalid BENCHMARK_ARCH"):
                runner.host_arch()

    def test_java_runtime_arch_reads_os_arch_from_stderr(self):
        result = subprocess.CompletedProcess(
            args=["java"],
            returncode=0,
            stdout="",
            stderr="    os.arch = aarch64\n",
        )
        with patch.object(runner.subprocess, "run", return_value=result) as run:
            self.assertEqual(runner.java_runtime_arch(), "aarch64")
        self.assertEqual(locale.getencoding(), run.call_args.kwargs["encoding"])
        self.assertEqual("replace", run.call_args.kwargs["errors"])

    def test_verify_jvm_matches_arch_passes_on_match(self):
        for java_arch, arch in (("aarch64", "arm64"), (None, "arm64"), ("riscv64", "arm64")):
            with self.subTest(java_arch=java_arch, arch=arch):
                with patch.object(runner, "java_runtime_arch", return_value=java_arch):
                    runner.verify_jvm_matches_arch(arch)

    def test_verify_jvm_matches_arch_rejects_mismatch_with_guidance(self):
        # An x64 JDK on an ARM64 runner makes
        # host detection (or an explicit --arch arm64) diverge from the JVM
        # that must load the native bridge. This must fail loudly and point at
        # the runner provisioning doc, not crash later inside the client.
        with patch.object(runner, "java_runtime_arch", return_value="amd64"):
            with self.assertRaises(RuntimeError) as ctx:
                runner.verify_jvm_matches_arch("arm64")
        message = str(ctx.exception)
        self.assertIn("arm64", message)
        self.assertIn("amd64", message)
        self.assertIn("BENCHMARKING.md", message)

    def test_tracy_java_depth_allows_zero(self):
        self.assertEqual(runner.tracy_java_depth({"profiling": {"tracyJavaMaxDepth": 0}}), 0)
        self.assertEqual(runner.tracy_java_depth({"profiling": {"tracyJavaMaxDepth": 3}}), 3)
        self.assertEqual(runner.tracy_java_depth({}), 6)

    def test_host_arch_accepts_explicit_arm64_override(self):
        self.assertEqual(runner.host_arch("arm64"), "arm64")
        self.assertEqual(runner.host_arch("x64"), "win64")

    def test_ci_config_extends_tracy_drain_by_default(self):
        original = {"profiling": {"tracyJavaMaxDepth": 5, "postResultExitDelaySeconds": 10}}
        config = runner.config_for_run(original, ci=True)

        self.assertEqual(config["profiling"]["tracyJavaMaxDepth"], 5)
        self.assertEqual(config["profiling"]["postResultExitDelaySeconds"], 60)
        self.assertEqual(config["profiling"]["postResultProcessWaitSeconds"], 105)
        self.assertTrue(config["profiling"]["tracyDrainWaitForIdle"])
        self.assertEqual(original["profiling"]["tracyJavaMaxDepth"], 5)
        self.assertEqual(original["profiling"]["postResultExitDelaySeconds"], 10)
        self.assertIn("postResultExitDelayReason", config["profiling"])

    def test_ci_config_allows_depth_override(self):
        original = {"profiling": {"tracyJavaMaxDepth": 5}}
        with patch.dict(os.environ, {"BENCHMARK_CI_TRACY_JAVA_MAX_DEPTH": "0"}):
            config = runner.config_for_run(original, ci=True)

        self.assertEqual(config["profiling"]["tracyJavaMaxDepth"], 0)
        self.assertEqual(original["profiling"]["tracyJavaMaxDepth"], 5)

    def test_ci_config_allows_drain_override(self):
        original = {"profiling": {"postResultExitDelaySeconds": 10}}
        with patch.dict(os.environ, {"BENCHMARK_CI_TRACY_DRAIN_SECONDS": "75"}):
            config = runner.config_for_run(original, ci=True)

        self.assertEqual(config["profiling"]["postResultExitDelaySeconds"], 75)
        self.assertEqual(config["profiling"]["postResultProcessWaitSeconds"], 120)
        self.assertEqual(original["profiling"]["postResultExitDelaySeconds"], 10)

    def test_config_for_run_applies_render_env_overrides(self):
        original = {"render": {"fullscreen": True}, "warmupLap": False}
        with patch.dict(
            os.environ,
            {
                "BENCHMARK_RENDER_FULLSCREEN": "false",
                "BENCHMARK_RENDER_WIDTH": "1920",
                "BENCHMARK_RENDER_HEIGHT": "1080",
                "BENCHMARK_TRACY_DRAIN_STABLE_SECONDS": "15",
                "BENCHMARK_POLL_TIMEOUT_SECONDS": "420",
                "BENCHMARK_INTER_RUN_COOLDOWN_SECONDS": "90",
                "BENCHMARK_WARMUP_LAP": "true",
            },
        ):
            config = runner.config_for_run(original, ci=True)

        self.assertFalse(config["render"]["fullscreen"])
        self.assertEqual(config["render"]["overrideWidth"], 1920)
        self.assertEqual(config["render"]["overrideHeight"], 1080)
        self.assertEqual(config["profiling"]["tracyDrainStableSeconds"], 15)
        self.assertEqual(config["pollTimeoutSeconds"], 420)
        self.assertEqual(config["interRunCooldownSeconds"], 90)
        self.assertTrue(config["warmupLap"])
        self.assertFalse(original["warmupLap"])
        self.assertNotIn("overrideWidth", original["render"])

    def test_post_result_process_wait_covers_profiler_delay(self):
        config = {"profiling": {"postResultExitDelaySeconds": 300, "postResultProcessWaitSeconds": 180}}

        self.assertEqual(runner.post_result_process_wait_seconds(config), 345)

    def test_wait_before_next_run_honors_inter_run_cooldown(self):
        with patch.object(runner.time, "sleep") as sleep:
            runner.wait_before_next_run({"interRunCooldownSeconds": 12}, 1, 2)

        sleep.assert_called_once_with(12)

    def test_wait_before_next_run_skips_zero_cooldown(self):
        with patch.object(runner.time, "sleep") as sleep:
            runner.wait_before_next_run({}, 1, 2)

        sleep.assert_not_called()

    def test_tracy_capture_tx_bytes_reads_latest_progress(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "profile.tracy.out.log"
            log.write_text(
                "\x1b[33mTx: \x1b[32m714.14 MB\x1b[0m\n\x1b[33mTx: \x1b[32m718.08 MB\x1b[0m\n",
                encoding="utf-8",
            )

            self.assertEqual(runner.tracy_capture_tx_bytes(log), int(718.08 * 1024 * 1024))

    def test_write_options_disables_first_run_screens(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(runner.util, "RUN_DIR", Path(tmp)):
            runner.write_options({"render": {}})

            options = (Path(tmp) / "options.txt").read_text(encoding="utf-8")
            self.assertIn("onboardAccessibility:false", options)
            self.assertIn("tutorialStep:none", options)
            self.assertIn("skipMultiplayerWarning:true", options)
            self.assertIn("hideBundleTutorial:true", options)

    def test_write_options_converts_fov_degrees_to_minecraft_option_value(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(runner.util, "RUN_DIR", Path(tmp)):
            runner.write_options({"render": {"fov": 90}})

            options = (Path(tmp) / "options.txt").read_text(encoding="utf-8")
            self.assertIn("fov:0.5", options)

    def test_write_options_leaves_fullscreen_resolution_on_current_by_default(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(runner.util, "RUN_DIR", Path(tmp)):
            runner.write_options({"render": {"overrideWidth": 1920, "overrideHeight": 1080}})

            options = (Path(tmp) / "options.txt").read_text(encoding="utf-8")
            self.assertIn("overrideWidth:1920", options)
            self.assertIn("overrideHeight:1080", options)
            self.assertEqual(options_value(options, "fullscreenResolution"), "")

    def test_write_options_pins_fullscreen_resolution_when_the_display_is_pinned(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(runner.util, "RUN_DIR", Path(tmp)):
            runner.write_options({"render": {"overrideWidth": 1920, "overrideHeight": 1080}}, pin_display=True)

            options = (Path(tmp) / "options.txt").read_text(encoding="utf-8")
            self.assertEqual(options_value(options, "fullscreenResolution"), "1920x1080@60:24")

    def test_write_options_clears_a_stale_pinned_fullscreen_resolution(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(runner.util, "RUN_DIR", Path(tmp)):
            options_path = Path(tmp) / "options.txt"
            options_path.write_text("fullscreenResolution:2560x1440@144:24\n", encoding="utf-8")

            # The window size is set, so an empty value can only come from an
            # unpinned display; pinning would rewrite it to 1920x1080.
            runner.write_options({"render": {"overrideWidth": 1920, "overrideHeight": 1080}})

            options = options_path.read_text(encoding="utf-8")
            self.assertEqual(options_value(options, "fullscreenResolution"), "")

    def test_write_options_rejects_pinning_the_display_without_a_window_size(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(runner.util, "RUN_DIR", Path(tmp)):
            with self.assertRaisesRegex(RuntimeError, "overrideWidth"):
                runner.write_options({"render": {}}, pin_display=True)

    def test_validate_benchmark_display_accepts_requested_windowed_mode(self):
        with tempfile.TemporaryDirectory() as tmp:
            benchmark_json = Path(tmp) / "benchmark.json"
            runner.util.write_json(
                benchmark_json,
                {
                    "metadata": {
                        "resolution": "1920x1080",
                        "windowResolution": "1920x1080",
                        "fullscreen": False,
                        "monitorAvailable": False,
                    }
                },
            )

            runner.validate_benchmark_display(
                {"render": {"fullscreen": False, "overrideWidth": 1920, "overrideHeight": 1080}},
                benchmark_json,
            )

    def test_validate_benchmark_display_rejects_wrong_resolution(self):
        with tempfile.TemporaryDirectory() as tmp:
            benchmark_json = Path(tmp) / "benchmark.json"
            runner.util.write_json(
                benchmark_json,
                {
                    "metadata": {
                        "resolution": "854x480",
                        "windowResolution": "854x480",
                        "fullscreen": False,
                        "monitorAvailable": False,
                    }
                },
            )

            with self.assertRaisesRegex(RuntimeError, "requested 1920x1080, captured 854x480"):
                runner.validate_benchmark_display(
                    {"render": {"fullscreen": False, "overrideWidth": 1920, "overrideHeight": 1080}},
                    benchmark_json,
                )

    def test_write_options_uses_configured_resource_pack_order(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(runner.util, "RUN_DIR", Path(tmp)):
            runner.write_options({"render": {}, "resourcePacks": ["world-demo-pbr"]})

            options = (Path(tmp) / "options.txt").read_text(encoding="utf-8")
            self.assertIn('resourcePacks:["vanilla","fabric","file/world-demo-pbr"]', options)

    def test_fov_option_value_preserves_legacy_slider_values(self):
        self.assertEqual(runner.minecraft_fov_option_value(1.0), "1")
        self.assertEqual(runner.minecraft_fov_option_value(0.0), "0")
        self.assertEqual(runner.minecraft_fov_option_value(-1.0), "-1")

    def test_write_auto_trigger_includes_profiler_drain_delay(self):
        for warmup_config, warmup_lap in (({}, False), ({"warmupLap": True}, True)):
            with self.subTest(warmup_lap=warmup_lap):
                config = {
                    "world": {"name": "DemoWorld", "camera": "simple"},
                    "profiling": {"postResultExitDelaySeconds": 10, "exitMode": "systemExit"},
                    **warmup_config,
                }
                with tempfile.TemporaryDirectory() as tmp:
                    bench = Path(tmp) / "benchmark"
                    run_dir = Path(tmp) / "run"
                    self._write_benchmark_world(bench, "DemoWorld", "simple")

                    with (
                        patch.object(runner.util, "BENCHMARK_DIR", bench),
                        patch.object(runner.util, "ASSETS_DIR", bench),
                        patch.object(runner.util, "RUN_DIR", run_dir),
                    ):
                        selection = runner.resolve_benchmark_selection(config)
                        runner.write_auto_trigger(config, selection, exit_on_complete=True)

                        trigger = runner.util.read_json(run_dir / "benchmark" / "auto_benchmark.json")

                self.assertTrue(trigger["exitOnComplete"])
                self.assertEqual(trigger["postResultExitDelaySeconds"], 10)
                self.assertEqual(trigger["exitMode"], "systemExit")
                self.assertEqual(trigger["mode"], "benchmark")
                self.assertEqual(trigger["benchmarkWorld"], "DemoWorld")
                self.assertEqual(trigger["worldVersion"], "")
                self.assertEqual(trigger["cameraStorageKey"], "DemoWorld")
                self.assertEqual(trigger["cameraPath"], "simple")
                self.assertTrue(trigger["disableWorldSaving"])
                self.assertEqual(trigger["screenshotFrameCount"], 5)
                self.assertEqual(trigger["screenshotFrames"], [])
                self.assertEqual(trigger["screenshotInterval"], 0)
                self.assertEqual(trigger["warmupLap"], warmup_lap)

    def test_write_auto_trigger_includes_selected_world_version(self):
        config = {
            "world": {"name": "DemoWorld", "version": "v0.3.0", "camera": "simple"},
            "worldVersion": "v0.3.0",
        }
        with tempfile.TemporaryDirectory() as tmp:
            assets = Path(tmp) / "assets"
            run_dir = Path(tmp) / "run"
            self._write_benchmark_world(assets, "DemoWorld_v0.3.0", "simple")

            with (
                patch.object(runner.util, "ASSETS_DIR", assets),
                patch.object(runner.util, "RUN_DIR", run_dir),
            ):
                selection = runner.resolve_benchmark_selection(config)
                runner.write_auto_trigger(config, selection, exit_on_complete=False)

                trigger = runner.util.read_json(run_dir / "benchmark" / "auto_benchmark.json")

        self.assertEqual(trigger["benchmarkWorld"], "DemoWorld")
        self.assertEqual(trigger["worldVersion"], "v0.3.0")
        self.assertEqual(trigger["cameraStorageKey"], "DemoWorld_v0.3.0")

    def test_write_auto_trigger_uses_resolved_versioned_directory_as_camera_storage_key(self):
        config = {"world": "DemoWorld", "camera": "simple"}
        with tempfile.TemporaryDirectory() as tmp:
            assets = Path(tmp) / "assets"
            run_dir = Path(tmp) / "run"
            self._write_benchmark_world(assets, "DemoWorld_v0.8.4", "simple")

            with (
                patch.object(runner.util, "ASSETS_DIR", assets),
                patch.object(runner.util, "RUN_DIR", run_dir),
            ):
                selection = runner.resolve_benchmark_selection(config)
                runner.write_auto_trigger(config, selection, exit_on_complete=False)
                trigger = runner.util.read_json(run_dir / "benchmark" / "auto_benchmark.json")

            canonical_cameras = assets / "worlds" / "DemoWorld_v0.8.4" / "cameras"

        self.assertEqual(trigger["worldVersion"], "")
        self.assertEqual(trigger["cameraStorageKey"], "DemoWorld_v0.8.4")
        # The mod derives no fixture paths, so the canonical camera directory
        # travels in the trigger as an absolute path.
        self.assertEqual(trigger["cameraCanonicalDirectory"], str(canonical_cameras))
        self.assertTrue(Path(trigger["cameraCanonicalDirectory"]).is_absolute())

    def test_write_auto_trigger_includes_configured_screenshot_frames(self):
        config = {
            "world": {"name": "DemoWorld", "camera": "simple"},
            "screenshots": {"count": 3, "frames": [42, 7, 42], "interval": 100},
        }
        with tempfile.TemporaryDirectory() as tmp:
            bench = Path(tmp) / "benchmark"
            run_dir = Path(tmp) / "run"
            self._write_benchmark_world(bench, "DemoWorld", "simple")

            with (
                patch.object(runner.util, "BENCHMARK_DIR", bench),
                patch.object(runner.util, "ASSETS_DIR", bench),
                patch.object(runner.util, "RUN_DIR", run_dir),
            ):
                selection = runner.resolve_benchmark_selection(config)
                runner.write_auto_trigger(config, selection, exit_on_complete=True)

                trigger = runner.util.read_json(run_dir / "benchmark" / "auto_benchmark.json")

        self.assertEqual(trigger["screenshotFrameCount"], 3)
        self.assertEqual(trigger["screenshotFrames"], [7, 42])
        self.assertEqual(trigger["screenshotInterval"], 100)

    def test_write_auto_trigger_includes_selected_camera_for_play_launch(self):
        with tempfile.TemporaryDirectory() as tmp:
            bench = Path(tmp) / "benchmark"
            run_dir = Path(tmp) / "run"
            self._write_benchmark_world(bench, "DemoWorld", "tour")
            (bench / "worlds" / "DemoWorld" / "config.json").write_text(
                '{"defaultCamera":"tour"}',
                encoding="utf-8",
            )

            with (
                patch.object(runner.util, "BENCHMARK_DIR", bench),
                patch.object(runner.util, "ASSETS_DIR", bench),
                patch.object(runner.util, "RUN_DIR", run_dir),
            ):
                config = {"world": "DemoWorld"}
                selection = runner.resolve_benchmark_selection(config, require_camera=False)
                runner.write_auto_trigger(
                    config,
                    selection,
                    exit_on_complete=False,
                    mode="play",
                    disable_world_saving=False,
                )
                trigger = runner.util.read_json(run_dir / "benchmark" / "auto_benchmark.json")

        self.assertEqual(trigger["mode"], "play")
        self.assertEqual(trigger["benchmarkWorld"], "DemoWorld")
        self.assertEqual(trigger["camera"], "tour")
        self.assertEqual(trigger["cameraPath"], "tour")
        self.assertFalse(trigger["disableWorldSaving"])

    def test_launch_client_uses_configured_default_world_when_omitted(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bench = root / "benchmark"
            run_dir = root / "run"
            project = root / "project"
            self._write_benchmark_world(bench, "ConfiguredWorld", None)
            (bench / "config.json").write_text('{"world": {"name": "ConfiguredWorld"}}', encoding="utf-8")
            project.mkdir()
            sdk_dll = project / "remix-runtime" / "arm64" / "d3d9.dll"
            sdk_dll.parent.mkdir(parents=True)
            sdk_dll.write_bytes(b"release runtime")

            with (
                patch.object(runner.util, "BENCHMARK_DIR", bench),
                patch.object(runner.util, "ASSETS_DIR", bench),
                patch.object(runner.util, "SHARED_CONFIG_PATH", bench / "config.json"),
                patch.object(runner.util, "RUN_DIR", run_dir),
                patch.object(runner.util, "PROJECT_DIR", project),
                patch.object(runner, "host_arch", return_value="arm64"),
                patch.object(
                    runner,
                    "fetch_sdk",
                    return_value=sdk_dll,
                ) as fetch_sdk,
                patch.object(runner, "gradle_env", return_value={"PLAY": "1"}),
                patch.object(runner, "benchmark_rtx_config_env") as benchmark_env,
                patch.object(runner.util, "run"),
            ):
                selection = runner.launch_client_for_selection()

            self.assertEqual(selection.world, "ConfiguredWorld")
            fetch_sdk.assert_called_once_with("release", "arm64", auto_restage=True)
            self.assertTrue((run_dir / "saves" / "rtx-benchmark" / "level.dat").exists())
            trigger = runner.util.read_json(run_dir / "benchmark" / "auto_benchmark.json")
            self.assertEqual(trigger["benchmarkWorld"], "ConfiguredWorld")
            self.assertEqual(self.launcher.prepared, [("arm64", True)])
            [request] = self.launcher.requests
            self.assertEqual(request.arch, "arm64")
            self.assertEqual(request.quick_play_world, runner.BENCHMARK_SAVE_NAME)
            self.assertEqual(request.env, {"PLAY": "1"})
            benchmark_env.assert_not_called()

    def test_launch_client_can_request_tracy_sdk_variant(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bench = root / "benchmark"
            run_dir = root / "run"
            project = root / "project"
            self._write_benchmark_world(bench, "DemoWorld", None)
            (bench / "config.json").write_text('{"world": {"name": "DemoWorld"}}', encoding="utf-8")
            project.mkdir()
            packman_dir = project / "packman"
            packman_dir.mkdir()
            sdk_dll = project / "remix-runtime" / "win64" / "d3d9.dll"
            sdk_dll.parent.mkdir(parents=True)
            runtime = b"tracy runtime ___tracy_emit_zone_begin_alloc"
            sdk_dll.write_bytes(runtime)
            (packman_dir / "remix-sdk.packman.xml").write_text(
                '<project><dependency name="remix-sdk-win64">'
                '<package name="rtx_remix_runtime" version="runtime-70deb622-win64-release" />'
                "</dependency></project>",
                encoding="utf-8",
            )

            with (
                patch.object(runner.util, "BENCHMARK_DIR", bench),
                patch.object(runner.util, "ASSETS_DIR", bench),
                patch.object(runner.util, "SHARED_CONFIG_PATH", bench / "config.json"),
                patch.object(runner.util, "RUN_DIR", run_dir),
                patch.object(runner.util, "PROJECT_DIR", project),
                patch.object(runner, "host_arch", return_value="win64"),
                patch.object(
                    runner,
                    "fetch_sdk",
                    return_value=sdk_dll,
                ) as fetch_sdk,
                patch.object(runner.util, "run"),
            ):
                runner.launch_client_for_selection(sdk_variant="tracy", quiet=True)

            fetch_sdk.assert_called_once_with("tracy", "win64", auto_restage=True)
            [request] = self.launcher.requests
            self.assertEqual(request.arch, "win64")
            self.assertTrue(request.quiet)
            self.assertEqual(
                (run_dir / "benchmark" / "dxvk_version.txt").read_text(encoding="utf-8"),
                "tracy win64 rtx_remix_runtime@runtime-70deb622-win64-debugoptimized "
                f"d3d9:{hashlib.sha256(runtime).hexdigest()[:12]}",
            )

    def test_resolve_benchmark_selection_uses_config_defaults(self):
        with tempfile.TemporaryDirectory() as tmp:
            bench = Path(tmp) / "benchmark"
            self._write_benchmark_world(bench, "nether-course", "lava_flyby")

            with patch.object(runner.util, "BENCHMARK_DIR", bench), patch.object(runner.util, "ASSETS_DIR", bench):
                selection = runner.resolve_benchmark_selection(
                    {"world": {"name": "nether-course", "camera": "lava_flyby"}}
                )

        self.assertEqual(selection.world, "nether-course")
        self.assertEqual(selection.camera, "lava_flyby")

    def test_resolve_benchmark_selection_accepts_cli_overrides(self):
        with tempfile.TemporaryDirectory() as tmp:
            bench = Path(tmp) / "benchmark"
            self._write_benchmark_world(bench, "default-world", "default_camera")
            self._write_benchmark_world(bench, "redstone-lab", "switching_test")

            with patch.object(runner.util, "BENCHMARK_DIR", bench), patch.object(runner.util, "ASSETS_DIR", bench):
                selection = runner.resolve_benchmark_selection(
                    {"world": {"name": "default-world", "camera": "default_camera"}},
                    world_override="redstone-lab",
                    camera_override="switching_test.json",
                )

        self.assertEqual(selection.world, "redstone-lab")
        self.assertEqual(selection.camera, "switching_test")

    def test_validate_camera_path_fixture_accepts_version_1_time_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            camera = Path(tmp) / "flythrough.json"
            camera.write_text(VALID_CAMERA_PATH_JSON, encoding="utf-8")

            metadata = runner.validate_camera_path_fixture(camera)

        self.assertEqual(metadata, {"waypoints": 2, "durationMs": 1000})

    def test_real_and_probe_selection_reject_legacy_camera_before_launch(self):
        with tempfile.TemporaryDirectory() as tmp:
            bench = Path(tmp) / "benchmark"
            self._write_benchmark_world(bench, "fixture-world", "flythrough")
            camera = bench / "worlds" / "fixture-world" / "cameras" / "flythrough.json"
            camera.write_text(
                '{"interpolation":"catmull-rom","capturesTimeOfDay":true,'
                '"waypoints":[{"x":0,"y":64,"z":0,"yaw":0,"pitch":0,"frames":60}]}',
                encoding="utf-8",
            )

            with patch.object(runner.util, "BENCHMARK_DIR", bench), patch.object(runner.util, "ASSETS_DIR", bench):
                for resolver in (runner.resolve_benchmark_selection, runner.resolve_benchmark_selection_probe):
                    with self.subTest(resolver=resolver.__name__):
                        with self.assertRaisesRegex(RuntimeError, "legacy frame-count schema"):
                            resolver({"world": "fixture-world", "camera": "flythrough"})

    def test_validate_camera_path_fixture_rejects_unknown_and_invalid_timing(self):
        cases = {
            "unknown root": (
                {
                    "version": 1,
                    "interpolation": "catmull-rom",
                    "capturesTimeOfDay": True,
                    "waypoints": [
                        {"timeMs": 0, "x": 0, "y": 64, "z": 0, "yaw": 0, "pitch": 0},
                        {"timeMs": 1, "x": 1, "y": 64, "z": 0, "yaw": 0, "pitch": 0},
                    ],
                },
                "unsupported field",
            ),
            "non-increasing time": (
                {
                    "version": 1,
                    "interpolation": "catmull-rom",
                    "waypoints": [
                        {"timeMs": 0, "x": 0, "y": 64, "z": 0, "yaw": 0, "pitch": 0},
                        {"timeMs": 0, "x": 1, "y": 64, "z": 0, "yaw": 0, "pitch": 0},
                    ],
                },
                "strictly increasing",
            ),
        }
        with tempfile.TemporaryDirectory() as tmp:
            camera = Path(tmp) / "flythrough.json"
            for label, (document, message) in cases.items():
                with self.subTest(label=label):
                    runner.util.write_json(camera, document)
                    with self.assertRaisesRegex(RuntimeError, message):
                        runner.validate_camera_path_fixture(camera)

    def test_resolve_benchmark_selection_allows_invalid_camera_for_authoring(self):
        with tempfile.TemporaryDirectory() as tmp:
            bench = Path(tmp) / "benchmark"
            self._write_benchmark_world(bench, "fixture-world", "flythrough")
            camera = bench / "worlds" / "fixture-world" / "cameras" / "flythrough.json"
            camera.write_text("{}", encoding="utf-8")

            with patch.object(runner.util, "BENCHMARK_DIR", bench), patch.object(runner.util, "ASSETS_DIR", bench):
                selection = runner.resolve_benchmark_selection(
                    {"world": "fixture-world", "camera": "flythrough"},
                    require_camera=False,
                )

        self.assertEqual(selection.camera_path, camera)

    def test_world_config_overrides_camera_default(self):
        with tempfile.TemporaryDirectory() as tmp:
            bench = Path(tmp) / "benchmark"
            self._write_benchmark_world(bench, "fixture-world", "flythrough")
            world_dir = bench / "worlds" / "fixture-world"
            (world_dir / "config.json").write_text('{"defaultCamera": "flythrough"}', encoding="utf-8")

            with patch.object(runner.util, "BENCHMARK_DIR", bench), patch.object(runner.util, "ASSETS_DIR", bench):
                config = {"world": "fixture-world", "defaultCamera": "missing"}
                selection = runner.configure_benchmark_selection(config)

        self.assertEqual(selection.world, "fixture-world")
        self.assertEqual(selection.camera, "flythrough")
        self.assertEqual(config["defaultCamera"], "flythrough")

    def test_install_fixtures_stages_selected_world_and_camera_set(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bench = root / "benchmark"
            run_dir = root / "run"
            project = root / "project"
            self._write_benchmark_world(bench, "fixture-world", "simple")
            project.mkdir()

            with (
                patch.object(runner.util, "BENCHMARK_DIR", bench),
                patch.object(runner.util, "ASSETS_DIR", bench),
                patch.object(runner.util, "RUN_DIR", run_dir),
                patch.object(runner.util, "PROJECT_DIR", project),
            ):
                config = {"world": "fixture-world", "camera": "simple"}
                runner.install_fixtures(config, runner.configure_benchmark_selection(config))

            self.assertTrue((run_dir / "saves" / "rtx-benchmark" / "level.dat").exists())
            self.assertTrue((run_dir / "camera-paths" / "fixture-world" / "simple.json").exists())

    def test_install_fixtures_stages_configured_world_resource_pack_folder(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bench = root / "benchmark"
            run_dir = root / "run"
            project = root / "project"
            self._write_benchmark_world(bench, "fixture-world", "simple")
            world_dir = bench / "worlds" / "fixture-world"
            (world_dir / "config.json").write_text(
                '{"resourcePacks": ["world-demo-pbr"]}',
                encoding="utf-8",
            )
            (world_dir / "resourcepacks" / "world-demo-pbr").mkdir(parents=True)
            (world_dir / "resourcepacks" / "world-demo-pbr" / "pack.mcmeta").write_text("world pack", encoding="utf-8")
            project.mkdir()

            with (
                patch.object(runner.util, "BENCHMARK_DIR", bench),
                patch.object(runner.util, "ASSETS_DIR", bench),
                patch.object(runner.util, "RUN_DIR", run_dir),
                patch.object(runner.util, "PROJECT_DIR", project),
            ):
                config = {"world": "fixture-world", "camera": "simple"}
                runner.install_fixtures(config, runner.configure_benchmark_selection(config))

            self.assertFalse((run_dir / "resourcepacks" / "rtx-remix").exists())
            self.assertFalse((run_dir / "resourcepacks" / "RtxRemix-LabPBR").exists())
            self.assertTrue((run_dir / "resourcepacks" / "world-demo-pbr" / "pack.mcmeta").exists())
            options = (run_dir / "options.txt").read_text(encoding="utf-8")
            self.assertIn('resourcePacks:["vanilla","fabric","file/world-demo-pbr"]', options)

    def test_only_an_interactive_install_survives_a_failed_pinned_art_pack_fetch(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bench = root / "benchmark"
            run_dir = root / "run"
            project = root / "project"
            self._write_benchmark_world(bench, "fixture-world", "simple")
            # The warning's retry hint reads the configured pin.
            (bench / "config.json").write_text("{}", encoding="utf-8")
            project.mkdir()
            stderr = io.StringIO()

            with (
                patch.object(runner.util, "BENCHMARK_DIR", bench),
                patch.object(runner.util, "ASSETS_DIR", bench),
                patch.object(runner.util, "RUN_DIR", run_dir),
                patch.object(runner.util, "PROJECT_DIR", project),
                patch.object(
                    runner, "fetch_resource_pack", side_effect=runner.FixtureDownloadError("packman pull failed")
                ),
                redirect_stderr(stderr),
            ):
                config = {
                    "world": "fixture-world",
                    "camera": "simple",
                    "resourcePack": {"mode": "pinned", "name": "texture", "version": "0.56.0"},
                }
                selection = runner.configure_benchmark_selection(config)
                with self.assertRaisesRegex(RuntimeError, "packman pull failed"):
                    runner.install_fixtures(config, selection, pin_display=True)
                runner.install_fixtures(config, selection, tolerate_art_pack_fetch_failure=True)

            self.assertTrue((run_dir / "saves" / "rtx-benchmark" / "level.dat").exists())
            options = (run_dir / "options.txt").read_text(encoding="utf-8")
            self.assertIn('resourcePacks:["vanilla","fabric"]', options)
            self.assertIn("texture@0.56.0", stderr.getvalue())

    def test_install_fixtures_stages_configured_world_resource_pack_zip(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bench = root / "benchmark"
            run_dir = root / "run"
            project = root / "project"
            self._write_benchmark_world(bench, "fixture-world", "simple")
            world_dir = bench / "worlds" / "fixture-world"
            (world_dir / "config.json").write_text(
                '{"resourcePacks": ["world-demo-pbr"]}',
                encoding="utf-8",
            )
            (world_dir / "resourcepacks").mkdir()
            (world_dir / "resourcepacks" / "world-demo-pbr.zip").write_bytes(b"zip")
            project.mkdir()

            with (
                patch.object(runner.util, "BENCHMARK_DIR", bench),
                patch.object(runner.util, "ASSETS_DIR", bench),
                patch.object(runner.util, "RUN_DIR", run_dir),
                patch.object(runner.util, "PROJECT_DIR", project),
            ):
                config = {"world": "fixture-world", "camera": "simple"}
                runner.install_fixtures(config, runner.configure_benchmark_selection(config))

            self.assertEqual((run_dir / "resourcepacks" / "world-demo-pbr.zip").read_bytes(), b"zip")
            self.assertFalse((run_dir / "resourcepacks" / "rtx-remix").exists())
            options = (run_dir / "options.txt").read_text(encoding="utf-8")
            self.assertIn('resourcePacks:["vanilla","fabric","file/world-demo-pbr.zip"]', options)

    def test_install_fixtures_drops_retired_baseline_from_legacy_config(self):
        with tempfile.TemporaryDirectory() as tmp:
            assets = Path(tmp) / "assets"
            run_dir = Path(tmp) / "run"
            self._write_benchmark_world(assets, "fixture-world", "simple")
            world_dir = assets / "worlds" / "fixture-world"
            (world_dir / "config.json").write_text(
                '{"resourcePacks": ["rtx-remix", "world-demo-pbr"]}', encoding="utf-8"
            )
            pack = world_dir / "resourcepacks" / "world-demo-pbr"
            pack.mkdir(parents=True)
            (pack / "pack.mcmeta").write_text("world pack", encoding="utf-8")

            with (
                patch.object(runner.util, "ASSETS_DIR", assets),
                patch.object(runner.util, "RUN_DIR", run_dir),
                patch("builtins.print") as log,
            ):
                config = {"world": "fixture-world", "camera": "simple"}
                runner.install_fixtures(config, runner.configure_benchmark_selection(config))

            self.assertIn(
                'resourcePacks:["vanilla","fabric","file/world-demo-pbr"]',
                (run_dir / "options.txt").read_text(encoding="utf-8"),
            )
            migration_logs = [call for call in log.call_args_list if "retired baseline" in str(call)]
            self.assertEqual(len(migration_logs), 1)
            self.assertIs(migration_logs[0].kwargs["file"], sys.stderr)

    def test_stage_resource_packs_removes_only_legacy_generated_baseline(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_dir = Path(tmp)
            packs = run_dir / "resourcepacks"
            legacy = packs / "rtx-remix"
            legacy.mkdir(parents=True)
            (legacy / "pack.mcmeta").write_text(
                '{"pack":{"pack_format":34,"description":"RTX Remix LabPBR Development Pack"}}', encoding="utf-8"
            )
            (legacy / "BLOCK_TEXTURES.txt").write_text("block inventory", encoding="utf-8")
            user_pack = packs / "my-textures"
            user_pack.mkdir()
            (packs / "rtx-remix.zip").write_bytes(b"user zip")

            with patch.object(runner.util, "RUN_DIR", run_dir):
                self.assertEqual(runner.stage_resource_packs({}, None, run_dir), [])

            self.assertFalse(legacy.exists())
            self.assertTrue(user_pack.is_dir())
            self.assertEqual((packs / "rtx-remix.zip").read_bytes(), b"user zip")

    def test_stage_resource_packs_keeps_unrecognized_rtx_remix_folder(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_dir = Path(tmp)
            pack = run_dir / "resourcepacks" / "rtx-remix"
            pack.mkdir(parents=True)
            (pack / "pack.mcmeta").write_text('{"pack":{"description":"My textures"}}', encoding="utf-8")

            with patch.object(runner.util, "RUN_DIR", run_dir):
                self.assertEqual(runner.stage_resource_packs({}, None, run_dir), [])

            self.assertEqual(
                (pack / "pack.mcmeta").read_text(encoding="utf-8"), '{"pack":{"description":"My textures"}}'
            )

    def test_install_fixtures_accepts_world_resource_pack_named_rtx_remix(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            assets = root / "assets"
            run_dir = root / "run"
            self._write_benchmark_world(assets, "fixture-world", "simple")
            pack = assets / "worlds" / "fixture-world" / "resourcepacks" / "rtx-remix"
            pack.mkdir(parents=True)
            metadata = '{"pack":{"pack_format":34,"description":"RTX Remix LabPBR Development Pack"}}'
            (pack / "pack.mcmeta").write_text(metadata, encoding="utf-8")
            (pack / "BLOCK_TEXTURES.txt").write_text("world pack inventory", encoding="utf-8")

            with (
                patch.object(runner.util, "ASSETS_DIR", assets),
                patch.object(runner.util, "RUN_DIR", run_dir),
                patch.object(runner.util, "PROJECT_DIR", root),
            ):
                config = {"world": "fixture-world", "camera": "simple", "resourcePacks": ["rtx-remix"]}
                runner.install_fixtures(config, runner.configure_benchmark_selection(config))
                runner.stage_resource_packs({}, None, run_dir)

            staged = run_dir / "resourcepacks" / "rtx-remix" / "pack.mcmeta"
            self.assertEqual(staged.read_text(encoding="utf-8"), metadata)
            self.assertTrue((staged.parent / ".rtx-remix-world-pack").is_file())
            self.assertFalse((pack / ".rtx-remix-world-pack").exists())
            options = (run_dir / "options.txt").read_text(encoding="utf-8")
            self.assertIn('resourcePacks:["vanilla","fabric","file/rtx-remix"]', options)

    def test_install_fixtures_accepts_world_resource_pack_zip_named_rtx_remix(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            assets = root / "assets"
            run_dir = root / "run"
            self._write_benchmark_world(assets, "fixture-world", "simple")
            pack = assets / "worlds" / "fixture-world" / "resourcepacks" / "rtx-remix.zip"
            pack.parent.mkdir()
            pack.write_bytes(b"world zip")
            stderr = io.StringIO()

            with (
                patch.object(runner.util, "ASSETS_DIR", assets),
                patch.object(runner.util, "RUN_DIR", run_dir),
                redirect_stderr(stderr),
            ):
                config = {"world": "fixture-world", "camera": "simple", "resourcePacks": ["rtx-remix"]}
                runner.install_fixtures(config, runner.configure_benchmark_selection(config))
                runner.stage_resource_packs({}, None, run_dir)

            self.assertEqual((run_dir / "resourcepacks" / "rtx-remix.zip").read_bytes(), b"world zip")
            self.assertNotIn("retired baseline", stderr.getvalue())
            options = (run_dir / "options.txt").read_text(encoding="utf-8")
            self.assertIn('resourcePacks:["vanilla","fabric","file/rtx-remix.zip"]', options)

    def test_install_fixtures_rejects_missing_configured_world_resource_pack(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bench = root / "benchmark"
            run_dir = root / "run"
            project = root / "project"
            self._write_benchmark_world(bench, "fixture-world", "simple")
            (bench / "worlds" / "fixture-world" / "config.json").write_text(
                '{"resourcePacks": ["missing-pack"]}',
                encoding="utf-8",
            )
            project.mkdir()

            with (
                patch.object(runner.util, "BENCHMARK_DIR", bench),
                patch.object(runner.util, "ASSETS_DIR", bench),
                patch.object(runner.util, "RUN_DIR", run_dir),
                patch.object(runner.util, "PROJECT_DIR", project),
            ):
                config = {"world": "fixture-world", "camera": "simple"}
                selection = runner.configure_benchmark_selection(config)
                with self.assertRaisesRegex(RuntimeError, "missing-pack"):
                    runner.install_fixtures(config, selection)

    def test_install_fixtures_can_strip_entities_from_staged_copy(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bench = root / "benchmark"
            run_dir = root / "run"
            project = root / "project"
            self._write_benchmark_world(bench, "entity-world", "fly")
            source_entities = bench / "worlds" / "entity-world" / "world" / "entities"
            source_entities.mkdir()
            (source_entities / "r.0.0.mca").write_bytes(b"bad entity chunk")
            project.mkdir()

            with (
                patch.object(runner.util, "BENCHMARK_DIR", bench),
                patch.object(runner.util, "ASSETS_DIR", bench),
                patch.object(runner.util, "RUN_DIR", run_dir),
                patch.object(runner.util, "PROJECT_DIR", project),
            ):
                config = {"world": "entity-world", "camera": "fly", "stripEntitiesOnStage": True}
                runner.install_fixtures(config, runner.configure_benchmark_selection(config))

            self.assertTrue((source_entities / "r.0.0.mca").exists())
            self.assertFalse((run_dir / "saves" / "rtx-benchmark" / "entities").exists())
            report = runner.util.read_json(run_dir / "benchmark" / "world-sanitize.json")
            self.assertEqual(report["world"], "entity-world")
            self.assertEqual(report["actions"][0]["action"], "stripEntities")

    def test_install_fixtures_removes_stale_session_lock_and_filefix_from_staged_copy(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bench = root / "benchmark"
            run_dir = root / "run"
            project = root / "project"
            self._write_benchmark_world(bench, "locked-world", "fly")
            world = bench / "worlds" / "locked-world" / "world"
            (world / "session.lock").write_text("locked", encoding="utf-8")
            (world / "filefix").mkdir()
            (world / "filefix" / "cache.dat").write_text("scratch", encoding="utf-8")
            project.mkdir()

            with (
                patch.object(runner.util, "BENCHMARK_DIR", bench),
                patch.object(runner.util, "ASSETS_DIR", bench),
                patch.object(runner.util, "RUN_DIR", run_dir),
                patch.object(runner.util, "PROJECT_DIR", project),
            ):
                config = {"world": "locked-world", "camera": "fly"}
                runner.install_fixtures(config, runner.configure_benchmark_selection(config))

            self.assertTrue((world / "session.lock").exists())
            self.assertTrue((world / "filefix" / "cache.dat").exists())
            self.assertFalse((run_dir / "saves" / "rtx-benchmark" / "session.lock").exists())
            self.assertFalse((run_dir / "saves" / "rtx-benchmark" / "filefix").exists())
            report = runner.util.read_json(run_dir / "benchmark" / "world-sanitize.json")
            self.assertEqual(
                [action["action"] for action in report["actions"]], ["removeSessionLock", "removeFileFixScratch"]
            )

    def test_world_config_can_strip_entities_from_staged_copy(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bench = root / "benchmark"
            run_dir = root / "run"
            project = root / "project"
            self._write_benchmark_world(bench, "fixture-world", "fly")
            world_dir = bench / "worlds" / "fixture-world"
            (world_dir / "config.json").write_text(
                '{"worldSanitizer": {"stripEntitiesOnStage": true}}',
                encoding="utf-8",
            )
            (world_dir / "world" / "entities").mkdir()
            (world_dir / "world" / "entities" / "r.0.0.mca").write_bytes(b"bad entity chunk")
            project.mkdir()

            with (
                patch.object(runner.util, "BENCHMARK_DIR", bench),
                patch.object(runner.util, "ASSETS_DIR", bench),
                patch.object(runner.util, "RUN_DIR", run_dir),
                patch.object(runner.util, "PROJECT_DIR", project),
            ):
                config = {"world": "fixture-world", "camera": "fly"}
                runner.install_fixtures(config, runner.configure_benchmark_selection(config))

            self.assertFalse((run_dir / "saves" / "rtx-benchmark" / "entities").exists())

    def test_import_world_replaces_world_but_preserves_cameras(self):
        with tempfile.TemporaryDirectory() as tmp:
            bench = Path(tmp) / "benchmark"
            source = Path(tmp) / "SourceWorld"
            source.mkdir()
            (source / "level.dat").write_text("new", encoding="utf-8")

            with patch.object(runner.util, "BENCHMARK_DIR", bench), patch.object(runner.util, "ASSETS_DIR", bench):
                target = runner.import_world(str(source), name="DemoWorld")
                (target / "cameras" / "flythrough.json").write_text("{}", encoding="utf-8")
                (target / "resourcepacks" / "world-demo-pbr").mkdir(parents=True)
                (target / "resourcepacks" / "world-demo-pbr" / "pack.mcmeta").write_text("pack", encoding="utf-8")
                (source / "level.dat").write_text("updated", encoding="utf-8")
                runner.import_world(str(source), name="DemoWorld", replace=True)

                self.assertEqual((target / "world" / "level.dat").read_text(encoding="utf-8"), "updated")
                self.assertTrue((target / "cameras" / "flythrough.json").exists())
                self.assertTrue((target / "resourcepacks" / "world-demo-pbr" / "pack.mcmeta").exists())

    def test_update_world_replaces_from_staged_save_by_default(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bench = root / "benchmark"
            run_dir = root / "run"
            staged = run_dir / "saves" / "rtx-benchmark"
            staged.mkdir(parents=True)
            (staged / "level.dat").write_text("updated", encoding="utf-8")
            self._write_benchmark_world(bench, "DemoWorld", "flythrough")
            (bench / "worlds" / "DemoWorld" / "resourcepacks" / "world-demo-pbr").mkdir(parents=True)
            (bench / "worlds" / "DemoWorld" / "resourcepacks" / "world-demo-pbr" / "pack.mcmeta").write_text(
                "pack",
                encoding="utf-8",
            )

            with (
                patch.object(runner.util, "BENCHMARK_DIR", bench),
                patch.object(runner.util, "ASSETS_DIR", bench),
                patch.object(runner.util, "RUN_DIR", run_dir),
            ):
                target = runner.update_world("DemoWorld")

                self.assertEqual((target / "world" / "level.dat").read_text(encoding="utf-8"), "updated")
                self.assertTrue((target / "cameras" / "flythrough.json").exists())
                self.assertTrue((target / "resourcepacks" / "world-demo-pbr" / "pack.mcmeta").exists())

    def test_update_world_refuses_a_fixture_directory_that_does_not_exist(self):
        # An unversioned import has no suffix; naming it with one must not create a second fixture.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bench = root / "benchmark"
            run_dir = root / "run"
            staged = run_dir / "saves" / "rtx-benchmark"
            staged.mkdir(parents=True)
            (staged / "level.dat").write_text("updated", encoding="utf-8")
            self._write_benchmark_world(bench, "DemoWorld", "flythrough")

            with (
                patch.object(runner.util, "ASSETS_DIR", bench),
                patch.object(runner.util, "RUN_DIR", run_dir),
                self.assertRaisesRegex(
                    RuntimeError, r"No fixture directory 'DemoWorld_v0\.1\.0'.*Fixture directories: DemoWorld\."
                ),
            ):
                runner.update_world("DemoWorld_v0.1.0")

            self.assertEqual(["DemoWorld"], sorted(path.name for path in (bench / "worlds").iterdir()))
            self.assertEqual(
                (bench / "worlds" / "DemoWorld" / "world" / "level.dat").read_text(encoding="utf-8"), "world"
            )

    def test_world_import_and_update_name_the_path_they_tried_for_a_missing_save(self):
        with tempfile.TemporaryDirectory() as tmp:
            project = Path(tmp)
            assets = project / "tools" / "assets"
            self._write_benchmark_world(assets, "DemoWorld", None)
            missing = str(project / "saves" / "Missing")

            with patch.object(runner.util, "PROJECT_DIR", project), patch.object(runner.util, "ASSETS_DIR", assets):
                for command, attempt in (
                    ("world import", lambda: runner.import_world("saves/Missing", name="Other")),
                    ("world update", lambda: runner.update_world("DemoWorld", source="saves/Missing")),
                ):
                    with self.subTest(command=command), self.assertRaises(RuntimeError) as raised:
                        attempt()
                    self.assertIn(missing, str(raised.exception))
                    self.assertIn("a relative path starts at the repository root", str(raised.exception))

    def test_world_import_and_update_take_a_relative_save_from_the_repository_root(self):
        # rtx.bat starts the CLI in tools/, so the working directory must not decide where a relative save is.
        with tempfile.TemporaryDirectory() as tmp:
            project = Path(tmp)
            tools_dir = project / "tools"
            tools_dir.mkdir()
            save = project / "saves" / "SourceWorld"
            save.mkdir(parents=True)
            (save / "level.dat").write_text("imported", encoding="utf-8")

            with (
                patch.object(runner.util, "PROJECT_DIR", project),
                patch.object(runner.util, "ASSETS_DIR", tools_dir / "assets"),
                chdir(tools_dir),
            ):
                target = runner.import_world("saves/SourceWorld", name="DemoWorld")
                self.assertEqual((target / "world" / "level.dat").read_text(encoding="utf-8"), "imported")

                (save / "level.dat").write_text("updated", encoding="utf-8")
                runner.update_world("DemoWorld", source="saves/SourceWorld")
                self.assertEqual((target / "world" / "level.dat").read_text(encoding="utf-8"), "updated")

    def test_normalize_raw_world_layout_preserves_resource_pack_metadata(self):
        for cameras_present in (False, True):
            with self.subTest(cameras_present=cameras_present), tempfile.TemporaryDirectory() as tmp:
                bench = Path(tmp) / "benchmark"
                raw = bench / "worlds" / "RawDrop"
                raw.mkdir(parents=True)
                (raw / "level.dat").write_text("world", encoding="utf-8")
                (raw / "region").mkdir()
                if cameras_present:
                    (raw / "cameras").mkdir()
                (raw / "resourcepacks" / "world-demo-pbr").mkdir(parents=True)
                (raw / "resourcepacks" / "world-demo-pbr" / "pack.mcmeta").write_text("pack", encoding="utf-8")
                (raw / "config.json").write_text('{"resourcePacks": ["world-demo-pbr"]}', encoding="utf-8")

                with patch.object(runner.util, "BENCHMARK_DIR", bench), patch.object(runner.util, "ASSETS_DIR", bench):
                    normalized = runner.normalize_world_layout("RawDrop")

                self.assertEqual(normalized, raw)
                self.assertTrue((raw / "world" / "level.dat").exists())
                self.assertTrue((raw / "world" / "region").is_dir())
                self.assertTrue((raw / "cameras").is_dir())
                self.assertTrue((raw / "resourcepacks" / "world-demo-pbr" / "pack.mcmeta").exists())
                self.assertFalse((raw / "world" / "resourcepacks").exists())
                self.assertTrue((raw / "config.json").exists())

    def test_benchmark_run_plan_is_non_mutating_for_raw_world_layout(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bench = root / "benchmark"
            run_dir = root / "run"
            project = root / "project"
            raw = bench / "worlds" / "RawDrop"
            raw.mkdir(parents=True)
            (raw / "level.dat").write_text("world", encoding="utf-8")
            (raw / "cameras").mkdir()
            (raw / "cameras" / "simple.json").write_text(VALID_CAMERA_PATH_JSON, encoding="utf-8")
            (bench / "config.json").write_text('{"world": {"name": "RawDrop", "camera": "simple"}}', encoding="utf-8")
            project.mkdir()

            args = SimpleNamespace(
                world=None,
                camera=None,
                ci=False,
                runs=0,
                strip_entities=False,
                sdk_variant="tracy",
                no_build=False,
                dry_run=True,
            )
            with (
                patch.object(runner.util, "BENCHMARK_DIR", bench),
                patch.object(runner.util, "ASSETS_DIR", bench),
                patch.object(runner.util, "SHARED_CONFIG_PATH", bench / "config.json"),
                patch.object(runner.util, "RUN_DIR", run_dir),
                patch.object(runner.util, "PROJECT_DIR", project),
                patch.object(runner, "host_arch", return_value="arm64"),
            ):
                plan = runner.benchmark_run_plan(args)

        self.assertEqual(plan["world"], "RawDrop")
        self.assertEqual(plan["worldLayout"], "raw")
        self.assertEqual(plan["arch"], "arm64")
        self.assertEqual(plan["resourcePacks"], [])
        self.assertEqual(plan["resourcePackFiles"], [])
        self.assertTrue(plan["sdk"]["wouldFetch"])
        self.assertFalse((raw / "world").exists())
        self.assertFalse((run_dir / "saves").exists())

    def test_run_benchmark_dry_run_does_not_fetch_build_or_create_result(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bench = root / "benchmark"
            run_dir = root / "run"
            project = root / "project"
            results = bench / "results"
            self._write_benchmark_world(bench, "DemoWorld", "simple")
            (bench / "config.json").write_text('{"world": {"name": "DemoWorld", "camera": "simple"}}', encoding="utf-8")
            project.mkdir()

            args = SimpleNamespace(
                world=None,
                camera=None,
                ci=False,
                runs=0,
                strip_entities=False,
                sdk_variant="tracy",
                no_build=False,
                dry_run=True,
            )
            with (
                patch.object(runner.util, "BENCHMARK_DIR", bench),
                patch.object(runner.util, "ASSETS_DIR", bench),
                patch.object(runner.util, "SHARED_CONFIG_PATH", bench / "config.json"),
                patch.object(runner.util, "RUN_DIR", run_dir),
                patch.object(runner.util, "PROJECT_DIR", project),
                patch.object(runner.util, "RESULTS_DIR", results),
                patch.object(runner, "host_arch", return_value="win64"),
                patch.object(runner, "fetch_sdk") as fetch_sdk,
                patch.object(runner.util, "run") as run,
                patch("builtins.print"),
            ):
                result = runner.run_benchmark(args)

        self.assertIsNone(result)
        fetch_sdk.assert_not_called()
        run.assert_not_called()
        self.assertFalse(results.exists())

    def test_ci_soak_continues_after_run_failure_and_finalizes_failed_manifest(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            project = root / "project"
            results = root / "results"
            result_dir = results / "20260701_120000"
            sdk = root / "sdk" / "d3d9.dll"
            sdk.parent.mkdir()
            sdk.write_bytes(b"sdk")
            project.mkdir()
            calls = 0

            def fake_run_client(config, selection, tools, run_log, exit_on_complete, arch):
                nonlocal calls
                calls += 1
                run_log.parent.mkdir(parents=True, exist_ok=True)
                run_log.write_text(f"run {calls}\n", encoding="utf-8")
                if calls == 1:
                    return {
                        "resultFile": None,
                        "failureReason": "NativeHeapCorruption",
                        "failureDetails": {"reason": "NativeHeapCorruption", "ntstatus": "0xC0000374"},
                    }
                raw = root / f"raw_{calls}"
                raw.mkdir()
                benchmark = raw / "benchmark.json"
                benchmark.write_text("{}\n", encoding="utf-8")
                return {"resultFile": benchmark, "failureReason": "", "failureDetails": None}

            def fake_generate(per_run, run_number, *, tracy_required=True):
                (per_run / "profile-data.json").write_text("{}\n", encoding="utf-8")

            aggregate_doc = {"frame": {"avgMs": 10.0}, "perRun": []}

            def fake_aggregate(path):
                runner.util.write_json(path / "aggregate.json", aggregate_doc)
                return aggregate_doc

            args = SimpleNamespace(
                dry_run=False,
                ci=True,
                world=None,
                camera=None,
                version=None,
                strip_entities=False,
                runs=3,
                arch=None,
                sdk_variant="tracy",
                no_build=True,
                note=None,
                tag=[],
                open=False,
            )
            selection = SimpleNamespace(world="DemoWorld", camera="simple")
            config = {"world": {"name": "DemoWorld"}, "runs": 3, "continueAfterRunFailure": True}
            with ExitStack() as stack:
                stack.enter_context(patch.object(runner.util, "PROJECT_DIR", project))
                stack.enter_context(patch.object(runner.util, "RUN_DIR", project / "run"))
                stack.enter_context(patch.object(runner.util, "RESULTS_DIR", results))
                stack.enter_context(patch.object(runner, "load_config", return_value=config))
                stack.enter_context(patch.object(runner, "ensure_world_staged"))
                stack.enter_context(patch.object(runner, "configure_benchmark_selection", return_value=selection))
                stack.enter_context(patch.object(runner, "config_for_run", return_value=config))
                stack.enter_context(patch.object(runner, "ensure_no_live_client"))
                stack.enter_context(patch.object(runner, "host_arch", return_value="win64"))
                verify_jvm = stack.enter_context(patch.object(runner, "verify_jvm_matches_arch"))
                stack.enter_context(patch.object(runner, "unique_result_dir", return_value=result_dir))
                stack.enter_context(patch.object(runner, "timestamp_id", return_value=result_dir.name))
                stack.enter_context(
                    patch.object(runner.gitmeta, "current_git", return_value={"dirty": False, "branch": "main"})
                )
                stack.enter_context(patch.object(runner.machine, "collect_machine", return_value={}))
                stack.enter_context(patch.object(runner, "fetch_sdk", return_value=sdk))
                stack.enter_context(patch.object(runner, "tracy_tools", return_value=None))
                stack.enter_context(patch.object(runner, "sdk_metadata", return_value={"variant": "tracy"}))
                stack.enter_context(patch.object(runner, "kill_leftovers"))
                stack.enter_context(patch.object(runner, "run_client_once", side_effect=fake_run_client))
                stack.enter_context(patch.object(runner, "validate_benchmark_display"))
                stack.enter_context(patch.object(runner, "generate_profile_data", side_effect=fake_generate))
                stack.enter_context(patch.object(runner, "wait_before_next_run"))
                stack.enter_context(patch.object(runner, "aggregate", side_effect=fake_aggregate))
                stack.enter_context(patch.object(runner, "attach_benchmark_system"))
                write_dashboard = stack.enter_context(patch.object(runner.catalog, "write_dashboard"))
                stack.enter_context(patch("builtins.print"))
                with self.assertRaisesRegex(RuntimeError, "Benchmark soak failed"):
                    runner.run_benchmark(args)
                verify_jvm.assert_called_once_with("win64")

            manifest = runner.util.read_json(result_dir / "manifest.json")
            metrics = (project / "metrics.txt").read_text(encoding="utf-8")

        self.assertEqual(calls, 3)
        # --no-build still installs the build Gradle already made into the launcher's instance.
        self.assertEqual(self.launcher.prepared, [("win64", False)])
        self.assertEqual(manifest["outcome"]["status"], "failed")
        # status is the single comparability signal; the legacy
        # eligibleForComparison flag is no longer written.
        self.assertNotIn("eligibleForComparison", manifest["outcome"])
        self.assertEqual(manifest["outcome"]["attemptedRuns"], 3)
        self.assertEqual(manifest["outcome"]["completedRuns"], 2)
        self.assertEqual(manifest["outcome"]["failedRuns"], 1)
        self.assertIn("benchmark_success 0", metrics)
        write_dashboard.assert_called_once_with(default_b="")

    def test_completed_run_with_teardown_failure_is_recorded_without_failing_outcome(self):
        # A run that writes benchmark.json and then crashes natively during
        # teardown keeps its valid measurement (outcome passes) but the crash
        # must be visible in the manifest, not only in run_N/failure.json.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            project = root / "project"
            results = root / "results"
            result_dir = results / "20260701_120000"
            sdk = root / "sdk" / "d3d9.dll"
            sdk.parent.mkdir()
            sdk.write_bytes(b"sdk")
            project.mkdir()

            def fake_run_client(config, selection, tools, run_log, exit_on_complete, arch):
                run_log.parent.mkdir(parents=True, exist_ok=True)
                run_log.write_text("run\n", encoding="utf-8")
                raw = root / "raw"
                raw.mkdir(exist_ok=True)
                benchmark = raw / "benchmark.json"
                benchmark.write_text("{}\n", encoding="utf-8")
                return {
                    "resultFile": benchmark,
                    "failureReason": "NativeHeapCorruption",
                    "failureDetails": {"reason": "NativeHeapCorruption", "ntstatus": "0xC0000374"},
                }

            def fake_generate(per_run, run_number, *, tracy_required=True):
                (per_run / "profile-data.json").write_text("{}\n", encoding="utf-8")

            aggregate_doc = {"frame": {"avgMs": 10.0}, "perRun": []}

            def fake_aggregate(path):
                runner.util.write_json(path / "aggregate.json", aggregate_doc)
                return aggregate_doc

            args = SimpleNamespace(
                dry_run=False,
                ci=True,
                world=None,
                camera=None,
                version=None,
                strip_entities=False,
                runs=1,
                arch=None,
                sdk_variant="tracy",
                no_build=True,
                note=None,
                tag=[],
                open=False,
            )
            selection = SimpleNamespace(world="DemoWorld", camera="simple")
            config = {"world": {"name": "DemoWorld"}, "runs": 1}
            with ExitStack() as stack:
                stack.enter_context(patch.object(runner.util, "PROJECT_DIR", project))
                stack.enter_context(patch.object(runner.util, "RUN_DIR", project / "run"))
                stack.enter_context(patch.object(runner.util, "RESULTS_DIR", results))
                stack.enter_context(patch.object(runner, "load_config", return_value=config))
                stack.enter_context(patch.object(runner, "ensure_world_staged"))
                stack.enter_context(patch.object(runner, "configure_benchmark_selection", return_value=selection))
                stack.enter_context(patch.object(runner, "config_for_run", return_value=config))
                stack.enter_context(patch.object(runner, "ensure_no_live_client"))
                stack.enter_context(patch.object(runner, "host_arch", return_value="win64"))
                stack.enter_context(patch.object(runner, "verify_jvm_matches_arch"))
                stack.enter_context(patch.object(runner, "unique_result_dir", return_value=result_dir))
                stack.enter_context(patch.object(runner, "timestamp_id", return_value=result_dir.name))
                stack.enter_context(
                    patch.object(runner.gitmeta, "current_git", return_value={"dirty": False, "branch": "main"})
                )
                stack.enter_context(patch.object(runner.machine, "collect_machine", return_value={}))
                stack.enter_context(patch.object(runner, "fetch_sdk", return_value=sdk))
                stack.enter_context(patch.object(runner, "tracy_tools", return_value=None))
                stack.enter_context(patch.object(runner, "sdk_metadata", return_value={"variant": "tracy"}))
                stack.enter_context(patch.object(runner, "kill_leftovers"))
                stack.enter_context(patch.object(runner, "run_client_once", side_effect=fake_run_client))
                stack.enter_context(patch.object(runner, "validate_benchmark_display"))
                stack.enter_context(patch.object(runner, "generate_profile_data", side_effect=fake_generate))
                stack.enter_context(patch.object(runner, "aggregate", side_effect=fake_aggregate))
                stack.enter_context(patch.object(runner, "attach_benchmark_system"))
                stack.enter_context(patch.object(runner.catalog, "write_dashboard"))
                stack.enter_context(patch("builtins.print"))
                runner.run_benchmark(args)

            manifest = runner.util.read_json(result_dir / "manifest.json")

        outcome = manifest["outcome"]
        self.assertEqual(manifest["launcher"], self.launcher.name)
        self.assertEqual(outcome["status"], "passed")
        self.assertEqual(outcome["completedRuns"], 1)
        self.assertEqual(outcome["failedRuns"], 0)
        post = outcome["postCompletionFailures"]
        self.assertEqual(len(post), 1)
        self.assertEqual(post[0]["reason"], "NativeHeapCorruption")
        self.assertEqual(post[0]["run"], 1)
        self.assertEqual(post[0]["artifact"], "run_1/failure.json")

    def test_run_with_degenerate_frames_fails_and_is_not_comparable(self):
        # A benchmark.json full of sub-cap frames (recording started on the
        # loading screen) must fail the run so it cannot become an A/B baseline.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            project = root / "project"
            results = root / "results"
            result_dir = results / "20260701_120000"
            sdk = root / "sdk" / "d3d9.dll"
            sdk.parent.mkdir()
            sdk.write_bytes(b"sdk")
            project.mkdir()

            def fake_run_client(config, selection, tools, run_log, exit_on_complete, arch):
                run_log.parent.mkdir(parents=True, exist_ok=True)
                run_log.write_text("run\n", encoding="utf-8")
                raw = root / "raw"
                raw.mkdir(exist_ok=True)
                benchmark = raw / "benchmark.json"
                frames = [1_500_000] * 400 + [30_000_000] * 3400
                runner.util.write_json(benchmark, {"metadata": {}, "perFrame": frames})
                return {"resultFile": benchmark, "failureReason": "", "failureDetails": None}

            args = SimpleNamespace(
                dry_run=False,
                ci=True,
                world=None,
                camera=None,
                version=None,
                strip_entities=False,
                runs=1,
                arch=None,
                sdk_variant="tracy",
                no_build=True,
                note=None,
                tag=[],
                open=False,
            )
            selection = SimpleNamespace(world="DemoWorld", camera="simple")
            config = {"world": {"name": "DemoWorld"}, "runs": 1, "render": {"maxFps": 260}}
            with ExitStack() as stack:
                stack.enter_context(patch.object(runner.util, "PROJECT_DIR", project))
                stack.enter_context(patch.object(runner.util, "RUN_DIR", project / "run"))
                stack.enter_context(patch.object(runner.util, "RESULTS_DIR", results))
                stack.enter_context(patch.object(runner, "load_config", return_value=config))
                stack.enter_context(patch.object(runner, "ensure_world_staged"))
                stack.enter_context(patch.object(runner, "configure_benchmark_selection", return_value=selection))
                stack.enter_context(patch.object(runner, "config_for_run", return_value=config))
                stack.enter_context(patch.object(runner, "ensure_no_live_client"))
                stack.enter_context(patch.object(runner, "host_arch", return_value="win64"))
                stack.enter_context(patch.object(runner, "verify_jvm_matches_arch"))
                stack.enter_context(patch.object(runner, "unique_result_dir", return_value=result_dir))
                stack.enter_context(patch.object(runner, "timestamp_id", return_value=result_dir.name))
                stack.enter_context(
                    patch.object(runner.gitmeta, "current_git", return_value={"dirty": False, "branch": "main"})
                )
                stack.enter_context(patch.object(runner.machine, "collect_machine", return_value={}))
                stack.enter_context(patch.object(runner, "fetch_sdk", return_value=sdk))
                stack.enter_context(patch.object(runner, "tracy_tools", return_value=None))
                stack.enter_context(patch.object(runner, "sdk_metadata", return_value={"variant": "tracy"}))
                stack.enter_context(patch.object(runner, "kill_leftovers"))
                stack.enter_context(patch.object(runner, "run_client_once", side_effect=fake_run_client))
                stack.enter_context(patch.object(runner, "validate_benchmark_display"))
                generate = stack.enter_context(patch.object(runner, "generate_profile_data"))
                stack.enter_context(patch.object(runner, "attach_benchmark_system"))
                stack.enter_context(patch.object(runner.catalog, "write_dashboard"))
                stack.enter_context(patch("builtins.print"))
                with self.assertRaisesRegex(RuntimeError, "DegenerateFrames"):
                    runner.run_benchmark(args)

            manifest = runner.util.read_json(result_dir / "manifest.json")
            failure_text = (result_dir / "run_1" / "failure.txt").read_text(encoding="utf-8")

        outcome = manifest["outcome"]
        self.assertEqual(outcome["status"], "failed")
        self.assertEqual(outcome["completedRuns"], 0)
        self.assertEqual(outcome["failures"][0]["reason"], "DegenerateFrames")
        self.assertIn("400 of 3800 frames", failure_text)
        generate.assert_not_called()

    def test_doctor_reports_local_non_tracy_sdk_as_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bench = root / "benchmark"
            project = root / "project"
            java_home = root / "jdk"
            self._write_benchmark_world(bench, "DemoWorld", "simple")
            (bench / "config.json").write_text('{"world": {"name": "DemoWorld", "camera": "simple"}}', encoding="utf-8")
            sdk = project / "remix-runtime" / "win64"
            sdk.mkdir(parents=True)
            (sdk / "d3d9.dll").write_bytes(b"fake release dll")
            (sdk / ".local-sdk-source").write_text("source: local", encoding="utf-8")

            with (
                patch.object(runner.util, "BENCHMARK_DIR", bench),
                patch.object(runner.util, "ASSETS_DIR", bench),
                patch.object(runner.util, "PROJECT_DIR", project),
                patch.object(runner, "detect_java_home", return_value=java_home),
                patch.object(runner, "java_runtime_arch", return_value="amd64"),
                patch.object(runner, "windows_process_arch", return_value="AMD64"),
                patch.object(runner, "host_arch", return_value="win64"),
            ):
                report = runner.doctor_report(sdk_variant="tracy")

        self.assertFalse(report["ok"])
        sdk_checks = [check for check in report["checks"] if check["name"] == "sdk"]
        self.assertEqual(sdk_checks[0]["status"], "error")

    def test_doctor_tracy_fetch_without_remote_names_required_settings(self):
        config = runner.load_config()
        config["sdk"]["tracyRemote"] = ""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            dll = root / "remix-runtime" / "win64" / "d3d9.dll"
            dll.parent.mkdir(parents=True)
            for staged in (False, True):
                with self.subTest(staged=staged):
                    if staged:
                        dll.write_bytes(b"fake release dll")
                    with (
                        patch.object(runner, "load_config", return_value=config),
                        patch.object(runner.util, "PROJECT_DIR", root),
                        patch.object(runner, "detect_java_home", return_value=root / "jdk"),
                        patch.object(runner, "java_runtime_arch", return_value="amd64"),
                        patch.object(runner, "windows_process_arch", return_value="AMD64"),
                        patch.object(runner, "host_arch", return_value="win64"),
                    ):
                        report = runner.doctor_report(sdk_variant="tracy")
                    check = next(check for check in report["checks"] if check["name"] == "sdk")
                    self.assertEqual(check["status"], "warn")
                    self.assertIn("sdk.tracyRemote", check["detail"])
                    self.assertNotIn("will fetch", check["detail"])

    def test_doctor_release_staged_tracy_sdk_reports_fetch_target_and_cache(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bench = root / "benchmark"
            project = root / "project"
            java_home = root / "jdk"
            cache_root = root / "packman-repo"
            self._write_benchmark_world(bench, "DemoWorld", "simple")
            (bench / "config.json").write_text('{"world": {"name": "DemoWorld", "camera": "simple"}}', encoding="utf-8")
            packman_dir = project / "packman"
            packman_dir.mkdir(parents=True)
            sdk = project / "remix-runtime" / "win64"
            sdk.mkdir(parents=True)
            (sdk / "d3d9.dll").write_bytes(b"fake release dll")
            (packman_dir / "remix-sdk.packman.xml").write_text(
                """<project toolsVersion="7.6">
  <dependency name="remix-sdk-win64" linkPath="../remix-runtime/win64">
    <package name="rtx_remix_runtime" version="demo-runtime-70deb622-win64-release" />
  </dependency>
</project>
""",
                encoding="utf-8",
            )
            cached = cache_root / "chk" / "rtx_remix_runtime" / "demo-runtime-70deb622-win64-debugoptimized"
            cached.mkdir(parents=True)

            with (
                patch.dict(os.environ, {"PM_PACKAGES_ROOT": str(cache_root)}),
                patch.object(runner.util, "BENCHMARK_DIR", bench),
                patch.object(runner.util, "ASSETS_DIR", bench),
                patch.object(runner.util, "PROJECT_DIR", project),
                patch.object(runner, "detect_java_home", return_value=java_home),
                patch.object(runner, "java_runtime_arch", return_value="amd64"),
                patch.object(runner, "windows_process_arch", return_value="AMD64"),
                patch.object(runner, "host_arch", return_value="win64"),
            ):
                report = runner.doctor_report(sdk_variant="tracy")

        sdk_checks = [check for check in report["checks"] if check["name"] == "sdk"]
        self.assertEqual(sdk_checks[0]["status"], "warn")
        self.assertIn("release variant", sdk_checks[0]["detail"])
        self.assertIn(
            "rtx_remix_runtime@demo-runtime-70deb622-win64-debugoptimized",
            sdk_checks[0]["detail"],
        )
        self.assertIn("already in the local packman cache", sdk_checks[0]["detail"])
        self.assertTrue(sdk_checks[0]["packmanCached"])
        self.assertTrue(sdk_checks[0]["wouldFetch"])

    def _doctor_on_a_staged_release_sdk(self, argv: list[str]) -> dict[str, dict]:
        """Run `rtx doctor --json` with a release runtime staged; return its checks by name."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bench = root / "benchmark"
            project = root / "project"
            self._write_benchmark_world(bench, "DemoWorld", "simple")
            (bench / "config.json").write_text('{"world": {"name": "DemoWorld", "camera": "simple"}}', encoding="utf-8")
            (project / "packman").mkdir(parents=True)
            (project / "packman" / "remix-sdk.packman.xml").write_text(
                """<project toolsVersion="7.6">
  <dependency name="remix-sdk-win64" linkPath="../remix-runtime/win64">
    <package name="rtx_remix_runtime" version="demo-runtime-70deb622-win64-release" />
  </dependency>
</project>
""",
                encoding="utf-8",
            )
            sdk = project / "remix-runtime" / "win64"
            sdk.mkdir(parents=True)
            (sdk / "d3d9.dll").write_bytes(b"fake release dll")
            stdout = io.StringIO()
            with (
                patch.dict(os.environ, {"PM_PACKAGES_ROOT": str(root / "packman-repo")}),
                patch.object(runner.util, "BENCHMARK_DIR", bench),
                patch.object(runner.util, "ASSETS_DIR", bench),
                patch.object(runner.util, "PROJECT_DIR", project),
                patch.object(runner, "detect_java_home", return_value=root / "jdk"),
                patch.object(runner, "host_arch", return_value="win64"),
                redirect_stdout(stdout),
            ):
                cli.cmd_doctor(cli.build_parser().parse_args(["doctor", "--json", *argv]))
        return {check["name"]: check for check in json.loads(stdout.getvalue())["checks"]}

    def test_doctor_checks_the_release_runtime_unless_tracy_is_requested(self):
        checks = self._doctor_on_a_staged_release_sdk([])
        self.assertEqual(checks["sdk"]["status"], "ok")
        self.assertIn("release/win64 SDK is staged", checks["sdk"]["detail"])
        self.assertNotIn("tracy tools", checks)

        checks = self._doctor_on_a_staged_release_sdk(["--sdk-variant", "tracy"])
        self.assertEqual(checks["sdk"]["status"], "warn")
        self.assertIn("release variant (no Tracy symbols)", checks["sdk"]["detail"])
        self.assertIn("tracy tools", checks)

    def test_run_and_doctor_default_to_the_release_runtime(self):
        parser = cli.build_parser()
        self.assertEqual(parser.parse_args(["run"]).sdk_variant, "release")
        self.assertEqual(parser.parse_args(["doctor"]).sdk_variant, "release")
        self.assertEqual(inspect.signature(runner.doctor_report).parameters["sdk_variant"].default, "release")

    def _doctor_resource_pack_check(self, resource_pack: dict, *, stage_pack: str | None = None):
        """Run doctor_report with a resourcePack config and return its check."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bench = root / "benchmark"
            project = root / "project"
            self._write_benchmark_world(bench, "DemoWorld", "simple")
            # Explicit fixture selection keeps this check independent of defaults;
            # the stage name is pinned too, since config merges are deep.
            (bench / "config.json").write_text(
                json.dumps(
                    {
                        "world": {"name": "DemoWorld", "version": "", "camera": "simple"},
                        "resourcePack": {"stageName": "rtx-tools-art", **resource_pack},
                    }
                ),
                encoding="utf-8",
            )
            if stage_pack:
                pack = bench / "resourcepacks" / stage_pack
                pack.mkdir(parents=True)
                (pack / "pack.mcmeta").write_text("{}", encoding="utf-8")
            project.mkdir()

            with (
                patch.object(runner.util, "BENCHMARK_DIR", bench),
                patch.object(runner.util, "ASSETS_DIR", bench),
                patch.object(runner.util, "PROJECT_DIR", project),
                patch.object(runner, "detect_java_home", return_value=root / "jdk"),
                patch.object(runner, "host_arch", return_value="win64"),
                # A read-only doctor must never reach the network.
                patch.object(
                    runner,
                    "fetch_resource_pack",
                    side_effect=AssertionError("doctor must not fetch"),
                ),
            ):
                report = runner.doctor_report(sdk_variant="tracy")

        checks = [check for check in report["checks"] if check["name"] == "resource packs"]
        self.assertEqual(len(checks), 1)
        return checks[0]

    def test_doctor_reports_pinned_art_pack_in_enabled_list(self):
        check = self._doctor_resource_pack_check(
            {"mode": "pinned", "name": "texture", "version": "0.25.0"},
            stage_pack="texture_0.25.0",
        )

        self.assertEqual(check["status"], "ok")
        self.assertEqual(check["artPackMode"], "pinned")
        self.assertIn("rtx-tools-art", check["resourcePacks"])
        self.assertIn("art pack pinned texture@0.25.0", check["detail"])

    def test_doctor_warns_when_pinned_art_pack_is_not_staged(self):
        check = self._doctor_resource_pack_check(
            {"mode": "pinned", "name": "texture", "version": "0.25.0"},
        )

        self.assertEqual(check["status"], "warn")
        self.assertEqual(check["artPackMode"], "pinned")
        self.assertNotIn("rtx-tools-art", check["resourcePacks"])
        self.assertIn("is not staged", check["detail"])
        self.assertIn("a run would fetch it", check["detail"])

    def _doctor_with_nothing_staged(
        self, world: dict, *, local: dict | None = None, world_override: str | None = None
    ) -> tuple[dict[str, dict], int]:
        """Run `rtx doctor --json` with nothing staged; return its checks by name and its exit code."""
        if local is not None:
            runner.util.write_json(runner.util.SHARED_CONFIG_PATH.with_name("config.local.json"), local)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bench = root / "benchmark"
            bench.mkdir()
            (bench / "config.json").write_text(json.dumps({"world": world}), encoding="utf-8")
            argv = ["doctor", "--json", "--sdk-variant", "release"]
            if world_override:
                argv += ["--world", world_override]
            stdout = io.StringIO()
            with (
                patch.object(runner.util, "BENCHMARK_DIR", bench),
                patch.object(runner.util, "ASSETS_DIR", bench),
                patch.object(runner.util, "PROJECT_DIR", root),
                patch.object(runner, "detect_java_home", return_value=root / "jdk"),
                patch.object(runner, "host_arch", return_value="win64"),
                patch.object(runner, "fetch_world", side_effect=AssertionError("doctor must not fetch")),
                patch.object(runner, "fetch_resource_pack", side_effect=AssertionError("doctor must not fetch")),
                redirect_stdout(stdout),
            ):
                exit_code = cli.cmd_doctor(cli.build_parser().parse_args(argv))

        checks = json.loads(stdout.getvalue())["checks"]
        by_name = {check["name"]: check for check in checks}
        self.assertEqual(len(by_name), len(checks))
        return by_name, exit_code

    def _doctor_fixture_check(self, world: dict, *, world_override: str | None = None):
        return self._doctor_with_nothing_staged(world, world_override=world_override)[0]["fixture"]

    def test_doctor_warns_when_the_pinned_world_is_not_staged(self):
        checks, exit_code = self._doctor_with_nothing_staged(
            {"name": "DemoWorld", "version": "v1.2.3", "camera": "simple"}
        )
        check = checks["fixture"]

        self.assertEqual(check["status"], "warn")
        self.assertIn("DemoWorld@v1.2.3", check["detail"])
        self.assertIn("is not staged", check["detail"])
        self.assertIn("a run would fetch it", check["detail"])
        self.assertIn("tools\\rtx.bat world fetch", check["detail"])
        # The checks that need no staged world still run: the shipped art pin is only unstaged.
        self.assertEqual(checks["resource packs"]["status"], "warn")
        self.assertIn("is not staged", checks["resource packs"]["detail"])
        self.assertEqual(checks["screenshots"]["status"], "ok")
        self.assertEqual(exit_code, 0)

    def test_doctor_still_checks_the_art_pack_while_the_pinned_world_is_not_staged(self):
        checks, exit_code = self._doctor_with_nothing_staged(
            {"name": "DemoWorld", "version": "v1.2.3", "camera": "simple"},
            local={"resourcePack": {"mode": "local", "localPath": "C:/does/not/exist"}},
        )

        self.assertEqual(checks["fixture"]["status"], "warn")
        self.assertEqual(checks["resource packs"]["status"], "error")
        self.assertIn("localPath does not exist", checks["resource packs"]["detail"])
        self.assertEqual(exit_code, 1)

    def test_doctor_reports_a_missing_unpinned_world_as_an_error(self):
        for world, override in (
            ({"name": "DemoWorld", "version": "", "camera": "simple"}, None),
            ({"name": "DemoWorld", "version": "v1.2.3", "camera": "simple"}, "OtherWorld"),
        ):
            with self.subTest(world=world, override=override):
                check = self._doctor_fixture_check(world, world_override=override)

                self.assertEqual(check["status"], "error")
                self.assertIn("does not contain a Minecraft save", check["detail"])
                self.assertNotIn("world fetch", check["detail"])

    def test_doctor_reports_art_pack_none_without_extra_pack(self):
        check = self._doctor_resource_pack_check({"mode": "none"})

        self.assertEqual(check["status"], "ok")
        self.assertEqual(check["artPackMode"], "none")
        self.assertEqual(check["resourcePacks"], [])
        self.assertIn("enabled=none", check["detail"])
        self.assertIn("art pack none", check["detail"])

    def test_doctor_json_keeps_legacy_pack_diagnostic_on_stderr(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            assets = root / "assets"
            self._write_benchmark_world(assets, "fixture-world", "simple")
            config = {
                "world": "fixture-world",
                "camera": "simple",
                "resourcePacks": ["rtx-remix"],
                "resourcePack": {"mode": "none"},
            }
            stdout = io.StringIO()
            stderr = io.StringIO()
            with (
                patch.object(runner.util, "ASSETS_DIR", assets),
                patch.object(runner.util, "PROJECT_DIR", root),
                patch.object(runner, "load_config", return_value=config),
                patch.object(runner, "detect_java_home", return_value=None),
                patch.object(runner, "java_runtime_arch", return_value="amd64"),
                patch.object(runner, "windows_process_arch", return_value="AMD64"),
                patch.object(runner, "host_arch", return_value="win64"),
                redirect_stdout(stdout),
                redirect_stderr(stderr),
            ):
                cli.cmd_doctor(cli.build_parser().parse_args(["doctor", "--json"]))

            report = json.loads(stdout.getvalue())
            pack_check = next(check for check in report["checks"] if check["name"] == "resource packs")
            self.assertEqual(pack_check["status"], "ok")
            self.assertEqual(pack_check["resourcePacks"], [])
            self.assertEqual(stderr.getvalue().count("retired baseline"), 1)

    def test_packman_cache_probe_reports_missing_package(self):
        with tempfile.TemporaryDirectory() as tmp:
            with patch.dict(os.environ, {"PM_PACKAGES_ROOT": tmp}):
                probe = runner.packman_cache_probe("rtx_remix_runtime", "demo-runtime-70deb622-win64-debugoptimized")

        self.assertFalse(probe["packmanCached"])
        self.assertEqual(
            Path(probe["packmanCacheDir"]),
            Path(tmp) / "chk" / "rtx_remix_runtime" / "demo-runtime-70deb622-win64-debugoptimized",
        )

    def test_packman_cache_probe_requires_package_identity(self):
        self.assertEqual(runner.packman_cache_probe("", "some-version"), {})
        self.assertEqual(runner.packman_cache_probe("rtx_remix_runtime", ""), {})

    def test_save_run_artifacts_copies_png_screenshots_next_to_result(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            raw = root / "raw"
            result_dir = root / "results"
            raw.mkdir()
            result_file = raw / "benchmark.json"
            screenshot = raw / "abc123_desktop.png"
            other_screenshot = raw / "mc26_frame_0042.png"
            result_file.write_text("{}", encoding="utf-8")
            screenshot.write_bytes(b"png")
            other_screenshot.write_bytes(b"png2")

            per_run = runner.save_run_artifacts({"resultFile": result_file}, result_dir, 1, None)

            self.assertTrue((per_run / "benchmark.json").exists())
            self.assertEqual((per_run / "abc123_desktop.png").read_bytes(), b"png")
            self.assertEqual((per_run / "mc26_frame_0042.png").read_bytes(), b"png2")

    def test_save_run_artifacts_copies_ingame_screenshot_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            benchmark_dir = root / "benchmark"
            raw_results = benchmark_dir / "results"
            raw_screenshots = benchmark_dir / "screenshots"
            result_dir = root / "results"
            raw_results.mkdir(parents=True)
            raw_screenshots.mkdir()
            result_file = raw_results / "benchmark.json"
            result_file.write_text("{}", encoding="utf-8")
            (raw_screenshots / "frame_0000.png").write_bytes(b"frame")

            per_run = runner.save_run_artifacts({"resultFile": result_file}, result_dir, 1, None)

            self.assertEqual((per_run / "frame_0000.png").read_bytes(), b"frame")

    def test_desktop_screenshot_timeout_is_warning_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "shot.png"
            with (
                patch.object(runner.os, "name", "nt"),
                patch.object(runner.util, "run", side_effect=RuntimeError("command timed out")) as run,
                patch("builtins.print") as print_fn,
            ):
                runner.capture_desktop_screenshot(output, 123)

        self.assertEqual(run.call_args.kwargs["timeout"], 15)
        print_fn.assert_called()

    def test_artifact_manifest_lists_per_run_screenshots(self):
        with tempfile.TemporaryDirectory() as tmp:
            result_dir = Path(tmp)
            per_run = result_dir / "run_1"
            per_run.mkdir()
            (per_run / "benchmark.json").write_text("{}", encoding="utf-8")
            (per_run / "abc123_desktop.png").write_bytes(b"png")
            (per_run / "mc26_frame_0042.png").write_bytes(b"png2")

            manifest = runner.artifact_manifest(result_dir)

        self.assertEqual(
            manifest["runs"][0]["screenshots"],
            ["run_1/abc123_desktop.png", "run_1/mc26_frame_0042.png"],
        )

    def test_invalid_exit_mode_falls_back_to_halt(self):
        self.assertEqual(runner.profiling_exit_mode({"profiling": {"exitMode": "systemExit"}}), "systemExit")
        self.assertEqual(runner.profiling_exit_mode({"profiling": {"exitMode": "banana"}}), "halt")

    def test_classify_run_failure_detects_device_lost(self):
        cases = (
            ("DxvkSubmissionQueue: Failed to sync fence: VK_ERROR_DEVICE_LOST", "Timeout", "DeviceLost"),
            (
                "[main/ERROR] java.lang.UnsatisfiedLinkError: RemixBridge.initialize(...)V\n",
                "Timeout",
                "NativeLinkError",
            ),
            ("---- Minecraft Crash Report ----\n// Oops.\n", "Timeout", "MinecraftCrash"),
            (
                "[Render thread/ERROR] (Benchmark) Cannot start benchmark: invalid camera path\n",
                "",
                "BenchmarkStartupRejected",
            ),
            ("ordinary timeout log", "Timeout", "Timeout"),
        )
        for log, fallback, expected in cases:
            with self.subTest(log=log, fallback=fallback):
                self.assertEqual(runner.classify_run_failure(log, fallback), expected)

    def test_classify_run_failure_detects_native_heap_corruption_ntstatus(self):
        log = "Process 'java.exe' finished with non-zero exit value -1073740940 (NTSTATUS 0xC0000374)"

        self.assertEqual(runner.classify_run_failure(log, "ProcessExited"), "NativeHeapCorruption")
        self.assertEqual(
            runner.native_exit_details(log),
            {"processExitCode": -1073740940, "ntstatus": "0xC0000374"},
        )

    def test_classify_run_failure_preserves_unknown_ntstatus(self):
        log = "Process 'java.exe' finished with non-zero exit value -1 (NTSTATUS 0xDEADBEEF)"

        self.assertEqual(runner.classify_run_failure(log, "ProcessExited"), "NativeCrash:0xDEADBEEF")

    def test_native_exit_details_derives_ntstatus_from_decimal_exit_code(self):
        # Gradle reports native JVM deaths as decimal only; nothing in the real
        # pipeline prints a literal "NTSTATUS 0x..." string.
        log = "Process 'command 'java.exe'' finished with non-zero exit value -1073740940"
        details = runner.native_exit_details(log)

        self.assertEqual(details, {"processExitCode": -1073740940, "ntstatus": "0xC0000374"})
        self.assertEqual(runner.classify_run_failure(log, ""), "NativeHeapCorruption")

    def test_native_exit_details_does_not_invent_ntstatus_for_ordinary_exit_codes(self):
        details = runner.native_exit_details("finished with non-zero exit value 1")

        self.assertEqual(details, {"processExitCode": 1})

    def test_native_exit_details_takes_ntstatus_from_the_game_exit_code(self):
        # A launcher that keeps the game's output to itself still hands over the JVM's exit code, unsigned.
        cases = (
            (0xC0000005, "AccessViolation"),
            (0xC0000374, "NativeHeapCorruption"),
            (0xC0000409, "NativeCrash:0xC0000409"),
        )
        for exit_code, reason in cases:
            with self.subTest(exit_code=exit_code):
                self.assertEqual(runner.native_exit_details("", exit_code), {"ntstatus": f"0x{exit_code:08X}"})
                self.assertEqual(runner.classify_run_failure("", "ProcessExited", exit_code), reason)

    def test_native_exit_details_prefers_the_logged_status_to_the_exit_code(self):
        log = "Process 'java.exe' finished with non-zero exit value -1073740940"

        self.assertEqual(runner.native_exit_details(log, 0xC0000005)["ntstatus"], "0xC0000374")
        self.assertEqual(runner.native_exit_details("NTSTATUS 0xC0000409", 0xC0000005)["ntstatus"], "0xC0000409")

    def test_exit_codes_that_are_not_windows_error_statuses_carry_no_ntstatus(self):
        # Minecraft exits with -1 after writing a crash report and -2 when it cannot; -1 is also the code of a
        # process that could not be opened to read one.
        for exit_code in (0, 1, 0xFFFFFFFF, 0xFFFFFFFE, -1, 0xE06D7363):
            with self.subTest(exit_code=exit_code):
                self.assertEqual(runner.native_exit_details("", exit_code), {})
        self.assertEqual(
            runner.classify_run_failure("---- Minecraft Crash Report ----", "ProcessExited", 0xFFFFFFFF),
            "MinecraftCrash",
        )

    def test_build_failure_details_records_the_ntstatus_of_the_launcher_exit_code(self):
        details = runner.build_failure_details(
            "AccessViolation", "", benchmark_status=None, launcher_exit_code=0xC0000005
        )

        self.assertEqual(details["ntstatus"], "0xC0000005")
        self.assertEqual(details["launcherExitCode"], 0xC0000005)

    def test_failure_warrants_crash_dump_wait_only_for_native_evidence(self):
        # Soft failures force-kill the launcher ourselves; no WER dump can
        # appear, so waiting the full crash-dump deadline is dead time.
        self.assertFalse(runner.failure_warrants_crash_dump_wait("Timeout", ""))
        self.assertFalse(runner.failure_warrants_crash_dump_wait("StartupHang", ""))
        self.assertFalse(runner.failure_warrants_crash_dump_wait("Stalled:NoHeartbeat", ""))
        self.assertFalse(runner.failure_warrants_crash_dump_wait("NativeLinkError", ""))
        self.assertTrue(runner.failure_warrants_crash_dump_wait("NativeHeapCorruption", ""))
        self.assertTrue(runner.failure_warrants_crash_dump_wait("NativeCrash:0xC0000409", ""))
        self.assertTrue(runner.failure_warrants_crash_dump_wait("JvmCrash", ""))
        # A silent nonzero exit may be an unclassified native crash.
        self.assertTrue(
            runner.failure_warrants_crash_dump_wait("ProcessExited", "finished with non-zero exit value -1073741819")
        )
        self.assertFalse(runner.failure_warrants_crash_dump_wait("ProcessExited", "no exit line"))

    def test_continue_after_run_failure_defaults_to_ci_and_honors_override(self):
        self.assertTrue(runner.continue_after_run_failure({}, ci=True))
        self.assertFalse(runner.continue_after_run_failure({}, ci=False))
        with patch.dict(os.environ, {runner.CONTINUE_AFTER_FAILURE_ENV: "0"}):
            self.assertFalse(runner.continue_after_run_failure({}, ci=True))

    def test_benchmark_status_signature_tracks_progress_fields(self):
        status = {
            "sequence": 42,
            "state": "RECORDING",
            "phase": "recording",
            "currentFrame": 128,
            "pendingFrameTimeIndex": 127,
        }

        # The monotonic "sequence" write counter must NOT be part of the
        # signature: it increments on every heartbeat write even when the run
        # is frozen on the same frame, which would mask a stall.
        self.assertEqual(
            runner.benchmark_status_signature(status),
            ("RECORDING", "recording", 128, 127),
        )

    def test_benchmark_status_signature_ignores_sequence_only_changes(self):
        base = {
            "sequence": 1,
            "state": "RECORDING",
            "phase": "recording",
            "currentFrame": 128,
            "pendingFrameTimeIndex": 127,
        }
        bumped = {**base, "sequence": 2}

        # A heartbeat that only advanced the write counter (no real progress)
        # must produce an identical signature so stall detection can fire.
        self.assertEqual(
            runner.benchmark_status_signature(base),
            runner.benchmark_status_signature(bumped),
        )

    def test_benchmark_heartbeat_stall_seconds_can_be_disabled(self):
        self.assertEqual(
            runner.benchmark_heartbeat_stall_seconds({"profiling": {"benchmarkHeartbeatStallSeconds": -1}}),
            0,
        )

    def test_save_failed_run_artifacts_keeps_status_and_diagnostics(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            status = root / runner.BENCHMARK_STATUS_FILE
            status.write_text('{"state":"RECORDING"}\n', encoding="utf-8")
            diagnostics = root / "diagnostics-src"
            diagnostics.mkdir()
            (diagnostics / "failure.json").write_text("{}\n", encoding="utf-8")
            preview = root / "preview-src"
            preview.mkdir()
            (preview / "java-1.threads.txt").write_text("stack\n", encoding="utf-8")
            crashes = root / "crashes-src"
            crashes.mkdir()
            (crashes / "java.exe.1234.dmp").write_bytes(b"dump")
            log = root / "run.log"
            log.write_text("failed\n", encoding="utf-8")
            result_dir = root / "result"
            result = {
                "statusOut": status,
                "diagnosticsDir": diagnostics,
                "previewDiagnosticsDir": preview,
                "crashDir": crashes,
                "runLog": log,
                "failureReason": "Stalled:RECORDING/recording frame 1/2",
                "failureDetails": {"reason": "NativeHeapCorruption", "ntstatus": "0xC0000374"},
            }

            per_run = runner.save_run_artifacts(result, result_dir, 1, tools=None)

            self.assertTrue((per_run / runner.BENCHMARK_STATUS_FILE).exists())
            self.assertTrue((per_run / "diagnostics" / "failure.json").exists())
            self.assertTrue((per_run / runner.STALL_PREVIEW_DIR / "java-1.threads.txt").exists())
            self.assertEqual((per_run / "crash" / "java.exe.1234.dmp").read_bytes(), b"dump")
            self.assertEqual((per_run / "failure.txt").read_text(encoding="utf-8").strip(), result["failureReason"])
            details = runner.util.read_json(per_run / "failure.json")
            self.assertEqual(details["run"], 1)
            self.assertEqual(details["ntstatus"], "0xC0000374")
            self.assertEqual(details["crashDumps"], ["crash/java.exe.1234.dmp"])
            # The in-memory details feed manifest_failure_entry later; they
            # must carry the same artifact-relative paths as failure.json.
            self.assertEqual(result["failureDetails"]["crashDumps"], ["crash/java.exe.1234.dmp"])

    def test_capture_hang_diagnostics_preview_is_light_and_lands_in_its_own_dir(self):
        calls: list[tuple[list[str], Path]] = []
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with (
                patch.object(runner, "windows_process_snapshot", return_value=("[]", {5452})),
                patch.object(runner, "capture_desktop_screenshot"),
                patch.object(runner, "java_tool_executable", return_value="jcmd"),
                patch.object(runner, "read_text_retry", return_value=""),
                patch.object(runner, "select_java_diagnostic_pids", return_value=[5452]),
                patch.object(
                    runner, "run_diagnostic_command", side_effect=lambda cmd, out, **kw: calls.append((cmd, out))
                ),
                patch.object(runner.time, "sleep"),
            ):
                path = runner.capture_hang_diagnostics(
                    # Only proc.pid is read, so a pid-only stand-in is enough.
                    cast(client.GameProcess, SimpleNamespace(pid=5452)),
                    root,
                    reason="StallPreview:WARMUP/warmup-start frame 0/600",
                    status={"state": "WARMUP"},
                    log_text="dxvk went quiet here\n",
                    subdir=runner.STALL_PREVIEW_DIR,
                    thread_dumps=2,
                    include_jfr=False,
                )

            self.assertEqual(path, root / runner.STALL_PREVIEW_DIR)
            # Two spaced dumps show whether the stuck thread is moving at all.
            self.assertEqual(
                [out.name for cmd, out in calls if "Thread.print" in cmd],
                ["java-5452.threads.txt", "java-5452.threads-2.txt"],
            )
            # JFR.dump is the slow one and the poll loop is blocked meanwhile.
            self.assertFalse(any("JFR.dump" in cmd for cmd, _ in calls))
            details = runner.util.read_json(path / "failure.json")
            self.assertEqual(details["logTail"], "dxvk went quiet here")
            self.assertTrue(details["reason"].startswith("StallPreview:"))

    def test_save_run_artifacts_keeps_stall_preview_for_a_recovered_run(self):
        # A run that stalls past the preview threshold and then recovers still
        # passes. The snapshot is the only record that the stall happened, so it
        # has to survive into the artifacts and the manifest.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            preview = root / "preview-src"
            preview.mkdir()
            (preview / "java-1.threads.txt").write_text("stack\n", encoding="utf-8")
            result_dir = root / "result"

            per_run = runner.save_run_artifacts({"previewDiagnosticsDir": preview}, result_dir, 2, tools=None)

            self.assertTrue((per_run / runner.STALL_PREVIEW_DIR / "java-1.threads.txt").exists())
            self.assertFalse((per_run / "failure.txt").exists())
            entry = runner.artifact_manifest(result_dir)["runs"][0]
            self.assertEqual(entry["diagnosticsPreview"], f"run_2/{runner.STALL_PREVIEW_DIR}")

    def test_packman_output_replaces_undecodable_bytes(self):
        script = "import sys; sys.stdout.buffer.write(b'\\x81\\n'); sys.stderr.buffer.write(b'\\x81\\n')"
        with (
            patch.object(runner, "packman_cmd_path", return_value=Path(sys.executable)),
            patch.object(runner.locale, "getencoding", return_value="cp1252"),
        ):
            result = runner.run_packman(["-c", script], capture=True)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "\ufffd\n")
        self.assertEqual(result.stderr, "\ufffd\n")

    def test_available_resource_pack_versions_scans_staged_dirs(self):
        # Both v-prefixed (our convention) and bare (art-team) version dirs.
        with tempfile.TemporaryDirectory() as tmp:
            packs = Path(tmp) / "resourcepacks"
            for dirname in ("lp_v0.1.0", "lp_0.2.0", "lp", "other_v0.9.0"):
                (packs / dirname).mkdir(parents=True)
            with patch.object(runner.util, "ASSETS_DIR", Path(tmp)):
                versions = runner.available_resource_pack_versions("lp")

        self.assertEqual(versions, ["v0.1.0", "0.2.0"])

    def test_resource_pack_versions_report_marks_pin_and_staged_versions(self):
        config = {"resourcePack": {"mode": "pinned", "name": "lp", "version": "v0.2.0"}}
        with ExitStack() as stack:
            stack.enter_context(patch.object(runner, "load_config", return_value=config))
            stack.enter_context(
                patch.object(runner, "available_resource_pack_versions", return_value=["v0.1.0", "v0.2.0"])
            )
            packman = stack.enter_context(patch.object(runner, "run_packman"))
            report = runner.resource_pack_versions_report("lp")

        packman.assert_not_called()
        self.assertEqual(report["name"], "lp")
        self.assertEqual(
            report["versions"],
            [
                {"version": "v0.2.0", "staged": True, "pinned": True},
                {"version": "v0.1.0", "staged": True, "pinned": False},
            ],
        )

    def test_world_versions_report_includes_unstaged_pin_without_remote_access(self):
        config = {"world": {"name": "DemoWorld", "version": "v0.6.0"}}
        with ExitStack() as stack:
            stack.enter_context(patch.object(runner, "load_config", return_value=config))
            stack.enter_context(patch.object(runner, "available_world_versions", return_value=["v0.5.0", "v0.4.0"]))
            packman = stack.enter_context(patch.object(runner, "run_packman"))
            report = runner.world_versions_report("DemoWorld")

        packman.assert_not_called()
        self.assertEqual(
            report["versions"],
            [
                {"version": "v0.6.0", "staged": False, "pinned": True},
                {"version": "v0.5.0", "staged": True, "pinned": False},
                {"version": "v0.4.0", "staged": True, "pinned": False},
            ],
        )

    def test_collect_windows_crash_dumps_copies_only_files_created_after_snapshot(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "wer"
            output = root / "artifacts"
            source.mkdir()
            old_dump = source / "java.exe.old.dmp"
            old_dump.write_bytes(b"old")
            baseline = runner.crash_dump_snapshot(source)
            started_ns = runner.time.time_ns()
            new_dump = source / "java.exe.new.dmp"
            new_dump.write_bytes(b"new")

            copied = runner.collect_windows_crash_dumps(
                output,
                baseline,
                started_ns=started_ns,
                source_dir=source,
                wait_seconds=0,
            )

            self.assertEqual([path.name for path in copied], ["java.exe.new.dmp"])
            self.assertEqual((output / "java.exe.new.dmp").read_bytes(), b"new")
            self.assertFalse((output / "java.exe.old.dmp").exists())

    def test_write_failure_metrics_without_aggregate(self):
        outcome = {
            "status": "failed",
            "requestedRuns": 3,
            "attemptedRuns": 2,
            "completedRuns": 1,
            "failedRuns": 1,
            "failures": [{"reason": "NativeHeapCorruption"}],
        }
        with tempfile.TemporaryDirectory() as tmp:
            project = Path(tmp)
            with patch.object(runner.util, "PROJECT_DIR", project):
                runner.write_metrics_file(None, outcome)
            metrics = (project / "metrics.txt").read_text(encoding="utf-8")

        self.assertIn("benchmark_success 0", metrics)
        self.assertIn("benchmark_runs_completed 1", metrics)
        self.assertIn("benchmark_native_crashes 1", metrics)
        self.assertNotIn("frame_time_avg_ms", metrics)

    def test_native_crash_metric_excludes_deployment_failures(self):
        # NativeLinkError (UnsatisfiedLinkError) is a JNI/ABI deployment
        # mismatch, not a native crash; it must not inflate the crash metric.
        outcome = {
            "status": "failed",
            "requestedRuns": 4,
            "attemptedRuns": 4,
            "completedRuns": 0,
            "failedRuns": 4,
            "failures": [
                {"reason": "NativeLinkError"},
                {"reason": "NativeCrash:0xC0000409"},
                {"reason": "NativeHeapCorruption"},
                {"reason": "Timeout"},
            ],
        }
        with tempfile.TemporaryDirectory() as tmp:
            project = Path(tmp)
            with patch.object(runner.util, "PROJECT_DIR", project):
                runner.write_metrics_file(None, outcome)
            metrics = (project / "metrics.txt").read_text(encoding="utf-8")

        self.assertIn("benchmark_native_crashes 2", metrics)

    def test_sdk_metadata_records_dll_identity(self):
        content = b"fake dll ___tracy_emit_zone_begin_alloc"
        with tempfile.TemporaryDirectory() as tmp:
            dll = Path(tmp) / "remix-runtime" / "win64" / "d3d9.dll"
            dll.parent.mkdir(parents=True)
            dll.write_bytes(content)

            metadata = runner.sdk_metadata(dll, "tracy")

        self.assertEqual(metadata["dllSizeBytes"], len(content))
        self.assertEqual(metadata["contract"], "tracy-debug-sdk")
        self.assertEqual(metadata["dllSha256"], hashlib.sha256(content).hexdigest())
        self.assertEqual(metadata["dllSha256Short"], hashlib.sha256(content).hexdigest()[:12])
        self.assertTrue(metadata["tracyEnabled"])
        self.assertIn("d3d9:", metadata["label"])

    def test_sdk_metadata_marks_release_contract(self):
        with tempfile.TemporaryDirectory() as tmp:
            dll = Path(tmp) / "remix-runtime" / "arm64" / "d3d9.dll"
            dll.parent.mkdir(parents=True)
            dll.write_bytes(b"fake release dll")

            metadata = runner.sdk_metadata(dll, "release")

        self.assertEqual(metadata["contract"], "release-sdk")
        self.assertEqual(metadata["variant"], "release")
        self.assertFalse(metadata["tracyEnabled"])

    def test_sdk_metadata_parses_local_source_sentinel(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "dxvk-remix" / "_output"
            source.mkdir(parents=True)
            dll = Path(tmp) / "remix-runtime" / "win64" / "d3d9.dll"
            dll.parent.mkdir(parents=True)
            dll.write_bytes(b"fake dll ___tracy_emit_zone_begin_alloc")
            (dll.parent / ".local-sdk-source").write_text(
                f"source: {source}\nstaged_at_utc: 2026-05-05T07:56:37Z\nstaged_by: developer\n",
                encoding="utf-8",
            )

            metadata = runner.sdk_metadata(dll, "tracy")

        self.assertEqual(metadata["sourcePath"], str(source))
        self.assertEqual(metadata["stagedAtUtc"], "2026-05-05T07:56:37Z")
        self.assertEqual(metadata["stagedBy"], "developer")
        self.assertIn("_output", metadata["label"])

    def test_fetch_sdk_uses_local_tracy_override_when_available(self):
        cases = (
            ("tracy", "win64", b"fake dll ___tracy_emit_zone_begin_alloc"),
            ("release", "arm64", b"fake local release dll"),
        )
        for variant, arch, dll_bytes in cases:
            with self.subTest(variant=variant, arch=arch), tempfile.TemporaryDirectory() as tmp:
                project = Path(tmp)
                sdk = project / "remix-runtime" / arch
                sdk.mkdir(parents=True)
                dll = sdk / "d3d9.dll"
                dll.write_bytes(dll_bytes)
                source = project / "dxvk-remix" / "_output"
                source.mkdir(parents=True)
                (source / "d3d9.dll").write_bytes(dll.read_bytes())
                (sdk / ".local-sdk-source").write_text(f"source: {source}", encoding="utf-8")

                with (
                    patch.object(runner.util, "PROJECT_DIR", project),
                    patch.object(runner.tracy, "fetch_sdk") as fetch_sdk,
                    patch.object(runner.runtime_sdk, "stage_packman_sdk") as stage_packman_sdk,
                ):
                    self.assertEqual(runner.fetch_sdk(variant, arch), dll)

                fetch_sdk.assert_not_called()
                stage_packman_sdk.assert_not_called()

    def test_generate_profile_data_marks_tracy_optional_when_tools_are_absent(self):
        with tempfile.TemporaryDirectory() as tmp:
            per_run = Path(tmp) / "run_1"
            per_run.mkdir()
            (per_run / "benchmark.json").write_text("{}", encoding="utf-8")
            with patch.object(runner.util, "run") as run:
                runner.generate_profile_data(per_run, 1, tracy_required=False)

        args = run.call_args.args[0]
        self.assertEqual([sys.executable, "-m", "rtx_tools.profiler.generate_profile_data"], args[:3])
        self.assertIn("--tracy-optional", args)

    def test_export_tracy_csv_uses_tab_separator(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            tracy = root / "profile.tracy"
            tracy.write_bytes(b"trace")
            csv = root / "profile.csv"
            tools = {"csvexport": root / "csvexport.exe"}
            tools["csvexport"].write_text("", encoding="utf-8")

            def fake_run(args, stdout, stderr, cwd):
                stdout.write("name\tsrc_file\tsrc_line\ttotal_ns\n")
                return SimpleNamespace(returncode=0)

            with patch.object(subprocess, "run", side_effect=fake_run) as run:
                runner.export_tracy_csv(tools, tracy, csv)

        args = run.call_args.args[0]
        self.assertIn("-s", args)
        self.assertIn("\t", args)

    def test_fetch_sdk_rejects_local_non_tracy_override_for_tracy_variant(self):
        with tempfile.TemporaryDirectory() as tmp:
            project = Path(tmp)
            sdk = project / "remix-runtime" / "win64"
            sdk.mkdir(parents=True)
            (sdk / "d3d9.dll").write_bytes(b"fake release dll")
            source = project / "dxvk-remix" / "_output"
            source.mkdir(parents=True)
            (source / "d3d9.dll").write_bytes(b"fake release dll")
            (sdk / ".local-sdk-source").write_text(f"source: {source}", encoding="utf-8")

            with (
                patch.object(runner.util, "PROJECT_DIR", project),
                patch.object(runner.tracy, "fetch_sdk") as fetch_sdk,
            ):
                with self.assertRaisesRegex(RuntimeError, "not Tracy-enabled"):
                    runner.fetch_sdk("tracy", "win64")

        fetch_sdk.assert_not_called()

    def _write_benchmark_world(self, assets_dir: Path, world: str, camera: str | None) -> None:
        world_dir = assets_dir / "worlds" / world
        (world_dir / "world").mkdir(parents=True)
        (world_dir / "world" / "level.dat").write_text("world", encoding="utf-8")
        (world_dir / "cameras").mkdir()
        if camera:
            (world_dir / "cameras" / f"{camera}.json").write_text(VALID_CAMERA_PATH_JSON, encoding="utf-8")

    def test_degenerate_frame_report_flags_loading_screen_frames(self):
        # Frames rendered under the loading overlay cost almost nothing; more
        # than 1% of them invalidates the run (the arm64 +24% artifact had 402
        # of 3861 such frames).
        real = [30_000_000] * 990  # 30ms frames, ns
        cheap = [1_500_000] * 30  # 1.5ms frames - no real path-traced frame is this cheap
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "benchmark.json"
            runner.util.write_json(path, {"metadata": {}, "perFrame": cheap + real})
            message = runner.degenerate_frame_report(path)

        self.assertIn("30 of 1020 frames", message)
        self.assertIn("loading screen", message)

    def test_degenerate_frame_report_tolerates_isolated_cheap_frames(self):
        frames = [30_000_000] * 995 + [1_500_000] * 5  # 0.5% cheap: below the 1% gate
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "benchmark.json"
            runner.util.write_json(path, {"metadata": {}, "perFrame": frames})

            self.assertEqual(runner.degenerate_frame_report(path), "")

    def test_degenerate_frame_report_flags_near_zero_frames_on_a_fast_machine(self):
        # 8ms real frames are 125fps, well past the old cap-derived floor of
        # 3.65ms. The floor must not scale with frame rate, or a fast run would
        # start reporting its own good frames as contamination.
        frames = [500_000] * 50 + [8_000_000] * 950  # 0.5ms frames
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "benchmark.json"
            runner.util.write_json(path, {"metadata": {}, "perFrame": frames})
            message = runner.degenerate_frame_report(path)

        self.assertIn("50 of 1000 frames", message)

    def test_degenerate_frame_report_ignores_missing_per_frame_data(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "benchmark.json"
            runner.util.write_json(path, {"metadata": {}})

            self.assertEqual(runner.degenerate_frame_report(path), "")

    def test_missing_world_hint_works_without_a_world_flag(self):
        with self.assertRaises(RuntimeError) as raised:
            runner.resolve_world_name({})
        self.assertIn("world.name", str(raised.exception))
        self.assertIn("tools/config.local.json", str(raised.exception))
        self.assertIn("world import", str(raised.exception))
        self.assertNotIn("--world", str(raised.exception))

    def test_camera_list_offers_import_and_only_configured_fetch(self):
        with tempfile.TemporaryDirectory() as tmp:
            for remote in ("", "demo-worlds"):
                for version in ("", "v0.6.0"):
                    with self.subTest(remote=remote, version=version):
                        config = {"world": {"remote": remote}}
                        args = ["camera", "list", "DemoWorld"]
                        if version:
                            args += ["--version", version]
                        out = io.StringIO()
                        with (
                            patch.object(runner.util, "ASSETS_DIR", Path(tmp)),
                            patch.object(runner, "load_config", return_value=config),
                            redirect_stdout(out),
                        ):
                            self.assertEqual(cli.main(args), 1)
                        name = f"DemoWorld_{version}" if version else "DemoWorld"
                        self.assertIn(f"world import <save-path> --name {name}", out.getvalue())
                        self.assertEqual("world fetch" in out.getvalue(), bool(remote))

    def test_camera_report_lists_staged_cameras_with_default(self):
        config = {"world": {"name": "DemoWorld", "version": "v0.6.0", "camera": "flythrough"}}
        with tempfile.TemporaryDirectory() as tmp:
            assets = Path(tmp)
            self._write_benchmark_world(assets, "DemoWorld_v0.6.0", "flythrough")
            (assets / "worlds" / "DemoWorld_v0.6.0" / "cameras" / "orbit.json").write_text("{}", encoding="utf-8")
            with (
                patch.object(runner.util, "ASSETS_DIR", assets),
                patch.object(runner, "load_config", return_value=config),
            ):
                report = runner.camera_report("DemoWorld")

        self.assertEqual(
            report,
            {
                "world": "DemoWorld",
                "version": "v0.6.0",
                "staged": True,
                "cameras": ["flythrough", "orbit"],
                "defaultCamera": "flythrough",
            },
        )

    def test_camera_report_flags_unstaged_world_version(self):
        # Cameras live inside the world package; an unfetched version has no
        # local camera files, so the report says staged=false instead of
        # raising - UIs offer a fetch.
        config = {"world": {"name": "DemoWorld", "version": "v0.6.0", "camera": "flythrough"}}
        with tempfile.TemporaryDirectory() as tmp:
            assets = Path(tmp)
            self._write_benchmark_world(assets, "DemoWorld_v0.6.0", "flythrough")
            with (
                patch.object(runner.util, "ASSETS_DIR", assets),
                patch.object(runner, "load_config", return_value=config),
            ):
                report = runner.camera_report("DemoWorld", "v0.5.0")

        self.assertEqual(report["version"], "v0.5.0")
        self.assertFalse(report["staged"])
        self.assertEqual(report["cameras"], [])

    def test_attach_machine_health_records_gpu_telemetry_and_off_spec_reasons(self):
        recording_in_p2 = {
            "phase": "RECORDING",
            "pstate": "P2",
            "graphicsClockMhz": 1800,
            "maxGraphicsClockMhz": 2505,
            "powerDrawW": 100.0,
            "powerLimitW": 300.0,
            "temperatureC": 50,
            "eventReasons": 0,
        }
        machine_info: dict[str, Any] = {
            "hostname": "BENCHMARK-HOST-01",
            "logicalCores": 32,
            "power": {"activeScheme": "High performance"},
        }
        expectations = {
            "RTXREMIX_EXPECT_POWER_PLAN": "High performance",
            "RTXREMIX_EXPECT_LOGICAL_PROCESSORS": "32",
        }
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, expectations):
            result_dir = Path(tmp)
            (result_dir / "run_1").mkdir()
            (result_dir / "run_1" / "gpu_samples.json").write_text(
                json.dumps({"schemaVersion": 1, "samples": [recording_in_p2]}), encoding="utf-8"
            )

            reasons = runner.attach_machine_health(machine_info, result_dir)

        self.assertEqual(machine_info["gpuTelemetry"]["recordingSamples"], 1)
        self.assertEqual(machine_info["health"]["spec"]["logicalProcessors"], 32)
        self.assertEqual(reasons, ["GPU spent 0% of the recording in P0, expected at least 90%"])
        self.assertEqual(machine_info["health"]["reasons"], reasons)

    def test_attach_machine_health_without_expectations_leaves_machine_unblocked(self):
        machine_info: dict[str, Any] = {
            "hostname": "workstation-01",
            "logicalCores": 12,
            "power": {"activeScheme": "Balanced"},
        }
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, {}, clear=True):
            reasons = runner.attach_machine_health(machine_info, Path(tmp))

        self.assertEqual(reasons, [])
        self.assertEqual(machine_info["health"], {"spec": None, "reasons": []})
        self.assertNotIn("gpuTelemetry", machine_info)

    def test_artifact_manifest_lists_gpu_samples(self):
        with tempfile.TemporaryDirectory() as tmp:
            per_run = Path(tmp) / "run_1"
            per_run.mkdir()
            (per_run / "benchmark.json").write_text("{}", encoding="utf-8")
            (per_run / "gpu_samples.json").write_text("{}", encoding="utf-8")

            manifest = runner.artifact_manifest(Path(tmp))

        self.assertEqual(manifest["runs"][0]["gpuSamples"], "run_1/gpu_samples.json")

    def test_save_run_artifacts_moves_gpu_samples_into_the_run_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            result_dir = Path(tmp)
            samples = result_dir / "run1-gpu_samples.json"
            samples.write_text('{"schemaVersion": 1, "samples": []}', encoding="utf-8")

            per_run = runner.save_run_artifacts({"gpuSamplesOut": samples}, result_dir, 1, tools=None)

            self.assertTrue((per_run / "gpu_samples.json").exists())
            self.assertFalse(samples.exists())


class FailFastTests(unittest.TestCase):
    def test_benchmark_startup_stall_seconds_honors_override(self):
        self.assertEqual(
            runner.benchmark_startup_stall_seconds({"profiling": {"benchmarkStartupStallSeconds": 200}}), 200
        )
        # Negative disables it.
        self.assertEqual(runner.benchmark_startup_stall_seconds({"profiling": {"benchmarkStartupStallSeconds": -5}}), 0)

    def test_startup_output_stalled_fires_when_silent_and_alive(self):
        self.assertTrue(
            runner.startup_output_stalled(
                idle_seconds=120, threshold=120, has_status=False, has_result=False, proc_alive=True
            )
        )
        self.assertFalse(
            runner.startup_output_stalled(
                idle_seconds=119, threshold=120, has_status=False, has_result=False, proc_alive=True
            )
        )

    def test_startup_output_stalled_yields_to_other_signals(self):
        # Benchmark already started -> heartbeat-stall covers it from here.
        self.assertFalse(
            runner.startup_output_stalled(
                idle_seconds=999, threshold=120, has_status=True, has_result=False, proc_alive=True
            )
        )
        # A result already landed.
        self.assertFalse(
            runner.startup_output_stalled(
                idle_seconds=999, threshold=120, has_status=False, has_result=True, proc_alive=True
            )
        )
        # Process already exited -> ProcessExited covers it.
        self.assertFalse(
            runner.startup_output_stalled(
                idle_seconds=999, threshold=120, has_status=False, has_result=False, proc_alive=False
            )
        )
        # Watchdog disabled (threshold 0).
        self.assertFalse(
            runner.startup_output_stalled(
                idle_seconds=999, threshold=0, has_status=False, has_result=False, proc_alive=True
            )
        )

    def test_log_tail_excerpt_bounds_lines_and_characters(self):
        text = "\n".join(f"line {index}" for index in range(200))
        tail = runner.log_tail_excerpt(text).splitlines()
        self.assertEqual(tail[0], "line 120")
        self.assertEqual(tail[-1], "line 199")
        self.assertEqual(runner.log_tail_excerpt(""), "")
        self.assertEqual(len(runner.log_tail_excerpt("x" * 20_000)), 16_000)

    def test_benchmark_stall_preview_seconds_defaults_to_half_the_kill_budget(self):
        self.assertEqual(runner.benchmark_stall_preview_seconds({}, 90), 45)
        self.assertEqual(
            runner.benchmark_stall_preview_seconds({"profiling": {"benchmarkStallPreviewSeconds": 20}}, 90), 20
        )
        # Negative disables it.
        self.assertEqual(
            runner.benchmark_stall_preview_seconds({"profiling": {"benchmarkStallPreviewSeconds": -5}}, 90), 0
        )

    def test_stall_preview_fires_once_while_the_run_is_still_alive(self):
        self.assertTrue(
            runner.stall_preview_due(idle_seconds=45, threshold=45, kill_threshold=90, already_captured=False)
        )
        # Not frozen long enough yet.
        self.assertFalse(
            runner.stall_preview_due(idle_seconds=44, threshold=45, kill_threshold=90, already_captured=False)
        )
        # Already snapshotted for this frozen signature.
        self.assertFalse(
            runner.stall_preview_due(idle_seconds=60, threshold=45, kill_threshold=90, already_captured=True)
        )
        # Preview disabled.
        self.assertFalse(
            runner.stall_preview_due(idle_seconds=999, threshold=0, kill_threshold=90, already_captured=False)
        )

    def test_stall_preview_yields_to_the_kill_capture(self):
        # At the heartbeat budget the watchdog kills the run and captures there.
        self.assertFalse(
            runner.stall_preview_due(idle_seconds=90, threshold=45, kill_threshold=90, already_captured=False)
        )
        # Kill watchdog disabled, so the preview is the only snapshot we get.
        self.assertTrue(
            runner.stall_preview_due(idle_seconds=999, threshold=45, kill_threshold=0, already_captured=False)
        )


class WorldPackmanNamingTests(unittest.TestCase):
    def test_world_package_name_rejects_traversal(self):
        with self.assertRaises(RuntimeError):
            runner.world_package_name("../evil")

    def test_world_pin_version_reads_config_world(self):
        config = {"world": {"name": "DemoWorld", "version": "abc123"}}
        self.assertEqual(runner.world_pin_version(config, "DemoWorld"), "abc123")

    def test_world_pin_version_missing_returns_none(self):
        # Not the benchmark world, or no version -> None.
        self.assertIsNone(runner.world_pin_version({"world": {"name": "Other", "version": "x"}}, "DemoWorld"))
        self.assertIsNone(runner.world_pin_version({"world": {"name": "DemoWorld"}}, "DemoWorld"))
        self.assertIsNone(runner.world_pin_version({}, "DemoWorld"))

    def test_world_package_override_only_applies_to_the_selected_world(self):
        config = {"world": {"name": "DemoWorld", "package": "demo_world"}}
        self.assertEqual(runner.world_package_name("DemoWorld", config), "demo_world")
        self.assertEqual(runner.world_package_name("OtherWorld", config), "rtx_remix_minecraft_world_OtherWorld")


class WorldPullProjectXmlTests(unittest.TestCase):
    def test_build_pull_project_xml_contains_package_and_copypath(self):
        import xml.etree.ElementTree as ET

        xml = runner.build_world_pull_project_xml("DemoWorld", "abc123", Path("C:/repo/assets/worlds/DemoWorld"))
        root = ET.fromstring(xml)
        dep = root.find("dependency")
        assert dep is not None
        self.assertEqual(dep.attrib["name"], "rtx_remix_minecraft_world_DemoWorld")
        package = dep.find("package")
        assert package is not None
        self.assertEqual(package.attrib["name"], "rtx_remix_minecraft_world_DemoWorld")
        self.assertEqual(package.attrib["version"], "abc123")
        # copyPath must be absolute and forward-slashed (packman roots relative
        # paths at the project file, which lives in a temp location).
        self.assertEqual(dep.attrib["copyPath"], "C:/repo/assets/worlds/DemoWorld")
        self.assertNotIn("\\", xml)


class WorldVersionTests(unittest.TestCase):
    def test_validate_world_version_accepts_v_semver(self):
        self.assertEqual(runner.validate_world_version("v0.3.0"), "v0.3.0")
        self.assertEqual(runner.validate_world_version(" v12.4.7 "), "v12.4.7")

    def test_validate_world_version_rejects_bad_format(self):
        for bad in ["v0.3", "0.3.0", "2026-05-07.1", "v0.3.0-rc1", "v0.3.0.1", ""]:
            with self.assertRaises(RuntimeError):
                runner.validate_world_version(bad)


class WorldDirHelperTests(unittest.TestCase):
    def test_parse_world_dir_name_splits_trailing_version(self):
        self.assertEqual(runner.parse_world_dir_name("DemoWorld_v0.3.0"), ("DemoWorld", "v0.3.0"))
        self.assertEqual(runner.parse_world_dir_name("cyberpunk_city"), ("cyberpunk_city", None))
        self.assertEqual(runner.parse_world_dir_name("cyberpunk_city_v1.0.0"), ("cyberpunk_city", "v1.0.0"))
        # underscores that are not a full v-semver suffix stay in the name
        self.assertEqual(runner.parse_world_dir_name("DemoWorld_v0.3"), ("DemoWorld_v0.3", None))

    def test_world_version_sort_key_orders_numerically(self):
        versions = ["v0.3.0", "v0.10.0", "v0.3.2", "v1.0.0", "v0.3.10"]
        self.assertEqual(
            sorted(versions, key=runner.world_version_sort_key),
            ["v0.3.0", "v0.3.2", "v0.3.10", "v0.10.0", "v1.0.0"],
        )


class FixtureVersionsTests(unittest.TestCase):
    def test_version_commands_report_pinned_and_staged_versions_without_remote_access(self):
        for command, key, directory in (
            ("world", "world", "worlds"),
            ("resourcepack", "resourcePack", "resourcepacks"),
        ):
            with self.subTest(command=command), tempfile.TemporaryDirectory() as tmp:
                config = {key: {"name": "ExampleFixture", "version": "v0.3.0"}}
                assets = Path(tmp)
                staged = assets / directory / "ExampleFixture_v0.3.0"
                staged.mkdir(parents=True)
                (staged / "level.dat").touch()
                with (
                    patch.object(runner.util, "ASSETS_DIR", assets),
                    patch.object(runner, "load_config", return_value=config),
                    patch.object(runner, "run_packman") as packman,
                ):
                    output = io.StringIO()
                    with redirect_stdout(output):
                        self.assertEqual(cli.main([command, "versions", "--json"]), 0)
                    report = json.loads(output.getvalue())
                    self.assertEqual(
                        report["versions"],
                        [
                            {"version": "v0.3.0", "staged": True, "pinned": True},
                        ],
                    )
                    output = io.StringIO()
                    with redirect_stdout(output):
                        self.assertEqual(cli.main([command, "versions"]), 0)
                    self.assertIn("v0.3.0  [pinned, staged]", output.getvalue())
                    self.assertNotIn("warning", output.getvalue())
                    packman.assert_not_called()

    def test_text_versions_report_names_a_pin_that_is_not_staged(self):
        for command, key, directory in (
            ("world", "world", "worlds"),
            ("resourcepack", "resourcePack", "resourcepacks"),
        ):
            for staged_version, expect_pin_line in ((None, True), ("v0.2.0", True), ("v0.3.0", False)):
                with (
                    self.subTest(command=command, staged=staged_version),
                    tempfile.TemporaryDirectory() as tmp,
                ):
                    config = {key: {"name": "ExampleFixture", "version": "v0.3.0"}}
                    assets = Path(tmp)
                    (assets / directory).mkdir(parents=True)
                    if staged_version:
                        staged = assets / directory / f"ExampleFixture_{staged_version}"
                        staged.mkdir()
                        (staged / "level.dat").touch()
                    with (
                        patch.object(runner.util, "ASSETS_DIR", assets),
                        patch.object(runner, "load_config", return_value=config),
                        patch.object(runner, "run_packman") as packman,
                    ):
                        output = io.StringIO()
                        with redirect_stdout(output):
                            self.assertEqual(cli.main([command, "versions"]), 0)
                    lines = output.getvalue().splitlines()
                    pin_lines = [line for line in lines if line.startswith("v0.3.0")]
                    self.assertEqual(len(pin_lines), 1)
                    if expect_pin_line:
                        self.assertIn("not staged yet", pin_lines[0])
                        self.assertNotIn("staged,", pin_lines[0])
                        self.assertNotIn("remote", pin_lines[0])
                    else:
                        self.assertEqual(pin_lines[0], "v0.3.0  [pinned, staged]")
                    packman.assert_not_called()


class FetchWorldTests(unittest.TestCase):
    def test_fetch_world_writes_project_and_pulls(self):
        manifests = []
        with tempfile.TemporaryDirectory() as tmp:
            assets = Path(tmp)
            dest = assets / "worlds" / "ExampleWorld_v0.3.0"

            def pull(args):
                manifests.append(Path(args[1]).read_text(encoding="utf-8"))
                dest.mkdir()
                (dest / ".rtx_remix_minecraft_world_ExampleWorld@v0.3.0").touch()

            with (
                patch.object(runner, "load_config", return_value={"world": {"remote": "demo-worlds"}}),
                patch.object(runner.util, "ASSETS_DIR", assets),
                patch.object(runner, "run_packman", side_effect=pull) as packman,
            ):
                self.assertEqual(runner.fetch_world("ExampleWorld", version="v0.3.0"), dest)

            self.assertEqual(list(dest.parent.glob("*.tmp.xml")), [])
        packman.assert_called_once()
        self.assertEqual(packman.call_args.args[0][0], "pull")
        self.assertIn("demo-worlds", packman.call_args.args[0])
        self.assertIn('version="v0.3.0"', manifests[0])
        self.assertIn("rtx_remix_minecraft_world_ExampleWorld", manifests[0])
        self.assertIn("ExampleWorld_v0.3.0", manifests[0])
        self.assertNotIn("<import", manifests[0])

    def test_fixture_fetch_requires_install_marker_and_retries_once(self):
        for fixture in ("world", "resourcePack"):
            for install_attempt in (1, 2, None):
                with (
                    self.subTest(fixture=fixture, install_attempt=install_attempt),
                    tempfile.TemporaryDirectory() as tmp,
                ):
                    config = runner.load_config()
                    config[fixture].update({"name": "ExampleFixture", "package": "example_package"})
                    assets = Path(tmp)
                    parent = assets / ("worlds" if fixture == "world" else "resourcepacks")
                    dest = parent / "ExampleFixture_v0.3.0"
                    marker = dest / ".example_package@v0.3.0"
                    attempts = []
                    events = []

                    def pull(
                        args,
                        attempts=attempts,
                        events=events,
                        install_attempt=install_attempt,
                        dest=dest,
                        marker=marker,
                    ):
                        attempts.append(args)
                        events.append("pull")
                        if len(attempts) == install_attempt:
                            dest.mkdir()
                            marker.touch()

                    fetch = runner.fetch_world if fixture == "world" else runner.fetch_resource_pack
                    with (
                        patch.object(runner.util, "ASSETS_DIR", assets),
                        patch.object(runner, "load_config", return_value=config),
                        patch.object(runner, "run_packman", side_effect=pull),
                        patch.object(
                            runner.time, "sleep", side_effect=lambda seconds, events=events: events.append(seconds)
                        ),
                    ):
                        if install_attempt is None:
                            with self.assertRaises(RuntimeError) as error:
                                fetch("ExampleFixture", version="v0.3.0")
                            message = str(error.exception)
                            for text in (
                                "example_package@v0.3.0",
                                str(dest),
                                "reported success without installing",
                                "most likely",
                                "final rename",
                                "transient file lock",
                                "antivirus",
                                "indexing",
                                "sync software",
                                "retry",
                                "exclude",
                            ):
                                self.assertIn(text, message)
                            self.assertNotIn("pack layout", message)
                        else:
                            self.assertEqual(fetch("ExampleFixture", version="v0.3.0"), dest)
                            self.assertTrue(marker.is_file())
                    self.assertEqual(len(attempts), install_attempt or 2)
                    # A scanner holding the first copy likely holds an immediate retry's too.
                    self.assertEqual(events, ["pull"] if install_attempt == 1 else ["pull", 3, "pull"])
                    if len(attempts) == 2:
                        self.assertEqual(attempts[0], attempts[1])
                    self.assertEqual(list(parent.glob("*.tmp.xml")), [])


class WorldStagedVersionTests(unittest.TestCase):
    """Packman writes .<package>@<version> into the directory it installs, and a
    fixture published from such a directory ships that marker as content."""

    def _seed(self, base: Path, dirname: str, *marker_versions: str) -> Path:
        world_dir = base / "benchmark" / "worlds" / dirname
        (world_dir / "world").mkdir(parents=True)
        (world_dir / "world" / "level.dat").write_bytes(b"x")
        for marker_version in marker_versions:
            (world_dir / f".rtx_remix_minecraft_world_DemoWorld@{marker_version}").touch()
        return world_dir

    def test_marker_matching_the_directory_version_wins_over_a_packaged_stale_one(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.object(runner.util, "ASSETS_DIR", Path(tmp) / "benchmark"),
        ):
            self._seed(Path(tmp), "DemoWorld_v0.10.1", "v0.10.0", "v0.10.1")
            self.assertEqual(runner.world_staged_version("DemoWorld", "v0.10.1"), "v0.10.1")

    def test_conflicting_markers_the_directory_name_cannot_arbitrate_are_an_error(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.object(runner.util, "ASSETS_DIR", Path(tmp) / "benchmark"),
        ):
            self._seed(Path(tmp), "DemoWorld", "v0.10.0", "v0.10.1")
            with self.assertRaisesRegex(RuntimeError, "conflicting packman install markers.*v0.10.0, v0.10.1"):
                runner.world_staged_version("DemoWorld")

    def test_single_marker_is_reported_even_when_it_disagrees_with_the_directory(self):
        # Drift, not a conflict: ensure_world_staged replaces such a directory from packman.
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.object(runner.util, "ASSETS_DIR", Path(tmp) / "benchmark"),
        ):
            self._seed(Path(tmp), "DemoWorld_v0.10.1", "v0.10.0")
            self.assertEqual(runner.world_staged_version("DemoWorld", "v0.10.1"), "v0.10.0")

    def test_no_marker_means_locally_authored(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.object(runner.util, "ASSETS_DIR", Path(tmp) / "benchmark"),
        ):
            self._seed(Path(tmp), "DemoWorld_v0.10.1")
            self.assertIsNone(runner.world_staged_version("DemoWorld", "v0.10.1"))


class AvailableWorldsTests(unittest.TestCase):
    def test_available_worlds_unions_benchmark_world_and_local(self):
        with patch.object(runner, "load_config", return_value={"world": {"name": "PinnedOnly", "version": "x"}}):
            with patch.object(runner, "_local_world_names", return_value=["LocalOnly"]):
                worlds = runner.available_worlds()
        self.assertEqual(worlds, ["LocalOnly", "PinnedOnly"])

    def test_world_status_reports_not_staged(self):
        with patch.object(runner, "benchmark_worlds_dir", return_value=Path("C:/repo/assets/worlds")):
            with patch.object(runner.Path, "is_dir", return_value=False):
                status = runner.world_status("DemoWorld", pinned_version="v0.3.0")
        self.assertEqual(status["staged"], False)
        self.assertEqual(status["pinned"], "v0.3.0")

    def test_world_status_reports_version_mismatch(self):
        with (
            patch.object(runner, "is_minecraft_world", return_value=True),
            patch.object(runner, "world_staged_version", return_value="v0.3.0"),
        ):
            status = runner.world_status("DemoWorld", pinned_version="v0.3.1")
        self.assertTrue(status["staged"])
        self.assertTrue(status["modified"])
        self.assertEqual(status["stagedVersion"], "v0.3.0")

    def test_world_status_no_mismatch_when_marker_matches_pin(self):
        with (
            patch.object(runner, "is_minecraft_world", return_value=True),
            patch.object(runner, "world_staged_version", return_value="v0.3.1"),
        ):
            status = runner.world_status("DemoWorld", pinned_version="v0.3.1")
        self.assertFalse(status["modified"])

    def test_world_status_no_mismatch_when_no_marker(self):
        with (
            patch.object(runner, "is_minecraft_world", return_value=True),
            patch.object(runner, "world_staged_version", return_value=None),
        ):
            status = runner.world_status("DemoWorld", pinned_version="v0.3.1")
        self.assertFalse(status["modified"])
        self.assertIsNone(status["stagedVersion"])


class LocateWorldDirTests(unittest.TestCase):
    def _seed_world(self, base, dirname):
        world = Path(base) / "benchmark" / "worlds" / dirname / "world"
        world.mkdir(parents=True)
        (world / "level.dat").write_bytes(b"x")

    def test_locate_prefers_explicit_version(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.object(runner.util, "BENCHMARK_DIR", Path(tmp) / "benchmark"),
            patch.object(runner.util, "ASSETS_DIR", Path(tmp) / "benchmark"),
        ):
            self.assertEqual(runner.locate_world_dir("DemoWorld", "v0.3.0").name, "DemoWorld_v0.3.0")

    def test_locate_uses_bare_dir_when_unversioned_local_present(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.object(runner.util, "BENCHMARK_DIR", Path(tmp) / "benchmark"),
            patch.object(runner.util, "ASSETS_DIR", Path(tmp) / "benchmark"),
        ):
            self._seed_world(tmp, "cyberpunk_city")
            located = runner.locate_world_dir("cyberpunk_city", None)
            self.assertEqual(located.name, "cyberpunk_city")

    def test_locate_falls_back_to_newest_versioned_dir(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.object(runner.util, "BENCHMARK_DIR", Path(tmp) / "benchmark"),
            patch.object(runner.util, "ASSETS_DIR", Path(tmp) / "benchmark"),
        ):
            self._seed_world(tmp, "DemoWorld_v0.3.0")
            self._seed_world(tmp, "DemoWorld_v0.3.2")
            self.assertEqual(runner.available_world_versions("DemoWorld"), ["v0.3.0", "v0.3.2"])
            self.assertEqual(runner.locate_world_dir("DemoWorld", None).name, "DemoWorld_v0.3.2")

    def test_available_worlds_dedupes_versions_to_logical_name(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.object(runner.util, "BENCHMARK_DIR", Path(tmp) / "benchmark"),
            patch.object(runner.util, "ASSETS_DIR", Path(tmp) / "benchmark"),
        ):
            for dirname in ("DemoWorld_v0.3.0", "DemoWorld_v0.3.2", "cyberpunk_city"):
                self._seed_world(tmp, dirname)
            with patch.object(runner, "load_config", return_value={}):
                self.assertEqual(runner.available_worlds(), ["DemoWorld", "cyberpunk_city"])


class EnsureWorldStagedTests(unittest.TestCase):
    def test_fetches_when_missing(self):
        config = {"world": {"name": "DemoWorld", "version": "v0.3.0"}}
        with (
            patch.object(runner, "is_minecraft_world", return_value=False),
            patch.object(runner, "fetch_world") as fetch,
        ):
            runner.ensure_world_staged(config, "DemoWorld")
        fetch.assert_called_once_with("DemoWorld", version="v0.3.0")

    def test_fetches_when_staged_version_mismatches(self):
        config = {"world": {"name": "DemoWorld", "version": "v0.3.1"}}
        with (
            patch.object(runner, "is_minecraft_world", return_value=True),
            patch.object(runner, "world_staged_version", return_value="v0.3.0"),
            patch.object(runner, "fetch_world") as fetch,
        ):
            runner.ensure_world_staged(config, "DemoWorld")
        fetch.assert_called_once_with("DemoWorld", version="v0.3.1")

    def test_no_fetch_when_marker_matches_pin(self):
        config = {"world": {"name": "DemoWorld", "version": "v0.3.1"}}
        with (
            patch.object(runner, "is_minecraft_world", return_value=True),
            patch.object(runner, "world_staged_version", return_value="v0.3.1"),
            patch.object(runner, "fetch_world") as fetch,
        ):
            runner.ensure_world_staged(config, "DemoWorld")
        fetch.assert_not_called()

    def test_no_fetch_when_staged_without_marker(self):
        # Locally authored / published-from copy (no packman marker): trust it.
        config = {"world": {"name": "DemoWorld", "version": "v0.3.1"}}
        with (
            patch.object(runner, "is_minecraft_world", return_value=True),
            patch.object(runner, "world_staged_version", return_value=None),
            patch.object(runner, "fetch_world") as fetch,
        ):
            runner.ensure_world_staged(config, "DemoWorld")
        fetch.assert_not_called()

    def test_explicit_version_override_fetches_that_version(self):
        # Locally, --version selects a specific version regardless of the pin.
        config = {"world": {"name": "DemoWorld", "version": "v0.3.0"}}
        with (
            patch.object(runner, "is_minecraft_world", return_value=False),
            patch.object(runner, "fetch_world") as fetch,
        ):
            runner.ensure_world_staged(config, "DemoWorld", version="v0.3.1")
        fetch.assert_called_once_with("DemoWorld", version="v0.3.1")

    def test_ci_ignores_version_override_and_uses_pin(self):
        config = {"world": {"name": "DemoWorld", "version": "v0.3.0"}}
        with (
            patch.object(runner, "is_minecraft_world", return_value=False),
            patch.object(runner, "fetch_world") as fetch,
        ):
            runner.ensure_world_staged(config, "DemoWorld", ci=True, version="v0.3.9")
        fetch.assert_called_once_with("DemoWorld", version="v0.3.0")

    def test_ci_run_of_unpinned_world_raises(self):
        # In CI, a world that isn't the pinned benchmark world must not float.
        with (
            patch.object(runner, "is_minecraft_world", return_value=False),
            patch.object(runner, "fetch_world") as fetch,
        ):
            with self.assertRaisesRegex(RuntimeError, "pinned benchmark world"):
                runner.ensure_world_staged({"world": {"name": "DemoWorld", "version": "v0.3.0"}}, "Other", ci=True)
        fetch.assert_not_called()

    def test_local_unpinned_unstaged_world_requires_a_version(self):
        with (
            patch.object(runner, "is_minecraft_world", return_value=False),
            patch.object(runner, "fetch_world") as fetch,
            self.assertRaisesRegex(RuntimeError, r"Other.*--version"),
        ):
            runner.ensure_world_staged({"world": {"name": "DemoWorld", "version": "v0.3.0"}}, "Other", ci=False)
        fetch.assert_not_called()

    def test_local_unpinned_staged_world_uses_local_fixture(self):
        with (
            patch.object(runner, "is_minecraft_world", return_value=True),
            patch.object(runner, "fetch_world") as fetch,
        ):
            runner.ensure_world_staged({"world": {"name": "DemoWorld", "version": "v0.3.0"}}, "Other", ci=False)
        fetch.assert_not_called()


class SelectionVersionTests(unittest.TestCase):
    def _seed(self, base, dirname, camera="flythrough"):
        root = Path(base) / "benchmark" / "worlds" / dirname
        (root / "world").mkdir(parents=True)
        (root / "world" / "level.dat").write_bytes(b"x")
        cameras = root / "cameras"
        cameras.mkdir(parents=True)
        (cameras / f"{camera}.json").write_text(VALID_CAMERA_PATH_JSON, encoding="utf-8")
        return root

    def test_version_override_selects_versioned_dir_and_sets_world_version(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.object(runner.util, "BENCHMARK_DIR", Path(tmp) / "benchmark"),
            patch.object(runner.util, "ASSETS_DIR", Path(tmp) / "benchmark"),
        ):
            self._seed(tmp, "DemoWorld_v0.3.1")
            config = {"world": {"name": "DemoWorld", "version": "v0.3.0", "camera": "flythrough"}}
            selection = runner.configure_benchmark_selection(config, version_override="v0.3.1")
            self.assertEqual(selection.version, "v0.3.1")
            self.assertEqual(selection.world_dir.name, "DemoWorld_v0.3.1")
            self.assertEqual(config["worldVersion"], "v0.3.1")

    def test_pin_version_selects_versioned_dir_without_override(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.object(runner.util, "BENCHMARK_DIR", Path(tmp) / "benchmark"),
            patch.object(runner.util, "ASSETS_DIR", Path(tmp) / "benchmark"),
        ):
            self._seed(tmp, "DemoWorld_v0.3.0")
            config = {"world": {"name": "DemoWorld", "version": "v0.3.0", "camera": "flythrough"}}
            selection = runner.configure_benchmark_selection(config)
            self.assertEqual(selection.world_dir.name, "DemoWorld_v0.3.0")
            self.assertEqual(config["worldVersion"], "v0.3.0")

    def test_install_fixtures_stages_the_selected_version_not_the_configured_one(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.object(runner.util, "BENCHMARK_DIR", Path(tmp) / "benchmark"),
            patch.object(runner.util, "ASSETS_DIR", Path(tmp) / "benchmark"),
            patch.object(runner.util, "RUN_DIR", Path(tmp) / "run"),
            patch.object(runner.util, "PROJECT_DIR", Path(tmp) / "project"),
        ):
            (Path(tmp) / "project").mkdir()
            self._seed(tmp, "DemoWorld_v0.10.0")
            selected = self._seed(tmp, "DemoWorld_v0.10.1")
            (selected / "world" / "level.dat").write_bytes(b"v0.10.1")
            (selected / "world" / "session.lock").write_bytes(b"")
            config: dict = {"world": {"name": "DemoWorld", "version": "v0.10.0", "camera": "flythrough"}}
            selection = runner.configure_benchmark_selection(config, version_override="v0.10.1")
            # The selection is the contract; a config whose bookkeeping has fallen
            # out of step with it must not steer which fixture gets staged.
            config["worldVersion"] = "v0.10.0"

            runner.install_fixtures(config, selection)

            run_dir = Path(tmp) / "run"
            self.assertEqual((run_dir / "saves" / "rtx-benchmark" / "level.dat").read_bytes(), b"v0.10.1")
            self.assertTrue((run_dir / "camera-paths" / "DemoWorld_v0.10.1" / "flythrough.json").is_file())
            report = runner.util.read_json(run_dir / "benchmark" / "world-sanitize.json")
            self.assertEqual(Path(report["sourceWorldPath"]), selection.world_path)

    def test_run_plan_honors_local_version_override(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.object(runner.util, "BENCHMARK_DIR", Path(tmp) / "benchmark"),
            patch.object(runner.util, "ASSETS_DIR", Path(tmp) / "benchmark"),
            patch.object(runner, "host_arch", return_value="win64"),
            patch.object(runner, "sdk_probe", return_value={}),
        ):
            self._seed(tmp, "DemoWorld_v0.3.0")
            self._seed(tmp, "DemoWorld_v0.4.0")
            args = SimpleNamespace(
                world="DemoWorld",
                camera="flythrough",
                version="v0.3.0",
                ci=False,
                runs=1,
                strip_entities=False,
                sdk_variant="tracy",
                no_build=False,
                dry_run=True,
            )
            config = {"world": {"name": "DemoWorld", "version": "v0.4.0", "camera": "flythrough"}}
            with patch.object(runner, "load_config", return_value=config):
                plan = runner.benchmark_run_plan(args)

            self.assertEqual(plan["worldVersion"], "v0.3.0")
            self.assertEqual(Path(plan["worldPath"]).parent.name, "DemoWorld_v0.3.0")

    def test_run_plan_ignores_version_override_in_ci(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.object(runner.util, "BENCHMARK_DIR", Path(tmp) / "benchmark"),
            patch.object(runner.util, "ASSETS_DIR", Path(tmp) / "benchmark"),
            patch.object(runner, "host_arch", return_value="win64"),
            patch.object(runner, "sdk_probe", return_value={}),
        ):
            self._seed(tmp, "DemoWorld_v0.3.0")
            self._seed(tmp, "DemoWorld_v0.4.0")
            args = SimpleNamespace(
                world="DemoWorld",
                camera="flythrough",
                version="v0.4.0",
                ci=True,
                runs=1,
                strip_entities=False,
                sdk_variant="tracy",
                no_build=False,
                dry_run=True,
            )
            config = {"world": {"name": "DemoWorld", "version": "v0.3.0", "camera": "flythrough"}}
            with patch.object(runner, "load_config", return_value=config):
                plan = runner.benchmark_run_plan(args)

            self.assertEqual(plan["worldVersion"], "v0.3.0")
            self.assertEqual(Path(plan["worldPath"]).parent.name, "DemoWorld_v0.3.0")


class LiveClientPreflightTests(unittest.TestCase):
    @unittest.skipUnless(os.name == "nt", "queries Win32_Process")
    def test_required_java_process_query_reports_failures(self):
        failures = [
            subprocess.CompletedProcess([], 1, stdout="", stderr="query failed"),
            subprocess.CompletedProcess([], 0, stdout="not json", stderr=""),
        ]
        for result in failures:
            with self.subTest(result=result):
                with patch.object(util, "run", return_value=result):
                    with self.assertRaisesRegex(RuntimeError, "Java process query"):
                        runner.java_process_command_lines(required=True)

    def test_file_lock_is_held_is_false_for_missing_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertFalse(runner.file_lock_is_held(Path(tmp) / "session.lock"))

    def test_file_lock_is_held_is_false_for_unlocked_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "session.lock"
            path.write_bytes(b"\xe2\x98\x83")
            self.assertFalse(runner.file_lock_is_held(path))

    @unittest.skipUnless(os.name == "nt", "relies on Windows per-handle byte-range locks")
    def test_file_lock_is_held_detects_lock_held_on_another_handle(self):
        import msvcrt

        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "session.lock"
            path.write_bytes(b"\xe2\x98\x83")
            with path.open("r+b") as holder:
                msvcrt.locking(holder.fileno(), msvcrt.LK_NBLCK, 1)
                try:
                    self.assertTrue(runner.file_lock_is_held(path))
                finally:
                    msvcrt.locking(holder.fileno(), msvcrt.LK_UNLCK, 1)

    def test_find_checkout_java_processes_matches_this_checkout_case_insensitively(self):
        project = Path(r"C:\work\rtx-remix-minecraft")
        client = (
            r"C:\jdk\bin\java.exe -Dfabric.dli.config=C:\WORK\RTX-REMIX-MINECRAFT\.gradle\loom-cache\launch.cfg"
            " net.fabricmc.devlaunchinjector.Main"
        )
        daemon = r"C:\jdk\bin\java.exe -cp C:\gradle\lib\gradle.jar org.gradle.launcher.daemon.bootstrap.GradleDaemon"
        other = r"C:\jdk\bin\java.exe -jar C:\work\other-project\app.jar"
        matches = runner.find_checkout_java_processes([(101, client), (102, daemon), (103, other)], project_dir=project)

        self.assertEqual([pid for pid, _ in matches], [101])

    def test_find_checkout_java_processes_tells_a_checkout_from_its_nested_worktrees_and_siblings(self):
        project = Path(r"C:\work\rtx-remix-minecraft")
        worktree = project / ".claude" / "worktrees" / "feature"

        def prism_game(checkout: Path) -> str:
            instance = (checkout / "run-prism" / "instances" / "rtx-remix-dev").as_posix()
            libraries = (checkout / "run-prism" / "libraries").as_posix()
            return (
                f'"C:/jdk/bin/javaw.exe" -Djava.library.path={instance}/natives '
                f"-cp {libraries}/net/fabricmc/fabric-loader.jar org.prismlauncher.EntryPoint"
            )

        loom_classes = rf"C:\jdk\bin\java.exe -cp {project}\build\classes\java\main net.fabricmc.devlaunchinjector.Main"
        processes = [
            (1, prism_game(project)),
            (2, prism_game(worktree)),
            (3, prism_game(Path(r"C:\work\rtx-remix-minecraft-old"))),
            (4, loom_classes),
            (5, rf"C:\jdk\bin\java.exe -jar {project}\run\tool.jar"),
        ]

        self.assertEqual([pid for pid, _ in runner.find_checkout_java_processes(processes, project)], [1, 4, 5])
        self.assertEqual([pid for pid, _ in runner.find_checkout_java_processes(processes, worktree)], [2])

    def test_find_checkout_java_processes_ignores_gradle_helpers_for_this_checkout(self):
        project = Path(r"C:\checkout")
        worker = r"C:\jdk\bin\java.exe -cp C:\checkout\build org.gradle.process.internal.worker.GradleWorkerMain"

        self.assertEqual(runner.find_checkout_java_processes([(7, worker)], project_dir=project), [])

    def test_find_checkout_java_processes_matches_an_installed_prism_instance(self):
        project = Path(r"C:\checkout")
        game_dir = Path(r"C:\profile\PrismLauncher\instances\rtx-remix-dev-checkout-a1\.minecraft")
        own = rf"C:\jdk\bin\javaw.exe -Djava.library.path={game_dir.parent}\natives org.prismlauncher.EntryPoint"
        sibling = own.replace("a1", "b2")

        matches = runner.find_checkout_java_processes(
            [(8, own), (9, sibling)],
            project_dir=project,
            game_dir=game_dir,
        )

        self.assertEqual([pid for pid, _ in matches], [8])

    def test_detect_live_client_reports_held_session_lock_without_process_scan(self):
        with (
            patch.object(runner, "file_lock_is_held", return_value=True),
            patch.object(runner, "java_process_command_lines") as scan,
        ):
            reason = runner.detect_live_client()

        self.assertIn("session.lock", reason or "")
        scan.assert_not_called()

    def test_detect_live_client_reports_live_java_process(self):
        with (
            patch.object(runner, "file_lock_is_held", return_value=False),
            patch.object(runner, "java_process_command_lines", return_value=[(4242, "java.exe")]),
            patch.object(runner, "find_checkout_java_processes", return_value=[(4242, "java.exe")]),
        ):
            self.assertIn("4242", runner.detect_live_client() or "")

    def test_ensure_no_live_client_aborts_locally(self):
        with patch.object(runner, "detect_live_client", return_value="session.lock is held"):
            with self.assertRaisesRegex(RuntimeError, "another Minecraft client is running from this checkout"):
                runner.ensure_no_live_client()

    def test_ensure_no_live_client_warns_but_continues_in_ci(self):
        with patch.object(runner, "detect_live_client", return_value="session.lock is held"):
            runner.ensure_no_live_client(ci=True)

    def test_ensure_no_live_client_honors_env_bypass(self):
        with (
            patch.dict(os.environ, {runner.LIVE_CLIENT_SKIP_ENV: "1"}),
            patch.object(runner, "detect_live_client") as detect,
        ):
            runner.ensure_no_live_client()

        detect.assert_not_called()

    def test_world_launch_aborts_before_sdk_fetch_and_staging(self):
        selection = SimpleNamespace(world="w", camera="c")
        with (
            patch.object(runner, "load_config", return_value={}),
            patch.object(runner, "configure_benchmark_selection", return_value=selection),
            patch.object(runner, "detect_live_client", return_value="session.lock is held"),
            patch.object(runner, "ensure_world_staged") as stage_world,
            patch.object(runner, "fetch_sdk") as fetch_sdk,
            patch.object(runner, "install_fixtures") as install_fixtures,
        ):
            with self.assertRaisesRegex(RuntimeError, "another Minecraft client"):
                runner.launch_client_for_selection()

        stage_world.assert_not_called()
        fetch_sdk.assert_not_called()
        install_fixtures.assert_not_called()

    def test_run_benchmark_aborts_before_creating_result_dir(self):
        args = SimpleNamespace(
            dry_run=False, ci=False, world=None, camera=None, strip_entities=False, runs=0, arch=None
        )
        selection = SimpleNamespace(world="w", camera="c")
        with (
            patch.object(runner, "load_config", return_value={}),
            patch.object(runner, "configure_benchmark_selection", return_value=selection),
            patch.object(runner, "host_arch", return_value="win64"),
            patch.object(runner, "detect_live_client", return_value="java process(es) 4242 reference this checkout"),
            patch.object(runner, "ensure_world_staged") as stage_world,
            patch.object(runner, "unique_result_dir") as result_dir,
            patch.object(runner, "fetch_sdk") as fetch_sdk,
        ):
            with self.assertRaisesRegex(RuntimeError, "another Minecraft client"):
                runner.run_benchmark(args)

        stage_world.assert_not_called()
        result_dir.assert_not_called()
        fetch_sdk.assert_not_called()


def benchmark_selection(root: Path) -> runner.BenchmarkSelection:
    return runner.BenchmarkSelection(
        world="DemoWorld",
        camera="simple",
        world_dir=root / "fixture",
        world_path=root / "fixture" / "world",
        cameras_dir=root / "fixture" / "cameras",
        camera_path=root / "fixture" / "cameras" / "simple.json",
    )


def stub_run_machinery(monkeypatch: pytest.MonkeyPatch, root: Path) -> None:
    """Stub what run_client_once does beyond the launch: fixture staging, machine-wide kills, Tracy, and crash dumps."""
    for name in ("install_fixtures", "write_auto_trigger", "kill_leftovers"):
        monkeypatch.setattr(runner, name, Mock())
    monkeypatch.setattr(runner, "start_tracy_capture", Mock(return_value=None))
    monkeypatch.setattr(runner, "windows_crash_dump_dir", Mock(return_value=root / "crash-source"))
    monkeypatch.setattr(runner, "crash_dump_snapshot", Mock(return_value=set()))
    monkeypatch.setattr(runner, "collect_windows_crash_dumps", Mock(return_value=[]))


class RecordingGame(FakeProcess):
    """A game process that records how the runner waits on and ends it."""

    def __init__(self, exit_code: int | None) -> None:
        self.exit_code = exit_code
        self.waits: list[float | None] = []

    def poll(self) -> int | None:
        return self.exit_code

    def wait(self, timeout: float | None = None) -> int:
        self.waits.append(timeout)
        return 0


def test_run_log_includes_the_launchers_log_files(
    fake_launcher: FakeLauncher, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    stub_run_machinery(monkeypatch, tmp_path)
    fake_launcher.extra_log = "A fatal error has been detected by the Java Runtime Environment"
    run_log = tmp_path / "run.log"
    result = runner.run_client_once(
        {"pollTimeoutSeconds": 0}, benchmark_selection(tmp_path), None, run_log, True, "win64"
    )
    assert result["failureReason"] == "JvmCrash"
    assert fake_launcher.extra_log in run_log.read_text(encoding="utf-8")


def test_a_game_that_exits_before_it_is_followed_is_a_classified_run_failure(
    fake_launcher: FakeLauncher, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    """The launcher saw the game come and go before it could follow it. The run still ends through the normal path:
    its logs classify it, Tracy stops, crash dumps are collected, and the benchmark goes on to its next run."""
    stub_run_machinery(monkeypatch, tmp_path)
    stop_tracy = Mock()
    monkeypatch.setattr(runner, "stop_tracy_capture", stop_tracy)
    collect_dumps = Mock(return_value=[])
    monkeypatch.setattr(runner, "collect_windows_crash_dumps", collect_dumps)
    fake_launcher.extra_log = "---- Minecraft Crash Report ----\n// Oops."
    crash_report = fake_launcher.log_files(0)

    def exits_as_it_starts(request: client.LaunchRequest) -> FakeProcess:
        fake_launcher.requests.append(request)
        raise client.GameExitedEarly("The game exited as it started.", logs=tuple(crash_report))

    monkeypatch.setattr(fake_launcher, "start", exits_as_it_starts)
    run_log = tmp_path / "run.log"
    config = {"pollTimeoutSeconds": 600, "pollIntervalSeconds": 0}
    result = runner.run_client_once(config, benchmark_selection(tmp_path), None, run_log, True, "win64")
    assert result["failureReason"] == "MinecraftCrash"
    # No process was followed, so there is no exit code to report.
    assert result["launcherExitCode"] is None
    assert "Minecraft Crash Report" in run_log.read_text(encoding="utf-8")
    stop_tracy.assert_called_once()
    collect_dumps.assert_called_once()


def test_a_launch_the_launcher_refuses_still_stops_the_run(
    fake_launcher: FakeLauncher, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    stub_run_machinery(monkeypatch, tmp_path)

    def refuses(request: client.LaunchRequest) -> FakeProcess:
        raise client.LaunchBlocked("Prism is already running.")

    monkeypatch.setattr(fake_launcher, "start", refuses)
    with pytest.raises(client.LaunchBlocked, match="already running"):
        runner.run_client_once({}, benchmark_selection(tmp_path), None, tmp_path / "run.log", True, "win64")


@pytest.mark.parametrize("override", ["900", "60"])
def test_run_client_once_hands_the_launcher_the_resolved_startup_budget(
    fake_launcher: FakeLauncher, tmp_path: Path, monkeypatch: pytest.MonkeyPatch, override: str
):
    stub_run_machinery(monkeypatch, tmp_path)
    monkeypatch.setenv("BENCHMARK_STARTUP_STALL_SECONDS", override)
    config = runner.config_for_run(
        {"pollTimeoutSeconds": 0, "profiling": {"benchmarkStartupStallSeconds": 480}}, ci=False
    )
    runner.run_client_once(config, benchmark_selection(tmp_path), None, tmp_path / "run.log", True, "win64")
    assert fake_launcher.requests[0].startup_seconds == int(override)


def test_a_fatal_error_in_the_launchers_log_ends_the_poll(
    fake_launcher: FakeLauncher, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    stub_run_machinery(monkeypatch, tmp_path)
    diagnostics = Mock(return_value=tmp_path / "diagnostics")
    monkeypatch.setattr(runner, "capture_hang_diagnostics", diagnostics)
    fake_launcher.extra_log = "A fatal error has been detected by the Java Runtime Environment"
    config = {"pollTimeoutSeconds": 600, "pollIntervalSeconds": 0}
    result = runner.run_client_once(config, benchmark_selection(tmp_path), None, tmp_path / "run.log", True, "win64")
    assert result["failureReason"] == "JvmCrash"
    assert diagnostics.call_args.kwargs["reason"] == "JvmCrash"


def test_run_client_once_waits_on_an_exited_game_so_its_launcher_is_ended(
    fake_launcher: FakeLauncher, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    stub_run_machinery(monkeypatch, tmp_path)
    monkeypatch.setattr(runner.time, "sleep", Mock())
    game = RecordingGame(exit_code=1)
    monkeypatch.setattr(fake_launcher, "start", Mock(return_value=game))
    config = {"pollTimeoutSeconds": 600, "pollIntervalSeconds": 0}
    result = runner.run_client_once(config, benchmark_selection(tmp_path), None, tmp_path / "run.log", True, "win64")
    assert result["failureReason"] == "ProcessExited"
    # terminate_process leaves an exited game alone; the wait is what ends a Prism still open after its game.
    assert game.waits == [30]


def test_run_client_once_classifies_a_native_crash_from_the_game_exit_code(
    fake_launcher: FakeLauncher, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    stub_run_machinery(monkeypatch, tmp_path)
    collect_dumps = Mock(return_value=[])
    monkeypatch.setattr(runner, "collect_windows_crash_dumps", collect_dumps)
    monkeypatch.setattr(runner.time, "sleep", Mock())
    monkeypatch.setattr(fake_launcher, "start", Mock(return_value=RecordingGame(exit_code=0xC0000374)))
    config = {"pollTimeoutSeconds": 600, "pollIntervalSeconds": 0}
    result = runner.run_client_once(config, benchmark_selection(tmp_path), None, tmp_path / "run.log", True, "win64")
    assert result["failureReason"] == "NativeHeapCorruption"
    assert result["failureDetails"]["ntstatus"] == "0xC0000374"
    assert result["launcherExitCode"] == 0xC0000374
    # Native evidence, so the runner waits for the crash dump.
    assert collect_dumps.call_args.kwargs["wait_seconds"] is None


def test_run_client_once_classifies_minecrafts_crash_exit_from_its_crash_report(
    fake_launcher: FakeLauncher, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    stub_run_machinery(monkeypatch, tmp_path)
    monkeypatch.setattr(runner.time, "sleep", Mock())
    monkeypatch.setattr(runner, "capture_hang_diagnostics", Mock(return_value=None))
    monkeypatch.setattr(fake_launcher, "start", Mock(return_value=RecordingGame(exit_code=0xFFFFFFFF)))
    fake_launcher.extra_log = "---- Minecraft Crash Report ----\n// Oops.\n"
    config = {"pollTimeoutSeconds": 600, "pollIntervalSeconds": 0}
    result = runner.run_client_once(config, benchmark_selection(tmp_path), None, tmp_path / "run.log", True, "win64")
    assert result["failureReason"] == "MinecraftCrash"
    assert "ntstatus" not in result["failureDetails"]


def test_run_client_once_ends_the_game_on_ctrl_c(
    fake_launcher: FakeLauncher, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    stub_run_machinery(monkeypatch, tmp_path)
    monkeypatch.setattr(runner.time, "sleep", Mock(side_effect=KeyboardInterrupt))
    terminate = Mock()
    monkeypatch.setattr(runner, "terminate_process", terminate)
    game = RecordingGame(exit_code=None)
    monkeypatch.setattr(fake_launcher, "start", Mock(return_value=game))
    with pytest.raises(KeyboardInterrupt):
        runner.run_client_once(
            {"pollTimeoutSeconds": 600}, benchmark_selection(tmp_path), None, tmp_path / "run.log", True, "win64"
        )
    terminate.assert_called_once_with(game, "game client")
    assert game.waits == [runner.CLIENT_EXIT_WAIT_SECONDS]


def test_started_client_ends_the_game_when_the_harness_fails(
    fake_launcher: FakeLauncher, monkeypatch: pytest.MonkeyPatch
):
    terminate = Mock()
    monkeypatch.setattr(runner, "terminate_process", terminate)
    game = RecordingGame(exit_code=None)
    monkeypatch.setattr(fake_launcher, "start", Mock(return_value=game))
    with pytest.raises(OSError, match="disk full"):
        with runner.started_client(client.LaunchRequest(arch="win64", env={})):
            raise OSError("disk full")
    terminate.assert_called_once_with(game, "game client")
    # The wait lets the launcher end what the game left behind, such as a Prism on an error console, on this path too.
    assert game.waits == [runner.CLIENT_EXIT_WAIT_SECONDS]


def test_started_client_leaves_a_game_the_harness_finished_with_to_exit(
    fake_launcher: FakeLauncher, monkeypatch: pytest.MonkeyPatch
):
    terminate = Mock()
    monkeypatch.setattr(runner, "terminate_process", terminate)
    game = RecordingGame(exit_code=0)
    monkeypatch.setattr(fake_launcher, "start", Mock(return_value=game))
    with runner.started_client(client.LaunchRequest(arch="win64", env={})) as proc:
        assert proc is game
    terminate.assert_not_called()
    assert game.waits == [runner.CLIENT_EXIT_WAIT_SECONDS]


def test_run_benchmark_checks_the_launcher_before_staging(fake_launcher: FakeLauncher, monkeypatch: pytest.MonkeyPatch):
    fake_launcher.block = client.LaunchBlocked("no account")
    stage_world = Mock()
    monkeypatch.setattr(runner, "load_config", Mock(return_value={}))
    monkeypatch.setattr(runner, "ensure_no_live_client", Mock())
    monkeypatch.setattr(runner, "ensure_world_staged", stage_world)
    args = SimpleNamespace(dry_run=False, ci=False, world=None, camera=None, strip_entities=False, runs=0, arch=None)
    with pytest.raises(client.LaunchBlocked):
        runner.run_benchmark(args)
    stage_world.assert_not_called()
    assert fake_launcher.prepared == []


@pytest.mark.parametrize("ci", [True, False])
def test_run_benchmark_opens_sign_in_only_outside_ci(
    ci: bool, fake_launcher: FakeLauncher, monkeypatch: pytest.MonkeyPatch
):
    fake_launcher.block = client.LaunchBlocked("no account")
    monkeypatch.setattr(runner, "load_config", Mock(return_value={}))
    monkeypatch.setattr(runner, "ensure_no_live_client", Mock())
    args = SimpleNamespace(dry_run=False, ci=ci, world=None, camera=None, strip_entities=False, runs=0, arch=None)
    with pytest.raises(client.LaunchBlocked):
        runner.run_benchmark(args)
    # A CI job has no one to sign in, so its check must never open a window.
    assert fake_launcher.sign_in_opened is not ci


def test_run_benchmark_installs_the_build_after_gradle_makes_it(
    fake_launcher: FakeLauncher, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    class StopAfterPrepare(Exception):
        pass

    order = Mock()
    order.prepare.side_effect = StopAfterPrepare
    monkeypatch.setattr(fake_launcher, "prepare", order.prepare)
    monkeypatch.setattr(runner, "run_gradle_build", order.run_gradle_build)
    monkeypatch.setattr(runner, "load_config", Mock(return_value={}))
    for name in ("ensure_no_live_client", "ensure_world_staged", "verify_jvm_matches_arch", "write_sdk_version"):
        monkeypatch.setattr(runner, name, Mock())
    monkeypatch.setattr(runner, "resolve_world_name", Mock(return_value="DemoWorld"))
    selection = SimpleNamespace(world="DemoWorld", camera="simple")
    monkeypatch.setattr(runner, "configure_benchmark_selection", Mock(return_value=selection))
    monkeypatch.setattr(runner, "config_for_run", Mock(return_value={}))
    monkeypatch.setattr(runner, "host_arch", Mock(return_value="win64"))
    monkeypatch.setattr(runner, "unique_result_dir", Mock(return_value=tmp_path / "result"))
    monkeypatch.setattr(runner.gitmeta, "current_git", Mock(return_value={"dirty": False}))
    monkeypatch.setattr(runner.machine, "collect_machine", Mock(return_value={}))
    monkeypatch.setattr(runner.machine, "off_spec_reasons", Mock(return_value=[]))
    monkeypatch.setattr(runner, "fetch_sdk", Mock(return_value=tmp_path / "d3d9.dll"))
    monkeypatch.setattr(runner, "tracy_tools", Mock(return_value=None))
    monkeypatch.setattr(runner, "sdk_metadata", Mock(return_value={}))
    args = SimpleNamespace(
        dry_run=False,
        ci=False,
        world=None,
        camera=None,
        version=None,
        strip_entities=False,
        runs=1,
        arch=None,
        sdk_variant="release",
        no_build=False,
    )
    with pytest.raises(StopAfterPrepare):
        runner.run_benchmark(args)
    assert [name for name, _, _ in order.mock_calls] == ["run_gradle_build", "prepare"]
    assert order.prepare.call_args == call("win64", build=False)


def test_doctor_reports_launcher_status_without_opening_sign_in(fake_launcher: FakeLauncher):
    fake_launcher.block = client.LaunchBlocked("no account")
    report = runner.doctor_report(world=None, camera=None, sdk_variant="release")
    check = next(c for c in report["checks"] if c["name"] == "launcher")
    assert check["status"] == "fail" and "no account" in check["detail"] and fake_launcher.sign_in_opened is False
    assert check["detail"].startswith(fake_launcher.description)
    # A blocked launcher means nothing can launch, so the doctor is not satisfied.
    assert not report["ok"]


def test_doctor_reports_a_launcher_that_can_start(fake_launcher: FakeLauncher):
    fake_launcher.description = "Prism, signed in as Alex"
    report = runner.doctor_report(world=None, camera=None, sdk_variant="release")
    check = next(c for c in report["checks"] if c["name"] == "launcher")
    assert check == {"status": "ok", "name": "launcher", "detail": "Prism, signed in as Alex"}


def test_doctor_reports_a_launcher_that_cannot_describe_itself(
    fake_launcher: FakeLauncher, monkeypatch: pytest.MonkeyPatch
):
    def broken_describe() -> str:
        raise OSError("launcher state unreadable")

    monkeypatch.setattr(fake_launcher, "describe", broken_describe)
    report = runner.doctor_report(world=None, camera=None, sdk_variant="release")
    check = next(c for c in report["checks"] if c["name"] == "launcher")
    assert check["status"] == "error" and "launcher state unreadable" in check["detail"]
    assert not report["ok"]


def test_manifest_records_the_launcher(fake_launcher: FakeLauncher, tmp_path: Path):
    manifest = runner.write_manifest(
        tmp_path,
        run_id="20260701_120000",
        started=dt.datetime.now().astimezone(),
        git={},
        machine_info={},
        sdk={},
        config={},
        summary={},
        notes="",
        tags=[],
        source={"type": "local"},
    )
    assert manifest["launcher"] == fake_launcher.name
    assert util.read_json(tmp_path / "manifest.json")["launcher"] == fake_launcher.name


def test_compare_flags_launcher_mismatch():
    assert cli.launcher_mismatch({"launcher": "prism"}, {}) == "A ran through prism, B through runClient"
    assert cli.launcher_mismatch({"launcher": "prism"}, {"launcher": "prism"}) is None
    assert cli.launcher_mismatch({}, {"launcher": "runClient"}) is None


def test_compare_warns_about_a_launcher_mismatch_before_the_table(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
):
    runs = {"a": {"id": "a", "launcher": "prism", "summary": {}}, "b": {"id": "b", "summary": {}}}
    monkeypatch.setattr(cli, "resolve_run", runs.__getitem__)
    monkeypatch.setattr(cli.catalog, "write_dashboard", Mock())
    assert cli.cmd_compare(SimpleNamespace(a="a", b="b")) == 0
    lines = capsys.readouterr().out.splitlines()
    assert lines[0] == "WARNING: A ran through prism, B through runClient"
    assert "Avg FPS" in lines[1]


@pytest.mark.parametrize("ci,task", [(True, "build"), (False, "package")])
def test_benchmark_build_skips_both_test_suites(monkeypatch, ci, task):
    run = Mock()
    monkeypatch.setattr(runner.util, "run", run)
    monkeypatch.setattr(runner, "gradle_env", lambda: {})
    runner.run_gradle_build("win64", ci=ci)
    args = run.call_args.args[0]
    assert args[1] == task
    excluded = [args[i + 1] for i, arg in enumerate(args[:-1]) if arg == "-x"]
    assert excluded == ["test", "fabricTest"]


if __name__ == "__main__":
    unittest.main()

# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import argparse
import contextlib
import io
import json
import unittest

import pytest

from rtx_tools import loader_registry
from rtx_tools.benchmark import util
from rtx_tools.cli import _extract_minecraft_version, build_parser

pytestmark = pytest.mark.usefixtures("isolated_tools_config")


class CliHelpTests(unittest.TestCase):
    def help_for(self, *args: str) -> str:
        parser = build_parser()
        out = io.StringIO()
        with self.assertRaises(SystemExit) as raised, contextlib.redirect_stdout(out):
            parser.parse_args([*args, "--help"])
        self.assertEqual(raised.exception.code, 0)
        return out.getvalue()

    def test_install_help_explains_development_payload_and_prerequisites(self):
        text = self.help_for("install")

        self.assertIn("selected loader's Gradle build task", text)
        self.assertNotIn(r"forge\gradlew.bat", text)
        self.assertIn("selected loader and its dependencies", text)
        self.assertIn("gameplay conf payload, and runtime textures", text)
        self.assertIn("Baseline PBR textures are built into the JAR", text)
        self.assertIn("packaging/migrate_resource_packs.ps1", text)
        self.assertIn("removes the retired generated baseline", text)
        self.assertIn("preserves user-pack ordering", text)
        self.assertIn(r"%APPDATA%\.minecraft", text)
        self.assertIn("--game-dir", text)
        self.assertIn("--arch {win64,arm64}", text)
        self.assertIn("--loader LOADER", text)
        self.assertIn("REMIX_BUILD", text)
        self.assertIn("REMIX_FORCE_SDK_COPY", text)
        self.assertIn("REMIX_ARCH, then host", text)
        self.assertIn("RTXREMIX_MOD_LOADER, then tools config, then fabric", " ".join(text.split()))

    def test_loader_commands_accept_every_catalog_loader(self):
        parser = build_parser()
        for command in ("launch", "run", "doctor", "install"):
            for loader in loader_registry.load_registry().ids:
                with self.subTest(command=command, loader=loader):
                    args = parser.parse_args([command, "--loader", loader])
                    self.assertEqual(loader, args.loader)

    def test_minecraft_version_selector_is_position_independent(self):
        self.assertEqual(
            (["launch", "--loader", "fabric"], "1.21.1"),
            _extract_minecraft_version(["launch", "--minecraft-version", "1.21.1", "--loader", "fabric"]),
        )
        self.assertEqual(
            (["launch"], "1.21.1"),
            _extract_minecraft_version(["--minecraft-version=1.21.1", "launch"]),
        )

    def test_download_and_publish_commands_are_not_registered(self):
        parser = build_parser()
        for command in (["download"], ["dl"], ["world", "publish"], ["resourcepack", "publish"]):
            with (
                self.subTest(command=command),
                self.assertRaises(SystemExit) as raised,
                contextlib.redirect_stderr(io.StringIO()),
            ):
                parser.parse_args([*command, "--help"])
            self.assertEqual(raised.exception.code, 2)

    def test_version_catalog_has_one_current_command_name(self):
        self.assertIn("Reads and validates every Minecraft version", self.help_for("versions"))
        parser = build_parser()
        with self.assertRaises(SystemExit) as raised, contextlib.redirect_stderr(io.StringIO()):
            parser.parse_args(["version-catalog"])
        self.assertEqual(raised.exception.code, 2)

    def test_world_help_explains_launch_workflow(self):
        text = self.help_for("world", "launch")

        self.assertIn("saves/rtx-benchmark in the game directory", text)
        self.assertIn("through this checkout's dedicated Prism instance", text)
        self.assertIn("dedicated Prism instance", text)
        self.assertNotIn("active launcher", text)
        self.assertNotIn("--camera", text)

    def test_no_help_names_runclient_or_the_old_run_directory(self):
        # Launches go through this checkout's Prism into its instance, not Gradle's runClient into run/.
        texts = []
        parsers = [build_parser()]
        while parsers:
            parser = parsers.pop()
            texts.append(parser.format_help())
            for action in parser._actions:
                if isinstance(action, argparse._SubParsersAction):
                    parsers.extend(action.choices.values())
        for text in texts:
            with self.subTest(help=text.splitlines()[0]):
                self.assertNotIn("runClient", text)
                self.assertNotRegex(text, r"(?<![\w-])run/")

    def test_sdk_local_help_lists_the_sources_in_the_order_they_apply(self):
        text = " ".join(self.help_for("sdk", "local").split())
        recorded = text.index("the recorded local source")
        configured = text.index("sdk.localPath from tools/config.local.json")
        sibling = text.index("../dxvk-remix/_output")
        self.assertLess(recorded, configured)
        self.assertLess(configured, sibling)

    def test_every_menu_option_is_described(self):
        parser = build_parser()
        commands = next(action for action in parser._actions if isinstance(action, argparse._SubParsersAction))
        menu = commands.choices["menu"]
        self.assertEqual([], [action.dest for action in menu._actions if not action.help])

    def test_doctor_help_names_the_launcher_check(self):
        self.assertIn("the client launcher", self.help_for("doctor"))

    def test_prism_help_explains_installed_and_portable_profiles(self):
        text = self.help_for("prism")
        self.assertIn("Installed Prism reuses its normal account profile", text)
        self.assertIn("run-prism", text)
        self.assertNotIn("By default", text)

    def test_launch_help_says_what_the_launch_does(self):
        text = self.help_for("launch")
        self.assertIn("installs it into this checkout's Prism instance", text)
        self.assertIn("use prism signin if needed", text)
        self.assertNotIn("unified launcher", text)

    def test_prism_signin_help_explains_the_sign_in(self):
        text = self.help_for("prism", "signin")

        self.assertIn("Accounts > Manage Accounts > Add Microsoft", " ".join(text.split()))
        self.assertIn("the page Prism shows", text)
        self.assertIn("selected Prism profile", text)

    def test_examples_name_the_shipped_demo_world(self):
        shipped = json.loads((util.TOOLS_DIR / "config.json").read_text(encoding="utf-8"))["world"]
        examples = [line.strip() for line in (build_parser().epilog or "").splitlines()]
        self.assertIn(rf"tools\rtx.bat world launch {shipped['name']}", examples)
        self.assertIn(rf"tools\rtx.bat run --world {shipped['name']} --camera {shipped['camera']}", examples)

    def test_world_update_takes_the_fixture_directory_name(self):
        text = " ".join(self.help_for("world", "update").split())
        self.assertIn("tools/assets/worlds/WORLD/world", text)
        self.assertIn("existing fixture directory name under tools/assets/worlds, including any version suffix", text)

    def test_fixture_fetch_help_offers_explicit_versions(self):
        for command in ("world", "resourcepack"):
            with self.subTest(command=command):
                text = self.help_for(command, "fetch")
                self.assertIn("--version", text)
                self.assertNotIn("--latest", text)

    def test_fixture_fetch_rejects_automatic_version_selection(self):
        parser = build_parser()
        for command in ("world", "resourcepack"):
            with (
                self.subTest(command=command),
                self.assertRaises(SystemExit) as raised,
                contextlib.redirect_stderr(io.StringIO()),
            ):
                parser.parse_args([command, "fetch", "--latest"])
            self.assertEqual(raised.exception.code, 2)


if __name__ == "__main__":
    unittest.main()

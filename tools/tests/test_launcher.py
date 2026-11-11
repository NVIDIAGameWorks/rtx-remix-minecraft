# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import json
from types import SimpleNamespace
from unittest import mock

import pytest
from conftest import FakeLauncher

from rtx_tools import cli, client, launcher, launcher_console
from rtx_tools.benchmark import runner, util

# Every dashboard draw describes the active launcher, so each test gets a fake one rather than this machine's Prism.
pytestmark = pytest.mark.usefixtures("isolated_tools_config", "fake_launcher")


@pytest.fixture
def config():
    return {
        "world": {"name": "DemoWorld", "version": "v0.11.0", "camera": "benchmark"},
        "resourcePack": {"mode": "pinned", "name": "texture", "version": "0.56.0"},
        "render": {"preset": "ultra"},
    }


@pytest.mark.parametrize(
    ("flags", "expected"),
    [
        (
            ["--mode", "benchmark", "--sdk", "tracy", "--world", "DemoWorld", "--camera", "flythrough"],
            "run --sdk-variant tracy --world DemoWorld --camera flythrough",
        ),
        (
            [
                "--mode",
                "play",
                "--sdk",
                "release",
                "--world",
                "DemoWorld",
                "--resource-pack",
                "none",
            ],
            "launch --world DemoWorld --sdk-variant release --resource-pack none",
        ),
        (["--mode", "menu"], "launch --sdk-variant release"),
        (["--mode", "play"], "launch --world DemoWorld --sdk-variant release"),
        (["--mode", "play", "--world", "none"], "launch --sdk-variant release"),
        (["--mode", "play", "--world", "NONE"], "launch --sdk-variant release"),
        (["--mode", "play", "--world", "None"], "launch --sdk-variant release"),
        (
            ["--mode", "play", "--world", "DemoWorld", "--loader", "forge"],
            "launch --world DemoWorld --loader forge --sdk-variant release",
        ),
        (["--mode", "benchmark"], "run --sdk-variant release"),
    ],
)
def test_dry_run_matches_flag_mode_without_dispatch_or_state(config, tmp_path, capsys, flags, expected):
    config_path = tmp_path / "config.json"
    config_path.write_text(json.dumps(config), encoding="utf-8")
    with (
        mock.patch.object(util, "SHARED_CONFIG_PATH", config_path),
        mock.patch.object(util, "TOOLS_DIR", tmp_path),
        mock.patch.object(cli, "cmd_launch") as launch,
        mock.patch.object(cli, "cmd_run") as run,
    ):
        assert cli.main(["menu", *flags, "--dry-run"]) == 0
    assert capsys.readouterr().out.strip() == f"Launcher -> .\\rtx.bat {expected}"
    launch.assert_not_called()
    run.assert_not_called()
    assert sorted(p.name for p in tmp_path.iterdir()) == ["config.json"]


@pytest.mark.parametrize("mode", ["play", "benchmark", "menu"])
@pytest.mark.parametrize("preset", ["high", "Custom"])
def test_explicit_preset_reaches_each_command(config, mode, preset):
    args = launcher.build_command(config, mode=mode, world="DemoWorld", preset=preset)
    assert args[args.index("--preset") + 1] == preset


def test_no_preset_leaves_config_pin_in_charge(config):
    assert "--preset" not in launcher.build_command(config, mode="play", world="DemoWorld")


def test_play_requires_world_when_no_pin():
    with pytest.raises(ValueError, match="No world specified"):
        launcher.build_command({}, mode="play")


@pytest.mark.parametrize("world", ["none", "NONE", "None"])
def test_benchmark_rejects_main_menu(config, world):
    with pytest.raises(ValueError, match="Benchmark mode requires a world"):
        launcher.build_command(config, mode="benchmark", world=world)


@pytest.mark.parametrize("runs", ["", "0", "-1", "many", "1.5", "2147483648", "1_0", "\u0661"])
def test_invalid_run_count_leaves_benchmark_default(config, runs):
    assert "--runs" not in launcher.build_command(config, mode="benchmark", runs=runs)


def test_benchmark_only_options(config):
    args = launcher.build_command(
        config,
        mode="benchmark",
        world="DemoWorld",
        camera="flythrough",
        runs="02",
    )
    assert args == [
        "run",
        "--sdk-variant",
        "release",
        "--world",
        "DemoWorld",
        "--camera",
        "flythrough",
        "--runs",
        "2",
    ]
    play = launcher.build_command(config, mode="play", camera="ignored", runs="2")
    assert "--version" not in play
    assert "--camera" not in play
    assert "--runs" not in play


@pytest.mark.parametrize(
    "pack",
    [{}, {"mode": "pinned"}, {"mode": "local", "localPath": "D:/Art pack"}],
)
def test_omitted_resource_pack_leaves_selection_to_effective_config(pack):
    args = launcher.build_command({"resourcePack": pack}, mode="menu")
    assert "--resource-pack" not in args


def test_explicit_resource_pack_override_does_not_validate_inherited_local_path():
    config = {"resourcePack": {"mode": "local"}}
    assert launcher.build_command(config, mode="menu", resource_pack="none")[-2:] == ["--resource-pack", "none"]


def test_interactive_toggles_default_on_and_follow_configuration(config):
    state = launcher.State.defaults(config)
    assert state.world_enabled and state.resource_pack_enabled
    assert state.loader == "fabric"
    assert (state.camera, state.preset, state.runs) == ("benchmark", "ultra", 1)
    assert state.world_label(config) == "On (DemoWorld v0.11.0)"
    assert state.pack_label(config) == "On (texture 0.56.0)"
    args = state.command(config, benchmark=False)
    assert args[:3] == ["launch", "--world", "DemoWorld"]
    assert "--resource-pack" not in args
    assert "--version" not in args
    config["world"] = {"name": "OtherWorld", "version": "v1.0.0"}
    config["resourcePack"] = {"mode": "pinned", "name": "ExampleArt", "version": "1.0.0"}
    args = state.command(config, benchmark=False)
    assert args[:3] == ["launch", "--world", "OtherWorld"]
    assert state.world_label(config) == "On (OtherWorld v1.0.0)"
    assert state.pack_label(config) == "On (ExampleArt 1.0.0)"
    assert "--resource-pack" not in args
    assert "--version" not in args


def test_dashboard_loader_selection_is_forwarded_without_changing_config(config):
    state = launcher.State.defaults(config)
    state.loader = "forge"
    args = state.command(config, benchmark=False)
    assert args[args.index("--loader") + 1] == "forge"
    assert "modLoader" not in config


def test_interactive_toggles_off_omit_world_and_art_pack(config):
    state = launcher.State(world_enabled=False, resource_pack_enabled=False)
    assert state.world_label(config) == "Off (main menu)"
    assert state.pack_label(config) == "Off (baseline only)"
    args = state.command(config, benchmark=False)
    assert args[0] == "launch"
    assert args[args.index("--resource-pack") + 1] == "none"
    assert "--world" not in args
    assert "--version" not in args


def test_art_pack_toggle_uses_a_configured_local_working_copy(config):
    config["resourcePack"] = {"mode": "local", "localPath": "C:/art/pack", "name": "ExampleArt", "version": "1.0.0"}
    state = launcher.State.defaults(config)
    assert state.pack_label(config) == "On (local C:/art/pack)"
    args = state.command(config, benchmark=False)
    assert "--resource-pack" not in args
    state.resource_pack_enabled = False
    args = state.command(config, benchmark=False)
    assert args[args.index("--resource-pack") + 1] == "none"


def test_camera_menu_uses_configured_world_even_when_toggled_off(config):
    console = mock.Mock(spec=launcher_console.Console)
    console.choose.return_value = -1
    with mock.patch.object(launcher, "read_shared_config", return_value=config):
        menu = launcher.Menu(console, mock.Mock(), dry_run=True)
    menu.state.world_enabled = False
    with mock.patch.object(menu, "report", return_value={"staged": True, "cameras": ["benchmark"]}) as report:
        menu.camera_menu()
    report.assert_called_once_with(["camera", "list", "DemoWorld", "--json"])


def test_dashboard_rejects_main_menu_benchmark_without_dispatch(config):
    console = mock.Mock(spec=launcher_console.Console)
    console.choose.side_effect = [1, 2, -1]
    command = mock.Mock()
    with mock.patch.object(launcher, "read_shared_config", return_value=config):
        menu = launcher.Menu(console, command, dry_run=True)
    menu.sdk_status_cache = {"sourceType": "packman", "state": "packman-missing"}
    menu.state.world_enabled = False
    assert menu.run() is None
    console.pause.assert_called_once_with(
        "Turn World on and set world.name in tools/config.local.json before running a benchmark."
    )
    command.assert_not_called()


def test_dashboard_defaults_launch_the_shipped_world_with_the_art_pack():
    config = launcher.read_shared_config()
    world, pack = config["world"], config["resourcePack"]
    state = launcher.State.defaults(config)
    assert state.world_enabled and state.resource_pack_enabled
    assert state.world_label(config) == f"On ({world['name']} {world['version']})"
    assert state.pack_label(config) == f"On ({pack['name']} {pack['version']})"
    assert state.notices(config, benchmark=False) == []
    args = state.command(config, benchmark=False)
    assert args[:3] == ["launch", "--world", world["name"]]
    assert "--resource-pack" not in args
    assert state.preset == "Custom"


@pytest.mark.parametrize(
    ("local", "label", "cli_error"),
    [
        ({"mode": "none"}, "On (mode none in configuration)", None),
        ({"mode": "local"}, "On (local, localPath unset)", "localPath"),
        ({"name": "MyArt"}, "On (pinned, name or version unset)", r"resourcePack\.version"),
    ],
)
def test_art_pack_toggle_leaves_the_merged_mode_in_charge_over_the_shipped_pin(
    isolated_tools_config, local, label, cli_error
):
    util.write_json(isolated_tools_config.with_name("config.local.json"), {"resourcePack": local})
    config = launcher.read_shared_config()
    state = launcher.State.defaults(config)
    assert state.resource_pack_enabled
    assert state.pack_label(config) == label
    assert "--resource-pack" not in state.command(config, benchmark=False)
    if cli_error is None:
        assert state.notices(config, benchmark=False) == [
            "No shared art pack is configured; a selected world fixture may still provide one."
        ]
        assert runner.resolve_art_pack(runner.load_config()) is None
    else:
        assert state.notices(config, benchmark=False) == []
        with pytest.raises(RuntimeError, match=cli_error):
            runner.resolve_art_pack(runner.load_config())


def test_shared_none_notice_does_not_claim_a_world_fixture_has_no_art_pack(tmp_path):
    world_dir = tmp_path / "world"
    pack_dir = tmp_path / "pack"
    world_dir.mkdir()
    pack_dir.mkdir()
    (pack_dir / "pack.mcmeta").write_text("{}", encoding="utf-8")
    util.write_json(
        world_dir / "config.json",
        {"resourcePack": {"mode": "local", "localPath": str(pack_dir)}},
    )
    config = {"world": {"name": "Fixture"}, "resourcePack": {"mode": "none"}}
    state = launcher.State.defaults(config)

    assert state.notices(config, benchmark=False) == [
        "No shared art pack is configured; a selected world fixture may still provide one."
    ]
    effective = runner.effective_config_for_world(config, world_dir)
    assert runner.resolve_art_pack(effective) is not None


def test_saved_toggles_round_trip_and_ignore_old_fixture_choices(config, tmp_path):
    path = tmp_path / ".launcher-state.json"
    path.write_text(
        '{"world": "Other", "world_version": "v9.0.0", "resource_pack_version": "9.0.0", '
        '"username": null, "unknown": true}',
        encoding="utf-8",
    )
    state = launcher.load_state(path, config)
    assert state.world_enabled and state.resource_pack_enabled
    assert state.preset == "ultra"
    assert "--version" not in state.command(config, benchmark=False)
    state.world_enabled = False
    state.resource_pack_enabled = False
    launcher.save_state(path, state)
    assert launcher.load_state(path, config) == state
    assert state.pack_spec() == "none"
    saved = json.loads(path.read_text(encoding="utf-8"))
    assert not saved["world_enabled"] and not saved["resource_pack_enabled"]
    assert "world_version" not in saved and "resource_pack_version" not in saved


@pytest.mark.parametrize("enabled", [True, False])
def test_dashboard_fixture_rows_toggle_and_persist_without_version_queries(config, tmp_path, enabled):
    console = mock.Mock(spec=launcher_console.Console)
    console.choose.side_effect = [0] if enabled else [2, 3, 0]
    command = mock.Mock()
    with (
        mock.patch.object(launcher, "read_shared_config", return_value=config),
        mock.patch.object(util, "TOOLS_DIR", tmp_path),
    ):
        menu = launcher.Menu(console, command)
        menu.sdk_status_cache = {}
        args = menu.run()
    assert args is not None
    assert ("--world" in args) == enabled
    if enabled:
        assert "--resource-pack" not in args
    else:
        assert args[args.index("--resource-pack") + 1] == "none"
    assert "--version" not in args
    saved = launcher.load_state(tmp_path / ".launcher-state.json", config)
    assert saved.world_enabled == enabled
    assert saved.resource_pack_enabled == enabled
    command.assert_not_called()
    console.prompt.assert_not_called()


@pytest.mark.parametrize("dry_run", [True, False])
def test_unconfigured_fixture_notices_print_after_the_console_closes(dry_run):
    events = []
    with (
        mock.patch.object(launcher, "Console") as console_type,
        mock.patch.object(launcher.Menu, "sdk_status", return_value={}),
        mock.patch.object(launcher, "read_shared_config", return_value={"resourcePack": {"mode": "none"}}),
        mock.patch.object(launcher.Menu, "save"),
        mock.patch(
            "builtins.print", side_effect=lambda *args, **kwargs: events.append(("print", " ".join(map(str, args))))
        ),
    ):
        console_type.return_value.__exit__.side_effect = lambda *args: events.append(("exit", "")) or False
        console = console_type.return_value.__enter__.return_value
        console.choose.side_effect = [2, 2, 3, 3, 0]
        command = mock.Mock(side_effect=lambda args: events.append(("dispatch", " ".join(args))) or 0)
        assert launcher.interactive(command, dry_run=dry_run) == 0
    kinds = [kind for kind, _ in events]
    exit_index = kinds.index("exit")
    texts = [text for _, text in events]
    world_notice = [i for i, text in enumerate(texts) if "No world is configured" in text]
    pack_notice = [i for i, text in enumerate(texts) if "No shared art pack is configured" in text]
    assert len(world_notice) == 1 and len(pack_notice) == 1
    assert exit_index < world_notice[0] and exit_index < pack_notice[0]
    launch_line = next(
        i
        for i, text in enumerate(texts)
        if "Launcher -> .\\rtx.bat launch --loader fabric --sdk-variant release" in text
    )
    assert "--resource-pack" not in texts[launch_line]
    assert max(world_notice[0], pack_notice[0]) < launch_line
    if dry_run:
        command.assert_not_called()
    else:
        assert kinds.index("dispatch") > launch_line


@pytest.mark.parametrize("contents", ["broken", "[]", "null"])
def test_corrupt_state_uses_defaults(config, tmp_path, contents):
    path = tmp_path / ".launcher-state.json"
    path.write_text(contents, encoding="utf-8")
    assert launcher.load_state(path, config) == launcher.State.defaults(config)


@pytest.mark.parametrize(
    ("name", "label"), [("", "Custom (in-game settings)"), ("Custom", "Custom (in-game settings)"), ("ultra", "ultra")]
)
def test_preset_labels(name, label):
    assert launcher.preset_label(name) == label


def test_auto_restage_reads_changed_local_config(tmp_path):
    shared = tmp_path / "config.json"
    shared.write_text('{"sdk": {"autoRestage": true}}', encoding="utf-8")
    path = tmp_path / "config.local.json"
    with mock.patch.object(util, "SHARED_CONFIG_PATH", shared):
        for config, expected in [
            ({}, True),
            ({"sdk": {"autoRestage": False}}, False),
            ({"sdk": {"autoRestage": True}}, True),
        ]:
            path.write_text(json.dumps(config), encoding="utf-8")
            assert launcher.auto_restage_enabled() == expected


def test_dashboard_keeps_auto_restage_next_to_sdk_source(config):
    items = launcher.dashboard_items(launcher.State.defaults(config), config, {"sourceType": "packman"}, True)
    index = next(i for i, item in enumerate(items) if item.id == "sdksource")
    assert items[index + 1].id == "sdkauto"
    assert items[index + 1].group == items[index].group == "sdk"


@pytest.mark.parametrize(
    ("status", "label"),
    [
        ({"sourceType": "local", "state": "local-refresh-available"}, "local (refresh available)"),
        ({"sourceType": "local", "state": "local-source-missing"}, "local (source missing)"),
        ({"sourceType": "local", "state": "local-source-unknown"}, "local (source unknown)"),
        ({"sourceType": "local", "sourcePath": "D:/dxvk/_output"}, "local (D:/dxvk/_output)"),
        ({"sourceType": "packman", "state": "packman-missing"}, "packman (not staged)"),
        ({"sourceType": "packman"}, "packman"),
    ],
)
def test_sdk_status_keeps_source_and_freshness_visible(status, label):
    assert launcher.sdk_source_label(status) == label


@pytest.mark.parametrize("can_restage", [False, True])
def test_sdk_source_menu_only_offers_restage_when_available(can_restage):
    choices = launcher.sdk_choices({"sourceType": "local", "canRestage": can_restage, "sourcePath": "D:/dxvk"})
    assert ("restage" in [item.id for item in choices]) == can_restage
    assert choices[-1].label == "Switch to Packman SDK"


def test_short_lists_fit_whole():
    assert launcher_console.choice_viewport(8, 3, 20) == (0, 8)


@pytest.mark.parametrize("selected", [0, 1, 12, 25, 32])
def test_long_list_keeps_selection_visible(selected):
    start, length = launcher_console.choice_viewport(33, selected, 20)
    assert length == 20
    assert start <= selected < start + length <= 33
    assert start >= 0


def test_viewport_pins_to_ends_and_small_console_still_shows_selection():
    assert launcher_console.choice_viewport(33, 0, 20) == (0, 20)
    assert launcher_console.choice_viewport(33, 32, 20) == (13, 20)
    assert launcher_console.choice_viewport(33, 7, 0) == (7, 1)


@pytest.mark.parametrize(
    ("key", "selected", "expected"),
    [("up", 0, 32), ("down", 32, 0), ("pageup", 25, 6), ("pagedown", 25, 32), ("home", 12, 0), ("end", 12, 32)],
)
def test_navigation_wraps_arrows_and_clamps_paging(key, selected, expected):
    assert launcher_console.move_selection(key, selected, 33, 20) == expected


def test_bare_rtx_and_menu_open_dashboard_without_choosing_launch():
    with mock.patch.object(launcher, "interactive", return_value=0) as interactive:
        assert cli.main([]) == 0
        assert cli.main(["menu"]) == 0
    assert interactive.call_count == 2


@pytest.mark.parametrize("interruption", [KeyboardInterrupt, EOFError])
@pytest.mark.parametrize("input_mode", ["choice", "prompt"])
def test_dashboard_input_cancel_restores_console_without_dispatch(interruption, input_mode, capsys):
    command = mock.Mock()
    with (
        mock.patch.object(launcher, "Console") as console_type,
        mock.patch.object(launcher.Menu, "sdk_status", return_value={}),
    ):
        console = console_type.return_value.__enter__.return_value
        if input_mode == "choice":
            console.choose.side_effect = interruption
        else:
            # Run benchmark, then Runs, which prompts for a count.
            console.choose.side_effect = lambda title, labels, **kwargs: next(
                index for index, label in enumerate(labels) if label.startswith(("Run benchmark", "Runs"))
            )
            console.prompt.side_effect = interruption
        assert launcher.interactive(command, dry_run=True) == 0
    exit_call = console_type.return_value.__exit__
    exit_call.assert_called_once()
    assert exit_call.call_args.args[0] is interruption
    command.assert_not_called()
    captured = capsys.readouterr()
    assert captured.out == captured.err == ""


def test_old_state_files_with_username_still_load(tmp_path):
    (tmp_path / "s.json").write_text('{"username": "CiSmoke", "preset": "ultra"}', encoding="utf-8")
    state = launcher.load_state(tmp_path / "s.json", {})
    assert state.preset == "ultra"
    launcher.save_state(tmp_path / "s.json", state)
    assert "username" not in json.loads((tmp_path / "s.json").read_text(encoding="utf-8"))


def test_dashboard_shows_the_launcher_row(config, fake_launcher: FakeLauncher):
    fake_launcher.description = "Prism, signed in as Alex"
    state = launcher.State.defaults(config)
    labels = [item.text() for item in launcher.dashboard_items(state, config, {"sourceType": "packman"}, True)]
    assert any(label.startswith("Launcher") and "Prism, signed in as Alex" in label for label in labels)
    assert not any(label.startswith("Username") for label in labels)


def test_dashboard_header_names_the_launcher(config, fake_launcher: FakeLauncher):
    fake_launcher.description = "Prism, signed in as Alex"
    console = mock.Mock(spec=launcher_console.Console)
    console.choose.return_value = -1
    with mock.patch.object(launcher, "read_shared_config", return_value=config):
        menu = launcher.Menu(console, mock.Mock(), dry_run=True)
    menu.sdk_status_cache = {}
    assert menu.run() is None
    rows = console.choose.call_args.kwargs["rows"]
    assert "Launcher: Prism, signed in as Alex" in rows
    assert not any(row.startswith("Username") for row in rows)


def choose_in_turn(*prefixes: str):
    """A console choice that picks, on each call, the first label starting with the next prefix."""
    picks = iter(prefixes)

    def choose(title: str, labels: list[str], **kwargs) -> int:
        prefix = next(picks)
        return next(index for index, label in enumerate(labels) if label.startswith(prefix))

    return choose


def test_dashboard_launcher_row_opens_sign_in(config, capsys):
    console = mock.Mock(spec=launcher_console.Console)
    console.choose.side_effect = choose_in_turn("Launcher", "Exit")
    command = mock.Mock(return_value=0)
    with mock.patch.object(launcher, "read_shared_config", return_value=config):
        menu = launcher.Menu(console, command)
    menu.sdk_status_cache = {}
    assert menu.run() is None
    command.assert_called_once_with(["prism", "signin"])
    console.pause.assert_called_once()
    assert "Launcher -> .\\rtx.bat prism signin" in capsys.readouterr().out


def test_dashboard_launcher_row_does_nothing_without_sign_in(config, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr(client, "_active", SimpleNamespace(name="other", describe=lambda: "Another launcher"))
    console = mock.Mock(spec=launcher_console.Console)
    console.choose.side_effect = choose_in_turn("Launcher", "Exit")
    command = mock.Mock(return_value=0)
    with mock.patch.object(launcher, "read_shared_config", return_value=config):
        menu = launcher.Menu(console, command)
    menu.sdk_status_cache = {}
    assert menu.run() is None
    command.assert_not_called()
    console.pause.assert_not_called()

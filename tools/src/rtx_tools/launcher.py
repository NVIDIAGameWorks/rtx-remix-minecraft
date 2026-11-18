# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Developer menu: resolve choices, then hand off to the existing rtx commands."""

from __future__ import annotations

import argparse
import io
import json
import re
from collections.abc import Callable
from contextlib import redirect_stdout
from dataclasses import asdict, dataclass, field, fields
from pathlib import Path
from typing import Any

from . import client, loader_registry, sdk
from . import config as tools_config
from .benchmark import util
from .launcher_console import Console

Command = Callable[[list[str]], int]


def read_shared_config() -> dict:
    return tools_config.load_config()


def configured_pack(config: dict) -> dict:
    pack = config.get("resourcePack")
    return pack if isinstance(pack, dict) else {}


def configured_pack_mode(config: dict) -> str:
    """resourcePack.mode as the CLI reads it: a missing mode means none."""
    return str(configured_pack(config).get("mode") or "none").strip().lower()


def positive_runs(value: str) -> int | None:
    if not re.fullmatch(r"[+-]?[0-9]+", value.strip()):
        return None
    try:
        count = int(value)
    except ValueError:
        return None
    return count if 0 < count <= 2147483647 else None


def build_command(
    config: dict,
    *,
    mode: str,
    loader: str = "",
    sdk: str = "release",
    world: str = "",
    resource_pack: str = "",
    camera: str = "",
    preset: str = "",
    runs: str = "",
) -> list[str]:
    """Resolve flag or dashboard choices without fetching, staging, or launching."""
    if mode == "play" and world.lower() == "none":
        mode = "menu"
    if mode == "benchmark" and world.lower() == "none":
        raise ValueError("Benchmark mode requires a world; choose a world or omit --world to use the pinned default.")
    if mode == "play":
        world = world or (config.get("world") or {}).get("name", "")
        if not world:
            raise ValueError("No world specified. Choose a local world or set 'world.name' in tools/config.local.json.")
        args = ["launch", "--world", world]
    else:
        args = ["run" if mode == "benchmark" else "launch"]
    if loader:
        args += ["--loader", loader]
    args += ["--sdk-variant", sdk]
    if resource_pack.strip():
        args += ["--resource-pack", resource_pack]
    if preset:
        args += ["--preset", preset]
    if mode == "benchmark" and world:
        args += ["--world", world]
    if mode == "benchmark":
        if camera:
            args += ["--camera", camera]
        count = positive_runs(runs)
        if count is not None:
            args += ["--runs", str(count)]
    return args


@dataclass
class State:
    loader: str = field(default_factory=lambda: loader_registry.load_registry().default_id)
    sdk: str = "release"
    world_enabled: bool = True
    resource_pack_enabled: bool = True
    camera: str = ""
    runs: int = 1
    preset: str = "Custom"

    @classmethod
    def defaults(cls, config: dict) -> State:
        world = config.get("world") or {}
        return cls(
            loader=tools_config.mod_loader(config),
            camera=world.get("camera") or "",
            preset=(config.get("render") or {}).get("preset") or "Custom",
        )

    def world_label(self, config: dict) -> str:
        if not self.world_enabled:
            return "Off (main menu)"
        pin = config.get("world") or {}
        name = pin.get("name")
        label = f"{name} {pin.get('version') or ''}".strip()
        return f"On ({label})" if name else "On (unconfigured)"

    def pack_label(self, config: dict) -> str:
        if not self.resource_pack_enabled:
            return "Off (baseline only)"
        pack, mode = configured_pack(config), configured_pack_mode(config)
        if mode == "pinned":
            name, version = pack.get("name"), pack.get("version")
            return f"On ({name} {version})" if name and version else "On (pinned, name or version unset)"
        if mode == "local":
            return f"On (local {pack['localPath']})" if pack.get("localPath") else "On (local, localPath unset)"
        return f"On (mode {mode} in configuration)"

    def pack_spec(self) -> str:
        """The --resource-pack override: none when the toggle is off.

        When it is on there is no override, so resourcePack.mode in the merged
        configuration decides, exactly as it does for the CLI."""
        return "" if self.resource_pack_enabled else "none"

    def notices(self, config: dict, *, benchmark: bool) -> list[str]:
        """Plain notices for toggles that are on but have nothing configured."""
        notices = []
        if self.world_enabled and not (config.get("world") or {}).get("name") and not benchmark:
            notices.append("No world is configured; launching the main menu.")
        if self.resource_pack_enabled and configured_pack_mode(config) == "none":
            notices.append("No shared art pack is configured; a selected world fixture may still provide one.")
        return notices

    def command(self, config: dict, *, benchmark: bool) -> list[str]:
        world = (config.get("world") or {}).get("name") if self.world_enabled else ""
        mode = "benchmark" if benchmark else "play" if world else "menu"
        return build_command(
            config,
            mode=mode,
            loader=self.loader,
            sdk=self.sdk,
            world=world or "none",
            resource_pack=self.pack_spec(),
            preset=self.preset,
            camera=self.camera if benchmark else "",
            runs=str(self.runs) if benchmark else "",
        )


def load_state(path: Path, config: dict) -> State:
    state = State.defaults(config)
    try:
        loaded = json.loads(path.read_text(encoding="utf-8-sig"))
    except FileNotFoundError, json.JSONDecodeError, UnicodeError:
        return state
    if isinstance(loaded, dict):
        for field in fields(state):
            if loaded.get(field.name) is not None:
                setattr(state, field.name, loaded[field.name])
    if state.loader not in loader_registry.load_registry().ids:
        state.loader = State.defaults(config).loader
    return state


def save_state(path: Path, state: State) -> None:
    path.write_text(json.dumps(asdict(state), indent=2) + "\n", encoding="utf-8")


def preset_label(name: str) -> str:
    return "Custom (in-game settings)" if not name.strip() or name == "Custom" else name


def auto_restage_enabled() -> bool:
    return sdk.configured_sdk_auto_restage(read_shared_config())


def auto_restage_label(enabled: bool) -> str:
    return "on (re-stage a changed local build at launch)" if enabled else "off (warn and use the staged copy)"


def sdk_source_label(status: dict) -> str:
    if status.get("sourceType") == "local":
        labels = {
            "local-refresh-available": "local (refresh available)",
            "local-source-missing": "local (source missing)",
            "local-source-unknown": "local (source unknown)",
        }
        return labels.get(
            status.get("state", ""), f"local ({status['sourcePath']})" if status.get("sourcePath") else "local"
        )
    if status.get("state") == "packman-missing":
        return "packman (not staged)"
    if status.get("sourceType") == "packman":
        return "packman"
    return "unavailable (run rtx.bat sdk status)"


@dataclass
class Item:
    id: str
    label: str
    detail: str = ""
    is_default: bool = False
    group: str = ""

    def text(self) -> str:
        label = f"{self.label:<16} {self.detail}" if self.detail else self.label
        return label + (" (default)" if self.is_default else "")


def dashboard_items(state: State, config: dict, status: dict, auto: bool) -> list[Item]:
    defaults = State.defaults(config)
    selected_loader = loader_registry.load_registry().get(state.loader)
    return [
        Item("launch", "Launch game", "Fetch, stage, build, and play current world", group="verb"),
        Item("benchmark", "Run benchmark", "Benchmark current world + camera", group="verb"),
        Item(
            "world",
            "World",
            state.world_label(config),
            state.world_enabled,
            "setting",
        ),
        Item("resourcepack", "Art pack", state.pack_label(config), state.resource_pack_enabled, "setting"),
        Item("loader", "Mod loader", selected_loader.display_name, state.loader == defaults.loader, "setting"),
        Item("launcher", "Launcher", client.active().describe(), group="setting"),
        Item("preset", "Quality preset", preset_label(state.preset), state.preset == defaults.preset, "setting"),
        Item("sdk", "SDK variant", state.sdk, state.sdk == defaults.sdk, "sdk"),
        Item("sdksource", "SDK source", sdk_source_label(status), group="sdk"),
        Item("sdkauto", "SDK auto-restage", auto_restage_label(auto), auto, "sdk"),
        Item("exit", "Exit", "Close launcher", group="exit"),
    ]


def sdk_choices(status: dict) -> list[Item]:
    choices = []
    if status.get("sourceType") == "local" and status.get("canRestage"):
        choices.append(Item("restage", f"Re-stage current local build ({status.get('sourcePath', '')})"))
    choices.append(Item("local", "Stage a local build..."))
    choices.append(
        Item("packman", "Refresh Packman SDK" if status.get("sourceType") == "packman" else "Switch to Packman SDK")
    )
    return choices


class Menu:
    def __init__(self, console: Console, command: Command, *, dry_run: bool = False):
        self.console = console
        self.command = command
        self.dry_run = dry_run
        self.config = read_shared_config()
        self.state_path = util.TOOLS_DIR / ".launcher-state.json"
        self.state = load_state(self.state_path, self.config)
        self.sdk_status_cache: dict | None = None
        self.preset_names_cache: list[str] | None = None
        # Printed by interactive() once the console's alternate screen is gone.
        self.notices: list[str] = []

    def save(self) -> None:
        if not self.dry_run:
            save_state(self.state_path, self.state)

    def report(self, args: list[str]) -> dict[str, Any] | None:
        output = io.StringIO()
        with redirect_stdout(output):
            result = self.command(args)
        if result:
            return None
        text = output.getvalue()
        start, end = text.find("{"), text.rfind("}")
        if start < 0 or end <= start:
            return None
        return json.loads(text[start : end + 1])

    def sdk_status(self) -> dict:
        if self.sdk_status_cache is None:
            self.sdk_status_cache = self.report(["sdk", "status", "--json"]) or {
                "arch": "unknown",
                "sourceType": "unknown",
                "state": "status-unavailable",
            }
        return self.sdk_status_cache

    def choose(self, title: str, labels: list[str], *, rows: list[str] | None = None, quit_key: bool = False) -> int:
        return self.console.choose(title, labels, rows=rows, quit_key=quit_key)

    def sdk_command(self, args: list[str], message: str) -> None:
        print(f"\n{message}")
        result = invoke(args, self.command, dry_run=self.dry_run)
        self.sdk_status_cache = None
        self.console.pause(
            "SDK source updated." if result == 0 and not self.dry_run else f"Command exited with code {result}."
        )

    def sign_in(self) -> None:
        print("\nOpening Prism to sign in...")
        result = invoke(["prism", "signin"], self.command, dry_run=self.dry_run)
        self.console.pause(
            "Close Prism once you have signed in; the Launcher row then names the account."
            if result == 0 and not self.dry_run
            else f"Command exited with code {result}."
        )

    def sdk_menu(self) -> None:
        status = self.sdk_status()
        arch = status.get("arch")
        arch_args = ["--arch", arch] if arch in {"win64", "arm64"} else []
        choices = sdk_choices(status)
        index = self.choose(f"SDK source for {arch or 'auto-detected architecture'}?", [item.label for item in choices])
        if index < 0:
            return
        action = choices[index].id
        args = ["sdk", action]
        if action == "local":
            default = status.get("sourcePath") or ""
            entry = self.console.prompt(f"Local dxvk build path (blank = {default or 'sibling dxvk build'})")
            path = entry if entry.strip() else default
            if path:
                args.append(path)
        self.sdk_command(args + arch_args, "Updating SDK source (network access may be required)...")

    def preset_menu(self) -> None:
        if self.preset_names_cache is None:
            report = self.report(["preset", "list", "--json"])
            self.preset_names_cache = report["presets"] if report else ["Custom"]
        names = self.preset_names_cache
        default = State.defaults(self.config).preset
        labels = [
            "Custom (leave the in-game settings alone)"
            if name == "Custom"
            else f"{name}   config default"
            if name == default
            else name
            for name in names
        ]
        index = self.choose("Which quality preset?", labels)
        if index >= 0:
            self.state.preset = names[index]
            self.save()

    def camera_menu(self) -> None:
        world = (self.config.get("world") or {}).get("name")
        if not world:
            self.state.camera = self.console.prompt("Camera (blank = default, used by Run benchmark)")
            self.save()
            return
        args = ["camera", "list", world, "--json"]
        self.console.clear()
        print(f"Listing cameras for {world}...")
        report = self.report(args)
        if report is None:
            self.console.pause("Could not list cameras (rtx.bat camera list failed).")
            return
        if not report.get("staged"):
            print(
                f"World {world} {report.get('version') or ''} is not staged locally; "
                "cameras live inside the world package."
            )
            answer = self.console.prompt("Fetch it from packman now? [y/N]")
            if not answer.lower().startswith("y"):
                return
            result = invoke(["world", "fetch", world], self.command, dry_run=self.dry_run)
            if self.dry_run:
                self.console.pause("Dry run: world was not fetched.")
                return
            if result:
                self.console.pause("World fetch failed.")
                return
            report = self.report(args)
            if not report or not report.get("staged"):
                self.console.pause("World is still not staged after the fetch.")
                return
        cameras = report.get("cameras") or []
        if not cameras:
            print(f"No cameras found in {world}.")
            self.state.camera = self.console.prompt("Camera name (blank = default)")
        else:
            labels = [f"{name}   default" if name == report.get("defaultCamera") else name for name in cameras]
            index = self.choose(f"Which camera for {world}?", labels + ["Default (blank)"])
            if index < 0:
                return
            self.state.camera = cameras[index] if index < len(cameras) else ""
        self.save()

    def benchmark_menu(self) -> bool:
        defaults = State.defaults(self.config)
        while True:
            items = [
                Item("camera", "Camera", self.state.camera or "(default)", self.state.camera == defaults.camera),
                Item("runs", "Runs", str(self.state.runs), self.state.runs == defaults.runs),
                Item("start", "Start benchmark", "Run with the options above"),
                Item("back", "Back", "Return to the main dashboard"),
            ]
            index = self.choose(
                "Run benchmark - options",
                [item.text() for item in items],
                rows=[f"World: {self.state.world_label(self.config)}"],
                quit_key=True,
            )
            if index < 0 or items[index].id == "back":
                return False
            action = items[index].id
            if action == "start":
                self.save()
                return True
            if action == "camera":
                self.camera_menu()
            elif action == "runs":
                entry = self.console.prompt("Runs (blank = default 1)")
                count = positive_runs(entry) if entry.strip() else 1
                if count is None:
                    self.console.pause("Runs must be a positive integer; keeping current value.")
                else:
                    self.state.runs = count
                    self.save()

    def run(self) -> list[str] | None:
        self.save()
        while True:
            status = self.sdk_status()
            auto = auto_restage_enabled()
            items = dashboard_items(self.state, self.config, status, auto)
            # The Launcher row's description, read once per draw: it can query Prism.
            launcher_description = next(item.detail for item in items if item.id == "launcher")
            rows = [
                f"Arch: {status.get('arch', 'unknown')}",
                f"World: {self.state.world_label(self.config)}",
                f"Art pack: {self.state.pack_label(self.config)}",
                f"Mod loader: {loader_registry.load_registry().get(self.state.loader).display_name}",
                f"Launcher: {launcher_description}",
                f"Quality preset: {preset_label(self.state.preset)}",
                f"SDK variant: {self.state.sdk}",
                f"SDK source: {sdk_source_label(status)}",
                f"SDK auto-restage: {auto_restage_label(auto)}",
                "Launch handles downloads, staging, and the Gradle build.",
            ]
            index = self.choose(
                "NVIDIA RTX Remix Minecraft - Dev Launcher", [item.text() for item in items], rows=rows, quit_key=True
            )
            if index < 0 or items[index].id == "exit":
                return None
            action = items[index].id
            if action == "launch":
                self.save()
                self.notices = self.state.notices(self.config, benchmark=False)
                return self.state.command(self.config, benchmark=False)
            if action == "benchmark" and self.benchmark_menu():
                if not self.state.world_enabled or not (self.config.get("world") or {}).get("name"):
                    self.console.pause(
                        "Turn World on and set world.name in tools/config.local.json before running a benchmark."
                    )
                else:
                    self.save()
                    self.notices = self.state.notices(self.config, benchmark=True)
                    return self.state.command(self.config, benchmark=True)
            elif action == "sdk":
                picked = self.choose("Which SDK variant?", ["Release", "Tracy (deep profiling)"])
                if picked >= 0:
                    self.state.sdk = ["release", "tracy"][picked]
                    self.save()
            elif action == "sdksource":
                self.sdk_menu()
            elif action == "sdkauto":
                state = "off" if auto else "on"
                self.sdk_command(["sdk", "auto-restage", state], f"Turning SDK auto-restage {state}...")
            elif action == "world":
                self.state.world_enabled = not self.state.world_enabled
                self.save()
            elif action == "resourcepack":
                self.state.resource_pack_enabled = not self.state.resource_pack_enabled
                self.save()
            elif action == "loader":
                registry = loader_registry.load_registry()
                picked = self.choose("Which mod loader?", [loader.display_name for loader in registry.loaders])
                if picked >= 0:
                    self.state.loader = registry.loaders[picked].id
                    self.save()
            elif action == "preset":
                self.preset_menu()
            elif action == "launcher" and hasattr(client.active(), "open_sign_in"):
                self.sign_in()


def invoke(args: list[str], command: Command, *, dry_run: bool) -> int:
    print(f"\nLauncher -> .\\rtx.bat {' '.join(args)}")
    return 0 if dry_run else command(args)


def interactive(command: Command, *, dry_run: bool = False) -> int:
    try:
        with Console() as console:
            menu = Menu(console, command, dry_run=dry_run)
            args = menu.run()
    except KeyboardInterrupt, EOFError:
        return 0
    if args is None:
        return 0
    # The console's alternate screen is gone now, so these stay visible.
    for notice in menu.notices:
        print(notice)
    return invoke(args, command, dry_run=dry_run)


def run(args: argparse.Namespace, command: Command) -> int:
    if args.mode is None:
        return interactive(command, dry_run=args.dry_run)
    resolved = build_command(
        read_shared_config(),
        mode=args.mode,
        loader=args.loader,
        sdk=args.sdk,
        world=args.world,
        resource_pack=args.resource_pack,
        camera=args.camera,
        preset=args.preset,
        runs=args.runs,
    )
    return invoke(resolved, command, dry_run=args.dry_run)

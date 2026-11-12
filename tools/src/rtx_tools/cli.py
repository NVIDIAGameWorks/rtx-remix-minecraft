# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import shutil
import sys
from pathlib import Path

from . import client, diagnostics, install, launcher, loader_registry, prism, sdk, target_status, version_catalog
from . import config as tools_config
from .benchmark import catalog, runner, screenshots, tracy, util


class HelpFormatter(argparse.RawDescriptionHelpFormatter):
    pass


DEFAULT_PRUNE_AGE = "14d"
RESULT_RUN_DIR_RE = re.compile(r"^(?P<stamp>\d{8}_\d{6})(?:_\d+)?$")


def configured_ci_runs() -> int:
    try:
        return int(runner.load_config().get("runs") or 3)
    except Exception:
        return 3


def parse_age(value: str) -> dt.timedelta:
    raw = value.strip().lower()
    units = {
        "d": "days",
        "day": "days",
        "days": "days",
        "h": "hours",
        "hour": "hours",
        "hours": "hours",
        "w": "weeks",
        "week": "weeks",
        "weeks": "weeks",
    }
    for suffix, field in sorted(units.items(), key=lambda x: len(x[0]), reverse=True):
        if raw.endswith(suffix):
            amount = float(raw[: -len(suffix)].strip())
            return dt.timedelta(**{field: amount})
    return dt.timedelta(days=float(raw))


def cmd_catalog(args) -> int:
    data = catalog.write_dashboard(default_a=args.a or "", default_b=args.b or "")
    print(f"Wrote {util.RESULTS_DIR / 'index.html'}")
    print(f"Catalog runs: {len(data['runs'])}")
    return 0


def cmd_open(args) -> int:
    catalog.write_dashboard(default_a=args.a or "", default_b=args.b or "")
    target = util.RESULTS_DIR / "index.html"
    if args.a or args.b:
        suffix = []
        if args.a:
            suffix.append(f"a={args.a}")
        if args.b:
            suffix.append(f"b={args.b}")
        print(f"Open {target}#{'&'.join(suffix)}")
    util.open_in_browser(target)
    return 0


def cmd_list(args) -> int:
    data = catalog.write_dashboard()
    runs = data["runs"]
    if args.limit:
        runs = runs[-args.limit :]
    for run in reversed(runs):
        git = run.get("git") or {}
        summary = run.get("summary") or {}
        dirty = " dirty" if git.get("dirty") else ""
        print(
            f"{run.get('id')}  "
            f"{summary.get('avgFps', 0):6.1f} FPS  "
            f"{summary.get('avgMs', 0):6.2f} ms  "
            f"{git.get('branch', 'unknown')} {git.get('commitShort', '')}{dirty}"
        )
    return 0


def resolve_run(prefix: str) -> dict:
    data = catalog.write_dashboard()
    matches = [r for r in data["runs"] if r["id"].startswith(prefix)]
    if not matches:
        raise RuntimeError(f"no run matches {prefix}")
    if len(matches) > 1:
        raise RuntimeError(f"ambiguous run prefix {prefix}: {', '.join(r['id'] for r in matches[:8])}")
    return matches[0]


def cmd_show(args) -> int:
    run = resolve_run(args.run)
    print(json.dumps(run, indent=2))
    return 0


# The launcher of a run whose manifest names none: runs went through Gradle's runClient before manifests recorded it.
UNRECORDED_LAUNCHER = "runClient"


def launcher_mismatch(a: dict, b: dict) -> str | None:
    """Which launcher each of two runs went through, or None when it was the same one."""
    first, second = (run.get("launcher") or UNRECORDED_LAUNCHER for run in (a, b))
    return None if first == second else f"A ran through {first}, B through {second}"


def cmd_compare(args) -> int:
    a = resolve_run(args.a)
    b = resolve_run(args.b)
    if mismatch := launcher_mismatch(a, b):
        print(f"WARNING: {mismatch}")
    for label, key, higher_better in [
        ("Avg FPS", "avgFps", True),
        ("Avg frame", "avgMs", False),
        ("p99 frame", "p99Ms", False),
        ("1% low FPS", "onePctLowFps", True),
        ("Frame CV", "cvPct", False),
    ]:
        av = float((a.get("summary") or {}).get(key) or 0.0)
        bv = float((b.get("summary") or {}).get(key) or 0.0)
        delta = bv - av
        good = (delta > 0) if higher_better else (delta < 0)
        marker = "=" if abs(delta) < 0.0001 else ("+" if good else "-")
        sign = "+" if delta > 0 else ""
        print(f"{marker} {label:12} A={av:.2f} B={bv:.2f} change={sign}{delta:.2f}")
    catalog.write_dashboard(default_a=a["id"], default_b=b["id"])
    print(f"Dashboard: {util.RESULTS_DIR / 'index.html'}#a={a['id']}&b={b['id']}")
    return 0


def _prunable_result_dirs(results_dir=None) -> list:
    root = results_dir or util.RESULTS_DIR
    if not root.exists():
        return []
    return sorted(
        [
            child
            for child in root.iterdir()
            if child.is_dir() and ((child / "manifest.json").exists() or RESULT_RUN_DIR_RE.match(child.name))
        ],
        key=lambda path: path.name,
    )


def _run_timestamp(run_dir) -> dt.datetime:
    if (run_dir / "manifest.json").exists():
        manifest = util.read_json(run_dir / "manifest.json")
        ts = (manifest.get("timestamp") or {}).get("utc")
        ts = ts if isinstance(ts, str) else ""
        try:
            return dt.datetime.fromisoformat(ts)
        except ValueError:
            pass

    match = RESULT_RUN_DIR_RE.match(run_dir.name)
    if match:
        try:
            return dt.datetime.strptime(match.group("stamp"), "%Y%m%d_%H%M%S").replace(tzinfo=dt.UTC)
        except ValueError:
            pass

    return dt.datetime.fromtimestamp(run_dir.stat().st_mtime, dt.UTC)


def _prune_victims(run_dirs, older_than: str | None, keep: int, now: dt.datetime) -> list:
    dated_runs = sorted(
        ((_run_timestamp(run_dir), run_dir) for run_dir in run_dirs), key=lambda item: (item[0], item[1].name)
    )

    if older_than is None and keep > 0:
        victims = [run_dir for _, run_dir in dated_runs]
    else:
        cutoff = now - parse_age(older_than or DEFAULT_PRUNE_AGE)
        victims = [run_dir for when, run_dir in dated_runs if when < cutoff]

    if keep > 0:
        victims = victims[:-keep] if len(victims) > keep else []
    return victims


def cmd_prune(args) -> int:
    if args.keep < 0:
        raise RuntimeError("--keep must be zero or greater")

    victims = _prune_victims(
        _prunable_result_dirs(),
        older_than=args.older_than,
        keep=args.keep,
        now=dt.datetime.now(dt.UTC),
    )

    if not victims:
        print("No benchmark runs matched prune criteria.")
        return 0
    for path in victims:
        print(path)
    if not args.yes:
        print("Pass --yes to delete these runs.")
        return 0
    for path in victims:
        util.ensure_inside(path, util.RESULTS_DIR)
        shutil.rmtree(path)
    catalog.write_dashboard()
    print(f"Deleted {len(victims)} run(s).")
    return 0


def cmd_delete(args) -> int:
    for run_id in args.runs:
        deleted = catalog.delete_run(run_id)
        print(f"Deleted {deleted}")
    return 0


def cmd_annotate(args) -> int:
    updated = catalog.annotate_run(args.run, note=args.note, tags=list(args.tag or []), clear_note=args.clear_note)
    print(f"Updated {updated}")
    return 0


def cmd_run(args) -> int:
    tools_config.set_runtime_mod_loader(getattr(args, "loader", None))
    runner.set_runtime_resource_pack_override(getattr(args, "resource_pack", None))
    runner.set_runtime_preset_override(getattr(args, "preset", None))
    runner.run_benchmark(args)
    return 0


def cmd_preset_list(args) -> int:
    names = list(runner.PRESET_NAMES)
    active = runner.configured_preset(runner.load_config())
    if args.json:
        print(json.dumps({"presets": names, "active": active}, indent=2))
        return 0
    for name in names:
        marker = "*" if name == active else " "
        print(f" {marker} {name}")
    return 0


def cmd_resource_pack_fetch(args) -> int:
    config = runner.load_config()
    rp = runner.configured_resource_pack(config)
    # Names compare stripped, as the configuration layers and the world pin compare them.
    name = str(args.name or rp.get("name") or "").strip()
    if not name:
        raise RuntimeError("Specify a NAME or set 'resourcePack.name' in tools/config.local.json.")
    configured_name = str(rp.get("name") or "").strip()
    configured_version = str(rp.get("version") or "").strip() if name == configured_name else ""
    version = args.version or configured_version
    if not version:
        raise RuntimeError(f"Resource pack '{name}' has no pinned version. Pass --version [v]MAJOR.MINOR.PATCH.")
    version = runner.validate_resource_pack_version(version)
    runner.fetch_resource_pack(name, version=version, config=config)
    print(f"Fetched {name}@{version}")
    return 0


def cmd_doctor(args) -> int:
    tools_config.set_runtime_mod_loader(getattr(args, "loader", None))
    report = runner.doctor_report(world=args.world, camera=args.camera, sdk_variant=args.sdk_variant)
    if args.json:
        print(json.dumps(report, indent=2))
        return 0 if report["ok"] else 1

    for check in report["checks"]:
        status = str(check["status"]).upper()
        print(f"[{status}] {check['name']}: {check['detail']}")
    return 0 if report["ok"] else 1


def print_sdk_status(report: dict, *, as_json: bool = False) -> int:
    if as_json:
        print(json.dumps(report, indent=2))
        return 0

    state_labels = {
        "packman-ready": "Packman SDK is staged",
        "packman-missing": "Packman selected; SDK is not staged yet",
        "local-current": "local staged copy matches its source",
        "local-refresh-available": "local source changed; refresh available",
        "local-source-missing": "local source is missing or has no d3d9.dll",
        "local-source-unknown": "local sentinel has no readable source path",
        "local-comparison-unavailable": "local source could not be compared with the staged copy",
    }
    print(f"SDK ({report['arch']})")
    print(f"  source: {report['sourceType']}")
    if report["sourceType"] == "local":
        print(f"  local path: {report['sourcePath'] or '(not recorded)'}")
        if report["stagedAtUtc"]:
            staged_by = f" by {report['stagedBy']}" if report["stagedBy"] else ""
            print(f"  staged: {report['stagedAtUtc']}{staged_by}")
    print(f"  target: {report['sdkDir']}")
    print(f"  d3d9.dll: {'present' if report['dllExists'] else 'missing'}")
    print(f"  status: {state_labels.get(report['state'], report['state'])}")
    if report["needsRestage"]:
        print(r"  next: tools\rtx.bat sdk restage")
    elif report["state"] in {"local-source-missing", "local-source-unknown"}:
        print(r"  next: tools\rtx.bat sdk local PATH")
    elif report["state"] == "packman-missing":
        print(r"  next: tools\rtx.bat sdk packman")
    return 0


def cmd_sdk_status(args) -> int:
    arch = runner.host_arch(args.arch)
    return print_sdk_status(sdk.sdk_source_status(arch), as_json=args.json)


def cmd_sdk_local(args) -> int:
    arch = runner.host_arch(args.arch)
    report = sdk.stage_local_sdk(arch, args.source_path or args.path)
    print("Local SDK staged successfully.")
    return print_sdk_status(report)


def cmd_sdk_restage(args) -> int:
    arch = runner.host_arch(args.arch)
    report = sdk.restage_local_sdk(arch)
    print("Local SDK re-staged successfully.")
    return print_sdk_status(report)


def cmd_sdk_packman(args) -> int:
    arches = ("win64", "arm64") if args.arch == "all" else (runner.host_arch(args.arch),)
    for arch in arches:
        report = sdk.stage_packman_sdk(arch, force=args.force)
        print("Packman SDK staged successfully.")
        print_sdk_status(report)
    return 0


def cmd_sdk_auto_restage(args) -> int:
    if args.state is not None:
        sdk.set_sdk_auto_restage(args.state == "on")
    enabled = sdk.configured_sdk_auto_restage(runner.load_config())
    if args.json:
        print(json.dumps({"autoRestage": enabled}))
    else:
        print(f"SDK auto-restage: {'on' if enabled else 'off'}")
        print(
            "  launch and run re-stage a changed local dxvk build before starting"
            if enabled
            else "  launch and run warn about a changed local dxvk build and use the staged copy"
        )
    return 0


def cmd_sdk_tracy_tools(args) -> int:
    tracy.fetch_tools()
    return 0


def cmd_world_list(args) -> int:
    records = runner.world_records()
    if not runner.configured_world_name(runner.load_config()):
        print(
            "World: none. Set 'world.name' and 'world.version' in tools/config.local.json "
            "to pin a world, or import a local world."
        )
    if not records:
        print("No benchmark worlds pinned in config or staged under assets/worlds.")
        return 0
    for record in records:
        raw_cameras = record.get("cameras") if isinstance(record, dict) else []
        cameras = [str(camera) for camera in raw_cameras] if isinstance(raw_cameras, list) else []
        camera_text = ", ".join(cameras) if cameras else "(no cameras)"
        flags = ["staged" if record.get("staged") else "not-staged"]
        if record.get("modified"):
            flags.append("version-mismatch")
        pin = record.get("pinned") or "(unpinned)"
        staged_version = record.get("stagedVersion") or "(none)"
        print(f"{record['name']}  [{', '.join(flags)}]  pin: {pin}  staged: {staged_version}  cameras: {camera_text}")
    return 0


def print_versions_report(report: dict, *, as_json: bool) -> int:
    if as_json:
        print(json.dumps(report, indent=2))
        return 0
    entries = report["versions"]
    if not entries:
        print(f"No pinned or locally staged versions of '{report['name']}'.")
    for entry in entries:
        flags = ["staged"] if entry["staged"] else ["not staged yet; launching or fetching downloads it"]
        if entry["pinned"]:
            flags.insert(0, "pinned")
        print(f"{entry['version']}  [{', '.join(flags)}]")
    return 0


def cmd_world_versions(args) -> int:
    config = runner.load_config()
    world = args.world or runner.configured_world_name(config)
    if not world:
        raise RuntimeError("Specify a WORLD or set 'world.name' in tools/config.local.json.")
    return print_versions_report(runner.world_versions_report(world), as_json=args.json)


def cmd_resource_pack_versions(args) -> int:
    config = runner.load_config()
    name = args.name or str(runner.configured_resource_pack(config).get("name") or "")
    if not name:
        raise RuntimeError("Specify a resource pack NAME or set 'resourcePack.name' in tools/config.local.json.")
    return print_versions_report(runner.resource_pack_versions_report(name), as_json=args.json)


def cmd_world_fetch(args) -> int:
    config = runner.load_config()
    world = args.world or runner.configured_world_name(config)
    if not world:
        raise RuntimeError("Specify a WORLD or set 'world.name' in tools/config.local.json.")
    version = args.version or runner.world_pin_version(config, world)
    if not version:
        raise RuntimeError(f"World '{world}' has no pinned version. Pass --version vMAJOR.MINOR.PATCH.")
    version = runner.validate_world_version(version)
    runner.fetch_world(world, version=version)
    print(f"Fetched {world}@{version}")
    return 0


def cmd_world_launch(args) -> int:
    tools_config.set_runtime_mod_loader(getattr(args, "loader", None))
    runner.set_runtime_resource_pack_override(getattr(args, "resource_pack", None))
    runner.launch_client_for_selection(
        world=args.world,
        mode="play",
        quiet=args.quiet,
        sdk_variant=args.sdk_variant,
        strip_entities=args.strip_entities,
        version=args.version,
    )
    return 0


def cmd_launch(args) -> int:
    tools_config.set_runtime_mod_loader(getattr(args, "loader", None))
    runner.set_runtime_preset_override(getattr(args, "preset", None))
    if args.world:
        runner.launch_with_resource_pack_override(
            world=args.world,
            sdk_variant=args.sdk_variant,
            resource_pack=args.resource_pack,
            version=args.version,
            quiet=args.quiet,
        )
    else:
        if args.resource_pack is not None:
            runner.set_runtime_resource_pack_override(args.resource_pack)
        runner.launch_client_main_menu(sdk_variant=args.sdk_variant, quiet=args.quiet)
    return 0


def cmd_prism_signin(args) -> int:
    active = client.active()
    open_sign_in = getattr(active, "open_sign_in", None)
    if open_sign_in is None:
        print(f"Nothing to sign in to: {active.name} does not launch through Prism.")
        return 1
    open_sign_in()
    print(prism.SIGN_IN_OPENED_STEPS)
    return 0


def cmd_install(args) -> int:
    tools_config.set_runtime_mod_loader(args.loader)
    mod_loader = tools_config.mod_loader(tools_config.load_config())
    return install.install_development_build(
        util.PROJECT_DIR,
        game_dir=args.game_dir,
        arch=args.arch,
        mod_loader=mod_loader,
    )


def cmd_textures_water_normals(args) -> int:
    seed = 225 if args.seed is None else args.seed
    if args.native:
        from . import native_water_normals

        bridge = util.project_path(args.bridge)
        texture = native_water_normals.generate(bridge, seed)
        print(
            f"Generated {len(texture.data)} raw RG8 bytes in memory through {bridge} "
            f"in {texture.timings.generation_seconds:.3f}s"
        )
        if args.reference:
            comparison = native_water_normals.compare(texture, util.project_path(args.reference))
            rms = ", ".join(f"{value:.6f}" for value in comparison.pillow_rms)
            print(
                f"Reference comparison: raw SHA-256 {comparison.sha256}, "
                f"DDS SHA-256 {comparison.reference_sha256}, "
                f"Pillow RMS [{rms}], max {comparison.pillow_max}"
            )
            # BC5 is lossy; the raw source may differ from its decoded reference
            # by one 8-bit quantization step, but never more.
            return 0 if max(comparison.pillow_max) <= 1 else 1
        return 0

    # Only this command needs numpy, so the rest of the CLI starts without importing it.
    from . import water_normals

    out = util.project_path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    params = water_normals.WaterParams() if args.seed is None else water_normals.WaterParams(seed=args.seed)
    stats = water_normals.write_flipbook(out, params)
    print(f"Wrote {out}")
    print(
        f"RMS slope {stats.slope_rms:.4f}, largest encoded tilt {stats.encoded_max:.3f}, "
        f"BC5 error {stats.encode_rms:.2f} steps RMS"
    )
    return 0


def cmd_world_import(args) -> int:
    client.active()
    target = runner.import_world(args.source, name=args.name, replace=args.replace)
    print(f"Imported world: {target}")
    return 0


def cmd_world_update(args) -> int:
    client.active()
    target = runner.update_world(args.world, source=args.source)
    print(f"Updated world: {target}")
    return 0


def cmd_camera_list(args) -> int:
    report = runner.camera_report(args.world, getattr(args, "version", None))
    if args.json:
        print(json.dumps(report, indent=2))
        return 0
    if not report["staged"]:
        target = f"{report['world']} {report['version']}" if report["version"] else report["world"]
        print(f"World {target} is not staged; cameras live inside the world package.")
        name = runner.world_dir_for(report["world"], report["version"]).name
        print(f"Import a local save first: tools\\rtx.bat world import <save-path> --name {name}")
        if runner.configured_world(runner.load_config()).get("remote"):
            version_arg = f" --version {report['version']}" if report["version"] else ""
            print(f"Or fetch it: tools\\rtx.bat world fetch {report['world']}{version_arg}")
        return 1
    if not report["cameras"]:
        print(f"No cameras found for {args.world}.")
        return 0
    for camera in report["cameras"]:
        marker = "  (default)" if camera == report["defaultCamera"] else ""
        print(f"{camera}{marker}")
    return 0


def cmd_screenshots_list(args) -> int:
    return screenshots.list_shots(as_json=args.json)


def cmd_screenshots_refresh(args) -> int:
    tools_config.set_runtime_mod_loader(getattr(args, "loader", None))
    return screenshots.refresh(args.names, sdk_variant=args.sdk_variant, timeout_seconds=args.timeout)


def cmd_menu(args) -> int:
    return launcher.run(args, main)


def cmd_check(args) -> int:
    targets = ["src", "tests"]
    cwd = util.TOOLS_DIR
    if args.fix:
        util.run(["ruff", "format", *targets], cwd=cwd)
        util.run(["ruff", "check", "--fix", *targets], cwd=cwd)
    else:
        util.run(["ruff", "format", "--check", *targets], cwd=cwd)
        util.run(["ruff", "check", *targets], cwd=cwd)
    if not args.no_types:
        util.run(["ty", "check"], cwd=cwd)
    if args.tests:
        util.run([sys.executable, "-m", "pytest", "-q"], cwd=cwd)
    return 0


def cmd_version_catalog(args) -> int:
    report = version_catalog.report(util.PROJECT_DIR)
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        version_catalog.print_report(report)
    return 0


def cmd_target_status(args) -> int:
    registry = loader_registry.load_registry()
    validation = target_status.run_validation(util.PROJECT_DIR, registry, args.run)
    report = target_status.report(util.PROJECT_DIR, validation, minecraft_version=registry.minecraft_version)
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        target_status.print_report(report)
    states = [
        report["automatedContracts"]["state"],
        *(loader["build"]["state"] for loader in report["loaders"]),
    ]
    return 1 if "failed" in states else 0


def cmd_diag_hang(args) -> int:
    client.active()
    return diagnostics.hang(pid=args.pid, print_output_root=args.print_output_root)


def cmd_diag_focus_torture(args) -> int:
    client.active()
    return diagnostics.focus_torture(
        max_cycles=args.max_cycles,
        dwell_seconds=args.dwell_seconds,
        print_output_root=args.print_output_root,
    )


def add_loader_argument(parser: argparse.ArgumentParser, help_text: str, *, default: str | None = None) -> None:
    def catalog_loader(value: str) -> str:
        if not value:
            return ""
        try:
            return loader_registry.load_registry().get(value).id
        except loader_registry.ManifestError as exc:
            raise argparse.ArgumentTypeError(str(exc)) from exc

    parser.add_argument(
        "--loader",
        type=catalog_loader,
        default=default,
        metavar="LOADER",
        help=help_text,
    )


def build_parser() -> argparse.ArgumentParser:
    ci_runs = configured_ci_runs()
    default_loader = loader_registry.load_registry().default_id
    parser = argparse.ArgumentParser(
        prog=r"tools\rtx.bat",
        description="Launch, benchmark, and manage RTX Remix Minecraft developer assets.",
        epilog="""examples:
  tools\\rtx.bat prism signin
  tools\\rtx.bat sdk status
  tools\\rtx.bat sdk restage
  tools\\rtx.bat run
  tools\\rtx.bat open --a 20260501_103000 --b 20260501_111500
  tools\\rtx.bat doctor
  tools\\rtx.bat world launch
  tools\\rtx.bat world launch RTXDemo
  tools\\rtx.bat world update MyWorld_v0.1.0
  tools\\rtx.bat run --world RTXDemo --camera benchmark
  tools\\rtx.bat annotate 20260501_103000 --tag local --note "interesting stutter"
  tools\\rtx.bat delete 20260501_103000
""",
        formatter_class=HelpFormatter,
    )
    parser.add_argument(
        "--minecraft-version",
        metavar="VERSION",
        help=(
            "Minecraft target for this invocation; accepted before or after "
            "the command (default: RTXREMIX_MINECRAFT_VERSION, then catalog default)"
        ),
    )
    sub = parser.add_subparsers(dest="command", required=True, metavar="command")

    p = sub.add_parser("menu", help="open the developer dashboard or resolve a launch with --mode")
    p.add_argument(
        "--mode",
        choices=["menu", "play", "benchmark"],
        type=str.lower,
        help="skip the dashboard: menu launches to the main menu, play into the world, benchmark runs a benchmark",
    )
    add_loader_argument(
        p,
        "mod loader for the resolved launch or benchmark (default: dashboard/config selection)",
        default="",
    )
    p.add_argument(
        "--sdk",
        choices=["release", "tracy"],
        type=str.lower,
        default="release",
        help="SDK variant, passed on as --sdk-variant (default: release)",
    )
    p.add_argument(
        "--world",
        default="",
        metavar="NAME",
        help="world to launch or benchmark (default: world.name); none launches to the main menu in play mode",
    )
    p.add_argument(
        "--resource-pack",
        default="",
        metavar="SPEC",
        help=(
            "art pack: none | local:<path> | <name>@<version> | pinned (default: resourcePack in the configuration); "
            "a relative path starts at the repository root"
        ),
    )
    p.add_argument("--camera", default="", metavar="NAME", help="benchmark camera path; benchmark mode only")
    p.add_argument(
        "--preset", default="", metavar="NAME", help="Remix graphics tier (default: render.preset in the configuration)"
    )
    p.add_argument("--runs", default="", metavar="N", help="positive benchmark run count")
    p.add_argument("--dry-run", action="store_true", help="print the resolved rtx invocation without running it")
    p.set_defaults(func=cmd_menu)

    p = sub.add_parser(
        "run",
        help="run a benchmark and write a new result directory",
        description=(
            "Builds the current checkout, stages the configured RTX Remix SDK,\n"
            "launches Minecraft, captures benchmark/JFR/Tracy artifacts, and\n"
            "writes a timestamped result directory."
        ),
        formatter_class=HelpFormatter,
    )
    p.add_argument(
        "--ci", action="store_true", help="use CI defaults for run count, metadata, recording, and drain time"
    )
    add_loader_argument(
        p,
        "mod loader to build and benchmark (default: RTXREMIX_MOD_LOADER, then merged configuration)",
    )
    p.add_argument(
        "--runs",
        type=int,
        default=0,
        metavar="N",
        help=f"override run count; default is 1 locally or {ci_runs} in CI from the benchmark package config.json",
    )
    p.add_argument(
        "--world",
        metavar="NAME",
        help=(
            "logical benchmark world name; the selected version is stored under "
            "tools/assets/worlds/<name>_<version> and defaults come from merged configuration"
        ),
    )
    p.add_argument(
        "--camera",
        metavar="NAME",
        help=(
            "camera path under the selected world's cameras/ directory; "
            "default can be overridden by the world's config.json"
        ),
    )
    p.add_argument(
        "--version",
        default=None,
        metavar="vMAJOR.MINOR.PATCH",
        help=(
            "run a specific on-disk/published world version instead of the pinned "
            "one (local/ad-hoc; ignored under --ci)"
        ),
    )
    p.add_argument(
        "--sdk-variant",
        choices=["release", "tracy"],
        default="release",
        help=(
            "SDK variant to stage before running; tracy adds native Tracy capture and needs a "
            "Tracy-enabled runtime (default: release)"
        ),
    )
    p.add_argument(
        "--arch",
        choices=["win64", "arm64"],
        help="native SDK architecture to benchmark; defaults to host detection",
    )
    p.add_argument("--note", default="", metavar="TEXT", help="note saved in the run manifest and dashboard")
    p.add_argument("--tag", action="append", default=[], metavar="TAG", help="tag saved in the manifest; repeatable")
    p.add_argument("--open", action="store_true", help="open the dashboard after the run completes")
    p.add_argument("--no-build", action="store_true", help="skip the Gradle build before launching Minecraft")
    p.add_argument(
        "--strip-entities",
        action="store_true",
        help="remove saved entity storage from the staged benchmark copy before launch",
    )
    p.add_argument(
        "--dry-run",
        action="store_true",
        help="print the resolved plan without fetching SDKs, building, launching, capturing, or writing files",
    )
    p.add_argument(
        "--resource-pack",
        default=None,
        metavar="SPEC",
        help="art pack: none | local:<path> | <name>@<version> | pinned; a relative path starts at the repository root",
    )
    p.add_argument(
        "--preset",
        default=None,
        metavar="NAME",
        help="Remix graphics tier to pin for this run; overrides the merged render.preset setting",
    )
    p.set_defaults(func=cmd_run)

    p = sub.add_parser(
        "launch",
        help="launch the game (main menu or into a world) with SDK + resource-pack selection",
        description=(
            "Builds the mod, installs it into this checkout's Prism instance, and starts Minecraft through\n"
            "Prism as the signed-in Microsoft account; use prism signin if needed. Omit --world for the\n"
            "main menu."
        ),
        formatter_class=HelpFormatter,
    )
    p.add_argument("--world", default=None, metavar="WORLD", help="world to quick-play into; omit for the main menu")
    add_loader_argument(
        p,
        "mod loader to build and launch (default: RTXREMIX_MOD_LOADER, then merged configuration)",
    )
    p.add_argument(
        "--sdk-variant",
        choices=["release", "tracy"],
        default="release",
        help="SDK variant to stage before launch (default: release)",
    )
    p.add_argument(
        "--resource-pack",
        default=None,
        metavar="SPEC",
        help="art pack: none | local:<path> | <name>@<version> | pinned; a relative path starts at the repository root",
    )
    p.add_argument(
        "--preset",
        default=None,
        metavar="NAME",
        help="Remix graphics tier for this launch; overrides the merged render.preset setting",
    )
    p.add_argument("--version", default=None, metavar="vMAJOR.MINOR.PATCH", help="world version override")
    p.add_argument("--quiet", action="store_true", help="print less while starting the game")
    p.set_defaults(func=cmd_launch)

    p = sub.add_parser(
        "prism",
        help="manage the Prism Launcher used by this checkout",
        description=(
            "The tools launch Minecraft through a checkout-specific Prism instance, signed in to a Microsoft\n"
            "account that owns Minecraft: Java Edition. Installed Prism reuses its normal account profile;\n"
            "portable Prism keeps its profile under this checkout's run-prism folder."
        ),
        formatter_class=HelpFormatter,
    )
    prism_sub = p.add_subparsers(dest="prism_command", required=True, metavar="prism-command")
    pp = prism_sub.add_parser(
        "signin",
        help="open Prism to sign in to a Microsoft account",
        description=(
            "Opens the selected Prism profile. Sign in on the page Prism shows (or Accounts > Manage Accounts\n"
            "> Add Microsoft) with the account that owns Minecraft: Java Edition, and close Prism."
        ),
        formatter_class=HelpFormatter,
    )
    pp.set_defaults(func=cmd_prism_signin)

    p = sub.add_parser(
        "install",
        help="install a built development mod into an existing loader installation",
        description=(
            "Installs the newest runnable development JAR for the selected loader, the\n"
            "staged runtime, JNI bridge, gameplay conf payload, and runtime textures.\n"
            "Baseline PBR textures are built into the JAR; packaging/migrate_resource_packs.ps1\n"
            "removes the retired generated baseline and preserves user-pack ordering.\n"
            "Build first with the selected loader's Gradle build task.\n"
            "The game directory must already have the selected loader and its dependencies;\n"
            "a missing Fabric API JAR produces a warning for Fabric. This does not launch the game."
        ),
        epilog=(
            "REMIX_BUILD overrides remix-runtime/<arch> with a custom runtime directory.\n"
            "Set REMIX_FORCE_SDK_COPY=1 to recopy an unchanged SDK. Relative paths\n"
            "are resolved from the repository root."
        ),
        formatter_class=HelpFormatter,
    )
    p.add_argument("--game-dir", type=Path, metavar="DIR", help=r"game directory (default: %%APPDATA%%\.minecraft)")
    p.add_argument("--arch", choices=["win64", "arm64"], help="target architecture (default: REMIX_ARCH, then host)")
    add_loader_argument(
        p,
        f"mod loader (default: RTXREMIX_MOD_LOADER, then tools config, then {default_loader})",
    )
    p.set_defaults(func=cmd_install)

    p = sub.add_parser(
        "textures",
        help="generate texture assets and references",
        description="Generates texture assets and developer reference outputs.",
        formatter_class=HelpFormatter,
    )
    textures_sub = p.add_subparsers(dest="textures_command", required=True, metavar="textures-command")
    tp = textures_sub.add_parser(
        "water-normals",
        help="generate the animated water normal flipbook",
        description=(
            "Generates 120 frames of a looping, tiling ocean surface packed 12 x 10 into\n"
            "one texture. Python writes a BC5 reference DDS; --native retains raw RG8\n"
            "pixels in memory and can compare them with --reference."
        ),
        epilog="""examples:
  tools\\rtx.bat textures water-normals
  tools\\rtx.bat textures water-normals --seed 7 --out build\\textures\\water_seed7.dds
  tools\\rtx.bat textures water-normals --native --reference build\\textures\\water_normals_flipbook.dds
""",
        formatter_class=HelpFormatter,
    )
    tp.add_argument(
        "--out",
        default="build/textures/water_normals_flipbook.dds",
        metavar="PATH",
        help="Python reference output; unused with --native (default: %(default)s)",
    )
    tp.add_argument("--seed", type=int, default=None, metavar="N", help="surface seed (default: the shipped surface's)")
    tp.add_argument(
        "--native",
        action="store_true",
        help="generate through the already-built Win64 bridge DLL instead of the Python reference",
    )
    tp.add_argument(
        "--bridge",
        default="build/native/win64/remixbridge.dll",
        metavar="PATH",
        help="bridge DLL used with --native; a relative path starts at the repository root (default: %(default)s)",
    )
    tp.add_argument(
        "--reference",
        default=None,
        metavar="DDS",
        help="with --native, compare in-memory output against this DDS using SHA-256 and Pillow",
    )
    tp.set_defaults(func=cmd_textures_water_normals)

    p = sub.add_parser(
        "preset",
        help="list the quality presets",
        description=(
            "A quality preset selects one of Remix's graphics tiers for one launch\n"
            "and changes nothing else. Render distance, simulation distance,\n"
            "graphics mode and the mod's distance settings come from render.* in\n"
            "tools/config.json."
        ),
        formatter_class=HelpFormatter,
    )
    preset_sub = p.add_subparsers(dest="preset_command", metavar="COMMAND", required=True)
    q = preset_sub.add_parser("list", help="list available presets in display order")
    q.add_argument("--json", action="store_true", help="print the presets as JSON")
    q.set_defaults(func=cmd_preset_list)

    p = sub.add_parser(
        "diag",
        help="capture hang evidence or reproduce focus-change hangs on Windows",
        description=(
            "Capture Java thread stacks and full native dumps under hang-dumps in the game directory without killing "
            "the client."
        ),
        formatter_class=HelpFormatter,
    )
    diag_sub = p.add_subparsers(dest="diag_command", metavar="COMMAND", required=True)
    q = diag_sub.add_parser("hang", help="capture a running client's Java and native stacks")
    q.add_argument("--pid", type=int, default=0, metavar="N", help="target PID; default discovers the Minecraft client")
    q.add_argument("--print-output-root", action="store_true", help="print the output root without capturing")
    q.set_defaults(func=cmd_diag_hang)

    q = diag_sub.add_parser("focus-torture", help="cycle focus and minimize/restore until the client hangs")
    q.add_argument("--max-cycles", type=int, default=80, metavar="N", help="maximum focus cycles (default: 80)")
    q.add_argument("--dwell-seconds", type=int, default=3, metavar="N", help="seconds per focus state (default: 3)")
    q.add_argument("--print-output-root", action="store_true", help="print the output root without cycling focus")
    q.set_defaults(func=cmd_diag_focus_torture)

    p = sub.add_parser(
        "doctor",
        help="check benchmark configuration without changing files",
        description=(
            "Validates benchmark config, selected world/camera, Java architecture,\n"
            "the client launcher and its sign-in, SDK state, and Tracy tool availability\n"
            "without fetching, staging, launching, or opening anything."
        ),
        formatter_class=HelpFormatter,
    )
    add_loader_argument(
        p,
        "mod loader whose launch configuration to inspect (default: environment, then merged configuration)",
    )
    p.add_argument(
        "--world",
        metavar="NAME",
        help="benchmark world to validate; defaults to the merged world.name setting",
    )
    p.add_argument(
        "--camera",
        metavar="NAME",
        help="camera path to validate; default can be overridden by the world's config.json",
    )
    p.add_argument(
        "--sdk-variant",
        choices=["release", "tracy"],
        default="release",
        help="SDK variant to inspect, as for run; tracy also checks the Tracy tools (default: release)",
    )
    p.add_argument("--json", action="store_true", help="print the full diagnostic report as JSON")
    p.set_defaults(func=cmd_doctor)

    p = sub.add_parser(
        "sdk",
        help="inspect and change the SDK source, or fetch matching Tracy tools",
        description=(
            "Manage the architecture-specific SDK staged under remix-runtime/<arch>. "
            "Local dxvk builds are copied snapshots, so re-stage after rebuilding _output."
        ),
        formatter_class=HelpFormatter,
    )
    sdk_sub = p.add_subparsers(dest="sdk_command", required=True, metavar="sdk-command")

    sp = sdk_sub.add_parser(
        "status",
        help="show the SDK source and whether a local refresh is available",
        description="Inspect SDK source state without fetching, copying, or changing files.",
        formatter_class=HelpFormatter,
    )
    sp.add_argument("--arch", choices=["win64", "arm64"], help="SDK architecture; defaults to Java/host detection")
    sp.add_argument("--json", action="store_true", help="print the full SDK source report as JSON")
    sp.set_defaults(func=cmd_sdk_status)

    sp = sdk_sub.add_parser(
        "local",
        help="stage or refresh an SDK from a local dxvk build",
        description=(
            "Copy a dxvk-remix _output directory into the selected SDK staging directory. "
            "With no PATH, reuse the recorded local source, then sdk.localPath from tools/config.local.json, "
            "then ../dxvk-remix/_output. A relative path starts at the repository root."
        ),
        formatter_class=HelpFormatter,
    )
    local_path = sp.add_mutually_exclusive_group()
    local_path.add_argument("path", nargs="?", metavar="PATH", help="dxvk _output directory containing d3d9.dll")
    local_path.add_argument(
        "--path", dest="source_path", metavar="PATH", help="dxvk _output directory containing d3d9.dll"
    )
    sp.add_argument("--arch", choices=["win64", "arm64"], help="SDK architecture; defaults to Java/host detection")
    sp.set_defaults(func=cmd_sdk_local)

    sp = sdk_sub.add_parser(
        "restage",
        help="refresh from the currently recorded local source",
        description=(
            "Re-copy the local dxvk build recorded in .local-sdk-source. Use this after rebuilding dxvk-remix/_output."
        ),
        formatter_class=HelpFormatter,
    )
    sp.add_argument("--arch", choices=["win64", "arm64"], help="SDK architecture; defaults to Java/host detection")
    sp.set_defaults(func=cmd_sdk_restage)

    sp = sdk_sub.add_parser(
        "packman",
        help="switch to or refresh the published Packman SDK",
        description="Remove any local override and stage the Packman SDK pinned in packman/remix-sdk.packman.xml.",
        formatter_class=HelpFormatter,
    )
    sp.add_argument(
        "--arch", choices=["win64", "arm64", "all"], help="SDK architecture; defaults to Java/host detection"
    )
    sp.add_argument(
        "--force", action="store_true", help="re-fetch even if the pinned Packman junction is already staged"
    )
    sp.set_defaults(func=cmd_sdk_packman)

    sp = sdk_sub.add_parser(
        "auto-restage",
        help="show or set whether launch re-stages a changed local build (default on)",
        description=(
            "With no argument, show the merged sdk.autoRestage setting. "
            "With on or off, write tools/config.local.json. On, launch and run re-stage "
            "a changed local dxvk build; off, they warn and use the staged copy."
        ),
        formatter_class=HelpFormatter,
    )
    sp.add_argument("state", nargs="?", choices=["on", "off"], help="new value; omit to show the current one")
    sp.add_argument("--json", action="store_true", help="print the setting as JSON")
    sp.set_defaults(func=cmd_sdk_auto_restage)

    sp = sdk_sub.add_parser(
        "tracy-tools",
        help="fetch pinned Tracy capture and CSV export tools without launching a client",
        description=(
            "Download matching Tracy tools from GitHub into tools/tracy-tools. No runtime or assets are required."
        ),
        formatter_class=HelpFormatter,
    )
    sp.set_defaults(func=cmd_sdk_tracy_tools)

    p = sub.add_parser(
        "world",
        help="list, import, and launch benchmark worlds",
        description=(
            "Manage versioned tools/assets/worlds/<name>_<version>/ fixtures "
            "and launch Minecraft into a selected world."
        ),
        formatter_class=HelpFormatter,
    )
    world_sub = p.add_subparsers(dest="world_command", required=True, metavar="world-command")

    wp = world_sub.add_parser("list", help="list benchmark worlds and cameras", formatter_class=HelpFormatter)
    wp.set_defaults(func=cmd_world_list)

    wp = world_sub.add_parser(
        "versions",
        help="list a world's pinned and locally staged versions",
        description=(
            "Lists vMAJOR.MINOR.PATCH versions of a world: its configured pin and versions "
            "staged under tools/assets/worlds. This command does not contact Packman."
        ),
        formatter_class=HelpFormatter,
    )
    wp.add_argument(
        "world",
        nargs="?",
        metavar="WORLD",
        help="world name; defaults to the merged world.name setting",
    )
    wp.add_argument("--json", action="store_true", help="emit the machine-readable report")
    wp.set_defaults(func=cmd_world_versions)

    wp = world_sub.add_parser(
        "launch",
        help="stage and launch a benchmark world for normal play",
        description=(
            "Stages tools/assets/worlds/<world>_<version>/world as "
            "saves/rtx-benchmark in the game directory, stages its camera paths "
            "under the exact fixture key, configures managed /rtx camera "
            "authoring/playback, and launches Minecraft through this checkout's "
            "dedicated Prism instance."
        ),
        formatter_class=HelpFormatter,
    )
    wp.add_argument(
        "world",
        nargs="?",
        metavar="WORLD",
        help="benchmark world name under tools/assets/worlds; defaults to the merged world.name setting",
    )
    wp.add_argument("--quiet", action="store_true", help="print less while starting the game")
    add_loader_argument(
        wp,
        "mod loader to build and launch (default: RTXREMIX_MOD_LOADER, then merged configuration)",
    )
    wp.add_argument(
        "--sdk-variant",
        choices=["release", "tracy"],
        default="release",
        help="SDK variant to stage before launch; release is default for normal play",
    )
    wp.add_argument(
        "--strip-entities",
        action="store_true",
        help="remove saved entity storage from the staged benchmark copy before launch",
    )
    wp.add_argument(
        "--version",
        default=None,
        metavar="vMAJOR.MINOR.PATCH",
        help="launch a specific on-disk/published world version instead of the pinned one",
    )
    wp.add_argument(
        "--resource-pack",
        default=None,
        metavar="SPEC",
        help="art pack: none | local:<path> | <name>@<version> | pinned; a relative path starts at the repository root",
    )
    wp.set_defaults(func=cmd_world_launch)

    wp = world_sub.add_parser(
        "import",
        help="copy a Minecraft save into tools/assets/worlds",
        description=(
            "Copies a Minecraft save into the selected "
            "tools/assets/worlds/<name>_<version>/world and preserves cameras/."
        ),
        formatter_class=HelpFormatter,
    )
    wp.add_argument(
        "source",
        metavar="SAVE_DIR",
        help="Minecraft save directory, or a benchmark world folder; a relative path starts at the repository root",
    )
    wp.add_argument(
        "--name", default=None, metavar="WORLD", help="benchmark world name; defaults to source folder name"
    )
    wp.add_argument("--replace", action="store_true", help="replace an existing world/ directory")
    wp.set_defaults(func=cmd_world_import)

    wp = world_sub.add_parser(
        "update",
        help="replace a fixture from the staged benchmark save",
        description=(
            "Copies saves/rtx-benchmark in the game directory into tools/assets/worlds/WORLD/world,\n"
            "where WORLD is an existing fixture directory name, including any version suffix, and\n"
            "preserves the fixture's cameras/ and config.json. A WORLD with no fixture directory is\n"
            "an error; world import creates one. This is the command form of the common edit\n"
            "fixture, then save it back workflow."
        ),
        formatter_class=HelpFormatter,
    )
    wp.add_argument(
        "world",
        metavar="WORLD",
        help=(
            "existing fixture directory name under tools/assets/worlds, including any version suffix, "
            "e.g. MyWorld_v0.1.0"
        ),
    )
    wp.add_argument(
        "--source",
        default=None,
        metavar="SAVE_DIR",
        help=(
            "source Minecraft save, a relative path starting at the repository root; "
            "defaults to saves/rtx-benchmark in the game directory"
        ),
    )
    wp.set_defaults(func=cmd_world_update)

    wp = world_sub.add_parser(
        "fetch",
        help="pull a pinned or explicit world version into tools/assets/worlds",
        description=(
            "Pulls the pinned world package into tools/assets/worlds/<world>_<version> via\n"
            "packman copyPath. Use --version to fetch an explicit version without changing the pin."
        ),
        formatter_class=HelpFormatter,
    )
    wp.add_argument(
        "world", nargs="?", metavar="WORLD", help="benchmark world name; defaults to the merged world.name setting"
    )
    wp.add_argument(
        "--version",
        default=None,
        metavar="vMAJOR.MINOR.PATCH",
        help="fetch a specific published version without re-pinning config.local.json",
    )
    wp.set_defaults(func=cmd_world_fetch)

    p = sub.add_parser(
        "camera",
        help="list benchmark cameras",
        description=(
            "List saved camera paths for a benchmark world. "
            "Use this to list benchmark cameras, then 'run --world ... --camera ...' to launch one."
        ),
        formatter_class=HelpFormatter,
    )
    camera_sub = p.add_subparsers(dest="camera_command", required=True, metavar="camera-command")

    cp = camera_sub.add_parser("list", help="list benchmark cameras for a world", formatter_class=HelpFormatter)
    cp.add_argument("world", metavar="WORLD", help="benchmark world name under tools/assets/worlds")
    cp.add_argument(
        "--version",
        default=None,
        metavar="vMAJOR.MINOR.PATCH",
        help="inspect a specific staged world version instead of the pinned one",
    )
    cp.add_argument("--json", action="store_true", help="emit the machine-readable report (used by the launcher)")
    cp.set_defaults(func=cmd_camera_list)

    p = sub.add_parser(
        "screenshots",
        help="recapture the documentation screenshots from the marked still shots",
        description=(
            "Still shots are marked in game with /rtx shot mark <name> and stored in\n"
            "tools/assets/shots/docs.shots.json. refresh stages the pinned world and\n"
            "art pack, captures every shot (or only the named ones) headlessly, writes the\n"
            "JPEGs into build/screenshots/ for upload as GitHub attachments, and regenerates\n"
            "the provenance in build/screenshots/README.md."
        ),
        formatter_class=HelpFormatter,
    )
    shot_sub = p.add_subparsers(dest="screenshots_command", required=True, metavar="COMMAND")
    sp = shot_sub.add_parser("list", help="print the marked shots")
    sp.add_argument("--json", action="store_true", help="print the shot set as JSON")
    sp.set_defaults(func=cmd_screenshots_list)
    sp = shot_sub.add_parser("refresh", help="capture the shots and write build/screenshots")
    sp.add_argument("names", nargs="*", metavar="NAME", help="shots to capture; default every shot")
    add_loader_argument(
        sp,
        "mod loader to build and capture with (default: RTXREMIX_MOD_LOADER, then merged configuration)",
    )
    sp.add_argument(
        "--sdk-variant",
        choices=["release", "tracy"],
        default="release",
        help="SDK variant to stage before launch (default: release)",
    )
    sp.add_argument(
        "--timeout",
        type=int,
        default=1800,
        metavar="SECONDS",
        help="give up if the client has not captured every shot in this long (default: 1800)",
    )
    sp.set_defaults(func=cmd_screenshots_refresh)

    p = sub.add_parser(
        "resourcepack",
        help="fetch art resource packs and list pinned or locally staged versions",
        description=(
            "Fetch resource packs from the configured resourcePack.remote into\n"
            "tools/assets/resourcepacks using the mod's Packman configuration.\n"
            "The resourcePack configuration selects none, local, or pinned mode."
        ),
        formatter_class=HelpFormatter,
    )
    resource_pack_sub = p.add_subparsers(dest="resourcepack_command", required=True, metavar="resourcepack-command")

    rp = resource_pack_sub.add_parser(
        "fetch",
        help="pull a pinned or explicit art pack version into tools/assets/resourcepacks",
        description=(
            "Pulls the art pack package into tools/assets/resourcepacks/<name>_<version>\n"
            "via packman copyPath. Use --version to fetch an explicit version without changing the pin."
        ),
        formatter_class=HelpFormatter,
    )
    rp.add_argument(
        "name",
        nargs="?",
        metavar="NAME",
        help="resource pack name; defaults to the merged resourcePack.name setting",
    )
    rp.add_argument(
        "--version",
        default=None,
        metavar="[v]MAJOR.MINOR.PATCH",
        help="fetch a specific published version without changing config.local.json",
    )
    rp.set_defaults(func=cmd_resource_pack_fetch)

    rp = resource_pack_sub.add_parser(
        "versions",
        help="list a resource pack's pinned and locally staged versions",
        description=(
            "Lists [v]MAJOR.MINOR.PATCH versions of a resource pack: its configured pin and versions "
            "staged under tools/assets/resourcepacks. This command does not contact Packman."
        ),
        formatter_class=HelpFormatter,
    )
    rp.add_argument(
        "name",
        nargs="?",
        metavar="NAME",
        help="resource pack name; defaults to the merged resourcePack.name setting",
    )
    rp.add_argument("--json", action="store_true", help="emit the machine-readable report")
    rp.set_defaults(func=cmd_resource_pack_versions)

    p = sub.add_parser(
        "catalog",
        help="rebuild static dashboard files from on-disk results",
        description=(
            "Scans tools/results/<run>/manifest.json and rewrites\n"
            "tools/results/index.html plus catalog.js.\n\n"
            "This does not run a benchmark, download artifacts, delete results, or open the browser."
        ),
        epilog="""examples:
  tools\\rtx.bat catalog
  tools\\rtx.bat catalog --a 20260501_103000 --b 20260501_111500
""",
        formatter_class=HelpFormatter,
    )
    p.add_argument("--a", default="", metavar="RUN_ID", help="default A/baseline run id to select in the dashboard")
    p.add_argument("--b", default="", metavar="RUN_ID", help="default B/current run id to select in the dashboard")
    p.set_defaults(func=cmd_catalog)

    p = sub.add_parser(
        "open",
        help="rebuild and open the static dashboard",
        description="Rebuilds the dashboard, then opens tools/results/index.html in the browser.",
        formatter_class=HelpFormatter,
    )
    p.add_argument("--a", default="", metavar="RUN_ID", help="select this run as A/baseline")
    p.add_argument("--b", default="", metavar="RUN_ID", help="select this run as B/current")
    p.set_defaults(func=cmd_open)

    p = sub.add_parser(
        "list",
        help="list local and downloaded benchmark runs",
        description="Prints runs under tools/results, newest first.",
        formatter_class=HelpFormatter,
    )
    p.add_argument("--limit", type=int, default=0, metavar="N", help="show only the newest N runs")
    p.set_defaults(func=cmd_list)

    p = sub.add_parser(
        "show",
        help="print one run manifest as JSON",
        description="Resolves a run id or unique prefix and prints its manifest.json.",
        formatter_class=HelpFormatter,
    )
    p.add_argument("run", metavar="RUN_ID", help="run id or unique prefix")
    p.set_defaults(func=cmd_show)

    p = sub.add_parser(
        "compare",
        help="compare two runs and refresh the dashboard selection",
        description="Prints headline metric deltas for A vs B, then rebuilds the dashboard with those runs selected.",
        formatter_class=HelpFormatter,
    )
    p.add_argument("a", metavar="A_RUN_ID", help="baseline run id or unique prefix")
    p.add_argument("b", metavar="B_RUN_ID", help="current run id or unique prefix")
    p.set_defaults(func=cmd_compare)

    p = sub.add_parser(
        "prune",
        help="preview or delete old benchmark result directories",
        description=(
            "Finds tools/results entries older than the requested age.\n"
            "By default this only prints candidates; "
            "pass --yes to delete them."
        ),
        formatter_class=HelpFormatter,
    )
    p.add_argument(
        "--older-than",
        default=None,
        metavar="AGE",
        help="age cutoff, e.g. 14d, 2w, or 12h; default 14d unless --keep is used by itself",
    )
    p.add_argument(
        "--keep",
        type=int,
        default=0,
        metavar="N",
        help="keep the newest N candidates; without --older-than, candidates are all runs",
    )
    p.add_argument("--yes", action="store_true", help="actually delete the matched result directories")
    p.set_defaults(func=cmd_prune)

    p = sub.add_parser(
        "delete",
        help="delete local benchmark run directories by id",
        description=(
            "Deletes entries under tools/results by exact id or unique prefix,\nthen refreshes the dashboard."
        ),
        formatter_class=HelpFormatter,
    )
    p.add_argument("runs", nargs="+", metavar="RUN_ID", help="run id or unique id prefix")
    p.set_defaults(func=cmd_delete)

    p = sub.add_parser(
        "annotate",
        help="add tags or a note to an existing benchmark run",
        description=(
            "Updates tools/results/<run>/manifest.json with extra tags or a note,\n"
            "then refreshes the static dashboard catalog. Run ids may be exact ids or unique prefixes."
        ),
        epilog="""examples:
  tools\\rtx.bat annotate 20260501_103000 --tag local --tag compare
  tools\\rtx.bat annotate 20260501_103000 --note "local comparison while CI runs"
  tools\\rtx.bat annotate 20260501_103000 --clear-note
""",
        formatter_class=HelpFormatter,
    )
    p.add_argument("run", metavar="RUN_ID", help="run id or unique id prefix")
    p.add_argument("--tag", action="append", default=[], metavar="TAG", help="tag to add to the manifest; repeatable")
    p.add_argument("--note", default=None, metavar="TEXT", help="replace the manifest note with this text")
    p.add_argument("--clear-note", action="store_true", help="remove the manifest note")
    p.set_defaults(func=cmd_annotate)

    p = sub.add_parser(
        "check",
        help="run Python checks; --tests adds the test suites",
        description=(
            "Runs Ruff formatting, Ruff linting, and ty type checks for the benchmark package. "
            "Pass --tests to also run the benchmark, JFR-profiler, and launcher test suites "
            "(CI always does; locally they turn a seconds-long lint gate into minutes)."
        ),
        formatter_class=HelpFormatter,
    )
    p.add_argument("--fix", action="store_true", help="format and apply safe Ruff fixes")
    p.add_argument("--no-types", action="store_true", help="skip ty type checking")
    p.add_argument("--tests", action="store_true", help="also run benchmark, JFR-profiler, and launcher tests")
    p.set_defaults(func=cmd_check)

    p = sub.add_parser(
        "versions",
        help="report Minecraft versions in this checkout",
        description=(
            "Reads and validates every Minecraft version and loader declared by "
            "the current checkout's project-state.json."
        ),
        formatter_class=HelpFormatter,
    )
    p.add_argument("--json", action="store_true", help="emit the stable machine-readable report")
    p.set_defaults(func=cmd_version_catalog)

    p = sub.add_parser(
        "target-status",
        help="report selected target validation state",
        description=(
            "Reports catalog, development artifact, automated-contract, and live-run evidence "
            "for one Minecraft version and all of its loaders. "
            "--run performs a proportionate validation before reporting."
        ),
        formatter_class=HelpFormatter,
    )
    p.add_argument(
        "--run",
        choices=["none", "automated-contracts", "build"],
        default="none",
        help="validation to run first: tools tests or all catalog build/test tasks (default: none)",
    )
    p.add_argument("--json", action="store_true", help="emit the stable machine-readable report")
    p.set_defaults(func=cmd_target_status)

    return parser


def _extract_minecraft_version(argv: list[str]) -> tuple[list[str], str | None]:
    """Accept the target selector on either side of the subcommand."""
    remaining: list[str] = []
    selected: str | None = None
    index = 0
    while index < len(argv):
        argument = argv[index]
        if argument == "--minecraft-version":
            if index + 1 >= len(argv):
                raise ValueError("--minecraft-version requires a value")
            value = argv[index + 1]
            index += 2
        elif argument.startswith("--minecraft-version="):
            value = argument.partition("=")[2]
            index += 1
        else:
            remaining.append(argument)
            index += 1
            continue
        if not value:
            raise ValueError("--minecraft-version requires a value")
        if selected is not None and selected != value:
            raise ValueError("--minecraft-version was supplied more than once with different values")
        selected = value
    return remaining, selected


def main(argv: list[str] | None = None) -> int:
    # The git-ignored tools/.env may set machine-local environment variables,
    # such as the benchmark machine-health expectations; a real environment
    # variable wins.
    util.load_env_file()
    previous_minecraft_version = os.environ.get("RTXREMIX_MINECRAFT_VERSION")
    selected_minecraft_version: str | None = None
    try:
        argv = sys.argv[1:] if argv is None else argv
        argv, selected_minecraft_version = _extract_minecraft_version(argv)
        if selected_minecraft_version is not None:
            os.environ["RTXREMIX_MINECRAFT_VERSION"] = selected_minecraft_version
        parser = build_parser()
        args = parser.parse_args(argv or ["menu"])
        return args.func(args)
    except Exception as exc:
        print(f"error: {exc}")
        return 1
    finally:
        if selected_minecraft_version is not None:
            if previous_minecraft_version is None:
                os.environ.pop("RTXREMIX_MINECRAFT_VERSION", None)
            else:
                os.environ["RTXREMIX_MINECRAFT_VERSION"] = previous_minecraft_version

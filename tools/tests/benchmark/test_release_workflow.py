# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""The release workflow: a Run workflow button that builds target release files and drafts a release.

The tools suite has no YAML library, so these checks read the workflow as text with regular expressions and
line scans. They hold the properties that keep a release safe: nothing runs on a push, only the draft job can
write to the repository, every action is pinned by commit, and an existing release or tag is never touched."""

import re
import unittest

from rtx_tools.benchmark import util

WORKFLOWS = util.PROJECT_DIR / ".github" / "workflows"
RELEASE = WORKFLOWS / "release.yml"
BUILD = WORKFLOWS / "build.yml"
CHECK = WORKFLOWS / "check.yml"
VALIDATOR = util.PROJECT_DIR / ".github" / "scripts" / "validate-project-state.ps1"

PIN = re.compile(r"^\s*(?:-\s+)?uses:\s*(?P<action>[\w./-]+)@(?P<sha>[0-9a-f]{40}) # v\d")


def _lines(path):
    return path.read_text(encoding="utf-8").splitlines()


def _top_level_block(lines, key):
    """The lines indented under a top-level `key:` line, up to the next top-level line."""
    start = lines.index(f"{key}:")
    block = []
    for line in lines[start + 1 :]:
        if line.strip() and not line.startswith(" "):
            break
        block.append(line)
    return block


def _job(name):
    """One job's lines, from its two-space-indented `name:` line to the next job."""
    block = _top_level_block(_lines(RELEASE), "jobs")
    start = block.index(f"  {name}:")
    job = [block[start]]
    for line in block[start + 1 :]:
        if re.match(r"^  \S", line):
            break
        job.append(line)
    return job


def _uses(lines):
    return [match for line in lines if (match := PIN.match(line))]


RUN = re.compile(r"^(?P<indent>\s*)(?:-\s+)?run:\s*(?P<rest>.*)$")


def _run_bodies(lines):
    """Each step's `run:` script: the inline value, or the block scalar's lines indented past the `run:` key."""
    bodies = []
    for i, line in enumerate(lines):
        match = RUN.match(line)
        if not match:
            continue
        rest = match["rest"]
        if not re.match(r"^[|>][-+]?\s*(?:#.*)?$", rest):
            bodies.append(rest)
            continue
        key = len(line) - len(line.lstrip(" -"))
        block = []
        for following in lines[i + 1 :]:
            if following.strip() and len(following) - len(following.lstrip()) <= key:
                break
            block.append(following)
        bodies.append("\n".join(block))
    return bodies


class ReleaseWorkflowTests(unittest.TestCase):
    def test_runs_only_from_the_button(self):
        triggers = [line for line in _top_level_block(_lines(RELEASE), "on") if re.match(r"^  \S", line)]

        self.assertEqual(["  workflow_dispatch:"], triggers)

    def test_the_draft_input_defaults_to_on(self):
        on = "\n".join(_top_level_block(_lines(RELEASE), "on"))

        self.assertRegex(on, r"(?m)^      create_draft:$")
        self.assertRegex(on, r"(?m)^        type: boolean$")
        self.assertRegex(on, r"(?m)^        default: true$")

    def test_every_action_is_pinned_by_sha(self):
        uses = [line for line in _lines(RELEASE) if re.match(r"^\s*(?:-\s+)?uses:", line)]
        pinned = _uses(uses)

        self.assertEqual(len(uses), len(pinned), f"unpinned action in {[line for line in uses if not PIN.match(line)]}")
        self.assertEqual(
            {"actions/checkout", "actions/setup-java", "actions/upload-artifact", "actions/download-artifact"},
            {match["action"] for match in pinned},
        )

    def test_checkout_and_java_pins_match_the_build_workflow(self):
        release = {match["action"]: match["sha"] for match in _uses(_lines(RELEASE))}
        build = {match["action"]: match["sha"] for match in _uses(_lines(BUILD))}

        for action in ("actions/checkout", "actions/setup-java"):
            self.assertEqual(build[action], release[action], action)

    def test_ci_uses_the_manifest_target_matrix_on_main(self):
        build = BUILD.read_text(encoding="utf-8")
        check = CHECK.read_text(encoding="utf-8")

        self.assertIn("validate-project-state.ps1", build)
        self.assertIn("-GitHubOutput $env:GITHUB_OUTPUT", build)
        self.assertIn("branches: [main]", build)
        self.assertIn("fromJSON(needs.catalog.outputs.matrix)", build)
        self.assertIn("TARGET_MINECRAFT_VERSION: ${{ matrix.minecraftVersion }}", build)
        self.assertIn('"-PmcVersion=$env:TARGET_MINECRAFT_VERSION"', build)
        self.assertIn('"-Ploader=$env:TARGET_LOADER"', build)
        self.assertIn("validate-project-state.ps1", check)
        self.assertIn("branches: [main]", check)
        self.assertIn("fromJSON(needs.catalog.outputs.matrix)", check)
        self.assertNotIn('"mc/**"', build + check)
        self.assertRegex(build, r"(?m)^  build:\n    name: build$")
        self.assertIn("needs: [catalog, targets]", build)
        self.assertRegex(check, r"(?m)^  check:\n    name: check$")
        self.assertIn("needs: [catalog, targets, tooling]", check)
        for workflow in (build, check):
            self.assertIn("if: always()", workflow)
            self.assertIn("-ne 'success'", workflow)
        for workflow in (build, check):
            run_bodies = _run_bodies(workflow.splitlines())
            self.assertEqual([], [body for body in run_bodies if "${{" in body])

    def test_validator_uses_the_authoritative_keyed_manifest(self):
        validator = VALIDATOR.read_text(encoding="utf-8")

        self.assertIn("'multi-version/v1'", validator)
        self.assertIn("defaultMinecraftVersion", validator)
        self.assertIn("versions.PSObject.Properties", validator)
        self.assertIn("defaultLoader", validator)
        self.assertIn("loaders.PSObject.Properties", validator)
        self.assertIn("entry id", validator)
        self.assertIn("mcVersionGradleProperty", validator)
        self.assertIn("ConvertTo-Json -Compress", validator)
        self.assertNotIn("minecraft_version", validator)
        self.assertNotIn("schemaVersion", validator)

    def test_only_the_draft_job_can_write(self):
        lines = _lines(RELEASE)

        self.assertIn("permissions: {}", lines)
        self.assertIn("      contents: read", _job("build"))
        self.assertIn("      contents: write", _job("draft"))
        writes = [line for line in lines if re.match(r"^\s+[\w-]+: write\s*$", line)]
        self.assertEqual(1, len(writes), writes)

    def test_no_caches_or_secrets(self):
        text = RELEASE.read_text(encoding="utf-8")
        checkouts = [line for line in _job("build") if "actions/checkout@" in line]

        self.assertNotIn("cache", text.lower())
        self.assertNotIn("secrets.", text)
        self.assertEqual(1, len(checkouts))
        self.assertEqual(1, text.count("persist-credentials: false"))
        self.assertEqual([], [line for line in _job("draft") if "actions/checkout@" in line])

    def test_no_run_script_expands_an_expression(self):
        # Actions pastes a ${{ }} expression into the script text before the shell reads it, so a value with quotes
        # or a newline becomes code. Values reach a script through env: instead.
        bodies = _run_bodies(_lines(RELEASE))

        self.assertGreaterEqual(len(bodies), 4, bodies)
        self.assertEqual([], [body for body in bodies if "${{" in body])

    def test_the_run_script_scan_reads_inline_and_block_scripts(self):
        sample = [
            "    steps:",
            "      - name: Block",
            "        run: |",
            "          echo one",
            "",
            '          echo "${{ github.ref }}"',
            "      - run: 'echo ${{ inputs.x }}'",
            "      - name: After",
            "        env:",
            "          A: ${{ github.token }}",
        ]

        self.assertEqual(
            ['          echo one\n\n          echo "${{ github.ref }}"', "'echo ${{ inputs.x }}'"],
            _run_bodies(sample),
        )

    def test_only_one_release_runs_at_a_time(self):
        self.assertIn("concurrency: release", _lines(RELEASE))

    def test_it_builds_the_release_assets(self):
        build = _job("build")
        text = "\n".join(build)

        self.assertIn("  build:", build)
        self.assertIn("    runs-on: windows-latest", build)
        self.assertEqual([], [line for line in build if re.match(r"^    if:", line)])
        self.assertRegex(
            text,
            r'run: \'& "\$env:GITHUB_WORKSPACE\\gradlew\.bat" stageReleaseAssets '
            r'"-PmcVersion=\$env:PROJECT_MINECRAFT_VERSION"\'',
        )
        self.assertNotIn("-Parch", "\n".join(_lines(RELEASE)))
        self.assertIn("validate-project-state.ps1", text)
        self.assertIn("-ReleaseAssetsDirectory", text)
        self.assertRegex(text, r"(?m)^          name: release-assets$")
        self.assertRegex(text, r"(?m)^          path: build/release-assets/\*$")
        self.assertRegex(text, r"(?m)^          if-no-files-found: error$")
        self.assertRegex(text, r"(?m)^          compression-level: 0$")

    def test_the_release_identity_comes_from_the_project_manifest(self):
        build = "\n".join(_job("build"))
        draft = "\n".join(_job("draft"))

        self.assertIn("$env:PROJECT_MOD_VERSION", build)
        self.assertIn("$env:PROJECT_MINECRAFT_VERSION", build)
        self.assertIn("$env:PROJECT_DEFAULT_MINECRAFT_VERSION", build)
        self.assertNotIn("gradle.properties", build)
        self.assertIn("GITHUB_OUTPUT", build)
        self.assertIn('"tag=$tag"', build)
        self.assertIn('"title=$title"', build)
        self.assertIn('"support_tier=$env:PROJECT_SUPPORT_TIER"', build)
        self.assertIn("steps.project_state.outputs.javaTarget", build)
        self.assertRegex(draft, r"(?m)^      RELEASE_TAG: \$\{\{ needs\.build\.outputs\.tag \}\}$")

    def test_the_draft_runs_only_from_main_and_only_when_asked(self):
        draft = _job("draft")
        text = "\n".join(draft)

        self.assertIn("    needs: build", draft)
        condition = next(line for line in draft if line.startswith("    if:"))
        self.assertIn("inputs.create_draft", condition)
        self.assertIn("needs.build.outputs.support_tier != 'archived'", condition)
        self.assertIn("github.ref == 'refs/heads/main'", condition)
        self.assertNotIn("refs/heads/mc/", condition)
        self.assertIn("    runs-on: ubuntu-latest", draft)
        self.assertRegex(text, r"(?m)^      GH_TOKEN: \$\{\{ github\.token \}\}$")
        self.assertRegex(text, r"(?m)^          name: release-assets$")

    def test_the_draft_refuses_an_existing_release(self):
        draft = _job("draft")
        text = "\n".join(draft)
        view = next(i for i, line in enumerate(draft) if 'gh release view "$RELEASE_TAG"' in line)
        listing = next(i for i, line in enumerate(draft) if "git ls-remote --tags" in line)
        step = next(i for i, line in enumerate(draft) if "name: Create the draft release" in line)
        create = next(i for i, line in enumerate(draft) if 'gh release create "$RELEASE_TAG"' in line)

        self.assertIn('--repo "$GITHUB_REPOSITORY"', draft[view])
        self.assertTrue(draft[view].lstrip().startswith("if gh release view"), "exits when the release is found")
        self.assertIn("exit 1", [line.strip() for line in draft[view:listing]])
        self.assertIn('"https://github.com/$GITHUB_REPOSITORY" "refs/tags/$RELEASE_TAG"', draft[listing])
        self.assertIn("exit 1", [line.strip() for line in draft[listing:step]])
        self.assertLess(view, create)
        self.assertLess(listing, create)
        self.assertNotIn("--force", text)

    def test_the_draft_is_created_with_all_staged_files(self):
        draft = _job("draft")
        text = "\n".join(draft)

        self.assertIn('--repo "$GITHUB_REPOSITORY"', text)
        self.assertIn("--draft", text)
        self.assertIn('--target "$GITHUB_SHA"', text)
        self.assertIn('--title "$RELEASE_TITLE"', text)
        self.assertIn('--notes "Write the release notes before publishing."', text)
        self.assertIn('"${files[@]}"', text)
        self.assertNotIn("${#files[@]}", text)


if __name__ == "__main__":
    unittest.main()

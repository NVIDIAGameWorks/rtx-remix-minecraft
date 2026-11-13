# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Apache-2.0 release compliance gates.

Two classes of regression prompted these tests, both caught by release
compliance review rather than by CI:

1. Rebasing onto a newer main silently reintroduced source files without SPDX
   headers, because the header sweep was a one-time pass over an older tree.
2. The reproduced Developer Certificate of Origin picked up three lines that
   are not in the canonical text. The certificate forbids modifying it, so a
   near-copy is a defect even though it reads correctly.

Both failure modes are invisible to a human reviewer and recur on every merge,
so they are asserted here instead.
"""

import re
import subprocess
import unittest
from pathlib import Path

from rtx_tools.benchmark import util

REPO_ROOT = util.PROJECT_DIR
WRAPPER_ROOT = REPO_ROOT / "rtxremix-java"

SPDX_LICENSE_LINE = "SPDX-License-Identifier: Apache-2.0"

# Release compliance review requires this exact entity, trailing period included.
NVIDIA_ENTITY = "NVIDIA CORPORATION & AFFILIATES. All rights reserved."

# The copyright line that must sit above the Apache body in the root LICENSE.
LICENSE_FIRST_LINE = f"Copyright (c) 2024-2026 {NVIDIA_ENTITY}"

# Suffixes that carry a source header. `.accesswidener` covers the shipped Fabric
# access widener. The GLSL suffixes cover shipped shaders, which carry the header
# in a comment above their `#version` directive. `.cpp` and `.h` cover the JNI bridge.
HEADERED_SUFFIXES = frozenset(
    {
        ".java",
        ".cpp",
        ".h",
        ".gradle",
        ".py",
        ".ps1",
        ".psd1",
        ".bat",
        ".cmd",
        ".accesswidener",
        ".comp",
        ".fsh",
        ".vsh",
        ".glsl",
    }
)

# gradle/ and gradlew.bat are the Gradle wrapper and packman/ contains the
# Packman bootstrap; both keep their upstream licensing, attributed in
# THIRD_PARTY_NOTICES.md. remix_c.h is vendored from dxvk-remix and remains MIT.
EXCLUDED_PREFIXES = ("gradle/", "packman/")
EXCLUDED_PATHS = frozenset({"gradlew.bat", "rtxremix-java/native/include/remix_c.h"})

# Lowercased phrasing that imposes a CLA, as opposed to merely mentioning one.
# The compliant text says there is no CLA to sign, so it must not trip these.
CLA_REQUIREMENT_PHRASES = (
    "sign the contributor license agreement",
    "sign the contribution license agreement",
    "sign the nvidia contributor license agreement",
    "prompt you to sign",
    "cla bot",
    "cla-bot",
)

# Reproduced verbatim from https://developercertificate.org/. Do not reformat,
# rewrap, or add to this. If it ever needs updating, copy from that URL rather
# than from another repository: the extra address lines this test exists to
# catch came from a second-hand copy.
CANONICAL_DCO = """Developer Certificate of Origin
Version 1.1

Copyright (C) 2004, 2006 The Linux Foundation and its contributors.

Everyone is permitted to copy and distribute verbatim copies of this
license document, but changing it is not allowed.


Developer's Certificate of Origin 1.1

By making a contribution to this project, I certify that:

(a) The contribution was created in whole or in part by me and I
    have the right to submit it under the open source license
    indicated in the file; or

(b) The contribution is based upon previous work that, to the best
    of my knowledge, is covered under an appropriate open source
    license and I have the right under that license to submit that
    work with modifications, whether created in whole or in part
    by me, under the same open source license (unless I am
    permitted to submit under a different license), as indicated
    in the file; or

(c) The contribution was provided directly to me by some other
    person who certified (a), (b) or (c) and I have not modified
    it.

(d) I understand and agree that this project and the contribution
    are public and that a record of the contribution (including all
    personal information I submit with it, including my sign-off) is
    maintained indefinitely and may be redistributed consistent with
    this project or the open source license(s) involved."""


def tracked_files() -> list[str]:
    """Repo-relative paths from the mod's tracked inventory."""
    stdout = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=REPO_ROOT,
        capture_output=True,
        check=True,
    ).stdout.decode("utf-8", errors="replace")
    return [name for name in stdout.split("\0") if name]


def headered_sources() -> list[str]:
    """Public tracked sources expected to carry an NVIDIA SPDX header."""
    selected = []
    for name in tracked_files():
        if name.startswith(EXCLUDED_PREFIXES) or name in EXCLUDED_PATHS:
            continue
        if Path(name).suffix not in HEADERED_SUFFIXES:
            continue
        if not (REPO_ROOT / name).is_file():
            continue  # files deleted in the working tree
        selected.append(name)
    return selected


def read_text(path: Path) -> str:
    """File contents with line endings normalized, so CRLF checkouts compare equal."""
    return path.read_text(encoding="utf-8", errors="replace").replace("\r\n", "\n")


class SourceHeaderTests(unittest.TestCase):
    def test_public_sources_carry_the_spdx_header(self):
        sources = headered_sources()

        missing = [name for name in sources if SPDX_LICENSE_LINE not in read_text(REPO_ROOT / name)]
        self.assertEqual([], missing, "sources without an SPDX header (new files usually arrive via rebase or merge)")

    def test_headers_use_the_required_nvidia_entity(self):
        # Release compliance tooling matches on the exact entity string, trailing
        # period included, so "NVIDIA CORPORATION" alone or a missing period fails.
        offenders = []
        for name in headered_sources():
            text = read_text(REPO_ROOT / name)
            if "SPDX-FileCopyrightText" in text and NVIDIA_ENTITY not in text:
                offenders.append(name)
        self.assertEqual([], offenders, f"SPDX headers must name '{NVIDIA_ENTITY}'")


class ScriptEncodingTests(unittest.TestCase):
    """Adding a header must not strip a PowerShell script's UTF-8 BOM.

    Windows PowerShell 5.1, which the CI runners use, assumes the ANSI code
    page for a .ps1 file with no BOM. A script containing non-ASCII text then
    decodes as mojibake and fails to parse, with errors that point at
    unrelated lines rather than at the encoding.

    The header sweep hit exactly this: it rewrote packaging/installer.ps1
    without preserving the BOM, and the file's typographic dashes turned 0
    parse errors into 80. The CI parse check caught it, but only after the
    change had shipped to a branch, so assert it here where it is cheap.
    """

    def test_powershell_scripts_with_non_ascii_keep_a_utf8_bom(self):
        offenders = []
        for name in tracked_files():
            if not name.endswith(".ps1"):
                continue
            path = REPO_ROOT / name
            if not path.is_file():
                continue
            raw = path.read_bytes()
            has_bom = raw.startswith(b"\xef\xbb\xbf")
            try:
                non_ascii = not raw.decode("utf-8").isascii()
            except UnicodeDecodeError:
                non_ascii = True
            if non_ascii and not has_bom:
                offenders.append(name)
        self.assertEqual(
            [],
            offenders,
            "PowerShell 5.1 reads a BOM-less .ps1 as ANSI, so non-ASCII content fails to parse. "
            "Either restore the UTF-8 BOM or make the file pure ASCII.",
        )


class AccessWidenerHeaderTests(unittest.TestCase):
    """The access widener is the one headered file with a format constraint.

    Fabric Loom parses the first line as the format header and rejects anything
    above it with ClassTweakerFormatException, so the SPDX block has to sit
    below it. That is easy to "fix" into a broken build during a header sweep.
    """

    PATH = Path("versions/1.21.1/fabric/src/main/resources/rtx-remix-renderer.accesswidener")

    def test_format_header_is_still_the_first_line(self):
        lines = read_text(REPO_ROOT / self.PATH).splitlines()
        self.assertRegex(
            lines[0],
            r"^accessWidener\s",
            "the accessWidener format header must remain line 1; Loom rejects anything above it",
        )

    def test_spdx_header_is_present_below_the_format_header(self):
        text = read_text(REPO_ROOT / self.PATH)
        self.assertIn(SPDX_LICENSE_LINE, text)
        self.assertLess(text.index("accessWidener"), text.index("SPDX-License-Identifier"))


class ContributionDocumentTests(unittest.TestCase):
    def contributing_files(self) -> list[Path]:
        return [REPO_ROOT / "CONTRIBUTING.md"]

    def test_dco_is_reproduced_verbatim(self):
        for path in self.contributing_files():
            with self.subTest(path=str(path)):
                self.assertTrue(path.is_file(), f"{path} is missing")
                self.assertIn(
                    CANONICAL_DCO,
                    read_text(path),
                    "the DCO block does not match developercertificate.org verbatim; "
                    "copy it from that URL, not from another repository",
                )

    def test_contribution_docs_do_not_require_a_cla(self):
        # Apache-2.0 projects use the DCO. The CLA wording this replaced was
        # copied in from another NVIDIA repository, so guard against a reflux.
        #
        # Matching on "contributor license agreement" alone is wrong: the
        # correct text says there is no CLA to sign. Match the phrasing that
        # actually imposes one instead.
        for path in self.contributing_files() + [REPO_ROOT / "README.md", WRAPPER_ROOT / "README.md"]:
            with self.subTest(path=str(path)):
                text = read_text(path).lower()
                for phrase in CLA_REQUIREMENT_PHRASES:
                    self.assertNotIn(phrase, text, f"{path.name} appears to require a CLA; Apache-2.0 uses the DCO")

    def test_contribution_docs_require_dco_signoff(self):
        for path in self.contributing_files() + [REPO_ROOT / "README.md"]:
            with self.subTest(path=str(path)):
                text = read_text(path)
                self.assertIn("developercertificate.org", text)
                self.assertIn("git commit -s", text)


class LicenseFileTests(unittest.TestCase):
    def test_license_opens_with_the_nvidia_copyright_line(self):
        lines = read_text(REPO_ROOT / "LICENSE").splitlines()
        self.assertEqual(LICENSE_FIRST_LINE, lines[0])
        self.assertEqual("", lines[1], "a blank line must separate the copyright from the Apache banner")
        self.assertIn("Apache License", lines[2])

    @staticmethod
    def referenced_license_targets() -> set[str]:
        """Repo-relative targets of the license-text bullets in THIRD_PARTY_NOTICES.md."""
        text = read_text(REPO_ROOT / "THIRD_PARTY_NOTICES.md")
        return {
            line.split("(")[-1].rstrip(")")
            for line in text.splitlines()
            if "packaging/licenses/" in line and line.lstrip().startswith("- [")
        }

    def test_notices_reference_license_texts_that_exist(self):
        # THIRD_PARTY_NOTICES.md points at files under packaging/licenses/. A
        # missing target means a redistributed component ships unattributed.
        referenced = self.referenced_license_targets()
        self.assertNotEqual(set(), referenced, "expected packaging/licenses references in the notices")

        missing = sorted(target for target in referenced if not (REPO_ROOT / target).exists())
        self.assertEqual([], missing, "THIRD_PARTY_NOTICES.md references license files that do not exist")

    def test_every_license_file_is_referenced_by_the_notices(self):
        # The reverse direction: a license text nobody points at is either a
        # component the notices forgot or a file renamed out from under them. A
        # bullet may cover a whole directory by linking it with a trailing slash.
        referenced = self.referenced_license_targets()
        licenses_root = REPO_ROOT / "packaging" / "licenses"
        unreferenced: list[str] = []
        for path in sorted(licenses_root.rglob("*")):
            if not path.is_file():
                continue
            covering_directories = set()
            directory = path.parent
            while directory != licenses_root.parent:
                covering_directories.add(f"{directory.relative_to(REPO_ROOT).as_posix()}/")
                directory = directory.parent
            relative = path.relative_to(REPO_ROOT).as_posix()
            if relative not in referenced and not (covering_directories & referenced):
                unreferenced.append(relative)
        self.assertEqual(
            [], unreferenced, "license files under packaging/licenses/ that THIRD_PARTY_NOTICES.md does not reference"
        )

    def test_bundled_mixinextras_is_attributed(self):
        forge_build = read_text(REPO_ROOT / "versions/1.21.1/forge/build.gradle")
        match = re.search(r"io\.github\.llamalad7:mixinextras-forge:([^'\"]+)", forge_build)
        if match is None:
            self.fail("expected the Forge module's bundled MixinExtras dependency")

        notices = read_text(REPO_ROOT / "THIRD_PARTY_NOTICES.md")
        self.assertIn(f"MixinExtras {match.group(1)}", notices)
        self.assertIn("packaging/licenses/mixinextras/LICENSE.txt", notices)

        license_text = read_text(REPO_ROOT / "packaging/licenses/mixinextras/LICENSE.txt")
        self.assertIn("MIT License", license_text)
        self.assertIn("Copyright (c) 2022-present LlamaLad7", license_text)

    def test_declared_neoforge_runtime_dependency_is_in_notices(self):
        project_state = read_text(REPO_ROOT / "project-state.json")
        self.assertIn('"neoforge"', project_state)

        notices = read_text(REPO_ROOT / "THIRD_PARTY_NOTICES.md")
        self.assertIn("- NeoForge (NeoForged)", notices)
        self.assertIn("GNU Lesser General Public License, version 2.1 only", notices)


if __name__ == "__main__":
    unittest.main()

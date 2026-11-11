# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import argparse
import json
import os
import re
import subprocess
import tempfile
import unittest
import zlib
from pathlib import Path
from unittest import mock

from rtx_tools.benchmark import util

REPO_ROOT = util.PROJECT_DIR


class PublicBoundaryTests(unittest.TestCase):
    def test_benchmark_guides_live_under_tools_docs(self):
        for filename in ("BENCHMARKING.md", "BENCHMARK_FIXTURES.md"):
            with self.subTest(filename=filename):
                self.assertTrue((REPO_ROOT / "tools" / "docs" / filename).is_file())
                self.assertFalse((REPO_ROOT / "docs" / filename).exists())

    def test_packman_bootstrap_has_one_tracked_root(self):
        tracked = subprocess.run(
            ["git", "ls-files", "-z", "*/packman.cmd"],
            cwd=REPO_ROOT,
            capture_output=True,
            check=True,
        ).stdout.decode("utf-8", errors="replace")

        self.assertEqual(["packman/packman.cmd"], list(filter(None, tracked.split("\0"))))
        self.assertTrue((REPO_ROOT / "packman" / "packman.cmd").is_file())


# Public rules cover every tracked file. Deployment-specific identifiers stay
# outside this repository and can be passed to the same scan functions.
# Each row is (label, byte regex, exact repository-relative exception paths).
# Upstream license texts retain their published addresses. All other paths are
# scanned, including new files beside these two verbatim license copies.
PUBLIC_LEAK_PATTERNS = [
    (
        "merge request reference",
        re.compile(rb"\bMR\s+![0-9]+|(?<![\w!])![0-9]{2,}\b|/merge_requests/[0-9]+", re.IGNORECASE),
        set(),
    ),
    ("NVBugs id", re.compile(rb"\b(?:nvbugs?|bugs?)\s*(?:id|#|:)?\s*[0-9]{7}\b", re.IGNORECASE), set()),
    (
        "local user path",
        re.compile(rb"[a-z]:[\\/]{1,2}users[\\/]|/(?:home|Users)/[^/\s]+/", re.IGNORECASE),
        set(),
    ),
    ("internal branch reference", re.compile(rb"\b(?:dev|private)/[a-z][\w.-]*/", re.IGNORECASE), set()),
    ("nvidia.com host", re.compile(rb"[a-z0-9.-]+\.nvidia\.com", re.IGNORECASE), set()),
    (
        "nvidia.com e-mail",
        re.compile(rb"[a-z0-9._%+-]+@nvidia\.com", re.IGNORECASE),
        {
            "packaging/licenses/remix-runtime/LICENSE.sharc.txt",
            "packaging/licenses/remix-runtime/ThirdPartyLicenses.txt",
        },
    ),
    ("author TODO in shipped text", re.compile(rb"\bTODO\b"), set()),
    ("unfilled placeholder", re.compile(rb"\[INSERT [A-Z ]+:|OWNER-FILL"), set()),
    ("em dash", re.compile(chr(0x2014).encode("utf-8")), set()),
]

# Prose rows apply to text bodies and commit messages, not file names or binary
# metadata, where punctuation and placeholder-like bytes can be incidental.
TEXT_BODY_ONLY = {"author TODO in shipped text", "unfilled placeholder", "em dash"}

# Set to "<base>..HEAD" to scan the commit messages a change adds. A message is
# as public as a file once it reaches GitHub.
COMMIT_RANGE_VARIABLE = "PUBLIC_COMMIT_RANGE"

# Public NVIDIA sites that a "nvidia.com host" match may legitimately name.
# The pattern still flags any other <sub>.nvidia.com host it finds.
PUBLIC_NVIDIA_HOSTS = {
    b"www.nvidia.com",
    b"docs.nvidia.com",
    b"developer.nvidia.com",
    b"bootstrap.packman.nvidia.com",
}

# Only PSIRT's public reporting address is allowed in the security policy.
PUBLIC_NVIDIA_EMAILS = {"SECURITY.md": {b"psirt@nvidia.com"}}

# Known binary suffixes. Every other tracked file is scanned as text,
# including the extensionless files (LICENSE, NOTICE) that a suffix allowlist
# would silently skip.
BINARY_SUFFIXES = {
    ".png",
    ".dds",
    ".jar",
    ".zip",
    ".ico",
    ".gif",
    ".jpg",
    ".jpeg",
    ".class",
    ".dll",
    ".exe",
}

ROOT_ENTRIES_THAT_MUST_NOT_BE_TRACKED = {".cursor/mcp.json"}

# Markdown inline link target, allowing one level of balanced parentheses
# inside the target (e.g. "docs/foo(bar).md") before the optional anchor.
MARKDOWN_LINK_PATTERN = re.compile(r"\]\(((?:[^()#:]|\([^()]*\))+)(#[^)]*)?\)")


def public_tracked_files():
    tracked = subprocess.run(["git", "ls-files", "-z"], cwd=REPO_ROOT, capture_output=True, check=True).stdout.decode(
        "utf-8", "replace"
    )
    for name in filter(None, tracked.split("\0")):
        if name == "tools/tests/benchmark/test_public_boundary.py":
            continue
        path = REPO_ROOT / name
        if path.is_file():
            yield name, path


def is_text(name):
    return not name.lower().endswith(tuple(BINARY_SUFFIXES))


def allowlisted(name, allow):
    return name in allow


def has_nvidia_host_leak(pattern, data):
    """True if a nvidia.com host match names something other than a public site."""
    return any(host.lower() not in PUBLIC_NVIDIA_HOSTS for host in pattern.findall(data))


def load_pattern_rows(path):
    """Public rules plus the validated pattern table at an explicit path."""
    rows = list(PUBLIC_LEAK_PATTERNS)
    try:
        entries = json.loads(path.read_text(encoding="utf-8-sig"))
        if not isinstance(entries, list) or not entries:
            raise ValueError("expected a nonempty JSON list of pattern rows")
        labels = {row[0] for row in rows}
        for entry in entries:
            label, pattern = entry["label"], entry["pattern"]
            exceptions = entry.get("exceptions", {})
            if not isinstance(label, str) or not label or label in labels:
                raise ValueError("each row needs a unique nonempty label")
            if not isinstance(pattern, str) or not pattern:
                raise ValueError(f"{label}: pattern must be a nonempty string")
            if not isinstance(exceptions, dict) or any(
                not isinstance(name, str)
                or not name
                or name.startswith("/")
                or name.endswith("/")
                or "\\" in name
                or ":" in name
                or ".." in name.split("/")
                or not isinstance(reason, str)
                or not reason.strip()
                for name, reason in exceptions.items()
            ):
                raise ValueError(f"{label}: exceptions must map exact relative file paths to reasons")
            rows.append((label, re.compile(pattern.encode("utf-8"), re.IGNORECASE), set(exceptions)))
            labels.add(label)
    except (OSError, ValueError, TypeError, KeyError, re.error) as exc:
        raise RuntimeError(f"Cannot load boundary patterns from {path}: {exc}") from exc
    return rows


def identifier_rows(rows=None):
    """Rows that name an internal identifier wherever it appears."""
    return [row for row in (PUBLIC_LEAK_PATTERNS if rows is None else rows) if row[0] not in TEXT_BODY_ONLY]


def row_applies(label, name):
    """The TODO row is about shipped prose, not about code that says TODO."""
    if label != "author TODO in shipped text" or not name:
        return True
    return name.startswith(("docs/", "packaging/")) or name.endswith(".md")


def leak_labels(data, rows, name=""):
    """Labels of the rows that match `data`, honouring each row's allowlist."""
    labels = []
    for label, pattern, allow in rows:
        if not row_applies(label, name) or allowlisted(name, allow):
            continue
        if label == "nvidia.com host":
            leaked = has_nvidia_host_leak(pattern, data)
        elif label == "nvidia.com e-mail":
            allowed = PUBLIC_NVIDIA_EMAILS.get(name, set())
            leaked = any(address.lower() not in allowed for address in pattern.findall(data))
        else:
            leaked = pattern.search(data) is not None
        if leaked:
            labels.append(label)
    return labels


def binary_metadata(path):
    """The author and path strings a binary can carry.

    Only PNG text chunks and JPEG application or comment segments are returned.
    Scanning a whole compressed image instead would match the short patterns by
    chance: today's JPEGs and DDS files contain byte runs that read as merge
    request references.
    """
    data = path.read_bytes()
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return b"\n".join(png_text_chunks(data))
    if data.startswith(b"\xff\xd8"):
        return b"\n".join(jpeg_metadata_segments(data))
    return b""


def png_text_chunks(data):
    offset = 8
    chunks = []
    while offset + 12 <= len(data):
        length = int.from_bytes(data[offset : offset + 4], "big")
        kind = data[offset + 4 : offset + 8]
        body = data[offset + 8 : offset + 8 + length]
        if kind in (b"tEXt", b"iTXt"):
            chunks.append(body)
        elif kind == b"zTXt":
            keyword, _, compressed = body.partition(b"\0")
            chunks.append(keyword)
            try:
                chunks.append(zlib.decompress(compressed[1:]))
            except zlib.error:
                chunks.append(compressed)
        elif kind == b"IEND":
            break
        offset += 12 + length
    return chunks


def jpeg_metadata_segments(data):
    offset = 2
    segments = []
    while offset + 4 <= len(data):
        if data[offset] != 0xFF:
            break
        marker = data[offset + 1]
        if marker == 0x01 or 0xD0 <= marker <= 0xD9:
            offset += 2
            continue
        if marker == 0xDA:  # Start of scan: compressed pixels from here on.
            break
        length = int.from_bytes(data[offset + 2 : offset + 4], "big")
        if 0xE0 <= marker <= 0xEF or marker == 0xFE:
            segments.append(data[offset + 4 : offset + 2 + length])
        offset += 2 + length
    return segments


TRAILER_LINE = re.compile(
    rb"^(Signed-off-by|Co-authored-by|Reviewed-by|Acked-by|Tested-by|Reported-by):",
    re.IGNORECASE | re.MULTILINE,
)


def without_trailers(body):
    """The commit body minus its RFC-style trailer lines.

    DCO sign-offs carry the author's e-mail address, so scanning them would
    flag every properly signed commit.
    """
    return b"\n".join(line for line in body.splitlines() if not TRAILER_LINE.match(line))


def commit_message_leaks(commit_range, rows):
    """Offenders in `commit_range` using the supplied rows, one line per hit."""
    log = subprocess.run(
        ["git", "log", "--format=%H%x00%B%x00", commit_range],
        cwd=REPO_ROOT,
        capture_output=True,
        check=True,
    ).stdout
    fields = log.split(b"\0")
    offenders = []
    for index in range(0, len(fields) - 1, 2):
        commit = fields[index].strip().decode("utf-8", "replace")[:8]
        for label in leak_labels(without_trailers(fields[index + 1]), rows):
            offenders.append(f"{commit}: {label}")
    return offenders


def tracked_file_leaks(rows):
    """Scan tracked text, file names and image metadata using the supplied rows."""
    offenders = []
    identifiers = identifier_rows(rows)
    for name, path in public_tracked_files():
        if is_text(name):
            body_rows, data = rows, path.read_bytes()
        else:
            body_rows, data = identifiers, binary_metadata(path)
        for label in leak_labels(data, body_rows, name=name):
            offenders.append(f"{name}: {label}")
        for label in leak_labels(name.encode("utf-8"), identifiers, name=name):
            offenders.append(f"{name}: {label} in the file name")
    return offenders


class PublicLeakPatternTests(unittest.TestCase):
    def test_tracked_sweep_includes_every_tree_except_this_module(self):
        names = [
            "README.md",
            "tools/src/example.py",
            "packman/config.packman.xml",
            "rtxremix-java/native/example.cpp",
            "tools/tests/benchmark/test_public_boundary.py",
        ]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name in names:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("fixture", encoding="utf-8")
            with (
                mock.patch.dict(globals(), REPO_ROOT=root),
                mock.patch.object(subprocess, "run", return_value=mock.Mock(stdout="\0".join(names).encode("utf-8"))),
            ):
                self.assertEqual(names[:-1], [name for name, _ in public_tracked_files()])

    def test_path_exceptions_never_exempt_a_directory(self):
        self.assertFalse(allowlisted("packaging/licenses/new.txt", {"packaging/licenses"}))

    def test_license_exceptions_do_not_cover_neighboring_files(self):
        sample = b"license-questions@nvidia.com"
        self.assertEqual(
            [],
            leak_labels(sample, PUBLIC_LEAK_PATTERNS, name="packaging/licenses/remix-runtime/LICENSE.sharc.txt"),
        )
        self.assertIn(
            "nvidia.com e-mail",
            leak_labels(sample, PUBLIC_LEAK_PATTERNS, name="packaging/licenses/remix-runtime/new.txt"),
        )

    def test_public_packman_bootstrap_host_is_allowed(self):
        self.assertEqual([], leak_labels(b"https://bootstrap.packman.nvidia.com/package.zip", PUBLIC_LEAK_PATTERNS))
        self.assertIn(
            "nvidia.com host",
            leak_labels(b"https://other.bootstrap.packman.nvidia.com/package.zip", PUBLIC_LEAK_PATTERNS),
        )

    def test_security_exception_only_allows_the_psirt_address(self):
        self.assertEqual([], leak_labels(b"psirt@nvidia.com", PUBLIC_LEAK_PATTERNS, name="SECURITY.md"))
        self.assertIn(
            "nvidia.com e-mail",
            leak_labels(b"someone@nvidia.com", PUBLIC_LEAK_PATTERNS, name="SECURITY.md"),
        )

    def test_explicit_pattern_table_adds_rows_without_changing_public_defaults(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "patterns.json"
            path.write_text(
                json.dumps([{"label": "deployment fixture", "pattern": r"\bExampleDeployment\b"}]),
                encoding="utf-8",
            )
            rows = load_pattern_rows(path)
            self.assertIn("deployment fixture", leak_labels(b"exampledeployment", identifier_rows(rows)))
            self.assertEqual([], leak_labels(b"exampledeployment", identifier_rows()))

    def test_explicit_pattern_table_must_be_valid(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "patterns.json"
            with self.assertRaisesRegex(RuntimeError, "Cannot load boundary patterns"):
                load_pattern_rows(path)
            invalid_tables = [
                "[]",
                "{}",
                "not JSON",
                '[{"label": "sample", "pattern": "["}]',
                '[{"label": "nvidia.com host", "pattern": "sample"}]',
                '[{"label": "sample", "pattern": "x", "exceptions": {"tools/": "too broad"}}]',
                '[{"label": "sample", "pattern": "x", "exceptions": {"tools/x.py": ""}}]',
            ]
            for contents in invalid_tables:
                with self.subTest(contents=contents):
                    path.write_text(contents, encoding="utf-8")
                    with self.assertRaisesRegex(RuntimeError, "Cannot load boundary patterns"):
                        load_pattern_rows(path)

    def test_commit_scan_uses_supplied_identifier_and_prose_rows(self):
        rows = [*PUBLIC_LEAK_PATTERNS, ("deployment fixture", re.compile(b"ExampleDeployment"), set())]
        message = f"ExampleDeployment TODO {chr(0x2014)} [INSERT OWNER:]\n".encode()
        log = b"1234567890\0" + message + b"\0"
        with mock.patch.object(subprocess, "run", return_value=mock.Mock(stdout=log)):
            self.assertEqual(
                [
                    "12345678: author TODO in shipped text",
                    "12345678: unfilled placeholder",
                    "12345678: em dash",
                    "12345678: deployment fixture",
                ],
                commit_message_leaks("base..HEAD", rows),
            )
            self.assertNotIn("12345678: deployment fixture", commit_message_leaks("base..HEAD", PUBLIC_LEAK_PATTERNS))

    def test_tracked_sweep_uses_supplied_rows_for_text_names_and_metadata(self):
        rows = [("deployment fixture", re.compile(b"ExampleDeployment"), set())]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "fixture"
            path.write_bytes(b"ExampleDeployment")
            files = [("README.md", path), ("ExampleDeployment.txt", path), ("image.png", path)]
            with (
                mock.patch.dict(globals(), public_tracked_files=lambda: iter(files)),
                mock.patch.dict(globals(), binary_metadata=lambda _: b"ExampleDeployment"),
            ):
                self.assertEqual(
                    [
                        "README.md: deployment fixture",
                        "ExampleDeployment.txt: deployment fixture",
                        "ExampleDeployment.txt: deployment fixture in the file name",
                        "image.png: deployment fixture",
                    ],
                    tracked_file_leaks(rows),
                )
                self.assertEqual([], tracked_file_leaks(PUBLIC_LEAK_PATTERNS))

    def test_public_files_contain_no_internal_identifiers(self):
        offenders = tracked_file_leaks(PUBLIC_LEAK_PATTERNS)
        self.assertFalse(offenders, "public leaks:\n" + "\n".join(offenders))

    def test_identifier_rows_match_the_spellings_that_actually_leak(self):
        rows = identifier_rows(PUBLIC_LEAK_PATTERNS)
        for sample in (
            b"cloned from secret.nvidia.com",
            b"mail contributor@nvidia.com",
            b"landed in MR !203",
            b"filed as bug 1234567",
            b"copied from C:" + rb"\Users\someone",
            b"branch dev/someone/fix",
        ):
            with self.subTest(sample=sample):
                self.assertNotEqual([], leak_labels(sample, rows))

    def test_commit_messages_in_the_range_are_clean(self):
        commit_range = os.environ.get(COMMIT_RANGE_VARIABLE)
        if not commit_range:
            self.skipTest(f"set {COMMIT_RANGE_VARIABLE}=<base>..HEAD to scan a change's commits")
        self.assertEqual([], commit_message_leaks(commit_range, PUBLIC_LEAK_PATTERNS))

    def test_commit_message_scan_ignores_sign_off_trailers(self):
        signed = b"Fix the thing\n\nBody text.\n\nSigned-off-by: Contributor <contributor@nvidia.com>\n"
        self.assertEqual([], leak_labels(without_trailers(signed), identifier_rows()))
        in_body = b"Fix the thing\n\nAsk contributor@nvidia.com for the key.\n"
        self.assertIn("nvidia.com e-mail", leak_labels(without_trailers(in_body), identifier_rows()))

    def test_commit_message_scan_reads_the_same_pattern_table(self):
        message = b"[REMIX-1234] Bump the fixture from dev/someone/fix, see secret.nvidia.com"
        self.assertEqual(
            ["internal branch reference", "nvidia.com host"],
            sorted(leak_labels(message, identifier_rows(PUBLIC_LEAK_PATTERNS))),
        )

    def test_ticket_keys_are_public(self):
        # Ticket keys are shared with the community, in titles and in prose.
        for sample in (b"[REMIX-1234] Fix the thing", b"see remix-1234 for the fix", b"MCRTX-12 and LIGHTS-345"):
            with self.subTest(sample=sample):
                self.assertEqual([], leak_labels(sample, PUBLIC_LEAK_PATTERNS))

    def test_markdown_link_regex_handles_balanced_parens(self):
        self.assertEqual(
            [("docs/foo(bar).md", "#s")],
            MARKDOWN_LINK_PATTERN.findall("[x](docs/foo(bar).md#s)"),
        )
        self.assertEqual(
            [("docs/a.md", "")],
            MARKDOWN_LINK_PATTERN.findall("[y](docs/a.md)"),
        )

    def test_public_markdown_links_resolve(self):
        broken = []
        for name, path in public_tracked_files():
            if not name.endswith(".md"):
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            for target, _anchor in MARKDOWN_LINK_PATTERN.findall(text):
                # A scheme-qualified target (http://, https://, mailto:) never
                # reaches the capture group: ":" is excluded from the target
                # character class, so the match stops before the scheme ends.
                if not (path.parent / target).resolve().exists():
                    broken.append(f"{name} -> {target}")
        self.assertFalse(broken, "broken links:\n" + "\n".join(broken))

    def test_root_has_no_machine_local_entries(self):
        tracked = {name for name, _ in public_tracked_files()}
        present = {
            e
            for e in ROOT_ENTRIES_THAT_MUST_NOT_BE_TRACKED
            if e in tracked or any(t.startswith(e + "/") for t in tracked)
        }
        self.assertFalse(present, "root entries present:\n" + "\n".join(present))


# The developer tools launch only through a signed-in Prism. runClient may appear as a string only as the launcher
# label compare shows for a manifest written before runs recorded their launcher; anywhere else it could be a launch.
RUN_CLIENT_LITERAL = re.compile(r"""(["'])runClient\1""")
RUN_CLIENT_LABEL = {"rtx_tools/cli.py": 1}
# Prism's offline switch as a bare token, quoted or not.
OFFLINE_FLAG = re.compile(r"(?<![\w-])--offline(?![\w-])")
# The only tracked files outside the tests that may name it: the root agent guide states that it is never passed.
OFFLINE_FLAG_RULES = {"AGENTS.md": 1}
# In the tools' own guides and launcher script, runClient may name only the benchmark run log's file.
RUN_CLIENT_OUTSIDE_LOG_NAME = re.compile(r"runClient(?!\.log\b)")


def tools_launch_text():
    """The tools' guides and launcher script, by tracked name: what tells a contributor how the tools launch."""
    for name, path in public_tracked_files():
        if name == "tools/rtx.bat" or (name.startswith("tools/") and name.endswith(".md")):
            yield name, path.read_text(encoding="utf-8")


def cli_help_texts():
    """Every command's help, as `--help` prints it."""
    from rtx_tools.cli import build_parser

    parsers = [build_parser()]
    while parsers:
        parser = parsers.pop()
        yield parser.prog, parser.format_help()
        for action in parser._actions:
            if isinstance(action, argparse._SubParsersAction):
                parsers.extend(action.choices.values())


def test_public_tools_never_launch_offline():
    src = util.TOOLS_DIR / "src"
    run_client_literals = {}
    for path in src.rglob("*.py"):
        text = path.read_text(encoding="utf-8")
        if count := len(RUN_CLIENT_LITERAL.findall(text)):
            run_client_literals[path.relative_to(src).as_posix()] = count
        assert "mcUsername" not in text, path
        assert not OFFLINE_FLAG.search(text), path
    assert run_client_literals == RUN_CLIENT_LABEL


def test_nothing_outside_the_tests_passes_prism_offline():
    # Wider than the tools: a guide, build.gradle or an installer script must not pass it either.
    found = {}
    for name, path in public_tracked_files():
        if name.startswith("tools/tests/") or not is_text(name):
            continue
        if count := len(OFFLINE_FLAG.findall(path.read_text(encoding="utf-8", errors="replace"))):
            found[name] = count
    assert found == OFFLINE_FLAG_RULES


def test_tools_guides_and_launcher_script_never_launch_offline():
    for name, text in tools_launch_text():
        assert not RUN_CLIENT_OUTSIDE_LOG_NAME.search(text), name
        assert "mcUsername" not in text, name


def test_cli_help_never_offers_an_offline_launch(isolated_tools_config):
    texts = dict(cli_help_texts())
    assert r"tools\rtx.bat launch" in texts
    for prog, text in texts.items():
        assert not OFFLINE_FLAG.search(text), prog
        assert "runClient" not in text, prog
        assert "mcUsername" not in text, prog


if __name__ == "__main__":
    unittest.main()

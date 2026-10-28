# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""The text the Materials and Showcase World zips carry, tracked under packaging/content/.

build.gradle zips these with the pack and world Packman pulls, so a reader who has only a zip meets the same
notices, license, and version wherever the zip came from."""

import unittest
from pathlib import Path

from rtx_tools.benchmark import util

CONTENT = util.PROJECT_DIR / "packaging" / "content"
MATERIALS_README = CONTENT / "materials" / "README.txt"
WORLD_README = CONTENT / "world" / "README.txt"
LICENSE = CONTENT / "LICENSE-CC-BY-4.0.txt"

NOT_OFFICIAL_NOTICE = "NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT."
LICENSE_SCOPE_NOTICE = (
    "The CC BY 4.0 license applies to the original world/resource-pack content NVIDIA is authorized to license. "
    "NVIDIA grants no rights to Minecraft software, assets, or trademarks owned by Mojang or Microsoft. "
    "Use of Minecraft remains subject to its applicable terms."
)


def _flattened(path: Path) -> str:
    """The file's text with every run of whitespace, line wraps included, as one space."""
    return " ".join(path.read_text(encoding="utf-8").split())


class ReleaseContentTests(unittest.TestCase):
    def test_each_readme_carries_both_notices_verbatim(self):
        for readme in (MATERIALS_README, WORLD_README):
            with self.subTest(readme=readme.parent.name):
                text = _flattened(readme)
                self.assertIn(NOT_OFFICIAL_NOTICE, text)
                self.assertIn(LICENSE_SCOPE_NOTICE, text)

    def test_the_materials_readme_does_not_promise_the_pdf_guides(self):
        # The release zip carries the pack alone; the artists' PDF guides are not part of it.
        self.assertNotIn("PDF", MATERIALS_README.read_text(encoding="utf-8"))

    def test_the_readmes_carry_the_version_token(self):
        # Gradle replaces @VERSION@ with the pin in tools/config.json, so a README never names a stale version.
        for readme in (MATERIALS_README, WORLD_README):
            with self.subTest(readme=readme.parent.name):
                first_line = readme.read_text(encoding="utf-8").splitlines()[0]
                self.assertIn("@VERSION@", first_line)

    def test_the_license_is_cc_by_4_0(self):
        self.assertTrue(LICENSE.read_text(encoding="utf-8").startswith("Attribution 4.0 International"))


if __name__ == "__main__":
    unittest.main()

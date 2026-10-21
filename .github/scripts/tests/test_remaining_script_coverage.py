#!/usr/bin/env python3
"""Tests for two scripts that had no tests on main: scripts/check_sensitive_files.py
(the content scan) and .github/scripts/track_build_size.py (the byte-size helper).

Run directly:
    python3 -m unittest discover -s .github/scripts/tests -p "test_*.py" -v
"""
from __future__ import annotations

import importlib.util
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]


def load(name: str, path: pathlib.Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


sensitive = load("check_sensitive_files", ROOT / "scripts" / "check_sensitive_files.py")
build = load("track_build_size", ROOT / ".github" / "scripts" / "track_build_size.py")


class SensitiveContentTest(unittest.TestCase):
    def test_flags_a_private_key_marker(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp, "key.txt")
            path.write_text("-----BEGIN PRIVATE KEY-----\n")
            found, pattern = sensitive.check_file_for_secrets(str(path))
        self.assertTrue(found)
        self.assertEqual(pattern, "PRIVATE KEY")

    def test_passes_a_file_with_no_markers(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp, "readme.md")
            path.write_text("Build the app with the standard steps.\n")
            found, _ = sensitive.check_file_for_secrets(str(path))
        self.assertFalse(found)


class BuildSizeHelperTest(unittest.TestCase):
    def test_returns_none_for_a_missing_file(self):
        self.assertIsNone(build.get_file_size_mb("/no/such/file.apk"))

    def test_reports_size_in_megabytes(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp, "app.apk")
            path.write_bytes(b"x" * (2 * 1024 * 1024))
            self.assertAlmostEqual(build.get_file_size_mb(str(path)), 2.0)


if __name__ == "__main__":
    unittest.main()

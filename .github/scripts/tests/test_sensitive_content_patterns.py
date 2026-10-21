#!/usr/bin/env python3
"""Tests for the content patterns in scripts/check_sensitive_files.py.

Run directly:
    python3 -m unittest discover -s .github/scripts/tests -p "test_*.py" -v
"""
from __future__ import annotations

import importlib.util
import pathlib
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[3] / "scripts" / "check_sensitive_files.py"
spec = importlib.util.spec_from_file_location("check_sensitive_files", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def scan(text: str):
    with tempfile.TemporaryDirectory() as tmp:
        path = pathlib.Path(tmp, "sample.txt")
        path.write_text(text, encoding="utf-8")
        return module.check_file_for_secrets(str(path))


class ContentPatternTest(unittest.TestCase):
    def test_prose_mentioning_a_keyword_passes(self):
        found, _ = scan("Set a strong password and keep the firebase console access private.")
        self.assertFalse(found)

    def test_assigned_password_is_flagged(self):
        found, pattern = scan('password = "hunter22-real"')
        self.assertTrue(found)
        self.assertEqual(pattern, "password")

    def test_private_key_marker_is_flagged(self):
        found, pattern = scan("-----BEGIN PRIVATE KEY-----")
        self.assertTrue(found)
        self.assertEqual(pattern, "PRIVATE KEY")


if __name__ == "__main__":
    unittest.main()

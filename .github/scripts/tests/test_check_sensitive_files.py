#!/usr/bin/env python3
"""Tests for staged file name parsing in scripts/check_sensitive_files.py.

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


class StagedNameTest(unittest.TestCase):
    def test_keeps_non_ascii_names_unquoted(self):
        names = module.parse_staged_names("conf-\u00e9.properties\0notes.txt\0")
        self.assertEqual(names, ["conf-\u00e9.properties", "notes.txt"])

    def test_content_scan_finds_a_secret_in_a_non_ascii_name(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp, "conf-\u00e9.properties")
            path.write_text("-----BEGIN PRIVATE KEY-----\n")
            found, pattern = module.check_file_for_secrets(str(path))
        self.assertTrue(found)
        self.assertIsNotNone(pattern)


if __name__ == "__main__":
    unittest.main()

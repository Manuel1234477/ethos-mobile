#!/usr/bin/env python3
"""Tests for scripts/lookup_error_code.py.

Run directly:
    python3 -m unittest discover -s .github/scripts/tests -p "test_*.py" -v
"""
from __future__ import annotations

import importlib.util
import pathlib
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[3] / "scripts" / "lookup_error_code.py"
spec = importlib.util.spec_from_file_location("lookup_error_code", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class LookupTest(unittest.TestCase):
    def test_finds_an_entry_by_its_name(self):
        entry = module.ERROR_CODES[0]
        results = module.lookup(entry["name"])
        self.assertIn(entry, results)

    def test_finds_an_entry_by_a_recovery_keyword_in_its_table(self):
        results = module.lookup("ASAuthorizationError")
        self.assertTrue(results)
        self.assertTrue(all("ASAuthorizationError" in str(e) for e in results))

    def test_returns_nothing_for_an_unknown_query(self):
        self.assertEqual(module.lookup("no-such-error-xyz-123"), [])


if __name__ == "__main__":
    unittest.main()

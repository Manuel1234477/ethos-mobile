#!/usr/bin/env python3
"""Tests for track_build_size.py regression handling.

Run directly:
    python3 -m unittest discover -s .github/scripts/tests -p "test_*.py" -v
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import os
import pathlib
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "track_build_size.py"
spec = importlib.util.spec_from_file_location("track_build_size", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

APK = "android/app/build/outputs/apk/release/app-release.apk"


class AndroidRegressionTest(unittest.TestCase):
    def setUp(self):
        self._old_cwd = os.getcwd()
        self._tmp = tempfile.TemporaryDirectory()
        os.chdir(self._tmp.name)
        pathlib.Path(APK).parent.mkdir(parents=True)

    def tearDown(self):
        os.chdir(self._old_cwd)
        self._tmp.cleanup()

    def _write_apk(self, size_mb: float):
        pathlib.Path(APK).write_bytes(b"x" * int(size_mb * 1024 * 1024))

    def test_growth_over_five_percent_warns(self):
        self._write_apk(10.6)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            result = module.track_android_size(10.0)
        self.assertEqual(result, "warning")
        self.assertIn("::warning::Android APK size increased", out.getvalue())

    def test_growth_within_five_percent_does_not_warn(self):
        self._write_apk(10.2)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            result = module.track_android_size(10.0)
        self.assertNotEqual(result, "warning")
        self.assertNotIn("::warning::", out.getvalue())


if __name__ == "__main__":
    unittest.main()

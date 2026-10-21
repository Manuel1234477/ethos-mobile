#!/usr/bin/env python3
"""Tests for the iOS size measurement in track_build_size.py.

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

APP_DIR = "build/Release-iphoneos/EthosProtocol.app"


class IosSizeTest(unittest.TestCase):
    def setUp(self):
        self._old_cwd = os.getcwd()
        self._tmp = tempfile.TemporaryDirectory()
        os.chdir(self._tmp.name)
        pathlib.Path(APP_DIR).mkdir(parents=True)

    def tearDown(self):
        os.chdir(self._old_cwd)
        self._tmp.cleanup()

    def _write_app(self, size_mb: float):
        pathlib.Path(APP_DIR, "binary").write_bytes(b"x" * int(size_mb * 1024 * 1024))

    def test_four_percent_growth_does_not_warn(self):
        self._write_app(10.4)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            module.track_ios_size(10.0)
        self.assertNotIn("::warning::iOS app size increased", out.getvalue())

    def test_six_percent_growth_warns(self):
        self._write_app(10.6)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            module.track_ios_size(10.0)
        self.assertIn("::warning::iOS app size increased", out.getvalue())


if __name__ == "__main__":
    unittest.main()

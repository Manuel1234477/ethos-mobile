#!/usr/bin/env python3
"""Tests for the pin-count guard in check_cert_expiry.py.

Run directly:
    python3 -m unittest discover -s .github/scripts/tests -p "test_*.py" -v
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import pathlib
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "check_cert_expiry.py"
spec = importlib.util.spec_from_file_location("check_cert_expiry", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class NoPinsTest(unittest.TestCase):
    def test_fails_when_both_pin_sources_are_empty(self):
        with tempfile.TemporaryDirectory() as tmp:
            empty_source = pathlib.Path(tmp, "CertificatePinning.kt")
            empty_source.write_text("")
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = module.run("api.example.test", 443, 90, [], str(empty_source))
        self.assertNotEqual(code, 0)
        self.assertIn("::error::No pinned certificate hashes", out.getvalue())


class FetchFailureTest(unittest.TestCase):
    def test_fails_when_the_fetch_keeps_raising(self):
        def raising_fetch(host, port):
            raise OSError("network unreachable")

        original_fetch = module.fetch_cert_chain
        original_backoff = module.FETCH_BACKOFF_SECONDS
        module.fetch_cert_chain = raising_fetch
        module.FETCH_BACKOFF_SECONDS = 0
        try:
            with tempfile.TemporaryDirectory() as tmp:
                source = pathlib.Path(tmp, "CertificatePinning.kt")
                source.write_text('val pins = listOf("sha256/AAAA")')
                with contextlib.redirect_stdout(io.StringIO()) as out:
                    code = module.run("api.example.test", 443, 90, [], str(source))
        finally:
            module.fetch_cert_chain = original_fetch
            module.FETCH_BACKOFF_SECONDS = original_backoff
        self.assertNotEqual(code, 0)
        self.assertIn("::error::Could not fetch the certificate chain", out.getvalue())


if __name__ == "__main__":
    unittest.main()

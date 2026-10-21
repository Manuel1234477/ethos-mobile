#!/usr/bin/env python3
"""Tests for verify_cert_pins.py (#173).

Run with: python3 .github/scripts/test_verify_cert_pins.py
"""

import base64
import hashlib
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import verify_cert_pins  # noqa: E402

PLACEHOLDER_SOURCE = '''
        val PLACEHOLDER_PINS: Set<String> = setOf(
            // Current certificate SPKI SHA-256 (replace with real value)
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            // Backup certificate SPKI SHA-256 (replace with real value)
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
        )
'''


def real_pin(seed):
    """A well-formed, non-placeholder 44-character Base64 SHA-256 digest."""
    return base64.b64encode(hashlib.sha256(seed.encode()).digest()).decode()


def source_with(pins):
    literals = ",\n".join('            "{}"'.format(pin) for pin in pins)
    return "val PLACEHOLDER_PINS: Set<String> = setOf(\n{}\n        )\n".format(literals)


def build_config_with(value):
    return 'public final class BuildConfig {\n  public static final String CERT_PINS = "%s";\n}\n' % value


class VerifyCertPinsTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def write(self, name, contents):
        path = os.path.join(self.tmp.name, name)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(contents)
        return path

    def verify(self, build_type="release", build_config="", source=PLACEHOLDER_SOURCE,
               warn_if_unconfigured=False, shared_pins=None):
        return verify_cert_pins.verify(
            build_type,
            self.write("BuildConfig.java", build_config_with(build_config)),
            self.write("CertificatePinning.kt", source),
            warn_if_unconfigured,
            shared_pins_path=self.write_shared_pins(shared_pins) if shared_pins is not None else None,
        )

    def write_shared_pins(self, pins):
        return self.write("cert-pins.json", json.dumps({"pins": list(pins)}))

    def test_placeholder_default_pins_fail_release(self):
        self.assertEqual(1, self.verify())

    def test_real_default_pins_pass_release(self):
        source = source_with([real_pin("current"), real_pin("backup")])
        self.assertEqual(0, self.verify(source=source))

    def test_build_config_pins_override_source_defaults(self):
        pins = "{},{}".format(real_pin("current"), real_pin("backup"))
        # Source is still the placeholder set; the configured release value wins.
        self.assertEqual(0, self.verify(build_config=pins))

    def test_placeholder_build_config_pin_fails_release(self):
        self.assertEqual(1, self.verify(build_config="PLACEHOLDER"))

    def test_empty_pin_configuration_fails_release(self):
        self.assertEqual(1, self.verify(source=source_with([])))

    def test_empty_pin_configuration_passes_for_debug(self):
        # CertificatePinner documents an empty pin set as "pinning disabled", which is a
        # legitimate debug configuration (local/dev host) — only release is gated.
        self.assertEqual(0, self.verify(build_type="debug", source=source_with([])))

    def test_placeholder_pins_pass_for_debug(self):
        self.assertEqual(0, self.verify(build_type="debug"))

    def test_malformed_pin_fails_release(self):
        self.assertEqual(1, self.verify(build_config="not-a-digest"))

    def test_unconfigured_placeholder_is_advisory_when_requested(self):
        # The unsigned R8 smoke build CI runs on every PR cannot reach users, so an
        # unconfigured pin set is reported rather than failing the job.
        self.assertEqual(0, self.verify(warn_if_unconfigured=True))
        self.assertEqual(0, self.verify(source=source_with([]), warn_if_unconfigured=True))

    def test_configured_placeholder_still_fails_when_warn_requested(self):
        # An explicitly configured pin set is always gated hard, however the build is run.
        self.assertEqual(1, self.verify(build_config="PLACEHOLDER", warn_if_unconfigured=True))

    def test_build_config_matching_shared_pins_passes_release(self):
        pins = [real_pin("current"), real_pin("backup")]
        self.assertEqual(
            0,
            self.verify(build_config=",".join(pins), shared_pins=pins),
        )

    def test_build_config_drifting_from_shared_pins_fails_release(self):
        shared = [real_pin("current"), real_pin("backup")]
        drifted = [real_pin("current"), real_pin("rotated")]
        self.assertEqual(
            1,
            self.verify(build_config=",".join(drifted), shared_pins=shared),
        )

    def test_build_config_missing_shared_pin_fails_release(self):
        shared = [real_pin("current"), real_pin("backup")]
        self.assertEqual(
            1,
            self.verify(build_config=real_pin("current"), shared_pins=shared),
        )

    def test_build_config_drift_is_not_gated_for_debug(self):
        # Drift is only a release gate; debug builds may pin a local/dev host.
        shared = [real_pin("current"), real_pin("backup")]
        self.assertEqual(
            0,
            self.verify(build_type="debug", build_config=real_pin("rotated"), shared_pins=shared),
        )


if __name__ == "__main__":
    unittest.main()

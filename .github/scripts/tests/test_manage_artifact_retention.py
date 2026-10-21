#!/usr/bin/env python3
"""Tests for the lifecycle rules in scripts/manage_artifact_retention.py.

Run directly:
    python3 -m unittest discover -s .github/scripts/tests -p "test_*.py" -v
"""
from __future__ import annotations

import importlib.util
import pathlib
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[3] / "scripts" / "manage_artifact_retention.py"
spec = importlib.util.spec_from_file_location("manage_artifact_retention", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class LifecycleRulesTest(unittest.TestCase):
    def rule(self, rules, rule_id):
        return next(r for r in rules if r["ID"] == rule_id)

    def test_release_rules_expire_after_the_release_window(self):
        rules = module.build_lifecycle_rules(release_days=365, snapshot_days=30)
        for rule_id in ("expire-ios-releases", "expire-android-releases"):
            with self.subTest(rule=rule_id):
                self.assertEqual(self.rule(rules, rule_id)["Expiration"]["Days"], 365)

    def test_snapshot_rules_use_the_snapshot_window(self):
        rules = module.build_lifecycle_rules(release_days=365, snapshot_days=30)
        self.assertEqual(
            sum(1 for r in rules if r.get("Expiration", {}).get("Days") == 30) >= 1, True
        )

    def test_every_rule_is_enabled(self):
        rules = module.build_lifecycle_rules(release_days=365, snapshot_days=30)
        self.assertTrue(all(r["Status"] == "Enabled" for r in rules))


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Single-source the certificate pin set across Android and iOS build configs.

Reads the canonical pin set from ``shared/cert-pins.json`` and keeps the
platform-specific inputs in sync:

* Android: ``CERT_PINS`` buildConfigField in ``android/app/build.gradle.kts``
* iOS:     ``TLS_PUBLIC_KEY_PIN_CURRENT`` / ``TLS_PUBLIC_KEY_PIN_BACKUP``
           build settings in ``ios/EthosProtocol/project.yml``

Usage:
    python .github/scripts/generate_cert_pins.py --check   # CI gate (exit 1 on drift)
    python .github/scripts/generate_cert_pins.py --write   # rewrite platform inputs

The script is intentionally dependency-free (stdlib only) so it can run in any
CI job without extra setup.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple

# ---------------------------------------------------------------------------
# Paths (relative to the repository root)
# ---------------------------------------------------------------------------

REPO_ROOT = Path(__file__).resolve().parents[2]

SHARED_PINS_PATH = REPO_ROOT / "shared" / "cert-pins.json"
ANDROID_BUILD_GRADLE = REPO_ROOT / "android" / "app" / "build.gradle.kts"
IOS_PROJECT_YML = REPO_ROOT / "ios" / "EthosProtocol" / "project.yml"

# ---------------------------------------------------------------------------
# Shared pin file
# ---------------------------------------------------------------------------

REQUIRED_KEYS = ("current", "backup")


class PinConfigError(Exception):
    """Raised when the shared pin file is missing or malformed."""


def load_shared_pins(path: Path = SHARED_PINS_PATH) -> Dict[str, str]:
    """Load and validate the canonical pin set.

    Expected schema::

        {
          "current": "<base64 sha256 pin>",
          "backup":  "<base64 sha256 pin>"
        }
    """
    if not path.is_file():
        raise PinConfigError(f"Shared pin file not found: {path}")

    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise PinConfigError(f"Invalid JSON in {path}: {exc}") from exc

    if not isinstance(raw, dict):
        raise PinConfigError(f"{path} must contain a JSON object")

    pins: Dict[str, str] = {}
    for key in REQUIRED_KEYS:
        value = raw.get(key)
        if not isinstance(value, str) or not value.strip():
            raise PinConfigError(f"{path}: missing or empty '{key}' pin")
        pins[key] = value.strip()

    if pins["current"] == pins["backup"]:
        raise PinConfigError(
            f"{path}: 'current' and 'backup' pins must differ "
            "(backup exists to allow rotation)"
        )

    return pins


# ---------------------------------------------------------------------------
# Android: build.gradle.kts
# ---------------------------------------------------------------------------

# Matches:  buildConfigField("String", "CERT_PINS", "\"pin1,pin2\"")
ANDROID_PIN_RE = re.compile(
    r'buildConfigField\(\s*"String"\s*,\s*"CERT_PINS"\s*,\s*"(?P<value>(?:[^"\\]|\\.)*)"\s*\)'
)


def _android_expected_value(pins: Dict[str, str]) -> str:
    """Return the escaped string literal content for CERT_PINS."""
    joined = f"{pins['current']},{pins['backup']}"
    # The value is embedded inside a Kotlin string literal, so escape quotes.
    return joined.replace("\\", "\\\\").replace('"', '\\"')


def read_android_pins(path: Path = ANDROID_BUILD_GRADLE) -> Optional[List[str]]:
    """Extract the CERT_PINS list from build.gradle.kts, or None if absent."""
    if not path.is_file():
        return None
    match = ANDROID_PIN_RE.search(path.read_text(encoding="utf-8"))
    if not match:
        return None
    raw = match.group("value")
    # Unescape the Kotlin string literal content.
    unescaped = raw.replace('\\"', '"').replace("\\\\", "\\")
    return [p.strip() for p in unescaped.split(",") if p.strip()]


def write_android_pins(pins: Dict[str, str], path: Path = ANDROID_BUILD_GRADLE) -> bool:
    """Rewrite CERT_PINS in build.gradle.kts. Returns True if the file changed."""
    if not path.is_file():
        raise PinConfigError(f"Android build file not found: {path}")

    original = path.read_text(encoding="utf-8")
    expected = _android_expected_value(pins)

    if not ANDROID_PIN_RE.search(original):
        raise PinConfigError(
            f"{path}: could not locate buildConfigField(\"String\", \"CERT_PINS\", ...)"
        )

    updated = ANDROID_PIN_RE.sub(
        lambda m: m.group(0)[: m.start("value") - m.start(0)]
        + expected
        + m.group(0)[m.end("value") - m.start(0):],
        original,
        count=1,
    )

    if updated == original:
        return False
    path.write_text(updated, encoding="utf-8")
    return True


# ---------------------------------------------------------------------------
# iOS: project.yml
# ---------------------------------------------------------------------------

IOS_CURRENT_RE = re.compile(
    r'^(?P<indent>\s*)TLS_PUBLIC_KEY_PIN_CURRENT\s*:\s*(?P<value>.*)$',
    re.MULTILINE,
)
IOS_BACKUP_RE = re.compile(
    r'^(?P<indent>\s*)TLS_PUBLIC_KEY_PIN_BACKUP\s*:\s*(?P<value>.*)$',
    re.MULTILINE,
)


def _strip_yaml_scalar(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
        return value[1:-1]
    return value


def read_ios_pins(path: Path = IOS_PROJECT_YML) -> Optional[Dict[str, str]]:
    """Extract iOS pin settings from project.yml, or None if absent."""
    if not path.is_file():
        return None
    text = path.read_text(encoding="utf-8")

    current_match = IOS_CURRENT_RE.search(text)
    backup_match = IOS_BACKUP_RE.search(text)
    if not current_match or not backup_match:
        return None

    return {
        "current": _strip_yaml_scalar(current_match.group("value")),
        "backup": _strip_yaml_scalar(backup_match.group("value")),
    }


def write_ios_pins(pins: Dict[str, str], path: Path = IOS_PROJECT_YML) -> bool:
    """Rewrite iOS pin settings in project.yml. Returns True if the file changed."""
    if not path.is_file():
        raise PinConfigError(f"iOS project file not found: {path}")

    original = path.read_text(encoding="utf-8")

    def _replace(regex: re.Pattern, key: str, text: str) -> str:
        match = regex.search(text)
        if not match:
            raise PinConfigError(
                f"{path}: could not locate TLS_PUBLIC_KEY_PIN_{key.upper()}"
            )
        indent = match.group("indent")
        return (
            text[: match.start()]
            + f"{indent}TLS_PUBLIC_KEY_PIN_{key.upper()}: {pins[key]}"
            + text[match.end():]
        )

    updated = _replace(IOS_CURRENT_RE, "current", original)
    updated = _replace(IOS_BACKUP_RE, "backup", updated)

    if updated == original:
        return False
    path.write_text(updated, encoding="utf-8")
    return True


# ---------------------------------------------------------------------------
# Drift detection
# ---------------------------------------------------------------------------

def check_drift(
    pins: Dict[str, str],
    android_path: Path = ANDROID_BUILD_GRADLE,
    ios_path: Path = IOS_PROJECT_YML,
) -> List[str]:
    """Return a list of human-readable drift descriptions (empty == in sync)."""
    problems: List[str] = []
    expected_list = [pins["current"], pins["backup"]]

    android_pins = read_android_pins(android_path)
    if android_pins is None:
        problems.append(
            f"Android: CERT_PINS buildConfigField not found in {android_path}"
        )
    elif android_pins != expected_list:
        problems.append(
            "Android: CERT_PINS drift in "
            f"{android_path}\n  expected: {expected_list}\n  actual:   {android_pins}"
        )

    ios_pins = read_ios_pins(ios_path)
    if ios_pins is None:
        problems.append(
            f"iOS: TLS_PUBLIC_KEY_PIN_CURRENT/BACKUP not found in {ios_path}"
        )
    else:
        for key in REQUIRED_KEYS:
            if ios_pins.get(key) != pins[key]:
                problems.append(
                    f"iOS: TLS_PUBLIC_KEY_PIN_{key.upper()} drift in {ios_path}\n"
                    f"  expected: {pins[key]}\n  actual:   {ios_pins.get(key)}"
                )

    return problems


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Sync certificate pins from shared/cert-pins.json to platform configs."
    )
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument(
        "--check",
        action="store_true",
        help="Verify platform configs match the shared pin file (exit 1 on drift).",
    )
    mode.add_argument(
        "--write",
        action="store_true",
        help="Rewrite platform configs from the shared pin file.",
    )
    parser.add_argument(
        "--shared",
        type=Path,
        default=SHARED_PINS_PATH,
        help=f"Path to shared pin file (default: {SHARED_PINS_PATH}).",
    )
    parser.add_argument(
        "--android",
        type=Path,
        default=ANDROID_BUILD_GRADLE,
        help=f"Path to Android build.gradle.kts (default: {ANDROID_BUILD_GRADLE}).",
    )
    parser.add_argument(
        "--ios",
        type=Path,
        default=IOS_PROJECT_YML,
        help=f"Path to iOS project.yml (default: {IOS_PROJECT_YML}).",
    )
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    args = _build_parser().parse_args(argv)

    try:
        pins = load_shared_pins(args.shared)
    except PinConfigError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    if args.check:
        problems = check_drift(pins, android_path=args.android, ios_path=args.ios)
        if problems:
            print("Certificate pin drift detected:", file=sys.stderr)
            for problem in problems:
                print(f"  - {problem}", file=sys.stderr)
            print(
                "\nRun: python .github/scripts/generate_cert_pins.py --write",
                file=sys.stderr,
            )
            return 1
        print("Certificate pins are in sync across Android and iOS.")
        return 0

    # --write
    try:
        android_changed = write_android_pins(pins, path=args.android)
        ios_changed = write_ios_pins(pins, path=args.ios)
    except PinConfigError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    print(f"Android build.gradle.kts: {'updated' if android_changed else 'unchanged'}")
    print(f"iOS project.yml:          {'updated' if ios_changed else 'unchanged'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
#!/usr/bin/env python3
"""Fails CI if a Release build's Info.plist lacks TLS certificate pinning.

CertificatePinning.swift's PinningDelegate reads its pin set from each
target's own Info.plist (Bundle.main resolves independently per target — an
app extension does NOT inherit its host app's Info.plist) under the key
TLS_PUBLIC_KEY_PINS. An absent or empty array is treated as "pinning
disabled" — intentionally, so local-dev builds can hit a different host
without a live certificate to pin against. That same fallback means a
Release build silently ships with pinning off if the key is ever removed,
or a new target/config is added without it, with no signal to anyone.

This script re-reads a target's Info.plist the same way Bundle.main does
(plistlib, not string-matching) and fails if TLS_PUBLIC_KEY_PINS is missing,
empty, not an array of non-empty strings, or contains nothing but unexpanded
$(BUILD_SETTING) placeholders (i.e. the pins were never configured for this
build configuration — see project.yml). It is a no-op for any
--configuration other than Release, matching PinningDelegate's documented
"empty pins disables pinning for local dev" design.

Usage:
    check_tls_pinning.py --configuration Release \\
        --target EthosProtocol:EthosProtocol/Info.plist \\
        --target TTLWidget:TTLWidget/Info.plist
"""
from __future__ import annotations

import argparse
import json
import plistlib
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

PINS_KEY = "TLS_PUBLIC_KEY_PINS"
ENFORCED_CONFIGURATION = "release"
DEFAULT_EXPIRY_WARNING_DAYS = 60
DEFAULT_EXPIRY_FAIL_DAYS = 14
DEFAULT_SHARED_PINS_PATH = "shared/cert-pins.json"


def parse_target(raw: str) -> tuple[str, str]:
    """Parses a --target NAME:PATH argument."""
    name, sep, path = raw.partition(":")
    if not sep or not name or not path:
        raise argparse.ArgumentTypeError(f"--target must be NAME:PATH, got {raw!r}")
    return name, path


def load_shared_pins(shared_pins_path: str) -> tuple[list[str] | None, str | None]:
    """Loads the single-source pin set from shared/cert-pins.json.

    Returns (pins, error). `pins` is None when the file could not be read or
    parsed; `error` is a failure message in that case.
    """
    path = Path(shared_pins_path)
    if not path.is_file():
        return None, f"Shared pin set not found at {shared_pins_path}"

    try:
        with path.open("r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception as exc:  # json raises several exception types
        return None, f"Could not parse {shared_pins_path} as JSON ({exc})"

    pins = data.get("pins") if isinstance(data, dict) else None
    if not isinstance(pins, list) or not pins:
        return None, (
            f"{shared_pins_path} must contain a non-empty 'pins' array of "
            f"Base64-encoded SPKI SHA-256 hashes."
        )
    if not all(isinstance(p, str) and p for p in pins):
        return None, (
            f"{shared_pins_path} 'pins' entries must be non-empty strings "
            f"(Base64-encoded SPKI SHA-256 hashes)."
        )
    return list(pins), None


def check_target(
    name: str,
    plist_path: str,
    shared_pins: list[str] | None = None,
) -> str | None:
    """Returns a failure message for `name`, or None if its pins are configured."""
    path = Path(plist_path)
    if not path.is_file():
        return f"{name}: Info.plist not found at {plist_path}"

    try:
        with path.open("rb") as f:
            plist = plistlib.load(f)
    except Exception as exc:  # plistlib raises several exception types
        return f"{name}: could not parse {plist_path} as a plist ({exc})"

    pins = plist.get(PINS_KEY)
    if not pins:
        return (
            f"{name}: {PINS_KEY} is missing or empty in {plist_path}. "
            f"Release builds must ship at least one pinned SPKI hash (see "
            f"Sources/Services/CertificatePinning.swift) or certificate "
            f"pinning is silently disabled for this target."
        )
    if not isinstance(pins, list) or not all(isinstance(p, str) and p for p in pins):
        return (
            f"{name}: {PINS_KEY} in {plist_path} must be an array of "
            f"non-empty strings (Base64-encoded SPKI SHA-256 hashes)."
        )
    # The plist declares its entries as $(TLS_PUBLIC_KEY_PIN_CURRENT) / $(..._BACKUP)
    # build settings, which are blank until configured for the build. PinningDelegate
    # ignores such entries, so a plist carrying only placeholders means pinning is off.
    if not [p for p in pins if not p.strip().startswith(("$(", "${"))]:
        return (
            f"{name}: {PINS_KEY} in {plist_path} contains only unconfigured build-setting "
            f"placeholders ({', '.join(pins)}). Set TLS_PUBLIC_KEY_PIN_CURRENT / "
            f"TLS_PUBLIC_KEY_PIN_BACKUP for this configuration, or certificate pinning is "
            f"silently disabled for this target."
        )
    if shared_pins is not None:
        # The plist's entries are build-setting placeholders; compare the
        # resolved values (the shared pin set) against the single source of
        # truth so a rotation that only updates one platform fails CI.
        resolved = [p for p in pins if not p.strip().startswith(("$(", "${"))]
        if resolved != shared_pins:
            return (
                f"{name}: {PINS_KEY} in {plist_path} does not match the shared pin set "
                f"({', '.join(resolved)} != {', '.join(shared_pins)}). Regenerate the "
                f"platform inputs from shared/cert-pins.json."
            )
    return None


def parse_certificate_expiry(raw: str) -> datetime:
    """Parses YYYY-MM-DD or ISO-8601 timestamps into UTC-aware datetimes."""
    value = raw.strip()
    if not value:
        raise ValueError("certificate expiry must not be empty")

    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        try:
            parsed = datetime.strptime(value, "%Y-%m-%d")
        except ValueError as exc:
            raise ValueError(
                "certificate expiry must be ISO-8601 or YYYY-MM-DD, got "
                f"{raw!r}"
            ) from exc

    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    else:
        parsed = parsed.astimezone(timezone.utc)
    return parsed


def check_certificate_expiry(
    certificate_expiry: str | None,
    *,
    warning_days: int = DEFAULT_EXPIRY_WARNING_DAYS,
    fail_days: int = DEFAULT_EXPIRY_FAIL_DAYS,
) -> tuple[int, str | None]:
    """Returns (exit_code, message) for the configured certificate expiry date.

    A warning is emitted when the remaining lifetime falls inside the warning window.
    If the cert is within the fail window, the script exits non-zero to force rotation.
    """
    if not certificate_expiry:
        return 0, None

    try:
        expires_at = parse_certificate_expiry(certificate_expiry)
    except ValueError as exc:
        return 1, f"::error::Invalid certificate expiry '{certificate_expiry}': {exc}"

    now = datetime.now(timezone.utc)
    remaining = expires_at - now
    if remaining <= timedelta(0):
        return 1, (
            "::error::Configured certificate expiry is in the past: "
            f"{expires_at.isoformat()} (today is {now.isoformat()}). "
            "Rotate the certificate before shipping."
        )

    if remaining <= timedelta(days=warning_days):
        message = (
            f"::warning::Current certificate expires on {expires_at.date().isoformat()} "
            f"({remaining.days} days remaining). Rotate the certificate before it falls "
            f"within {warning_days} days."
        )
        if remaining <= timedelta(days=fail_days):
            return 1, message.replace("::warning::", "::error::")
        return 0, message

    return 0, None


def run(
    configuration: str,
    targets: list[tuple[str, str]],
    *,
    certificate_expiry: str | None = None,
    expiry_warning_days: int = DEFAULT_EXPIRY_WARNING_DAYS,
    expiry_fail_days: int = DEFAULT_EXPIRY_FAIL_DAYS,
    shared_pins_path: str = DEFAULT_SHARED_PINS_PATH,
) -> int:
    if configuration.strip().lower() != ENFORCED_CONFIGURATION:
        print(
            f"Skipping {PINS_KEY} check for '{configuration}' configuration — "
            f"pinning is only enforced for Release builds (empty pins "
            f"intentionally disable pinning for local dev, per PinningDelegate)."
        )
        return 0

    shared_pins, shared_pins_error = load_shared_pins(shared_pins_path)
    if shared_pins_error:
        print(f"::error::{shared_pins_error}")
        return 1

    failures = [
        msg
        for name, path in targets
        for msg in [check_target(name, path, shared_pins)]
        if msg
    ]

    expiry_code, expiry_message = check_certificate_expiry(
        certificate_expiry,
        warning_days=expiry_warning_days,
        fail_days=expiry_fail_days,
    )
    if expiry_message:
        print(expiry_message)

    if failures:
        print("TLS certificate pinning check FAILED for Release configuration:")
        for msg in failures:
            print(f"::error::{msg}")
        return 1

    if expiry_code != 0:
        return expiry_code

    names = ", ".join(name for name, _ in targets)
    print(f"TLS certificate pinning check passed for: {names}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--configuration",
        required=True,
        help="Build configuration being checked (e.g. Debug, Release). Only "
        "'Release' is enforced; every other value is skipped.",
    )
    parser.add_argument(
        "--target",
        dest="targets",
        action="append",
        type=parse_target,
        required=True,
        metavar="NAME:PATH",
        help="Target name and path to its Info.plist. May be repeated.",
    )
    parser.add_argument(
        "--certificate-expiry",
        default=None,
        help="Documented certificate expiry date in YYYY-MM-DD or ISO-8601 format. "
             "If set, the script warns when the cert is within the configured lead time.",
    )
    parser.add_argument(
        "--expiry-warning-days",
        type=int,
        default=DEFAULT_EXPIRY_WARNING_DAYS,
        help="Warn when the certificate has fewer than this many days remaining.",
    )
    parser.add_argument(
        "--expiry-fail-days",
        type=int,
        default=DEFAULT_EXPIRY_FAIL_DAYS,
        help="Fail the build when the certificate has fewer than this many days remaining.",
    )
    parser.add_argument(
        "--shared-pins",
        default=DEFAULT_SHARED_PINS_PATH,
        help="Path to the single-source pin set (shared/cert-pins.json) that the "
             "generated platform inputs must match.",
    )
    args = parser.parse_args(argv)
    return run(
        args.configuration,
        args.targets,
        certificate_expiry=args.certificate_expiry,
        expiry_warning_days=args.expiry_warning_days,
        expiry_fail_days=args.expiry_fail_days,
        shared_pins_path=args.shared_pins,
    )


if __name__ == "__main__":
    sys.exit(main())

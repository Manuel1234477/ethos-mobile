#!/usr/bin/env python3
"""verify_cert_pins.py

#173 — Release-build gate against shipping placeholder certificate pins.

Reads the certificate-pin configuration that would actually be compiled into a
`release` build and fails if it is empty or looks like a placeholder, so a
forgotten pin surfaces in CI instead of as universal connection failures in
production.

The effective pin set is resolved the same way `CertificatePinner` resolves it
at runtime (see `android/app/src/main/java/com/ethosprotocol/api/CertificatePinning.kt`):

  1. `BuildConfig.CERT_PINS` (comma-separated), supplied by the `ETHOS_CERT_PINS`
     environment variable / `ethos.certPins` Gradle property for release builds.
  2. When that is blank, the pin literals compiled into the source.

Only `release` is gated: `CertificatePinner` documents an empty pin set as
"pinning disabled", which is the intended configuration for debug builds pointed
at a local/dev host.

A pin set that was *explicitly configured* is always gated hard — a typo or a
placeholder in `ETHOS_CERT_PINS` must fail. `--warn-if-unconfigured` downgrades the
"nothing configured at all" case to a warning, for release builds that cannot reach
users anyway (the unsigned R8 smoke build CI runs on every PR).

Usage:
  verify_cert_pins.py --build-type release [--warn-if-unconfigured] \\
      --build-config app/build/generated/source/buildConfig/release/com/ethosprotocol/BuildConfig.java \\
      --source app/src/main/java/com/ethosprotocol/api/CertificatePinning.kt

Exit codes:
  0 – configuration is a plausible real pin set (or the build type is not gated)
  1 – empty or placeholder pin configuration (details printed as ::error:: annotations)
"""

import argparse
import base64
import json
import re
import sys
from datetime import datetime, timedelta, timezone

# A Base64-encoded SHA-256 digest: 43 payload characters plus one '=' pad.
PIN_PATTERN = re.compile(r"^[A-Za-z0-9+/]{43}=$")

CERT_PINS_PATTERN = re.compile(r'String\s+CERT_PINS\s*=\s*"([^"]*)"')
PINS_BLOCK = re.compile(
    r"(?:DEFAULT_PINS|PLACEHOLDER_PINS)[^=]*=\s*setOf\((.*?)\)", re.DOTALL)
QUOTED = re.compile(r'"([^"]*)"')
# Matches a string literal *or* a line comment, so comments can be stripped without
# mistaking the "//" inside a Base64 pin for the start of one.
STRING_OR_COMMENT = re.compile(r'"[^"\n]*"|//[^\n]*')


def error(message):
    print("::error::{}".format(message))


def warn(message):
    print("::warning::{}".format(message))


def read(path):
    """Returns the contents of `path`, or "" when it does not exist."""
    try:
        with open(path, encoding="utf-8") as handle:
            return handle.read()
    except FileNotFoundError:
        return ""


def build_config_pins(path):
    """Returns the pins declared by BuildConfig.CERT_PINS, or [] when unset."""
    match = CERT_PINS_PATTERN.search(read(path))
    if not match:
        return []
    return [pin.strip() for pin in match.group(1).split(",") if pin.strip()]


def source_default_pins(path):
    """Returns the pin literals compiled into CertificatePinning.kt."""
    # Comments are stripped first: they contain both parentheses and quotes, either of
    # which would otherwise confuse the literal extraction below.
    source = STRING_OR_COMMENT.sub(
        lambda m: m.group(0) if m.group(0).startswith('"') else "", read(path))
    match = PINS_BLOCK.search(source)
    if not match:
        return []
    return [pin.strip() for pin in QUOTED.findall(match.group(1)) if pin.strip()]


def shared_pins(path):
    """Returns the canonical pin set from shared/cert-pins.json, or None when absent.

    The file is the single source of truth both platform inputs are generated from:
    `{"pins": ["<base64 sha-256 spki>", ...]}`.
    """
    raw = read(path)
    if not raw.strip():
        return None
    try:
        data = json.loads(raw)
    except ValueError as exc:
        raise ValueError("shared pin file {} is not valid JSON: {}".format(path, exc))
    if not isinstance(data, dict):
        raise ValueError("shared pin file {} must be a JSON object".format(path))
    pins = data.get("pins")
    if not isinstance(pins, list):
        raise ValueError(
            "shared pin file {} must contain a 'pins' array".format(path))
    result = []
    for pin in pins:
        if not isinstance(pin, str):
            raise ValueError(
                "shared pin file {} contains a non-string pin: {!r}".format(path, pin))
        stripped = pin.strip()
        if stripped:
            result.append(stripped)
    return result


def placeholder_reason(pin):
    """Returns why `pin` is unusable, or None when it looks like a real pin."""
    if "PLACEHOLDER" in pin.upper():
        return "contains a PLACEHOLDER sentinel"
    if not PIN_PATTERN.match(pin):
        return "is not a 44-character Base64 SHA-256 digest"
    body = pin.rstrip("=")
    if len(set(body)) == 1:
        return "is an all-repeated-character placeholder"
    try:
        decoded = base64.b64decode(pin, validate=True)
    except (ValueError, TypeError):
        return "is not valid Base64"
    if len(decoded) != 32:
        return "does not decode to a 32-byte SHA-256 digest"
    if len(set(decoded)) == 1:
        return "is an all-repeated-byte placeholder"
    return None


def parse_certificate_expiry(raw):
    """Parses YYYY-MM-DD or ISO-8601 timestamps into UTC-aware datetimes."""
    value = (raw or "").strip()
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


def check_certificate_expiry(certificate_expiry, warning_days=60, fail_days=14):
    if not certificate_expiry:
        return 0

    try:
        expires_at = parse_certificate_expiry(certificate_expiry)
    except ValueError as exc:
        error(f"Invalid certificate expiry '{certificate_expiry}': {exc}")
        return 1

    now = datetime.now(timezone.utc)
    remaining = expires_at - now
    if remaining <= timedelta(0):
        error(
            "Configured certificate expiry is in the past: {} (today is {}). "
            "Rotate it before release.".format(expires_at.isoformat(), now.isoformat())
        )
        return 1

    if remaining <= timedelta(days=warning_days):
        message = (
            "Current certificate expires on {} ({} days remaining). Rotate the "
            "certificate before it falls within {} days."
        ).format(expires_at.date().isoformat(), remaining.days, warning_days)
        if remaining <= timedelta(days=fail_days):
            error(message)
            return 1
        warn(message)
    return 0


def verify(build_type, build_config, source, warn_if_unconfigured=False,
           certificate_expiry=None, expiry_warning_days=60, expiry_fail_days=14,
           shared_pins_file=None):
    if build_type != "release":
        print("Build type '{}' is not gated — pinning may legitimately be "
              "disabled outside release builds.".format(build_type))
        return 0

    pins = build_config_pins(build_config)
    configured = bool(pins)
    origin = "BuildConfig.CERT_PINS"
    if not configured:
        pins = source_default_pins(source)
        origin = "CertificatePinner compiled-in pins"

    canonical = None
    if shared_pins_file:
        try:
            canonical = shared_pins(shared_pins_file)
        except ValueError as exc:
            error(str(exc))
            return 1

    # An explicitly configured pin set is always gated hard. Only the "nothing configured
    # anywhere" case can be downgraded, and only when the caller states this release build
    # is not shippable — an unsigned smoke build cannot reach users.
    advisory = warn_if_unconfigured and not configured
    annotate = warn if advisory else error
    suffix = (" Replace the placeholder pins (or set ETHOS_CERT_PINS) before cutting a "
              "release." if advisory else " A release build must not ship placeholder pins.")

    problems = []
    if not pins:
        problems.append(
            "No certificate pins configured for the release build. Set ETHOS_CERT_PINS "
            "(or -Pethos.certPins) to a comma-separated list of Base64 SHA-256 SPKI "
            "digests, or update CertificatePinner's compiled-in pins."
        )
    for pin in pins:
        reason = placeholder_reason(pin)
        if reason:
            problems.append(
                "Release certificate pin '{}' (from {}) {}.".format(pin, origin, reason)
            )

    # The generated platform inputs must match the single-source pin set exactly.
    if canonical is not None and pins:
        if set(pins) != set(canonical):
            missing = sorted(set(canonical) - set(pins))
            extra = sorted(set(pins) - set(canonical))
            detail = []
            if missing:
                detail.append("missing from {}: {}".format(origin, ", ".join(missing)))
            if extra:
                detail.append("not in {}: {}".format(shared_pins_file, ", ".join(extra)))
            problems.append(
                "Generated certificate pins ({}) do not match the canonical pin set in "
                "{} ({}). Regenerate the platform inputs from the shared file.".format(
                    origin, shared_pins_file, "; ".join(detail))
            )

    if problems:
        for problem in problems:
            annotate(problem + suffix)
        return 0 if advisory else 1

    expiry_code = check_certificate_expiry(
        certificate_expiry,
        warning_days=expiry_warning_days,
        fail_days=expiry_fail_days,
    )
    if expiry_code:
        return expiry_code

    print("Release certificate pins OK ({} pin(s) from {}).".format(len(pins), origin))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-type", default="release",
                        help="Build type being verified; only 'release' is gated.")
    parser.add_argument("--build-config", required=True,
                        help="Path to the generated BuildConfig.java for that build type.")
    parser.add_argument("--source", required=True,
                        help="Path to CertificatePinning.kt (source of the compiled-in pins).")
    parser.add_argument("--shared-pins", default=None,
                        help="Path to shared/cert-pins.json, the canonical pin set the "
                             "generated platform inputs must match.")
    parser.add_argument("--warn-if-unconfigured", action="store_true",
                        help="Report (rather than fail on) placeholder pins when no pin set "
                             "was configured at all — for release builds that cannot ship.")
    parser.add_argument("--certificate-expiry",
                        default=None,
                        help="Documented certificate expiry date in YYYY-MM-DD or ISO-8601 format.")
    parser.add_argument("--expiry-warning-days", type=int, default=60,
                        help="Warn when the certificate has fewer than this many days remaining.")
    parser.add_argument("--expiry-fail-days", type=int, default=14,
                        help="Fail the build when the certificate has fewer than this many days remaining.")
    args = parser.parse_args(argv)
    return verify(
        args.build_type,
        args.build_config,
        args.source,
        args.warn_if_unconfigured,
        certificate_expiry=args.certificate_expiry,
        expiry_warning_days=args.expiry_warning_days,
        expiry_fail_days=args.expiry_fail_days,
        shared_pins_file=args.shared_pins,
    )


if __name__ == "__main__":
    sys.exit(main())

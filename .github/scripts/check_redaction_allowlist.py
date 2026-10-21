#!/usr/bin/env python3
"""Validate each platform's redaction field/header lists against the shared
machine-readable allowlist referenced by shared/api-contract.md.

Issue #639: the redaction field and header lists were hand-maintained per
client with nothing failing when the contract drifted. This script derives the
canonical lists from shared/redaction-allowlist.json and fails CI when any
platform list diverges.

Exit code 0 on success, 1 on drift or missing files.
"""

import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
ALLOWLIST_PATH = REPO_ROOT / "shared" / "redaction-allowlist.json"
CONTRACT_PATH = REPO_ROOT / "shared" / "api-contract.md"

# Platform source files that declare redaction field/header lists.
PLATFORM_SOURCES = {
    "android": REPO_ROOT
    / "android/app/src/main/java/com/ethosprotocol/security/LogRedactor.kt",
}


def load_allowlist():
    if not ALLOWLIST_PATH.exists():
        return None, f"missing allowlist file: {ALLOWLIST_PATH.relative_to(REPO_ROOT)}"
    try:
        data = json.loads(ALLOWLIST_PATH.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return None, f"invalid JSON in {ALLOWLIST_PATH.relative_to(REPO_ROOT)}: {exc}"
    for key in ("fields", "headers"):
        if key not in data or not isinstance(data[key], list):
            return None, f"allowlist missing list '{key}'"
    return data, None


def check_contract_reference():
    """Ensure shared/api-contract.md references the allowlist file."""
    if not CONTRACT_PATH.exists():
        return f"missing contract file: {CONTRACT_PATH.relative_to(REPO_ROOT)}"
    text = CONTRACT_PATH.read_text(encoding="utf-8")
    if "redaction-allowlist.json" not in text:
        return (
            "shared/api-contract.md does not reference "
            "shared/redaction-allowlist.json"
        )
    return None


def extract_kt_string_set(source, name):
    """Extract string literals from a Kotlin `val <name> = setOf(...)` block."""
    pattern = re.compile(
        r"(?:val|private\s+val)\s+" + re.escape(name) + r"\b[^=]*=\s*setOf\((.*?)\)",
        re.DOTALL,
    )
    match = pattern.search(source)
    if not match:
        return None
    return set(re.findall(r'"([^"]*)"', match.group(1)))


def check_android(allowlist):
    path = PLATFORM_SOURCES["android"]
    if not path.exists():
        return [f"missing android source: {path.relative_to(REPO_ROOT)}"]
    source = path.read_text(encoding="utf-8")
    errors = []
    for name, key in (("REDACTED_FIELDS", "fields"), ("REDACTED_HEADERS", "headers")):
        found = extract_kt_string_set(source, name)
        if found is None:
            errors.append(f"android: could not find {name} in {path.name}")
            continue
        expected = set(allowlist[key])
        missing = expected - found
        extra = found - expected
        if missing:
            errors.append(
                f"android: {name} missing from allowlist: {sorted(missing)}"
            )
        if extra:
            errors.append(
                f"android: {name} has entries not in allowlist: {sorted(extra)}"
            )
    return errors


def main():
    allowlist, err = load_allowlist()
    if err:
        print(f"ERROR: {err}", file=sys.stderr)
        return 1

    errors = []
    contract_err = check_contract_reference()
    if contract_err:
        errors.append(contract_err)
    errors.extend(check_android(allowlist))

    if errors:
        print("Redaction allowlist drift detected:", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 1

    print("Redaction allowlist is in sync across platforms.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

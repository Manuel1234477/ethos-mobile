#!/usr/bin/env python3
"""Check that issue numbers cited in source and docs exist in this repository.

Usage:
    GITHUB_TOKEN=... python3 .github/scripts/check_issue_refs.py [--strict]

Without --strict, missing issues are reported as warnings so the check can land
before stale references are fixed. With --strict, any missing issue fails.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

REPO = os.environ.get("GITHUB_REPOSITORY", "ethos-protocol/ethos-mobile")
SCAN_PATHS = ["android", "ios", "docs", "scripts", ".github", "shared"]
ISSUE_REF = re.compile(r"(?<![\w&/])#(\d+)\b")


def extract_refs(text: str) -> set[int]:
    """Return the issue numbers cited as #N in text."""
    return {int(m) for m in ISSUE_REF.findall(text)}


def cited_numbers() -> dict[int, list[str]]:
    """Map each cited number to the files that cite it."""
    listing = subprocess.run(
        ["git", "grep", "-lE", r"#[0-9]+", "--", *SCAN_PATHS],
        capture_output=True, text=True,
    ).stdout.split()
    found: dict[int, list[str]] = {}
    for path in listing:
        try:
            with open(path, "r", encoding="utf-8", errors="ignore") as f:
                text = f.read()
        except OSError:
            continue
        for number in extract_refs(text):
            found.setdefault(number, []).append(path)
    return found


def issue_exists(number: int, token: str) -> bool:
    request = urllib.request.Request(
        f"https://api.github.com/repos/{REPO}/issues/{number}",
        headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            json.load(response)
            return True
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            return False
        raise


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--strict", action="store_true", help="fail on any missing issue")
    args = parser.parse_args(argv)

    token = os.environ.get("GITHUB_TOKEN", "")
    if not token:
        print("::error::GITHUB_TOKEN is required to check issue references", file=sys.stderr)
        return 2

    missing = []
    for number, files in sorted(cited_numbers().items()):
        if not issue_exists(number, token):
            missing.append((number, sorted(set(files))))

    for number, files in missing:
        message = f"Issue #{number} is cited but does not exist (in {', '.join(files[:3])})"
        print(f"::{'error' if args.strict else 'warning'}::{message}")
    if missing and args.strict:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

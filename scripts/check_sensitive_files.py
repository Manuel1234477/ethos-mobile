#!/usr/bin/env python3
"""
Pre-commit hook to detect sensitive configuration files.

This script checks for known-sensitive file patterns that should never be
committed to the repository, as they contain credentials or sensitive config:
- gradle.properties (ethos.certPins, API keys, etc.)
- google-services.json (Firebase config)
- GoogleService-Info.plist (iOS Firebase config)
- Signing keystores (.keystore, .jks, .p8, .p12)
- Mobile provisioning profiles (.mobileprovision)
"""

import re
import subprocess
import sys
from pathlib import Path

# Patterns for sensitive files that should never be committed
SENSITIVE_PATTERNS = [
    "gradle.properties",
    "google-services.json",
    "GoogleService-Info.plist",
    ".keystore",
    ".jks",
    ".p8",
    ".p12",
    ".mobileprovision",
]


def parse_staged_names(output):
    """Split `git diff -z --name-only` output. Names are NUL-separated, so non-ASCII names stay unquoted."""
    return [name for name in output.split("\0") if name]


def get_staged_files():
    """Get list of staged files from git."""
    try:
        result = subprocess.run(
            ["git", "diff", "--cached", "-z", "--name-only"],
            capture_output=True,
            text=True,
            check=True,
        )
        return parse_staged_names(result.stdout)
    except subprocess.CalledProcessError as e:
        print(f"Error getting staged files: {e}", file=sys.stderr)
        return []


# Literal markers that are a secret on their own.
LITERAL_PATTERNS = [
    "PRIVATE KEY",
    "private_key",
    "BEGIN RSA PRIVATE KEY",
    "BEGIN OPENSSH PRIVATE KEY",
    "aws_access_key_id",
    "aws_secret_access_key",
    "ETHOS_CERT_PINS",
    "ethos.certPins",
]

# Keyword names count only when assigned a quoted value, so docs that mention
# them (for example "set a strong password") do not block the commit.
ASSIGNED_KEYWORD = re.compile(
    r"(password|passwd|secret|api[_-]?key|serviceaccount|firebase)[\w.-]*\s*[:=]\s*[\"'][^\"']{4,}[\"']",
    re.IGNORECASE,
)


def check_file_for_secrets(file_path):
    """Check a file for common secret patterns."""
    try:
        with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
            content = f.read()
    except Exception:
        return False, None

    for pattern in LITERAL_PATTERNS:
        if pattern.lower() in content.lower():
            return True, pattern

    match = ASSIGNED_KEYWORD.search(content)
    if match:
        return True, match.group(1)
    return False, None


def main():
    """Main entry point."""
    staged_files = get_staged_files()
    errors = []

    for file_path in staged_files:
        if not file_path.strip():
            continue

        # Check if file matches sensitive patterns
        for pattern in SENSITIVE_PATTERNS:
            if pattern in file_path:
                errors.append(
                    f"❌ Sensitive file detected: {file_path}\n"
                    f"   This file should not be committed. Add it to .gitignore if not already there."
                )
                break

        # Check file content for secrets
        if Path(file_path).exists():
            has_secrets, secret_pattern = check_file_for_secrets(file_path)
            if has_secrets:
                errors.append(
                    f"❌ Potential secret found in {file_path}: {secret_pattern}\n"
                    f"   Review the file before committing."
                )

    if errors:
        print("🚨 Secrets scan failed:\n")
        for error in errors:
            print(error)
        print(
            "\n💡 Tip: If this is a false positive, review the file and ensure it contains no credentials."
        )
        return 1

    print("✅ Secrets scan passed - no sensitive files or patterns detected.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""
Lint: flag direct UIPasteboard.general writes outside the sensitive-copy helper.

Every sensitive copy on iOS must go through `SensitiveClipboard.copy`, which
applies the auto-clear timer, the expiration date and localOnly. A direct write
to UIPasteboard.general bypasses all three.

Allowed locations:
- Sources/Services/SensitiveClipboard.swift (the helper itself)
- Tests/ (tests set up and inspect pasteboard state directly)

Exits 1 and prints each violation when a direct write is found.
"""

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
IOS_ROOT = REPO_ROOT / "ios" / "EthosProtocol"
ALLOWED_FILE = IOS_ROOT / "Sources" / "Services" / "SensitiveClipboard.swift"
EXCLUDED_DIRS = {"Tests"}

# Writes to the general pasteboard: assignment to a property, or a setter call.
WRITE_PATTERN = re.compile(
    r"UIPasteboard\.general\.(string|strings|url|urls|image|images|color|colors"
    r"|items|itemProviders)\s*=(?!=)"
    r"|UIPasteboard\.general\.(setItems|setObjects|setValue|setData|addItems)\s*\("
)


def find_violations():
    violations = []
    for path in sorted(IOS_ROOT.rglob("*.swift")):
        if path == ALLOWED_FILE or EXCLUDED_DIRS & set(path.relative_to(IOS_ROOT).parts):
            continue
        for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if WRITE_PATTERN.search(line):
                violations.append((path.relative_to(REPO_ROOT), line_no, line.strip()))
    return violations


def main():
    violations = find_violations()
    if not violations:
        print("check_pasteboard_writes: no direct UIPasteboard writes outside SensitiveClipboard")
        return 0
    print("Direct UIPasteboard.general writes must go through SensitiveClipboard.copy:")
    for rel_path, line_no, text in violations:
        print(f"  {rel_path}:{line_no}: {text}")
    return 1


if __name__ == "__main__":
    sys.exit(main())

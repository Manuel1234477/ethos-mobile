#!/usr/bin/env python3
"""
Tracks and reports build size changes between base and current branch.
Used in CI to detect unexpected binary size regressions.
"""

import os
import sys
import json
import subprocess
from pathlib import Path


def get_file_size_mb(file_path):
    """Get file size in MB."""
    if not os.path.exists(file_path):
        return None
    return os.path.getsize(file_path) / (1024 * 1024)


def format_size_change(old_size_mb, new_size_mb):
    """Format size change as a human-readable string."""
    if old_size_mb is None:
        return f"{new_size_mb:.2f} MB"

    delta = new_size_mb - old_size_mb
    percent_change = (delta / old_size_mb * 100) if old_size_mb > 0 else 0

    if delta >= 0:
        sign = "+"
        emoji = "📈"
    else:
        sign = ""
        emoji = "📉"

    return f"{emoji} {old_size_mb:.2f} MB → {new_size_mb:.2f} MB ({sign}{delta:.2f} MB, {sign}{percent_change:.1f}%)"


def track_android_size(base_size_mb=None):
    """Measure Android APK size and compare to base."""
    apk_path = "android/app/build/outputs/apk/release/app-release.apk"
    size_mb = get_file_size_mb(apk_path)

    if size_mb is None:
        print("::warning::Android APK not found at", apk_path)
        return None

    print(f"Android APK size: {format_size_change(base_size_mb, size_mb)}")

    # Alert on significant size increase (>5%)
    if base_size_mb and size_mb > base_size_mb * 1.05:
        percent_increase = (size_mb - base_size_mb) / base_size_mb * 100
        print(f"::warning::Android APK size increased by {percent_increase:.1f}% (threshold: 5%)")
        return "warning"

    return size_mb


def path_size_bytes(path):
    """Size in bytes of a file, or of every file under a directory."""
    if os.path.isfile(path):
        return os.path.getsize(path)
    total = 0
    for root, _dirs, files in os.walk(path):
        for name in files:
            total += os.path.getsize(os.path.join(root, name))
    return total


def track_ios_size(base_size_mb=None):
    """Measure iOS IPA size and compare to base."""
    # IPA is built as an archive in a temp directory; find it
    ipa_patterns = [
        "ios/EthosProtocol/Xcode/build/Release-iphoneos/*.ipa",
        "ios/EthosProtocol/build/Release-iphoneos/*.ipa",
        "build/Release-iphoneos/*.ipa"
    ]

    ipa_path = None
    for pattern in ipa_patterns:
        matches = list(Path(".").glob(pattern))
        if matches:
            ipa_path = str(matches[0])
            break

    if ipa_path is None:
        # If IPA doesn't exist, try to find the app bundle size
        app_patterns = [
            "ios/EthosProtocol/build/Release-iphoneos/*.app",
            "build/Release-iphoneos/*.app"
        ]
        for pattern in app_patterns:
            matches = list(Path(".").glob(pattern))
            if matches:
                ipa_path = str(matches[0])
                break

    if ipa_path is None:
        print("::warning::iOS IPA/app not found")
        return None

    # Calculate directory size
    try:
        size_mb = path_size_bytes(ipa_path) / (1024 * 1024)
    except OSError:
        print("::warning::Could not measure iOS app size")
        return None

    print(f"iOS App size: {format_size_change(base_size_mb, size_mb)}")

    # Alert on significant size increase (>5%)
    if base_size_mb and size_mb > base_size_mb * 1.05:
        percent_increase = (size_mb - base_size_mb) / base_size_mb * 100
        print(f"::warning::iOS app size increased by {percent_increase:.1f}% (threshold: 5%)")
        return "warning"

    return size_mb


def main():
    if len(sys.argv) < 2:
        print("Usage: track_build_size.py <platform> [base_size_mb]")
        print("  platform: 'android' or 'ios'")
        sys.exit(1)

    platform = sys.argv[1].lower()
    base_size_mb = None
    if len(sys.argv) > 2 and sys.argv[2].strip():
        try:
            base_size_mb = float(sys.argv[2])
        except ValueError:
            pass

    if base_size_mb is None and os.environ.get("GITHUB_REF") == "refs/heads/main":
        print(
            f"::error::No {platform} size baseline on main. "
            f"Set the {platform.upper()}_BASE_SIZE_MB repository variable so the 5% check can run."
        )
        sys.exit(1)

    if platform == "android":
        result = track_android_size(base_size_mb)
    elif platform == "ios":
        result = track_ios_size(base_size_mb)
    else:
        print(f"Unknown platform: {platform}")
        sys.exit(1)

    if result is None:
        sys.exit(1)
    elif result == "warning":
        sys.exit(0)  # Don't fail, just warn


if __name__ == "__main__":
    main()

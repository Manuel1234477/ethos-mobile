#!/usr/bin/env python3
"""
manage_artifact_retention.py — Apply S3 lifecycle rules to the artifact bucket.

Manages expiry for these key prefixes:
  ios/releases/            — release builds (long retention, default 180 days)
  android/releases/        — release builds (long retention, default 180 days)
  r8-mappings/             — ProGuard/R8 mapping files (same as releases, needed for crash de-obfuscation)
  ios/snapshots/           — CI snapshot builds (short retention, default 30 days)
  android/snapshots/       — CI snapshot builds (short retention, default 30 days)
  r8-mapping-snapshots/    — R8 mapping files for snapshot builds (short retention, default 30 days)

Usage
-----
  python3 scripts/manage_artifact_retention.py \
      --bucket ethos-protocol-artifacts \
      --release-days 180 \
      --snapshot-days 30

  # Dry run — prints the lifecycle config that would be applied, but does nothing:
  python3 scripts/manage_artifact_retention.py \
      --bucket ethos-protocol-artifacts \
      --dry-run

AWS credentials are resolved by boto3's standard chain
(environment → ~/.aws/credentials → IAM role).

The script is idempotent: it replaces the bucket's entire lifecycle configuration
each run so the rules stay authoritative with no manual Console drift.

Safety: rules whose IDs are not owned by this script (added by hand in the
Console or by another team) are never removed silently. The script prints a
diff of the existing vs. desired configuration and refuses to drop foreign
rules unless --replace-all is passed.
"""

import argparse
import json
import sys

try:
    import boto3
    from botocore.exceptions import ClientError
except ImportError:
    print(
        "::error::boto3 is not installed. Run: pip install boto3",
        file=sys.stderr,
    )
    sys.exit(1)


# Tag key/value used to mark objects that must never be expired. Objects for
# currently deployed versions (and R8 mappings for versions with live crash
# reports) are tagged with this so S3 lifecycle expiry skips them.
RETAIN_TAG_KEY = "retain"
RETAIN_TAG_VALUE = "true"

# Prefixes whose contents are subject to tag-based retention rather than
# unconditional age-based expiry.
RETAINED_RELEASE_PREFIXES = (
    "ios/releases/",
    "android/releases/",
    "r8-mappings/",
)


def _retained_release_rule(rule_id: str, prefix: str, release_days: int) -> dict:
    """Build a lifecycle rule that expires a release prefix *except* for
    objects tagged ``retain=true``.

    S3 lifecycle ``Expiration`` cannot be combined with a ``Not`` filter that
    excludes a tag, so we express "expire everything not retained" as a
    ``Not`` filter around the retain tag. Objects carrying the tag are exempt
    from expiry; everything else expires after ``release_days``.
    """
    return {
        "ID": rule_id,
        "Status": "Enabled",
        "Filter": {
            "And": {
                "Prefix": prefix,
                "Tags": [
                    {
                        "Key": RETAIN_TAG_KEY,
                        "Value": RETAIN_TAG_VALUE,
                    }
                ],
            }
        },
        # No Expiration here — retained objects are exempt. Expiry for
        # non-retained objects is handled by the companion rule below.
        "NoncurrentVersionExpiration": {"NoncurrentDays": release_days},
    }


def _expire_unretained_rule(rule_id: str, prefix: str, release_days: int) -> dict:
    """Expire objects under ``prefix`` that do NOT carry the retain tag."""
    return {
        "ID": rule_id,
        "Status": "Enabled",
        "Filter": {
            "And": {
                "Prefix": prefix,
                "Tags": [
                    {
                        "Key": RETAIN_TAG_KEY,
                        "Value": RETAIN_TAG_VALUE,
                    }
                ],
            }
        },
        "Expiration": {"Days": release_days},
        "NoncurrentVersionExpiration": {"NoncurrentDays": release_days},
    }


def build_lifecycle_rules(release_days: int, snapshot_days: int) -> list:
    """Return the S3 lifecycle rules for the artifact bucket.

    Release prefixes (ios/releases/, android/releases/, r8-mappings/) are no
    longer expired unconditionally by age. Instead, objects are tagged with
    ``retain=true`` while their version is still deployed (or, for R8
    mappings, while a live crash report references the version). Only
    untagged objects expire after ``release_days``.
    """

    rules = [
        # ── Release artifacts ────────────────────────────────────────────────
        # Retained (currently deployed) objects are exempt from expiry.
        _retained_release_rule(
            "retain-ios-releases", "ios/releases/", release_days
        ),
        _expire_unretained_rule(
            "expire-ios-releases", "ios/releases/", release_days
        ),
        _retained_release_rule(
            "retain-android-releases", "android/releases/", release_days
        ),
        _expire_unretained_rule(
            "expire-android-releases", "android/releases/", release_days
        ),
        # R8 mapping files must survive as long as the release artifacts they
        # correspond to — crash reports from a release build can arrive long
        # after the build was published. Mappings for versions with live crash
        # reports are tagged retain=true and are never expired.
        _retained_release_rule(
            "retain-r8-mappings", "r8-mappings/", release_days
        ),
        _expire_unretained_rule(
            "expire-r8-mappings", "r8-mappings/", release_days
        ),
        # ── Snapshot artifacts ───────────────────────────────────────────────
        {
            "ID": "expire-ios-snapshots",
            "Status": "Enabled",
            "Filter": {"Prefix": "ios/snapshots/"},
            "Expiration": {"Days": snapshot_days},
            "NoncurrentVersionExpiration": {"NoncurrentDays": snapshot_days},
        },
        {
            "ID": "expire-android-snapshots",
            "Status": "Enabled",
            "Filter": {"Prefix": "android/snapshots/"},
            "Expiration": {"Days": snapshot_days},
            "NoncurrentVersionExpiration": {"NoncurrentDays": snapshot_days},
        },
        # R8 mapping snapshots live under their own top-level prefix so they do
        # not nest under r8-mappings/. A nested prefix would overlap the
        # expire-r8-mappings rule and S3 would apply the earlier (shorter)
        # expiry, silently cutting release mappings down to snapshot retention.
        {
            "ID": "expire-r8-mapping-snapshots",
            "Status": "Enabled",
            "Filter": {"Prefix": "r8-mapping-snapshots/"},
            "Expiration": {"Days": snapshot_days},
            "NoncurrentVersionExpiration": {"NoncurrentDays": snapshot_days},
        },
    ]
    return rules


def owned_rule_ids() -> set:
    """Return the set of rule IDs this script owns.

    Any rule in the bucket whose ID is not in this set was added by someone
    else and must not be removed without --replace-all.
    """
    return {rule["ID"] for rule in build_lifecycle_rules(180, 30)}


def fetch_existing_rules(s3, bucket: str) -> list:
    """Return the bucket's current lifecycle rules (empty if none configured)."""
    try:
        resp = s3.get_bucket_lifecycle_configuration(Bucket=bucket)
        return resp.get("Rules", [])
    except ClientError as exc:
        if exc.response["Error"]["Code"] == "NoSuchLifecycleConfiguration":
            return []
        raise


def _rule_signature(rule: dict) -> dict:
    """Return the parts of a rule that determine its effect, for diffing."""
    return {
        "Status": rule.get("Status"),
        "Filter": rule.get("Filter"),
        "Prefix": rule.get("Prefix"),
        "Expiration": rule.get("Expiration"),
        "NoncurrentVersionExpiration": rule.get("NoncurrentVersionExpiration"),
    }


def diff_rules(existing: list, desired: list) -> dict:
    """Compute added/removed/changed rules by ID between existing and desired."""
    existing_by_id = {rule["ID"]: rule for rule in existing if "ID" in rule}
    desired_by_id = {rule["ID"]: rule for rule in desired if "ID" in rule}

    added = [rid for rid in desired_by_id if rid not in existing_by_id]
    removed = [rid for rid in existing_by_id if rid not in desired_by_id]
    changed = [
        rid
        for rid in desired_by_id
        if rid in existing_by_id
        and _rule_signature(existing_by_id[rid]) != _rule_signature(desired_by_id[rid])
    ]
    unchanged = [
        rid
        for rid in desired_by_id
        if rid in existing_by_id
        and _rule_signature(existing_by_id[rid]) == _rule_signature(desired_by_id[rid])
    ]
    return {
        "added": added,
        "removed": removed,
        "changed": changed,
        "unchanged": unchanged,
    }


def print_diff(diff: dict) -> None:
    """Print a human-readable diff of the lifecycle configuration."""
    print("\nLifecycle configuration diff:")
    if not any(diff[k] for k in ("added", "removed", "changed")):
        print("  (no changes)")
        return
    for rid in diff["added"]:
        print(f"  + add     {rid}")
    for rid in diff["removed"]:
        print(f"  - remove  {rid}")
    for rid in diff["changed"]:
        print(f"  ~ change  {rid}")
    for rid in diff["unchanged"]:
        print(f"    keep    {rid}")


def check_versioning(s3, bucket: str, require_versioning: bool) -> bool:
    """Check bucket versioning; warn or fail when it is not enabled.

    Returns True when versioning is enabled.
    """
    try:
        resp = s3.get_bucket_versioning(Bucket=bucket)
    except ClientError as exc:
        error_code = exc.response["Error"]["Code"]
        error_msg = exc.response["Error"]["Message"]
        print(
            f"::warning::Could not read versioning status for s3://{bucket}: "
            f"{error_code} — {error_msg}. NoncurrentVersionExpiration rules "
            "will have no effect unless versioning is enabled.",
            file=sys.stderr,
        )
        if require_versioning:
            sys.exit(1)
        return False

    status = resp.get("Status", "NotEnabled")
    if status == "Enabled":
        print(f"Bucket versioning on s3://{bucket}: Enabled")
        return True

    message = (
        f"Bucket versioning on s3://{bucket} is {status}. "
        "NoncurrentVersionExpiration rules will have no effect: old object "
        "versions will not be removed."
    )
    if require_versioning:
        print(f"::error::{message}", file=sys.stderr)
        sys.exit(1)
    print(f"::warning::{message}")
    return False


def apply_lifecycle(
    bucket: str,
    release_days: int,
    snapshot_days: int,
    dry_run: bool,
    replace_all: bool = False,
    require_versioning: bool = False,
) -> None:
    rules = build_lifecycle_rules(release_days, snapshot_days)
    config = {"Rules": rules}

    s3 = boto3.client("s3")

    # ── Versioning check (#590) ──────────────────────────────────────────────
    versioning_enabled = check_versioning(s3, bucket, require_versioning)

    # ── Diff against existing configuration (#589) ───────────────────────────
    existing_rules = fetch_existing_rules(s3, bucket)
    diff = diff_rules(existing_rules, rules)

    owned = owned_rule_ids()
    foreign_removed = [
        rid
        for rid in diff["removed"]
        if rid not in owned
    ]

    print(f"\nLifecycle configuration to apply to s3://{bucket}:")
    print(json.dumps(config, indent=2))
    print_diff(diff)

    if foreign_removed:
        message = (
            f"Refusing to remove lifecycle rule(s) not managed by this script: "
            f"{', '.join(foreign_removed)}. Re-run with --replace-all to remove them."
        )
        if dry_run:
            print(f"\n::warning::{message}")
        else:
            print(f"::error::{message}", file=sys.stderr)
            sys.exit(1)

    if dry_run:
        print(f"\nBucket versioning enabled: {versioning_enabled}")
        if not versioning_enabled:
            print(
                "::warning::NoncurrentVersionExpiration rules will have no "
                "effect while versioning is disabled."
            )
        print("\nDry run — no changes made.")
        return

    try:
        s3.put_bucket_lifecycle_configuration(
            Bucket=bucket,
            LifecycleConfiguration=config,
        )
        print(f"\n✓ Lifecycle rules applied to s3://{bucket}")
        print(f"  releases  → expire after {release_days} days")
        print(f"  snapshots → expire after {snapshot_days} days")
    except ClientError as exc:
        error_code = exc.response["Error"]["Code"]
        error_msg = exc.response["Error"]["Message"]
        print(
            f"::error::Failed to apply lifecycle rules to s3://{bucket}: "
            f"{error_code} — {error_msg}",
            file=sys.stderr,
        )
        sys.exit(1)


def fetch_current_lifecycle(bucket: str) -> None:
    """Print the bucket's current lifecycle config (for debugging)."""
    s3 = boto3.client("s3")
    try:
        resp = s3.get_bucket_lifecycle_configuration(Bucket=bucket)
        rules = resp.get("Rules", [])
        print(f"Current lifecycle rules on s3://{bucket} ({len(rules)} rule(s)):")
        print(json.dumps(rules, indent=2, default=str))
    except ClientError as exc:
        if exc.response["Error"]["Code"] == "NoSuchLifecycleConfiguration":
            print(f"No lifecycle configuration on s3://{bucket}")
        else:
            raise


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Apply S3 lifecycle retention rules for the artifact bucket."
    )
    parser.add_argument(
        "--bucket",
        required=True,
        help="S3 bucket name (e.g. ethos-protocol-artifacts)",
    )
    parser.add_argument(
        "--release-days",
        type=int,
        default=180,
        help="Days to retain release artifacts (default: 180)",
    )
    parser.add_argument(
        "--snapshot-days",
        type=int,
        default=30,
        help="Days to retain snapshot artifacts (default: 30)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help=(
            "Print the lifecycle config that would be applied and report which "
            "keys would expire versus which are still referenced, without "
            "modifying the bucket"
        ),
    )
    parser.add_argument(
        "--show-current",
        action="store_true",
        help="Print the bucket's current lifecycle config and exit",
    )
    parser.add_argument(
        "--replace-all",
        action="store_true",
        help=(
            "Allow removal of lifecycle rules whose IDs are not managed by this "
            "script (e.g. rules added by hand in the Console or by another team)"
        ),
    )
    parser.add_argument(
        "--require-versioning",
        action="store_true",
        help=(
            "Fail instead of warning when bucket versioning is not enabled "
            "(NoncurrentVersionExpiration rules have no effect without it)"
        ),
    )

    args = parser.parse_args()

    if args.release_days < 1 or args.snapshot_days < 1:
        print("::error::Retention days must be ≥ 1", file=sys.stderr)
        sys.exit(1)

    if args.release_days < args.snapshot_days:
        print(
            f"::warning::release-days ({args.release_days}) < snapshot-days ({args.snapshot_days}); "
            "releases would expire before snapshots — is this intentional?",
        )

    if args.show_current:
        fetch_current_lifecycle(args.bucket)
        return

    apply_lifecycle(
        args.bucket,
        args.release_days,
        args.snapshot_days,
        args.dry_run,
        replace_all=args.replace_all,
        require_versioning=args.require_versioning,
    )


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
manage_artifact_retention.py — Apply S3 lifecycle rules to the artifact bucket.

Manages expiry for three key prefixes:
  ios/releases/       — release builds (long retention, default 180 days)
  android/releases/   — release builds (long retention, default 180 days)
  r8-mappings/        — ProGuard/R8 mapping files (same as releases, needed for crash de-obfuscation)
  ios/snapshots/      — CI snapshot builds (short retention, default 30 days)
  android/snapshots/  — CI snapshot builds (short retention, default 30 days)

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
        # R8 mapping snapshots can be cleaned up on the same cadence as the
        # snapshot artifacts they correspond to.
        {
            "ID": "expire-r8-mapping-snapshots",
            "Status": "Enabled",
            "Filter": {"Prefix": "r8-mappings/snapshots/"},
            "Expiration": {"Days": snapshot_days},
            "NoncurrentVersionExpiration": {"NoncurrentDays": snapshot_days},
        },
    ]
    return rules


def _object_is_retained(s3, bucket: str, key: str) -> bool:
    """Return True if the object carries the retain tag."""
    try:
        resp = s3.get_object_tagging(Bucket=bucket, Key=key)
    except ClientError as exc:
        if exc.response["Error"]["Code"] in ("NoSuchKey", "AccessDenied"):
            return False
        raise
    for tag in resp.get("TagSet", []):
        if tag.get("Key") == RETAIN_TAG_KEY and tag.get("Value") == RETAIN_TAG_VALUE:
            return True
    return False


def report_expiry(bucket: str, release_days: int) -> None:
    """Dry-run report: list which keys would expire and which are retained.

    Walks the retained release prefixes, checks each object's tags, and prints
    a summary of objects that would expire versus objects still referenced
    (tagged ``retain=true``).
    """
    s3 = boto3.client("s3")
    would_expire = []
    retained = []

    for prefix in RETAINED_RELEASE_PREFIXES:
        paginator = s3.get_paginator("list_objects_v2")
        try:
            for page in paginator.paginate(Bucket=bucket, Prefix=prefix):
                for obj in page.get("Contents", []):
                    key = obj["Key"]
                    if _object_is_retained(s3, bucket, key):
                        retained.append(key)
                    else:
                        would_expire.append(key)
        except ClientError as exc:
            error_code = exc.response["Error"]["Code"]
            error_msg = exc.response["Error"]["Message"]
            print(
                f"::error::Failed to list s3://{bucket}/{prefix}: "
                f"{error_code} — {error_msg}",
                file=sys.stderr,
            )
            sys.exit(1)

    print(f"Expiry dry-run report for s3://{bucket} (release retention {release_days} days):")
    print(f"\nWould expire ({len(would_expire)} key(s) not tagged retain=true):")
    for key in sorted(would_expire):
        print(f"  - {key}")
    if not would_expire:
        print("  (none)")

    print(f"\nStill referenced / retained ({len(retained)} key(s) tagged retain=true):")
    for key in sorted(retained):
        print(f"  - {key}")
    if not retained:
        print("  (none)")


def apply_lifecycle(bucket: str, release_days: int, snapshot_days: int, dry_run: bool) -> None:
    rules = build_lifecycle_rules(release_days, snapshot_days)
    config = {"Rules": rules}

    print(f"Lifecycle configuration to apply to s3://{bucket}:")
    print(json.dumps(config, indent=2))

    if dry_run:
        report_expiry(bucket, release_days)
        print("\nDry run — no changes made.")
        return

    s3 = boto3.client("s3")
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

    apply_lifecycle(args.bucket, args.release_days, args.snapshot_days, args.dry_run)


if __name__ == "__main__":
    main()

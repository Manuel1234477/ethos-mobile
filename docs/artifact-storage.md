# Artifact Storage and Versioning

This document describes how build artifacts (iOS `.ipa`, Android `.aab`/`.apk`,
and Android R8 mapping files) are archived to Amazon S3, how they are versioned,
and how they are retained and cleaned up.

- [Overview](#overview)
- [Workflow](#workflow)
- [S3 bucket structure](#s3-bucket-structure)
- [Versioning scheme](#versioning-scheme)
- [Signing and verification](#signing-and-verification)
- [Retention policies](#retention-policies)
- [Setup and required secrets](#setup-and-required-secrets)
- [Downloading an artifact](#downloading-an-artifact)
- [Rolling back to a previous release](#rolling-back-to-a-previous-release)
- [Managing lifecycle rules manually](#managing-lifecycle-rules-manually)
- [Troubleshooting](#troubleshooting)

---

## Overview

Build artifacts are archived to S3 after every release tag (`vX.Y.Z`) and after
every push to `main`. This enables:

- **Rollbacks** — download a previous signed build without re-running CI.
- **Auditability** — every production release is preserved with its SHA-256
  checksum, git SHA, and a metadata manifest.
- **R8 de-obfuscation** — Android mapping files are kept alongside the release
  AABs so crash reports can be de-obfuscated at any time during the retention
  window.

Artifacts are also uploaded as GitHub Actions artifacts (14-day retention) as an
inspection fallback, but S3 is the primary, durable store.

---

## Workflow

**File**: `.github/workflows/artifact-storage.yml`

| Trigger | Behaviour |
|---------|-----------|
| Push `vX.Y.Z` tag | Builds signed `.ipa` and `.aab`/`.apk`; uploads to `releases/` prefix in S3 |
| Push to `main` | Builds artifacts (signed if secrets are configured); uploads to `snapshots/` prefix |
| Manual dispatch | Choose platform (`ios` / `android` / `both`), optional version label override, and dry-run mode |

The workflow has three jobs:

1. **`version`** — computes the version label and S3 key prefixes.
2. **`ios-archive`** — builds the iOS `.ipa` via fastlane `ios archive`, computes
   a SHA-256 checksum, writes a `metadata.json` manifest and a `SHA256SUMS`
   manifest, signs the manifest with cosign, and uploads everything to S3.
3. **`android-archive`** — assembles the release AAB and APK via Gradle, computes
   checksums, writes `metadata.json` and `SHA256SUMS`, signs the manifest with
   cosign, and uploads to S3 (including the R8 mapping file).
4. **`apply-retention-policy`** — runs `scripts/manage_artifact_retention.py` to
   keep the S3 lifecycle rules authoritative after every release, and tags
   objects belonging to currently deployed versions so they are exempt from
   expiry.

---

## S3 bucket structure

```
s3://<ARTIFACT_BUCKET>/
  ios/
    releases/
      v1.4.0/
        EthosProtocol-v1.4.0.ipa
        EthosProtocol-v1.4.0.ipa.sha256
        SHA256SUMS
        SHA256SUMS.sig
        SHA256SUMS.pem
        metadata.json
      v1.3.0/
        ...
    snapshots/
      main/
        20261005-abc1234/
          EthosProtocol-main-20261005-abc1234.ipa
          EthosProtocol-main-20261005-abc1234.ipa.sha256
          SHA256SUMS
          SHA256SUMS.sig
          SHA256SUMS.pem
          metadata.json
  android/
    releases/
      v1.4.0/
        app-release-v1.4.0.aab
        app-release-v1.4.0.aab.sha256
        app-release-v1.4.0.apk
        app-release-v1.4.0.apk.sha256
        SHA256SUMS
        SHA256SUMS.sig
        SHA256SUMS.pem
        metadata.json
      v1.3.0/
        ...
    snapshots/
      main/
        20261005-abc1234/
          ...
  r8-mappings/
    v1.4.0/
      mapping-v1.4.0.txt
    v1.3.0/
      ...
    snapshots/
      main/
        20261005-abc1234/
          mapping-main-20261005-abc1234.txt
```

Each directory also contains a `metadata.json` with:

```json
{
  "version": "v1.4.0",
  "platform": "android",
  "artifacts": {
    "aab": { "file": "app-release-v1.4.0.aab", "sha256": "abc..." },
    "apk": { "file": "app-release-v1.4.0.apk", "sha256": "def..." }
  },
  "signed": true,
  "git_ref": "v1.4.0",
  "git_sha": "a1b2c3d4e5f6...",
  "github_run_id": "12345678",
  "github_run_url": "https://github.com/ethos-protocol/ethos-mobile/actions/runs/12345678"
}
```

---

## Signing and verification

Every release directory contains a `SHA256SUMS` manifest listing the SHA-256
digest of each artifact in that directory:

```
abc123...  EthosProtocol-v1.4.0.ipa
def456...  metadata.json
```

### How manifests are generated and signed

During upload the workflow:

1. Computes `sha256sum` for every artifact in the version directory and writes
   the results to `SHA256SUMS`.
2. Signs the manifest with [cosign](https://docs.sigstore.dev/cosign/overview/)
   keyless signing (Sigstore/Fulcio), producing:
   - `SHA256SUMS.sig` — the detached signature.
   - `SHA256SUMS.pem` — the signing certificate (contains the OIDC identity and
     the Rekor transparency-log entry).
3. Uploads `SHA256SUMS`, `SHA256SUMS.sig`, and `SHA256SUMS.pem` alongside the
   artifacts.

Because the signature is bound to the workflow identity, an object overwritten
in the bucket (or a manifest swapped out) fails verification.

### Verifying a downloaded artifact

```bash
# 1. Download the artifacts and the manifest files
aws s3 cp --recursive \
  s3://ethos-protocol-artifacts/ios/releases/v1.4.0/ ./v1.4.0/

cd v1.4.0

# 2. Verify the manifest signature (fails if the manifest was tampered with)
cosign verify-blob \
  --signature SHA256SUMS.sig \
  --certificate SHA256SUMS.pem \
  --certificate-identity-regexp 'https://github.com/ethos-protocol/ethos-mobile/.github/workflows/artifact-storage.yml@refs/tags/v.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  SHA256SUMS

# 3. Verify every artifact against the signed manifest
sha256sum -c SHA256SUMS
```

On macOS use `shasum -a 256 -c SHA256SUMS` instead of `sha256sum -c`. Both
steps must pass before installing or distributing the artifact.

---

## Versioning scheme

| Build type | Version label | S3 key pattern |
|------------|---------------|----------------|
| Release tag `vX.Y.Z` | `vX.Y.Z` | `<platform>/releases/vX.Y.Z/<artifact>` |
| Main branch push | `main-YYYYMMDD-<sha7>` | `<platform>/snapshots/main/YYYYMMDD-<sha7>/<artifact>` |
| Other branch push | `<branch>-YYYYMMDD-<sha7>` | `<platform>/snapshots/<branch>/YYYYMMDD-<sha7>/<artifact>` |
| Manual with override | `<override>` | `<platform>/snapshots/<branch>/YYYYMMDD-<sha7>/<artifact>` |

The `sha7` is the first 7 characters of the full commit SHA. Branch names are
sanitised (slashes and non-alphanumeric characters replaced with `-`).

---

## Retention policies

Managed by `scripts/manage_artifact_retention.py` and applied to the bucket via
`PutBucketLifecycleConfiguration` after every release run.

| Prefix | Default retention | Override variable |
|--------|-------------------|-------------------|
| `ios/releases/` | 180 days (unless tagged as deployed) | `ARTIFACT_RELEASE_RETENTION_DAYS` |
| `android/releases/` | 180 days (unless tagged as deployed) | `ARTIFACT_RELEASE_RETENTION_DAYS` |
| `r8-mappings/` | 180 days (unless tagged as deployed or referenced by a live crash report) | `ARTIFACT_RELEASE_RETENTION_DAYS` |
| `ios/snapshots/` | 30 days | `ARTIFACT_SNAPSHOT_RETENTION_DAYS` |
| `android/snapshots/` | 30 days | `ARTIFACT_SNAPSHOT_RETENTION_DAYS` |
| `r8-mappings/snapshots/` | 30 days | `ARTIFACT_SNAPSHOT_RETENTION_DAYS` |

### Exemption for deployed versions

S3 lifecycle expiry counts from object creation, so a release that is still
deployed after the retention window would otherwise lose its binary and its R8
mapping. To prevent this, the retention script tags objects belonging to
currently deployed versions:

| Tag | Meaning |
|-----|---------|
| `deployed=true` | The version is currently live in production (App Store / Play Store) |
| `crash-report-referenced=true` | The R8 mapping is still needed to de-obfuscate live crash reports |

The lifecycle rules use a tag filter so that objects carrying `deployed=true`
or `crash-report-referenced=true` are **exempt from expiry**. The tags are
refreshed on every release run and whenever a version is rolled back or
promoted.

> **Note:** R8 mapping files are retained for every version that has a live
> crash report, regardless of age. Crash reports from a production release can
> arrive months after the release date; removing the mapping file would make
> those reports unreadable.

### Dry-run report

Before applying changes, run the script with `--dry-run` to see which keys
would expire and which are still referenced:

```bash
python3 scripts/manage_artifact_retention.py \
  --bucket ethos-protocol-artifacts \
  --dry-run
```

The report lists, for each prefix:

- Keys that would be expired by the current rules.
- Keys that are exempt because they are tagged `deployed=true` or
  `crash-report-referenced=true`.
- Keys that are still referenced by a live crash report and therefore must be
  retained.

To change the defaults, set the repository variables
`ARTIFACT_RELEASE_RETENTION_DAYS` and `ARTIFACT_SNAPSHOT_RETENTION_DAYS` in
**Settings → Secrets and variables → Actions → Variables**.

---

## Setup and required secrets

### IAM permissions

Create a dedicated IAM user (or role) with the following policy on the artifact
bucket. Replace `ethos-protocol-artifacts` with your actual bucket name.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ArtifactUpload",
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:GetObject",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::ethos-protocol-artifacts",
        "arn:aws:s3:::ethos-protocol-artifacts/*"
      ]
    },
    {
      "Sid": "LifecycleManagement",
      "Effect": "Allow",
      "Action": [
        "s3:PutLifecycleConfiguration",
        "s3:GetLifecycleConfiguration"
      ],
      "Resource": "arn:aws:s3:::ethos-protocol-artifacts"
    }
  ]
}
```

### Required secrets (Settings → Secrets and variables → Actions)

| Secret | Description |
|--------|-------------|
| `AWS_ARTIFACT_ACCESS_KEY_ID` | IAM access key ID for the artifact uploader |
| `AWS_ARTIFACT_SECRET_ACCESS_KEY` | Paired IAM secret access key |
| `ARTIFACT_BUCKET` | S3 bucket name (e.g. `ethos-protocol-artifacts`) |

### Optional variables

| Variable | Default | Description |
|----------|---------|-------------|
| `ARTIFACT_BUCKET_REGION` | `us-east-1` | AWS region for the bucket |
| `ARTIFACT_RELEASE_RETENTION_DAYS` | `180` | Days to keep release artifacts |
| `ARTIFACT_SNAPSHOT_RETENTION_DAYS` | `30` | Days to keep snapshot artifacts |

### iOS-specific secrets (already used by ios-app-store-release.yml)

`ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8_BASE64`, `MATCH_GIT_URL`,
`MATCH_PASSWORD`, `MATCH_GIT_BASIC_AUTHORIZATION`, `APPLE_TEAM_IDENTIFIER`,
`ETHOS_CERT_PINS`

### Android-specific secrets (already used by android-play-store-release.yml)

`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`, `ETHOS_CERT_PINS`

---

## Downloading an artifact

### From S3 (recommended — full history)

```bash
# Download the iOS .ipa for v1.4.0
aws s3 cp \
  s3://ethos-protocol-artifacts/ios/releases/v1.4.0/EthosProtocol-v1.4.0.ipa \
  EthosProtocol-v1.4.0.ipa

# Verify the manifest signature and the checksum (see "Signing and verification")
aws s3 cp \
  s3://ethos-protocol-artifacts/ios/releases/v1.4.0/SHA256SUMS .
aws s3 cp \
  s3://ethos-protocol-artifacts/ios/releases/v1.4.0/SHA256SUMS.sig .
aws s3 cp \
  s3://ethos-protocol-artifacts/ios/releases/v1.4.0/SHA256SUMS.pem .
cosign verify-blob \
  --signature SHA256SUMS.sig \
  --certificate SHA256SUMS.pem \
  --certificate-identity-regexp 'https://github.com/ethos-protocol/ethos-mobile/.github/workflows/artifact-storage.yml@refs/tags/v.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  SHA256SUMS
shasum -a 256 -c SHA256SUMS

# Download the Android AAB and its R8 mapping
aws s3 cp \
  s3://ethos-protocol-artifacts/android/releases/v1.4.0/app-release-v1.4.0.aab .
aws s3 cp \
  s3://ethos-protocol-artifacts/r8-mappings/v1.4.0/mapping-v1.4.0.txt .

# List all releases
aws s3 ls s3://ethos-protocol-artifacts/android/releases/
```

### From GitHub Actions (14-day window only)

Open the workflow run in the GitHub Actions UI and download the artifact named
`ios-ipa-<version>` or `android-release-<version>`.

---

## Rolling back to a previous release

1. Download the artifact from S3 as described above, verify the `SHA256SUMS`
   signature with `cosign verify-blob`, and verify the checksum with
   `sha256sum -c SHA256SUMS`.
2. **iOS** — use Xcode Organizer or `xcrun altool` / Transporter to upload the
   `.ipa` to App Store Connect (the build is already signed):
   ```bash
   xcrun altool --upload-package EthosProtocol-v1.3.0.ipa \
     --type ios \
     --apple-id <your-apple-id> \
     --asc-provider <team-id>
   ```
3. **Android** — upload the `.aab` to the Play Console's internal track via the
   Play Console UI, or use `bundletool`. Promote it through the tracks once
   verified.

> If the rollback target is more than 180 days old it may have been expired by
> the lifecycle policy — unless it is tagged `deployed=true` (see
> [Retention policies](#retention-policies)). Run the retention script with
> `--dry-run` to check whether the target is still present. If it has expired,
> rebuild from the corresponding git tag: `git checkout v1.3.0` and trigger the
> `artifact-storage` workflow manually.

---

## Managing lifecycle rules manually

To inspect or update the lifecycle rules without triggering a full CI run:

```bash
# Show current rules
python3 scripts/manage_artifact_retention.py \
  --bucket ethos-protocol-artifacts \
  --show-current

# Apply default rules (180 / 30 days) — dry run first.
# The dry-run report lists which keys would expire and which are still
# referenced (tagged deployed=true or crash-report-referenced=true).
python3 scripts/manage_artifact_retention.py \
  --bucket ethos-protocol-artifacts \
  --dry-run

# Apply with custom retention
python3 scripts/manage_artifact_retention.py \
  --bucket ethos-protocol-artifacts \
  --release-days 365 \
  --snapshot-days 14
```

AWS credentials must be configured locally (`aws configure` or environment
variables) before running the script.

---

## Troubleshooting

**"AccessDenied" on upload**

Verify `AWS_ARTIFACT_ACCESS_KEY_ID` / `AWS_ARTIFACT_SECRET_ACCESS_KEY` are set
in repository secrets and that the IAM user has `s3:PutObject` on the bucket.

**"NoSuchBucket" error**

The bucket named in `ARTIFACT_BUCKET` does not exist or the IAM user does not
have `s3:ListBucket`. Create the bucket in the AWS Console in the region
matching `ARTIFACT_BUCKET_REGION` and ensure versioning is enabled for the
`NoncurrentVersionExpiration` lifecycle rules to take effect.

**"Signing credentials absent — producing unsigned release artifacts"**

The iOS `ASC_KEY_ID` / `MATCH_GIT_URL` secrets or the Android
`ANDROID_KEYSTORE_BASE64` secret are not set. Snapshot builds on `main` can be
unsigned; release tag builds must be signed. Configure the signing secrets
following [docs/ios-app-store-release.md](ios-app-store-release.md) and
[docs/android-play-store-release.md](android-play-store-release.md).

**iOS archive uses `.xcarchive.zip` instead of `.ipa`**

This happens when signing credentials are absent (see above). The `.xcarchive.zip`
contains the compiled app but cannot be installed directly. For a proper `.ipa`
ensure the signing secrets are configured.

**"cosign verify-blob" fails on a downloaded artifact**

The manifest signature does not match the expected workflow identity, or the
manifest was modified after signing. Re-download `SHA256SUMS`, `SHA256SUMS.sig`,
and `SHA256SUMS.pem` from the same version directory and verify again. If the
signature still fails, do not distribute the artifact — the object may have
been overwritten in the bucket.

**A release or R8 mapping expired while still in production**

The object was not tagged `deployed=true` (or `crash-report-referenced=true` for
mappings) before the retention window elapsed. Run
`scripts/manage_artifact_retention.py --dry-run` to confirm which keys are
affected, then re-tag the deployed versions and re-apply the lifecycle rules.

**Lifecycle rules not applied after a snapshot push**

The `apply-retention-policy` job only runs after a release tag build or a manual
dispatch. This is intentional — snapshot pushes don't update the rules to avoid
unnecessary API calls. Run the workflow manually or trigger a release tag to
force a rule update.

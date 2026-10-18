# CI/CD Pipeline

This document describes the continuous integration and continuous delivery (CI/CD)
pipeline for the Ethos-Protocol mobile apps (iOS + Android). It covers the workflows
that run on every pull request and on `main`, the build artifacts they produce, the
release process, and the deployment procedures for each environment.

- [Overview](#overview)
- [Workflow map](#workflow-map)
- [Pull request gates](#pull-request-gates)
- [Build artifacts](#build-artifacts)
- [Artifact storage and versioning](#artifact-storage-and-versioning)
- [Release process](#release-process)
- [Release security: SBOMs and build provenance](#release-security-sboms-and-build-provenance)
- [Deployment procedures](#deployment-procedures)
- [Secrets and configuration](#secrets-and-configuration)
- [Troubleshooting](#troubleshooting)

## Overview

All pipeline logic lives in `.github/workflows/` and is executed by GitHub Actions.
The pipeline is split into three layers:

1. **Per-Platform CI** — iOS and Android build and test independently.
2. **Quality and security gates** — coverage upload, dependency vulnerability
   scanning, certificate-pin checks, and Apple App Site Association / Asset Links
   verification.
3. **Staging and release** — builds are published as artifacts and the staging
   environment is exercised before a release is cut.

The pipeline is designed so that any branch can be built and tested, but only
`main` produces shippable artifacts.

## Workflow map

| Workflow file | Trigger | Purpose |
|---------------|---------|---------|
| `.github/workflows/ios-ci.yml` | PR, push to `main` | Build + test the iOS app and the SPM package. Runs the Release TLS pin check. |
| `.github/workflows/android-ci.yml` | PR, push to `main` | Build + test the Android app (unit + connected tests). Runs the release certificate-pin check. |
| `.github/workflows/ios-dependency-check.yml` | Weekly, PR on `Package.swift` / `Package.resolved` | OSV scan of pinned SPM dependencies. Fails on CVE at CVSS ≥ 7.0. |
| `.github/workflows/android-dependency-check.yml` | Weekly, PR on gradle dependency changes | OWASP dependency-check against Android dependencies. |
| `.github/workflows/ios-applinks-verify.yml` | Daily, PR on `EthosProtocol.entitlements` | Verifies the Apple App Site Association file is served correctly. |
| `.github/workflows/android-applinks-verify.yml` | Daily, PR on `AndroidManifest.xml` | Verifies the Asset Links file is served correctly. |
| `.github/workflows/staging-deploy.yml` | Push to `main`, manual dispatch | Builds staging artifacts and runs the staging smoke tests. |
| `.github/workflows/staging-smoke-test.yml` | `workflow_call` | Reusable smoke-test workflow used by staging and release. |
| `.github/workflows/ios-app-store-release.yml` | Tag `vX.Y.Z`, manual dispatch | Builds signed iOS archive and uploads to TestFlight. |
| `.github/workflows/android-play-store-release.yml` | Tag `vX.Y.Z`, manual dispatch | Builds signed Android AAB and uploads to Google Play internal track. |
| `.github/workflows/artifact-storage.yml` | Tag `vX.Y.Z`, push to `main`, manual dispatch | Archives signed `.ipa`, `.aab`, `.apk`, and R8 mappings to S3 with versioning. See [docs/artifact-storage.md](artifact-storage.md). |
| `.github/workflows/cert-pin-expiry-monitor.yml` | Scheduled | Monitors TLS certificate expiry and alerts if pins need rotation. |
| `.github/workflows/release-notes-parity-check.yml` | PR, push to `main` | Validates release notes stay aligned with `PARITY.md`. |

Workflows that touch shipping code are pinned to explicit runner versions so that
build results are reproducible.

## Pull request gates

Every pull request must pass the following checks before it can be merged:

- `build-and-test` (iOS) — compiles the SPM package and the app target, then runs
  the test suite on an iOS Simulator destination.
- `build-and-test` (Android) — compiles the debug variant, runs the JVM unit tests,
  and (on the connected job) runs instrumented tests.
- `coverage` — uploads iOS and Android coverage to CodeCov under the `ios` and
  `android` flags.
- `check-tls-pinning` — fails a Release build if either `TLS_PUBLIC_KEY_PIN_CURRENT`
  or `TLS_PUBLIC_KEY_PIN_BACKUP` is missing or empty.
- `Verify release certificate pins are not placeholders` — reports an unconfigured
  release build and fails once pins are configured but wrong, or once release signing
  is configured.
- `dependency-check` — runs on changes to gradle dependency files and weekly on a
  schedule.
- `spm-vulnerability-scan` — runs on changes to `Package.swift` / `Package.resolved`
  and weekly on a schedule.

A PR that fails any of these gates cannot be merged. The branch protection rule on
`main` requires the `build-and-test` jobs from both platforms.

## Build artifacts

Each workflow uploads a well-defined set of artifacts.

### iOS

| Artifact | Contents | Retention |
|----------|----------|-----------|
| `ios-build-logs` | `xcodebuild` logs and the `.xcresult` bundle from the test run | 14 days |
| `ios-coverage` | The `.xccoverage` file uploaded to CodeCov under the `ios` flag | 14 days |
| `ios-staging-app` | The Staging `.app` bundle built by `staging-deploy.yml` | 7 days |
| `ios-ipa-<version>` | Signed `.ipa` and SHA-256 checksum — fallback copy of the S3 upload | 14 days |

The iOS build artifact is an unsigned `.app` bundle during CI. Signing happens in the
release job (fastlane match). The shippable `.ipa` is archived to S3 by
`artifact-storage.yml` with a 180-day retention window.

### Android

| Artifact | Contents | Retention |
|----------|----------|-----------|
| `android-debug-apk` | `app-debug.apk` from the debug build | 14 days |
| `android-staging-apk` | `app-staging.apk` from the staging build | 7 days |
| `android-unit-test-reports` | JUnit test reports (HTML + XML) | 14 days |
| `android-coverage-report` | JaCoCo coverage report uploaded to CodeCov under the `android` flag | 14 days |
| `android-r8-mapping` | R8 mapping file from the release build | 14 days |
| `android-release-<version>` | Signed `.aab` + `.apk` and SHA-256 checksums — fallback copy of S3 upload | 14 days |

Android artifacts are built with the debug keystore unless release signing is
configured in the repository secrets. The staging APK bakes `STAGING_API_BASE_URL`
into BuildConfig at compile time.

## Artifact storage and versioning

Release artifacts are archived to Amazon S3 for durable, rollback-friendly storage.
GitHub Actions artifacts serve as a 14-day fallback and inspection copy.

**Workflow**: `.github/workflows/artifact-storage.yml`

**Versioning scheme**

| Build type | Version label | S3 path pattern |
|------------|---------------|-----------------|
| Release tag `vX.Y.Z` | `vX.Y.Z` | `<platform>/releases/vX.Y.Z/` |
| Push to `main` | `main-YYYYMMDD-<sha7>` | `<platform>/snapshots/main/YYYYMMDD-<sha7>/` |
| Other branch | `<branch>-YYYYMMDD-<sha7>` | `<platform>/snapshots/<branch>/YYYYMMDD-<sha7>/` |

**Retention policy** (managed by `scripts/manage_artifact_retention.py`)

| Prefix | Default | Override variable |
|--------|---------|-------------------|
| `*/releases/` and `r8-mappings/` | 180 days | `ARTIFACT_RELEASE_RETENTION_DAYS` |
| `*/snapshots/` | 30 days | `ARTIFACT_SNAPSHOT_RETENTION_DAYS` |

For full setup instructions, download procedures, rollback steps, and
troubleshooting, see **[docs/artifact-storage.md](artifact-storage.md)**.

## Release process

Releases are cut from `main` only. The process is:

1. **Version bump** — update the version in `ios/EthosProtocol/project.yml`
   (`CURRENT_PROJECT_VERSION` and `MARKETING_VERSION`) and in
   `android/app/build.gradle.kts` (`versionName` / `versionCode`).
2. **Staging validation** — push to `main` and wait for `Staging Deploy` to green.
   This builds the staging artifacts and runs the smoke test suite against the live
   staging API.
3. **Tag** — create an annotated tag of the form `v<major>.<minor>.<patch>`
   (e.g. `v1.4.0`) and push it. The tag triggers the release and artifact-storage
   workflows simultaneously.
4. **Release build** — the release workflows build the signed iOS archive and the
   signed Android Bundle (`.aab`).
5. **Artifact archive** — `artifact-storage.yml` uploads the signed artifacts to
   `s3://<ARTIFACT_BUCKET>/ios/releases/vX.Y.Z/` and
   `s3://<ARTIFACT_BUCKET>/android/releases/vX.Y.Z/`.
6. **Store upload** — the `App Store Connect` and `Google Play` upload steps promote
   the builds to their respective stores.
7. **Tag notes** — add the release notes to the GitHub Release created from the tag.

The release workflow depends on the staging smoke test workflow via `workflow_call`,
so a failing smoke test blocks the release:

```yaml
jobs:
  staging-smoke:
    uses: ./.github/workflows/staging-smoke-test.yml
    secrets: inherit
  release:
    needs: staging-smoke
    ...
```

## Release security: SBOMs and build provenance

Every release produces a machine-readable inventory of its dependencies and a signed
attestation of the build that produced each binary. Both are published alongside the
release artifacts so that a dependency CVE can be scoped quickly and so that users
and store reviewers can verify which commit produced a binary.

### Software bill of materials (SBOM)

The release workflows generate [CycloneDX](https://cyclonedx.org/) SBOMs for both
dependency graphs:

| Platform | Tool | Dependency graph covered | SBOM file |
|----------|------|--------------------------|-----------|
| Android | CycloneDX Gradle plugin (`cyclonedxDirectBuild`) | Gradle (runtime + compile classpaths) | `android-cyclonedx-<version>.json` |
| iOS | CycloneDX SwiftPM plugin (`cyclonedxAggregate`) | SwiftPM (`Package.resolved`) | `ios-cyclonedx-<version>.json` |

SBOMs are generated in the release jobs (`android-play-store-release.yml`,
`ios-app-store-release.yml`) and in the Firebase distribution job, and are published
in two places:

1. **GitHub Release assets** — attached to the GitHub Release created from the
   `vX.Y.Z` tag, next to the signed `.aab` / `.apk` / `.ipa` and their SHA-256
   checksums.
2. **Artifact bucket** — uploaded to S3 alongside the binaries:
   ```
   s3://<ARTIFACT_BUCKET>/android/releases/vX.Y.Z/android-cyclonedx-vX.Y.Z.json
   s3://<ARTIFACT_BUCKET>/ios/releases/vX.Y.Z/ios-cyclonedx-vX.Y.Z.json
   ```
   SBOMs follow the same 180-day release retention policy as the binaries (see
   [Artifact storage and versioning](#artifact-storage-and-versioning)).

To inspect an SBOM locally, download it and run:

```bash
# List every component and its version
jq -r '.components[] | "\(.name)@\(.version)"' android-cyclonedx-vX.Y.Z.json

# Find the component that owns a CVE
jq -r '.components[] | select(.name == "okhttp")' android-cyclonedx-vX.Y.Z.json
```

### Build provenance attestations

The Android Play, Firebase, and iOS release workflows call
[`actions/attest-build-provenance`](https://github.com/actions/attest-build-provenance)
after signing, so each `.aab`, `.apk`, and `.ipa` has a Sigstore-backed attestation
recording the repository, workflow, commit SHA, and runner that produced it. The
attestations are stored in the repository's attestation store and are also attached
to the GitHub Release.

To verify a downloaded artifact, run:

```bash
gh attestation verify EthosProtocol-vX.Y.Z.ipa --repo <owner>/<repo>
```

A successful verification prints the attestation details and exits `0`:

```
Loaded digest sha256:3f2a...c91d for file://EthosProtocol-vX.Y.Z.ipa
Loaded 1 attestation from GitHub API
✓ Verification succeeded!

sha256:3f2a...c91d was attested by:
REPO                PREDICATE_TYPE                  WORKFLOW
<owner>/<repo>      https://slsa.dev/provenance/v1  .github/workflows/ios-app-store-release.yml@refs/tags/vX.Y.Z
```

If the artifact was tampered with, or was not built by the expected workflow, the
command exits non-zero with `✗ Verification failed!` and lists the reason (for
example `no attestations found` or a digest mismatch). The same command works for
the Android artifacts:

```bash
gh attestation verify app-release-vX.Y.Z.aab --repo <owner>/<repo>
gh attestation verify app-release-vX.Y.Z.apk --repo <owner>/<repo>
```

Verification requires the GitHub CLI (`gh`) version 2.49 or newer and an
authenticated session (`gh auth login`).

## Deployment procedures

### Staging

Staging is deployed automatically on every push to `main` via
`.github/workflows/staging-deploy.yml`. The workflow:

1. Builds the Android staging APK (`assembleStagingRelease`).
2. Builds the iOS Staging app (`xcodebuild -configuration Staging`).
3. Runs `scripts/smoke_test_staging.sh` against the live staging API.

No manual step is required. The builds are attached to the workflow run and the smoke
test results are visible in the job logs. See
[docs/staging-environment.md](staging-environment.md) for the full staging setup and
the manual testing checklist.

### Production

Currently, production deployment is manual. The release manager:

1. Runs the release workflow from the tag.
2. Downloads the signed artifacts from S3 using `scripts/manage_artifact_retention.py
   --show-current` to find the version, then:
   ```bash
   aws s3 cp s3://<ARTIFACT_BUCKET>/ios/releases/vX.Y.Z/EthosProtocol-vX.Y.Z.ipa .
   aws s3 cp s3://<ARTIFACT_BUCKET>/android/releases/vX.Y.Z/app-release-vX.Y.Z.aab .
   ```
3. Verifies the SHA-256 checksums and the build provenance attestation:
   ```bash
   gh attestation verify EthosProtocol-vX.Y.Z.ipa --repo <owner>/<repo>
   gh attestation verify app-release-vX.Y.Z.aab --repo <owner>/<repo>
   ```
4. Uploads the iOS archive to App Store Connect using Transporter.
5. Uploads the Android Bundle to the Play Console.
6. Promotes the build to the desired track (internal → beta → production) in the
   Play Console.
7. Creates the GitHub Release with the tag notes.

The production deployment is deliberately manual so that a human can review the
staging smoke results and the release notes before shipping.

### Rollback

If a release is broken:

- **iOS**: use App Store Connect's "Phased Release" pause, or download and re-submit
  the previous signed `.ipa` from S3:
  ```bash
  aws s3 cp s3://<ARTIFACT_BUCKET>/ios/releases/v1.3.0/EthosProtocol-v1.3.0.ipa .
  ```
- **Android**: use the Play Console's "halt rollout" or download and re-upload the
  previous `.aab` from S3:
  ```bash
  aws s3 cp s3://<ARTIFACT_BUCKET>/android/releases/v1.3.0/app-release-v1.3.0.aab .
  ```
- **Both**: create a hotfix branch from the last good tag, bump the patch version, and
  repeat the release process.

See [docs/artifact-storage.md](artifact-storage.md) for detailed download and
rollback instructions.

## Secrets and configuration

The pipeline relies on GitHub repository secrets. The following are required for the
staging and release workflows:

| Secret | Used by | Description |
|--------|---------|-------------|
| `STAGING_API_BASE_URL` | staging-deploy | Staging API root, e.g. `https://staging-api.ethos-protocol.app/v1` |
| `STAGING_SMOKE_TOKEN` | staging-deploy | Long-lived JWT for the dedicated smoke-test account |
| `STAGING_SMOKE_VAULT_ID` | staging-deploy | Vault ID owned by the smoke-test account |
| `AWS_ARTIFACT_ACCESS_KEY_ID` | artifact-storage | IAM access key for the artifact uploader |
| `AWS_ARTIFACT_SECRET_ACCESS_KEY` | artifact-storage | Paired IAM secret access key |
| `ARTIFACT_BUCKET` | artifact-storage | S3 bucket name (e.g. `ethos-protocol-artifacts`) |

Release signing secrets (Apple Developer Team, iOS distribution certificate, Android
keystore) are managed separately and are not committed to the repository. See
`SECURITY.md` for the secrets policy and `.pre-commit-config.yaml` for the local
scanning hooks.

## Troubleshooting

**Staging Deploy fails at the smoke test step:**
The staging backend may be down or unreachable. Check that `STAGING_API_BASE_URL` is
correct and the staging server is healthy. See
[docs/staging-environment.md](staging-environment.md#troubleshooting) for the full
troubleshooting guide.

**iOS build fails with "unable to find Info.plist":**
The Staging configuration was added to `project.yml` after you last ran `xcodegen`.
Re-run:
```bash
cd ios/EthosProtocol && mkdir -p Xcode && xcodegen generate --project Xcode
```

**Android staging APK points at the production URL:**
Confirm `STAGING_API_BASE_URL` is set in the environment when running
`assembleStagingRelease`, or in `~/.gradle/gradle.properties` locally. The value is
baked into `BuildConfig.API_BASE_URL` at compile time; installing the APK without
rebuilding will keep the old URL.

**Release build fails the TLS pin check:**
Set both `TLS_PUBLIC_KEY_PIN_CURRENT` and `TLS_PUBLIC_KEY_PIN_BACKUP` to
Base64-encoded SPKI SHA-256 hashes. See the README's Setup → iOS section for the
command to compute a pin.

**Artifact upload fails with "AccessDenied" or "NoSuchBucket":**
See [docs/artifact-storage.md#troubleshooting](artifact-storage.md#troubleshooting).

**`gh attestation verify` fails with "no attestations found":**
The artifact was not produced by a release workflow, or the attestation was not
published. Confirm the file was downloaded from the GitHub Release for the tag (not
rebuilt locally) and that the `--repo` value matches the repository that ran the
release workflow. Attestations are only created for artifacts built by
`android-play-store-release.yml`, the Firebase distribution job, and
`ios-app-store-release.yml`.

**SBOM is missing from the release:**
Check that the `cyclonedxDirectBuild` (Android) or `cyclonedxAggregate` (iOS) task ran
in the release job logs. A failed SBOM generation fails the job, so a missing SBOM
usually means the release job did not complete.

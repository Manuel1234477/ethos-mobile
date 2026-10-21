# Staging Environment

This document covers everything needed to build, deploy, and test against the staging environment.

## Overview

The staging environment is a separate backend deployment (distinct from production) that lets the team validate API contract changes and app builds before cutting a release. It uses the same schema as production but is safe to run destructive operations against (repeated check-ins, dummy push tokens, etc.).

Staging is **not** pointed at by the production app builds. It is a CZ-only and developer-only target.

## Architecture

```
Production:  com.ethosprotocol        → https://api.ethos-protocol.app/v1
Staging:     com.ethosprotocol.staging → https://staging-api.ethos-protocol.app/v1
```

Both builds can be installed simultaneously on the same device because they use distinct application identifiers.

## Required Secrets

These must be configured as GitHub repository secrets before the staging CA workflow can run:

| Secret | Description |
|--------|-------------|
| `STAGING_API_BASE_URL` | Staging API root, e.g. `https://staging-api.ethos-protocol.app/v1` |
| `STAGING_SMOKE_TOKEN` | Long-lived JWT for the dedicated smoke-test account on staging |
| `STAGING_SMOKE_VAULT_ID` | Vault ID owned by the smoke-test account; safe to check-in against repeatedly |

Optional:

| Secret | Description |
|--------|-------------|
| `ETHOS_STAGING_CERT_PINS` | Comma-separated Base64 SPKI SHA-256 hashes for the staging TLS certificate. Leave unset to disable certificate pinning on staging builds (staging cert rotates independently of production). |
| `STAGING_SMOKE_PUSH_TOKEN` | Real or dummy push token for the notification registration smoke test. Defaults to a random sentinel value if unset. |

## CI/CD Pipeline

The CI/CD pipeline is implemented as GitHub Actions workflows under `.github/workflows/`. There are two workflows that matter for staging:

| Workflow | File | Triggers | Purpose |
|----------|------|----------|---------|
| Staging Deploy | `.kube/workflows/staging-deploy.yml` | push to `main`, `workflow_dispatch` | Builds Android + iOS staging artifacts and runs the smoke test suite |
| Staging Smoke Test | `.kube/workflows/staging-smoke-test.yml` | `workflow_call`, `workflow_dispatch` | Reusable smoke test job that can be gated on by the release workflow |

#### Staging Deploy Workflow

.github/workflows/staging-deploy.yml runs on every push to `main` and on manual dispatch. It:

1. **Builds the Android staging APK** (`assembleStaging`) — STAGING_API_BASE_URL is baked into BuildConfig at compile time.
2. **Builds the iOS Staging app** (`xcodebuild -configuration Staging`) — STAGING_API_BASE_URL is injected as an xcodebuild setting.
3. **Runs the smoke test suite** (`scripts/smoke_test_staging.sh`) against the live staging API once both builds succeed.

The smoke test job is also exposed via `workflow_call` in `staging-smoke-test.yml`, so a future release workflow can gate on it:

```yaml
jobs:
  staging-smoke:
    uses: ./.github/workflows/staging-smoke-test.yml
    secrets: inherit
  release:
    needs: staging-smoke
    ...
```

#### Build Artifacts

Each successful run of the staging workflow produces the following artifacts, uploaded via `actions/upload-artifact`:

| Artifact | Path | Retention | Description |
|----------|------|-----------|------------|
| `android-staging-apk` | `android/app/build/outputs/apk/staging/app-staging.apk` | 14 days | Signed (debug-key) staging APK with `STAGING_API_BASE_URL` baked in |
| `ios-staging-app` | `ios/EthosProtocol/build/Staging-iphoneos/EthosProtocol.app` | 14 days | iOS Staging `.app` bundle (unsigned); download and install via Xcode or `osdeploy` |
| `smoke-test-logs` | `smoke-test-logs.txt` | 7 days | Stdout/stderr from `scripts/smoke_test_staging.sh`, uploaded even on failure |

Artifacts are named with the commit SHA (e.g. `android-staging-apk-a3f1c9d`) so they can be traced back to a specific revision. The `Artifact URL` printed in the workflow summary is the canonical link to download them.

#### Release Process

Staging is the gate before a production release. The end-to-end flow is:

1. **Merge to `main`.** The staging workflow runs automatically and produces fresh staging artifacts.
2. **Verify the smoke tests.** The `staging-smoke-test` job must be green. If it fails, the release is blocked.
3. **Manual staging validation.** Run through the Staging Testing Checklist below on a physical device using the artifacts from step 1.
4. **Tag the release.** Once staging is validated, create an annotated tag on the verified commit:

   ```bash
   git tag -a v1.2.0 -m "Release v1.2.0" <commit-sha>
   git push origin v1.2.0
   ```

5. **Trigger the production release workflow.** The release workflow depends on the staging smoke test job via `needs`:

   ```yaml
   jobs:
     staging-smoke:
       uses: ./.github/workflows/staging-smoke-test.yml
       secrets: inherit
     release:
       needs: staging-smoke
       runs: ./scripts/release.sh
   ```

6. **Sign and distribute.** The release workflow signs the production APK / iPA and uploads to the store tracks. Staging artifacts are never shipped to end users.

#### Deployment Procedures

##### Deploying to Staging

Staging deploys are fully automated on merge to `main`. To deploy a specific commit manually:

1. Open the **Staging Deploy** workflow in the Actions tab.
2. Click **Run workflow** and select the branch or paste the commit SHA in the `ref` field.
3. Wait for the build and smoke test jobs to finish.
4. Download the artifacts from the workflow summary and install them on a test device.

###### Deploying to Production

Production deploys are tag-driven and gated on the staging smoke tests. Never deploy directly from `main` without a tag.

1. Ensure the commit you are releasing passed staging smoke tests.
2. Create and push an annotated tag (`git tag -a v1.2.0 ...`).
3. The release workflow triggers on the tag, re-runs the staging smoke test as a gate, then builds and signs the production binaries.
4. Upload to the store tracks (Android internal → beta → production; iOS TestFlight → App Store).
5. Version bumps and release notes are handled by the release workflow.

##### Rollback

If a staging deploy is broken, re-run the workflow on the last known-good commit. If a production release is broken, revert the tag and re-run the release workflow on the previous tag; do not force-push tags.

## Building Locally

### Android

```bash
# Build the staging APK (reads STAGING_API_BASE_URL from env, or uses the default subdomain)
cd android
STAGING_API_BASE_URL=https://staging-api.ethos-protocol.app/v1 ./gradlew assembleStaging

# Install on a connected device/emulator
adb install -r app/build/outputs/apk/staging/app-staging.apk`
```

Alternatively, set `ethos.stagingApiBaseUrl` in `~/.gradle/gradle.properties` (never commit this file):

```properties
ethos.stagingApiBaseUrl=https://staging-api.ethos-protocol.app/v1
```

### iOS

The Staging configuration is defined in `ios/EthosProtocol/project.yml`. You need to regenerate the Xcode project before building it for the first time after pulling this change:

```bash
cd ios/EthosProtocol
mkdir -p Xcode
xcodegen generate --project Xcode
open Xcode/EthosProtocol.xcodeproj
```

Then in Xcode:

1. Select the `EthosProtocol` scheme.
2. Go to **Product → Scheme → Edit Scheme → Run → Info**.
3. Set **Build Configuration** to `Staging`.
4. (Optional) Add a build setting override: `STAGING_API_BASE_URL = https://staging-api.ethos-protocol.app/v1`.
5. Run the app.

Or from the command line:

```bash
cd ios/EthosProtocol
xcodebuild build \
  -project Xcode/EthosProtocol.xcodeproj \
  -scheme EthosProtocol \
  -configuration Staging \
  -destination 'generic/platform=iOS Simulator' \
  -skipMacroValidation \
  STAGING_API_BASE_URL=https://staging-api.ethos-protocol.app/v1
```

The Staging configuration:
- Uses bundle ID `com.ethosprotocol.staging` (coexists with production on device)
- Points at the staging API via `STAGING_API_BASE_URL`
- Has certificate pinning disabled by default (staging cert is separate from production)
- Has minification enabled (same as Release, to catch ProGuard/dead-code issues early)

## Smoke Test Suite

`scripts/smoke_test_staging.sh` covers five core API flows in order:

| # | Flow | Endpoint | Notes |
|---|------|----------|-------|
| 1 | Auth challenge | `POST /auth/challenge` | Unauthenticated; verifies challenge + credential_ids fields |
| 2 | Vault list | `GET /vaults` | Authenticated; verifies response shape |
| 3 | Vault list pagination | `GET /vaults?limit=1` + cursor round-trip | Verifies `X-Next-Cursor` header and cursor acceptance |
| 4 | Check-in | `POST /vaults/{id}/checkin` | Includes required `X-Nonce` and `X-Timestamp` anti-replay headers |
| 5 | Push notification register | `POST /notifications/register` | Accepts dummy token; verifies endpoint contract |

Run locally:

```bash
export STAGING_API_BASE_URL=https://staging-api.ethos-protocol.app/v1
export STAGING_SMOKE_TOKEN=<jwt from smoke-test account>
export STAGING_SMOKE_VAULT_ID=<vault id>

./scripts/smoke_test_staging.sh
```

The script exits non-zero and prints `STAGING SMOKE TEST SUITE FAILED` if any test fails, making it safe to use as a CI step or a pre-release gate.

## Staging Testing Checklist

Run through these manually before cutting a release build, in addition to the automated smoke tests:

### Auth

- [ ] Cold-start the staging app; confirm it reaches the sign-in screen.
- [ ] Complete a passkey registration on staging (`POST /auth/challenge` → `POST /auth/register`).
- [ ] Sign in with the new passkey (`POST /auth/challenge` → `POST /auth/verify`).
- [ ] Confirm the JWT is stored in Keychain (iOS) / EncryptedSharedPreferences (Android) and not in plain logs.
- [ ] Token refresh: leave the app idle until the JWT nears expiry (or shorten `expires_at` on the staging backend); confirm the app proactively refreshes without signing out.

### Vaults

- [ ] Vault list loads after sign-in; confirm all owned staging vaults appear.
- [ ] If the staging account has more than 50 vaults: scroll to the bottom and confirm the next page loads (pagination).
- [ ] Tap a vault to open the detail view; confirm TTL countdown is live.
- [ ] Perform a check-in; confirm TTL resets and a success notification appears.
- [ ] While offline (disable Wi-Fi/cellular): perform a check-in; confirm it is queued.
- [ ] Re-enable connectivity; confirm the queued check-in is sent and synced.

### Push Notifications

- [ ] On first launch after sign-in, confirm `POST /notifications/register` is called (visible in staging API logs).
- [ ] Create a vault with a short TTL on staging; confirm a local reminder fires 24 h before expiry.
- [ ] Trigger a push from the staging backend; confirm it is delivered and the deep-link opens the correct vault.

### Widget (iOS)

- [ ] Add the TTL widget to the home screen while running the Staging build.
- [ ] Confirm the widget shows vault data from the staging API (not production).
- [ ] Perform a check-in; confirm the widget refreshes within its WidgetKit timeline budget.

### Widget (Android)

- [ ] Add the vault widget to the home screen while running the Staging build.
- [ ] Confirm the widget shows vault data from the staging API.
- [ ] Perform a check-in; confirm the widget updates.

### Regression guards

- [ ] Confirm no production API URL (`api.ethos-protocol.app`) appears in staging app network traffic (use a proxy / Charles).
- [ ] Confirm the staging app displays a visible indicator (e.g. "Staging" in the app name or a banner) that distinguishes it from the production build — **important before distributing to beta testers**.

## Differences from Production

| Property | Production | Staging |
|----------|------------|---------|
| Bundle ID (iOS) | `com.ethosprotocol` | `com.ethosprotocol.staging` |
| Bundle ID (Android) | `com.ethosprotocol` | `com.ethosprotocol.staging` |
| API URL | `https://api.ethos-protocol.app/v1` | `https://staging-api.ethos-protocol.app/v1` |
| TLS pinning | Required (CI-gated) | Optional (disabled by default) |
| Data | Real user data | Test data only — safe to wipe |
| Push notifications | Production APNs/FCM | Staging APNs/FCM (separate credentials) |

## Troubleshooting

**Smoke test fails on `POST /auth/challenge`:**
The staging backend may be down or unreachable. Check `STAGING_API_BASE_URL` is correct and the staging server is healthy.

**Smoke test fails on `POST /vaults/{id}/checkin` with 409:**
The server rejected the nonce (replay detected). This should not happen on a fresh run; if it does, the nonce-deduplication window may be set too wide on the staging backend.

**iOS Staging build fails with "unable to find Info.plist":**
The Staging configuration was added to `project.yml` after you last ran `xcodegen`. Re-run:
```bash
cd ios/EthosProtocol && mkdir -p Xcode && xcodegen generate --project Xcode
```

**Android staging APK points at production URL:**
Confirm `STAGING_API_BASE_URL` is set in the environment when running `assembleStaging`, or in `~/.gradle/gradle.properties` locally. The value is baked into `BuildConfig.API_BASE_URL` at compile time; installing the APK without rebuilding will keep the old URL.

## Dedicated smoke vault (#572)

The smoke test checks in to `STAGING_SMOKE_VAULT_ID` only when `STAGING_SMOKE_ALLOW_CHECKIN=1`,
because a check-in extends the vault's TTL for every other test that uses it.

1. Create a vault in staging that is used only by the smoke test.
2. Set `STAGING_SMOKE_VAULT_ID` to that vault and `STAGING_SMOKE_ALLOW_CHECKIN=1` for the smoke job.
3. Delete the vault after the smoke run. Deleting it on teardown keeps staging free of stale vaults.

# Security Guidelines

> **Audience:** Anyone writing or reviewing code in this repository.
> **Scope:** The iOS and Android clients in `ios/` and `android/`, plus the shared
> API contract in `shared/api-contract.md`.
> **Not in scope:** Reporting vulnerabilities. See [SECURITY.md](../SECURITY.md) for
> responsible disclosure — do **not** open a public issue for a security bug.

These guidelines are the checklist you run against yourself *before* opening a pull
request, and the checklist a reviewer runs against your PR. They are derived from the
security controls this codebase already enforces, so following them keeps the app
consistent with its own architecture rather than with generic advice.

Each rule is marked with how strongly it is enforced:

| Marking | Meaning |
|---------|---------|
| **MUST** | Violating this is a security defect. Review will block on it. |
| **SHOULD** | Strong default. Deviating needs a reason in the PR description. |
| **NEVER** | Do not do this under any circumstances. No exceptions. |

---

## Table of contents

1. [Secure coding practices](#1-secure-coding-practices)
2. [Key management guidelines](#2-key-management-guidelines)
3. [Authentication best practices](#3-authentication-best-practices)
4. [Data protection and logging](#4-data-protection-and-logging)
5. [Network and transport security](#5-network-and-transport-security)
6. [Secrets and supply chain](#6-secrets-and-supply-chain)
7. [Security checklist for pull requests](#7-security-checklist-for-pull-requests)
8. [Known gaps](#8-known-gaps)
9. [References](#9-references)

---

## 1. Secure coding practices

### 1.1 Never introduce these sinks

This codebase has no `WebView`/`WKWebView`, no JavaScript bridge, and no raw or
dynamically-constructed SQL. Keep it that way — each of these sinks requires a
security review that normal PR review will not catch.

- **NEVER** add a `WebView`/`WKWebView` or any JavaScript bridge to render untrusted
  or server-supplied content. If a screen needs remote content, render it natively.
- **NEVER** build SQL with string interpolation. Use parameterized queries. Today the
  only database is Room (`PendingActionDatabase.kt`); if you add one, use bound
  parameters.
- **NEVER** shell out (`Process`, `Runtime.exec`, `NSTask`) with values that come from
  the network, a deep link, or user input.
- **NEVER** deserialize attacker-influenced data into a type that can instantiate
  arbitrary classes.

### 1.2 Validate all untrusted input at the boundary

Untrusted input includes: deep-link and universal-link URLs, push-notification
payloads, API responses, and anything a user types.

Validate at the point of entry, and reject rather than sanitize.

- **MUST** allowlist, not denylist. Character denylists are bypassed by encoding.
  Follow the pattern in `VaultDeepLinkParser.kt`, which validates vault IDs and
  acceptance tokens against an explicit allowlist (`[A-Za-z0-9_-]`).
- **MUST** validate the scheme **and** the host of an incoming deep link before
  reading any path or query component. `VaultDeepLinkParser` rejects anything that is
  not `https://ethos-protocol.app` or the `ethosprotocol://vault` custom scheme, which
  is what stops a custom-scheme forgery — any app on the device can send an intent to
  a custom scheme.
- **MUST** bound the length of every validated string. Unbounded input is a
  memory-exhaustion vector before it is anything else. `UsernameValidator` enforces
  3–32 characters with a regex; apply the same discipline to other identifiers.
- **SHOULD** centralize validation in a named type (`UsernameValidator`,
  `SecurityHeaderValidator`) rather than scattering `if` statements through call
  sites, so the rule is testable and greppable.

### 1.3 Build URLs from components

- **MUST** construct URLs with a component-aware builder (`URLComponents` on iOS,
  `URLBuilder` on Android) or allowlist the interpolated values.
- **SHOULD NOT** interpolate a path segment directly into a URL string. An unencoded
  value containing `/`, `?`, or `#` silently changes which endpoint you are calling.
  `VaultEventSocket` does this correctly — copy that pattern.

### 1.4 Handle errors without leaking

Error messages cross a trust boundary: they end up in logs, in crash reporters, and
sometimes in the UI.

- **MUST** distinguish user-cancellation from a genuine failure, and handle
  cancellation as a normal outcome, not an error. `BiometricHelper.AuthFailure`
  models this explicitly (`USER_CANCELLED`, `LOCKOUT`, `NOT_ENROLLED`,
  `HARDWARE_UNAVAILABLE`, `NOT_RECOGNISED`) precisely so callers do not depend on
  platform error strings, which are not stable across OEMs and API levels.
- **MUST** map platform errors to a closed set of cases. Add a new case to the
  failure enum rather than matching on a raw error string.
- **NEVER** put a secret, token, or raw credential in an error message, even one that
  is only logged. Assume everything logged eventually reaches a third party (see §4).
- **SHOULD** show users a generic failure message and keep the diagnostic detail in
  the redacted log.

### 1.5 Fail closed on security checks

- **MUST** fail closed. If a security check cannot run, the request must not proceed.
  A trust-manager installation that silently degrades to "no pinning" is a
  fail-open bug even though the happy path is correct.
- **MUST** scope a validator to what it is meant to cover. A pin check that applies to
  every host on a shared connection pool is broader than intended and will produce
  confusing failures on unrelated hosts.
- **SHOULD** delete dead code that models insecure behavior rather than leaving it for
  someone to wire up later. An unused helper with a plausible name and an unsound
  implementation is a future vulnerability.

### 1.6 Maintain parity deliberately

Security fixes are not optional on one platform. PARITY.md tracks user-facing
features; security controls are in scope for that table too.

- **MUST** ship a security control on both platforms, or document explicitly in the PR
  why the other platform is deferred and file a follow-up issue.
- **MUST** not weaken a control on one platform to make an implementation easier. If
  local-auth strength differs between platforms, that is a finding, not a preference.
- **SHOULD** write a parity test for anything security-relevant that exists on one
  platform only, so the gap is visible in CI.

---

## 2. Key management guidelines

### 2.1 What must never be handled by app code

- **NEVER** handle, store, log, or transmit a Stellar secret seed, a wallet private
  key, or a seed-phrase mnemonic. The private key material for passkeys lives in the
  Secure Enclave (iOS) or the TEE-backed credential store (Android) and never leaves
  it. No client code path should ever need raw key material.
- **NEVER** export a passkey private key, even "temporarily", even for a migration.
- **NEVER** put key material in `UserDefaults`/`SharedPreferences`, in a Swift
  `Codable` model, in a URL, in a navigation argument, or in a notification payload.

### 2.2 Choosing a storage class

| Data | iOS | Android | Notes |
|------|-----|---------|-------|
| Session JWT + expiry | Keychain | `EncryptedSharedPreferences` | See accessibility rules below |
| Passkey credential ID | Keychain | `EncryptedSharedPreferences` | |
| Push / APNs token | Keychain | `EncryptedSharedPreferences` | |
| Local fallback PIN verifier | Keychain | — | See §2.4 |
| Account-scoped identifiers, credential IDs | Keychain | `EncryptedSharedPreferences` | **Not** `UserDefaults` |
| Non-sensitive UI preferences (theme, locale) | `UserDefaults` | `SharedPreferences` | No secrets |

The two platform primitives are `KeychainService.swift` (iOS, thin wrapper over
`SecItem*`) and `EncryptedSharedPreferences` built on an AES256-GCM `MasterKey` in
`api/Infrastructure.kt` (Android).

- **MUST** add new secrets through the existing `KeychainService` / encrypted-prefs
  helpers rather than calling `SecItem*` or `SharedPreferences` directly. The wrappers
  are where accessibility, deletion, and sign-out purging are implemented.
- **NEVER** store an account-scoped identifier or credential ID in `UserDefaults` or
  plain `SharedPreferences`. These files are included in unencrypted iOS backups and
  are readable by anything with filesystem access; the Keychain is neither.
- **SHOULD** keep the sign-out purge list explicit and tested. When you add a new
  persisted secret, add its deletion to sign-out in the same PR. An orphaned secret
  after sign-out is a data-retention defect as well as a security one.

### 2.3 iOS Keychain accessibility

`kSecAttrAccessible` is a real security control, not a formality. `KeychainService`
already distinguishes two cases:

- **Tokens read while the device is locked** (background refresh, the TTL widget) use
  `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. `WhenUnlockedThisDeviceOnly`
  would return `nil` in that context and the request would silently go out
  unauthenticated. This is a deliberate, documented trade-off.
- **Everything else** defaults to `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`.

- **MUST** use `ThisDeviceOnly` variants everywhere. The non-`ThisDeviceOnly` variants
  sync the secret into iCloud Keychain, which puts a vault session token in a backup.
- **MUST** justify any new `AfterFirstUnlock` item in a comment naming the specific
  locked-device context that needs it.
- **SHOULD** verify the Keychain access group. Both the app and the widget declare
  `com.ethosprotocol.shared` in their `.entitlements`, but a `SecItem*` call only
  lands in that group if it sets `kSecAttrAccessGroup`. Any item that both the widget
  and the app must read needs it set explicitly, and a test that reads it back from
  the other target is the only reliable way to know it worked.

### 2.4 Local fallback PIN

The 6-digit fallback PIN gates app access when biometrics are unavailable, so its
storage is a security boundary.

- **NEVER** store a bare, unsalted fast hash of a PIN. A 6-digit PIN is ~10^6
  candidates — a single SHA-256 over the digits is trivially brute-forced offline if
  the hash ever leaks. The former iOS implementation
  (`KeychainService.hashPIN`) was a plain `CC_SHA256` with no salt and no iterations;
  it has been replaced (see below).
- **MUST** use a memory-hard or iterated KDF (PBKDF2-HMAC-SHA256 with at least 600,000
  iterations, or Argon2id) with a **per-PIN random salt** stored alongside the
  verifier in the Keychain. `KeychainService` now derives the PIN verifier with
  PBKDF2-HMAC-SHA256 and a per-PIN random salt, and stores the salt next to the hash.
- **MUST** migrate legacy verifiers. A stored unsalted SHA-256 hash is upgraded to the
  salted KDF on the next successful unlock, in the same operation that verifies the
  PIN, so no user is left on the weak format.
- **MUST** bound attempts. Track failed attempts in a persisted counter, apply an
  escalating cooldown (reusing the `OTPRateLimiter` schedule), and require the device
  passcode or a re-login after a fixed number of failures. Without a counter, PIN
  entry is an unlimited guessing oracle bounded only by the UI.
  `PINAuthenticationService` now enforces this.
- **SHOULD** prefer biometric/device-credential over a PIN wherever the platform
  allows it, and let the user remove the PIN entirely.

### 2.5 Sensitive values on the clipboard

The clipboard is a global, unencrypted, and highly persistent buffer that other apps
can read while it is set. It is also a place secrets have historically escaped from.

- **MUST** route every copy of a sensitive value through `SensitiveClipboard` — iOS
  `Sources/Services/SensitiveClipboard.swift`, Android
  `services/SensitiveClipboard.kt`. These enforce a 60-second auto-clear.
- **NEVER** write to `UIPasteboard.general` or the Android clipboard directly.
- **MUST** prefer not offering a copy affordance at all. A value the app does not put
  on the clipboard cannot be exfiltrated from it. Reserve copy for values the user
  genuinely needs elsewhere.
- **SHOULD** clear the clipboard on sign-out for anything that was copied.

---

## 3. Authentication best practices

Ethos-Protocol authenticates vault owners with platform passkeys (WebAuthn). The
transport is described in [mobile-passkey-flow.md](mobile-passkey-flow.md); this
section is the rules.

### 3.1 Passkey registration

- **MUST** send the COSE public key (RFC 9052) extracted from the attestation
  object's `authData` as `public_key`. **NEVER** send the raw `attestationObject`
  re-encoded as the public key. Apple's
  `ASAuthorizationPlatformPublicKeyCredentialRegistration` does not expose a
  `credentialPublicKey` property, so both clients parse it out themselves —
  `PasskeyService.extractCOSEPublicKey` (iOS) and `extractCosePublicKey` (Android) —
  and both must send byte-identical `COSE_Key` data.
- **MUST** keep the relying party ID identical on both platforms
  (`ethos-protocol.app`) and identical to the associated-domain configuration in
  [`.well-known/assetlinks.json`](../.well-known/assetlinks.json) and the iOS AASA.
  CI verifies these with `android-applinks-verify.yml` and
  `ios-applinks-verify.yml`.
- **MUST** use platform authenticators only. Do not add a cross-platform/roaming
  authenticator flow without a security review — it changes the threat model from
  "key never leaves this device" to "key syncs through a cloud account".
- **MUST** persist the credential ID locally inside the registration routine itself,
  in the same synchronous continuation as the successful `/auth/register` response.
  Deferring it to a caller opens a crash window between "server registered the key"
  and "local credential ID saved", which breaks iCloud-sync associations.
- **MUST** make `user.id` an opaque, stable, non-PII handle. It must not be an email
  address, a display name, a wallet address, or anything else identifying. WebAuthn
  caps the user handle at 64 bytes. Both clients currently derive it from the
  username, which is a known gap — see [§8](#8-known-gaps).

### 3.2 Passkey authentication

- **MUST** use a single request builder per platform that covers **both** registration
  and authentication, so challenge handling cannot be implemented on one path and
  missed on the other. `PasskeyRequestBuilder` (Android) is the model; the builder
  must be exercised by the production code path, not only by tests.
- **MUST** validate the challenge received from the backend before handing it to the
  authenticator. A challenge that is empty, not base64url, or not of the expected
  length is a bug in the caller or a MITM signal — reject it.
- **MUST** request user verification. Do not weaken `userVerification` to
  `discouraged` to fix a UX issue.
- **NEVER** cache a successful assertion and reuse it. Every authentication needs a
  fresh server-issued challenge.

### 3.3 Challenge and nonce generation

- **MUST** generate challenges and nonces with a cryptographically secure random
  source. On iOS that means `SecRandomCopyBytes` or `CryptoKit.SymmetricKey`; on
  Android, `java.security.SecureRandom`. `arc4random`, `UInt8.random`, `random()`,
  and timestamps are not acceptable.
- **MUST** use the exact primitive the shared contract specifies.
  `shared/api-contract.md` mandates `CryptoKit.SymmetricKey(size: .bits256)` for
  `X-Nonce`. Both clients now use the contract-specified primitive: iOS uses
  `SecRandomCopyBytes` / `CryptoKit.SymmetricKey`, and Android uses
  `java.security.SecureRandom`.
- **MUST** send a unique `X-Nonce` and a timestamp on every mutating request, and
  **MUST** include them in any request signature you add.
- **SHOULD** add a test asserting the nonce is exactly 32 bytes. A test costs one
  line and catches an entropy regression that is otherwise invisible.

### 3.4 Session tokens

- **MUST** clear the token on any `401` response, and treat the session as
  unauthenticated from that point.
- **MUST** refresh with a single-flight guard so concurrent requests cannot trigger a
  refresh stampede. `ApiClient` (Android) and `APIClient` (iOS) both do this with a
  mutex/`singleFlight`; preserve that property in any refactor.
- **NEVER** treat a token as valid because the local clock says it has not expired.
  Local expiry is a refresh hint, not an authorization decision.
- **NEVER** write a code path that extends a local expiry without a corresponding
  server call. It makes an expired session look valid.
- **MUST** redact `Authorization` before any header map reaches a log sink — see §4.

### 3.5 Request signing

If you add or change request signing, all four of these are required:

- **MUST** bind the HTTP method, the path, a hash of the body, the nonce, and the
  timestamp into the signature. A signature over only `nonce:timestamp:idempotencyKey`
  adds nothing against an attacker who already holds the bearer token and permits
  cross-request substitution. The Android `X-Request-Signature` now binds the method,
  path, and body hash into its canonical string.
- **MUST** use a key that is **distinct** from the bearer token. Signing with the
  credential you are trying to protect is circular. The Android `X-Request-Signature`
  now derives its signing key from the server-issued session secret rather than the
  bearer token.
- **MUST** be documented in `shared/api-contract.md` and implemented on both clients
  or on neither. An undocumented header the server does not interpret is worse than
  no header, because it creates a false sense of protection.
- **MUST** add the header name to `LogRedactor`'s sensitive set on both platforms.

### 3.6 Local authentication and session locking

- **MUST** require `BIOMETRIC_STRONG` **or** `DEVICE_CREDENTIAL` on Android
  (`BiometricHelper`) and Face ID / Touch ID or device passcode on iOS. Do not accept
  `BIOMETRIC_WEAK` for a vault-unlocking action.
- **MUST** re-lock the session on backgrounding after a configurable timeout
  (`SessionLockManager` on Android, `ReLockTimeoutOption` + the background timestamp
  in `Stores.swift` on iOS). Do not add an option that disables re-locking entirely
  without a security review.
- **MUST** not gate vault *content* behind a UI-only check. The screen may be hidden,
  but the data must already be behind the session lock.
- **SHOULD** keep the lock screen honest: it must cover the whole app, including the
  Android task-switcher snapshot and any widget preview.

### 3.7 Tamper and root detection

`AppIntegrityService`, `SignatureVerifier`, and `TamperWarningDialog` (Android, under
`security/`) and `IntegrityService` (iOS) exist to detect repackaged builds.

- **MUST** keep these checks **non-blocking and advisory**. A root/jailbreak heuristic
  produces false positives, and bricking the app for them is a worse outcome than
  warning. Do not convert them into hard gates without a security review.
- **NEVER** log or display the actual certificate digest. It is a diagnostic detail
  that helps an attacker confirm which build they are inspecting.
- **SHOULD** treat the expected signing-certificate digest as build configuration
  supplied out of band, not a value committed to the repository. An empty digest
  disables the check, which is the correct state for debug and CI builds.

---

## 4. Data protection and logging

The redaction policy is normative and lives in
[`shared/api-contract.md`](../shared/api-contract.md) § *Logging Redaction Policy*.
This section is how to comply with it.

### 4.1 What must never be logged

In **any** build configuration, debug included:

1. `Authorization: Bearer <jwt>` headers, or any raw JWT
2. 2FA secrets, OTPs, TOTP seeds, provisioning URIs
3. Vault balances, deposit and withdrawal amounts
4. Beneficiary and owner wallet addresses
5. Acceptance tokens (`/accept?token=…`)
6. Full request or response bodies for any authenticated endpoint
7. Passkey material of any kind — public keys, signatures, challenge bytes,
   credential IDs, raw attestation objects

Debug builds may log the HTTP method, path, and status code (never a query string
carrying a token), non-sensitive model identifiers, and timing metrics.

- **MUST** route every string or header map destined for a log sink through
  `LogRedactor` (iOS `Sources/Services/LogRedactor.swift`, Android
  `security/LogRedactor.kt`).
- **MUST** add any new sensitive header to **both** platforms' `LogRedactor`
  sensitive sets. A header that is sensitive on one platform and logged on the other
  is still leaked.
- **MUST** keep the redaction list derived from the shared contract rather than
  hand-maintained per platform, so a contract change cannot leave one client behind.
- **NEVER** log "temporarily". Debug logging added under time pressure has a habit of
  surviving. `api/ApiDebugLog.kt` must stay behind a debug-only guard.

### 4.2 What the build already does for you

- Android R8 strips `Log.v`/`Log.d`/`Log.i` in release builds
  (`proguard-rules.pro`, `-assumenosideeffects`). `Log.w`/`Log.e` are deliberately
  retained. Treat debug logging as automatically dead in release, but do not rely on
  that as your only control — a `println`/`print` is not covered.
- iOS `os_log` is a system log readable by anyone with a Mac and a device. It is
  still a log sink.

### 4.3 In-memory diagnostic buffers

- **MUST** cap the size of any buffer that retains response data
  (`DecodingFailureLogger` keeps a rolling response buffer).
- **MUST** apply the full redaction list to buffered data, not just the obvious
  headers. Balances, owner addresses, and trust tokens have all appeared in these
  buffers.
- **MUST** clear diagnostic buffers on sign-out.
- **SHOULD** scrub Sentry breadcrumbs. A breadcrumb recorded before a redaction rule
  existed is still in the envelope by the time the report uploads.

---

## 5. Network and transport security

### 5.1 TLS certificate pinning

Both clients pin the SPKI SHA-256 of the API certificate against
`api.ethos-protocol.app`.

- **MUST** fail closed. If the pin set cannot be installed, refuse the connection.
- **MUST** validate the SPKI with a real ASN.1 parser. Hardcoding the P-256 and
  RSA-2048 SPKI headers and falling back to hashing raw key bytes for any other key
  type produces a pin that does not match a standards-compliant calculation and
  silently degrades security rather than failing loudly.
- **MUST** scope the pin check to the expected host.
- **MUST** keep pins in every build configuration that can be **signed or
  distributed**. `check_tls_pinning.py` is a no-op for any configuration other than
  `Release`, so a new signable configuration with no pins would ship unverified
  without a CI signal. When you add a configuration, extend that script.
- **MUST** follow [cert-pin-rotation-runbook.md](cert-pin-rotation-runbook.md) when
  rotating. An app version in the field with a stale pin set loses connectivity
  immediately.
- **MUST** keep at least two pins live during a rotation window (current + backup).

### 5.2 Request construction

- **MUST** send `X-Nonce` and a timestamp on every mutating request.
- **MUST** carry the WebSocket auth token in a header, never in a query string —
  query strings get logged by proxies.
- **SHOULD** validate response security headers. `SecurityHeaderValidator` checks
  `X-Content-Type-Options`, `Strict-Transport-Security`, and `X-Frame-Options` and
  records violations via `SecurityHeaderTelemetry`. Treat a missing header as
  diagnostic, not a hard failure.

### 5.3 Environment separation

| Environment | iOS bundle ID | Android application ID | Base URL |
|-------------|---------------|------------------------|----------|
| Production | `com.ethosprotocol` | `com.ethosprotocol` | `https://api.ethos-protocol.app/v1` |
| Staging | `com.ethosprotocol.staging` | `com.ethosprotocol.staging` | `https://staging-api.ethos-protocol.app/v1` |

- **NEVER** point a Release build at a non-production host.
- **MUST** source base URLs and pins from build configuration or CI secrets, never
  from a committed literal. Staging pins are optional by design
  (`ETHOS_STAGING_CERT_PINS`) because the staging certificate rotates independently.
- **SHOULD** declare the default staging URL in exactly one place. A default that
  disagrees with the real host fails silently — the request just goes to the wrong
  place and returns a 404.

### 5.4 Android backup and screen capture

- **MUST** keep `android:allowBackup="false"` in `AndroidManifest.xml`, with
  `res/xml/backup_rules.xml` as the second layer. This prevents the encrypted token
  store from being extracted via `adb backup`.
- **MUST** set `FLAG_SECURE` on screens rendering vault balances, addresses, or keys
  so they do not appear in screenshots or the task-switcher thumbnail. This is
  covered by `security/SecureFlagTest.kt`.
- **SHOULD** treat any new `exported` activity or service as a public API surface and
  validate its inputs accordingly.

---

## 6. Secrets and supply chain

### 6.1 What must never enter the repository

- Signing material: `.keystore`, `.jks`, `.p8`, `.p12`, `.mobileprovision`
- `gradle.properties` containing `ethos.certPins` or API keys — put them in
  `~/.gradle/gradle.properties` instead
- `google-services.json` and `GoogleService-Info.plist`
- Sentry DSNs, Firebase config, API keys, JWTs, `.env` files

### 6.2 How this is enforced

| Control | Where | Scope |
|---------|-------|-------|
| Gitleaks | `.pre-commit-config.yaml` | Content scan, staged files |
| detect-secrets | `.pre-commit-config.yaml` | Content scan, staged files |
| `check_sensitive_files.py` | `scripts/` | Blocks known-sensitive filenames |
| OWASP dependency-check | `android-ci.yml`, `android-dependency-check.yml` | Android dependencies |
| TLS pin verification | `check_tls_pinning.py`, `verify_cert_pins.py` | Release builds |
| Certificate expiry monitor | `cert-pin-expiry-monitor.yml` | Daily, warns at 90 days |

- **MUST** run `pre-commit install` before your first commit. See
  [contributor-onboarding.md](contributor-onboarding.md).
- **MUST** add a new sensitive header or secret shape to the scanner configuration
  when you introduce one, so the next person is protected too.
- **SHOULD** pin third-party CI actions to a full commit SHA. A mutable tag
  (`@v4`) is third-party code that executes with access to your repository's secrets.
  Workflows holding App Store Connect or signing secrets especially must not use
  mutable tags.
- **MUST** set top-level `permissions: contents: read` on every workflow, and scope
  `GITHUB_TOKEN` down to the minimum each job needs.
- **MUST** keep secrets in GitHub environment secrets, never in `env:` blocks that
  reach steps which do not need them.
- **SHOULD** make security checks fail closed. A monitoring job that treats an
  unreachable host as "not expiring soon" has silently stopped monitoring.

### 6.3 Dependencies

- **MUST** review the transitive dependency list of anything security-relevant
  (crypto, TLS, auth, storage) before adding it.
- **SHOULD** prefer platform APIs over third-party libraries for security primitives.
  Keychain, `EncryptedSharedPreferences`, `CredentialManager`, and
  `AuthenticationServices` are all platform-maintained; a wrapper library adds
  attack surface for no gain.
- **SHOULD** avoid adding a dependency that requires a known-vulnerable transitive.

---

## 7. Security checklist for pull requests

Copy this into your PR description. If a box does not apply, say why rather than
deleting the line — reviewers need to know it was considered.

### Applies to every PR

- [ ] `pre-commit run --all-files` passes (gitleaks + detect-secrets clean).
- [ ] No secrets, signing material, `gradle.properties` with pins, or Firebase config
      in the diff.
- [ ] No new dependency, or the dependency's transitive tree was reviewed and is clean
      in the OWASP report.
- [ ] No `print`, `println`, `NSLog`, or `os_log` call emits a value that could be a
      token, balance, address, OTP, or credential.
- [ ] Any new sensitive header was added to `LogRedactor` on **both** platforms.
- [ ] Any new secret is stored via `KeychainService` / `EncryptedSharedPreferences`,
      not `UserDefaults` / plain `SharedPreferences`.
- [ ] Sign-out deletes the new secret.
- [ ] New untrusted input is allowlist-validated at the boundary, with a length bound.
- [ ] No `WebView`, JS bridge, raw SQL, or shell execution was introduced.
- [ ] Error paths do not include secret material, and platform errors are mapped to a
      closed enum rather than string-matched.

### If the PR touches authentication, keys, or sessions (additional)

- [ ] Passkeys: `public_key` is the COSE key extracted from `authData`, not the raw
      attestation object.
- [ ] Both platforms use the same request builder, and the builder is on the
      production path (not test-only).
- [ ] Challenges and nonces come from a CSPRNG and match the size in
      `shared/api-contract.md`; a test asserts the length.
- [ ] `user.id` is an opaque, non-PII handle within the WebAuthn 64-byte cap, and is
      not a display name or email.
- [ ] Relying party ID is unchanged on both platforms and matches `assetlinks.json` /
      the AASA.
- [ ] 401 still clears the token; the refresh single-flight guard is intact.
- [ ] Any new secret is cleared on sign-out and has a test covering that.
- [ ] Any local-auth change still requires strong biometrics or device credential.
- [ ] Session re-lock on background is still enforced.
- [ ] PIN handling, if touched, uses a salted KDF with attempt limiting — and the PR
      says so explicitly if it does not yet.

### If the PR touches networking or transport (additional)

- [ ] Pins fail closed; a missing or unparseable pin set does not degrade to
      "unpinned".
- [ ] The SPKI is computed by a real ASN.1 parser, with a fallback that fails loudly
      rather than hashing raw key bytes.
- [ ] The pin check is scoped to the expected host.
- [ ] `check_tls_pinning.py` covers any new signable or distributable configuration.
- [ ] Any pin change follows [cert-pin-rotation-runbook.md](cert-pin-rotation-runbook.md)
      and keeps a backup pin live.
- [ ] `X-Nonce` and timestamp are present on every mutating request; the WebSocket
      token is in a header, not a query string.
- [ ] No base URL, host, or pin is hardcoded where build config or a CI secret belongs.
- [ ] If request signing changed: method, path, body hash, nonce, and timestamp are
      all bound; the signing key is distinct from the bearer token; it is documented in
      `shared/api-contract.md` and implemented on both clients or neither.

### If the PR touches CI or release (additional)

- [ ] Every third-party action is pinned to a full commit SHA, especially in workflows
      with access to signing or App Store Connect secrets.
- [ ] Top-level `permissions: contents: read`; job permissions are the minimum needed.
- [ ] Secrets come from environment secrets and are not passed to steps that do not
      need them.
- [ ] Security checks fail closed — a skipped or unreachable check is not a pass.
- [ ] No duplicate security job that contradicts the documented rate-limit rationale.

### Reviewer sign-off

- [ ] Every "MUST" above is either satisfied or explicitly waived in this description.
- [ ] Any "SHOULD" deviation has a stated reason.
- [ ] PARITY.md is updated if user-facing behaviour changed on one platform only.
- [ ] Anything that looks like a vulnerability found along the way was reported
      privately to **security@ethos-protocol.app**, not in this PR.

---

## 8. Known gaps

These are documented here so contributors do not mistake them for endorsed practice
while the doc is being rolled out. They are **not** a substitute for a fix, and they
are not a disclosure list — if you find something new, report it per
[SECURITY.md](../SECURITY.md).

| Area | Gap | Direction |
|------|-----|-----------|

| Clipboard | `SensitiveClipboard` exists but several copy paths bypass it | §2.5 |
| iOS Keychain | Access group declared in entitlements but not set on `SecItem` calls, so widget reads may fail | §2.3 |
| Nonce generation | iOS uses `UInt8.random`; the contract mandates `CryptoKit.SymmetricKey` | §3.3 |
| `X-Request-Signature` | Not in the shared contract; Android-only; signs too little and keys with the bearer token | §3.5 |
| `user.id` | Derived from the username on both platforms rather than an opaque handle | §3.1 |
| Request signature / redaction | Redaction list is hand-maintained and does not cover every contract header | §4.1 |

The pending-action store (`pending_actions.db`) is **no longer** a known gap. It is
encrypted at rest with SQLCipher, using a passphrase held in the Android Keystore
(via `EncryptedSharedPreferences` / the existing `MasterKey`), and the existing
plaintext database is migrated on first launch. `android:allowBackup="false"` plus
`res/xml/backup_rules.xml` continue to exclude the database from backups. See §2.2
and §5.4.

---

## 9. References

- [SECURITY.md](../SECURITY.md) — vulnerability reporting and disclosure policy
- [shared/api-contract.md](../shared/api-contract.md) — the normative API contract,
  including the logging redaction policy and header definitions
- [mobile-passkey-flow.md](mobile-passkey-flow.md) — end-to-end passkey flow
- [cert-pin-rotation-runbook.md](cert-pin-rotation-runbook.md) — zero-downtime pin
  rotation
- [contributor-onboarding.md](contributor-onboarding.md) — pre-commit hook setup
- [docs/adr/](adr/) — architecture decision records, including offline-first and the
  Android passkey decision
- [W3C WebAuthn](https://www.w3.org/TR/webauthn/) — RFC 9052 `COSE_Key` format
- [RFC 9116](https://www.rfc-editor.org/rfc/rfc9116) — `security.txt`

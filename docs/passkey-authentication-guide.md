# Passkey Authentication Guide

This guide documents the passkey (WebAuthn) flow used by the Ethos mobile clients. It is written from the client implementation and the shared API contract; server-side verification details must be confirmed against the backend implementation.

## Flow at a Glance

Passkeys use a public/private key pair. The authenticator keeps the private key and the app sends a signed assertion, not the private key, to the API. The API issues a session token after registration or successful assertion verification.

### Registration

1. The app requests a challenge from `POST /auth/challenge`.
2. The app builds a WebAuthn `create` request for relying party `ethos-protocol.app` and starts the platform ceremony.
3. The OS or credential provider creates a credential after user verification. The response contains a credential ID, client data, and attestation object.
4. The client sends `credential_id`, `public_key`, and `client_data_json` to `POST /auth/register`.
5. On success, the API returns an `AuthToken` directly. Registration does not need a second assertion ceremony.

### Authentication

1. The app requests a fresh challenge from `POST /auth/challenge`.
2. The app requests a WebAuthn `get` assertion for `ethos-protocol.app`, requiring user verification.
3. The platform authenticator signs the assertion data with the credential's private key after the user verifies.
4. The client sends `credential_id`, `client_data_json`, and `signature` to `POST /auth/verify`.
5. On success, the API returns an `AuthToken`, which the client stores through its token provider.

Challenges are server-issued and are not interchangeable between ceremonies. Do not cache or reuse a challenge after a failed, cancelled, or completed attempt; start again with a new challenge.

## Platform Implementations

### iOS

Implementation: [PasskeyService.swift](../ios/EthosProtocol/Sources/Services/PasskeyService.swift)

- Uses `AuthenticationServices` and `ASAuthorizationPlatformPublicKeyCredentialProvider` with RP ID `ethos-protocol.app`.
- Decodes the challenge from base64url before passing its bytes to the registration or assertion request.
- Registration parses the CBOR attestation object's `authData` and extracts the COSE public key. The API's `public_key` field is the base64url-encoded COSE key, not the full attestation object.
- Authentication sends the credential ID, raw client data JSON, and signature as base64url strings.
- On iOS 17.4 and later, registration excludes credential IDs returned by the challenge endpoint to help prevent duplicate credentials. This option is unavailable on earlier supported iOS versions.
- After `/auth/register` succeeds, the credential ID is persisted to Keychain immediately. Preserve that ordering; it supports the app's local vault-to-credential association.
- The app has an Associated Domains entitlement for `webcredentials:ethos-protocol.app`. Passkey availability also depends on the matching `webcredentials` entry in the domain's Apple App Site Association file.
- iOS errors are mapped to actionable cases such as cancellation, a non-interactive request, an existing credential, and missing biometric/passcode setup.

The app's minimum deployment target is iOS 17. See [EthosProtocol.entitlements](../ios/EthosProtocol/EthosProtocol/EthosProtocol.entitlements) and [Package.swift](../ios/EthosProtocol/Package.swift).

### Android

Implementation: [PasskeyService.kt](../android/app/src/main/java/com/ethosprotocol/services/PasskeyService.kt), with request construction in [PasskeyRequestBuilder.kt](../android/app/src/main/java/com/ethosprotocol/services/PasskeyRequestBuilder.kt).

- Uses AndroidX Credential Manager with `CreatePublicKeyCredentialRequest` and `GetPublicKeyCredentialOption`; the factory is injected to keep service tests independent of a device.
- The registration request sets the RP ID, ES256 (`-7`), a platform authenticator, a discoverable credential, and required user verification. The user handle is the base64url-encoded username.
- The assertion request sets the RP ID and `userVerification: required`.
- Both flows check for missing biometric or screen-lock enrollment before opening Credential Manager. Device PIN/passcode can satisfy user verification; biometrics are not the only supported method.
- Credential Manager errors are mapped to user-facing messages, including cancellation, provider setup, interrupted flows, and no matching credential.
- The Android app's minimum SDK is API 28 (Android 9). See [ADR-0003](adr/adr-0003-credentialmanager-android-passkeys.md).

### Current Android Registration Gaps

The following code paths currently differ from the documented API contract and iOS behavior. Resolve and test these before relying on cross-platform registration parity:

- `extractCosePublicKey` in `PasskeyService.kt` base64url-decodes the complete `attestationObject`, then base64-encodes those same bytes. It does not parse `authData` or extract the COSE key, and its output uses standard Base64 rather than base64url. The API contract requires `public_key` to be the base64url-encoded COSE key; iOS does this extraction.
- Android's `PasskeyRequestBuilder.registrationRequestJson` does not include `excludeCredentials`, although `/auth/challenge` returns `existing_credential_ids` and the iOS implementation uses them on supported OS versions.

Do not copy the Android helper's current `public_key` behavior as the wire-format example. See [PasskeyRegisterRequest](../shared/api-contract.md) for the intended contract.

## API Contract

The API base URL is configurable; the documented production base is `https://api.ethos-protocol.app/v1`. The relevant routes are declared in [ApiClient.kt](../android/app/src/main/java/com/ethosprotocol/api/ApiClient.kt) and [APIClient.swift](../ios/EthosProtocol/Sources/Services/APIClient.swift).

| Route | Purpose | Successful response |
| --- | --- | --- |
| `POST /auth/challenge` | Obtain a challenge; may include existing credential IDs | `AuthChallenge` |
| `POST /auth/register` | Register a credential | `AuthToken` |
| `POST /auth/verify` | Verify an assertion | `AuthToken` |

The shared registration request is:

```json
{
  "credential_id": "base64url",
  "public_key": "base64url COSE_Key",
  "client_data_json": "base64url"
}
```

Authentication sends `credential_id`, `client_data_json`, and `signature`, each base64url-encoded. Refer to [shared/api-contract.md](../shared/api-contract.md) for the current request and response contract. Do not infer backend validation behavior from the client examples alone.

The app also supports account recovery and adding credentials to an existing account. Those are distinct authorization paths: recovery requires recovery proof/token, while adding a credential to a signed-in account uses the existing session. See the `recoverAccount`, `linkAdditionalPasskey`, and `addPasskey` methods in the platform services and their API client methods before changing those flows.

## Security Considerations

- Obtain a fresh, unpredictable challenge from the server for every ceremony. The server must bind it to the intended account/action, enforce expiry and one-time use, and reject replay.
- Verify the challenge, WebAuthn ceremony type, origin, RP ID hash, credential/account association, user-presence and user-verification flags, and assertion signature server-side. Follow the backend's WebAuthn implementation and conformance tests; mobile code is not a substitute for server validation.
- Keep the RP ID consistent across platforms and configure its domain association correctly. A mismatch prevents the authenticator from using the credential for this relying party.
- Never export, log, or transmit private key material. The app receives public credential data and signed assertions from the platform API.
- Treat attestation as optional provenance data, not proof that every passkey is hardware-backed. Passkeys may be synchronized by a user's platform provider, and platform behavior varies.
- Keep session tokens in the existing secure token-storage path. Do not put tokens, challenges, credential IDs, public keys, signatures, client data, or raw attestation responses in logs or analytics.
- Registration diagnostics are in-memory and local only. They record a failure reason, authenticator attachment, and possibly attestation format; they must not be expanded to include credential or cryptographic response data. See [SECURITY.md](../SECURITY.md).
- Rate-limit challenge and verification endpoints, return generic authentication failures where appropriate, and avoid revealing whether an account or credential exists.
- Recovery and additional-passkey enrollment must require proof of account ownership. A fresh passkey ceremony alone must not authorize attaching a new credential to an existing account.

## Troubleshooting

| Symptom | Checks |
| --- | --- |
| Passkey option is missing on iOS | Confirm the app has the `webcredentials:ethos-protocol.app` entitlement, the signed provisioning profile includes Associated Domains, and the site's Apple App Site Association file has a matching `webcredentials` entry. Also confirm a passkey provider is configured on the device. |
| Android reports no passkey provider | Check Credential Manager/provider availability and setup on the device. The provider may need to be installed, enabled, or updated. The app targets API 28+, but a supported OS version alone does not guarantee a configured credential provider. |
| User verification cannot start | Set up a device passcode/PIN or an available biometric and retry. Android checks enrollment before prompting; iOS maps underlying LocalAuthentication enrollment errors to guidance. |
| User cancels or the request is interrupted | Treat cancellation as user intent. For interruption/backgrounding, return the app to the foreground and restart with a newly fetched challenge. |
| No credential is found | Confirm the user is signing into the expected account and the passkey exists with a provider available to this device. For a lost device, use the account recovery flow rather than trying to register an unrelated credential. |
| Registration fails before reaching the API | Inspect the platform error mapping and local diagnostic entry. Check challenge validity/encoding and platform credential-provider setup; never add the challenge or response payload to logs while debugging. |
| API rejects registration | Compare `credential_id`, `public_key`, and `client_data_json` encodings with the API contract. In particular, check the Android COSE extraction gap above; confirm the challenge is fresh and the RP/domain configuration matches. |
| API rejects an assertion | Confirm the client is posting the credential ID, client data JSON, and signature from the same assertion, with base64url encoding. Check server-side challenge expiry/reuse, RP ID/origin validation, credential lookup, signature verification, and device clock only if the backend contract depends on it. |
| Credential already exists during registration | On iOS 17.4+, check the challenge's existing credential IDs and the excluded-credential setup. Android currently does not pass `excludeCredentials`; a duplicate may therefore fail later or be accepted depending on provider/backend behavior. |

When diagnosing a production failure, record the platform/OS version, app version, route, and sanitized error code. Do not collect or attach raw WebAuthn responses, tokens, challenges, credential IDs, signatures, or key material to a support report.

## Code and Test References

- Android orchestration: [PasskeyService.kt](../android/app/src/main/java/com/ethosprotocol/services/PasskeyService.kt)
- Android request builder: [PasskeyRequestBuilder.kt](../android/app/src/main/java/com/ethosprotocol/services/PasskeyRequestBuilder.kt)
- Android tests: [PasskeyServiceTest.kt](../android/app/src/test/java/com/ethosprotocol/PasskeyServiceTest.kt), [PasskeyRequestBuilderTest.kt](../android/app/src/test/java/com/ethosprotocol/PasskeyRequestBuilderTest.kt)
- iOS orchestration and CBOR/COSE parsing: [PasskeyService.swift](../ios/EthosProtocol/Sources/Services/PasskeyService.swift)
- iOS associated-domain configuration: [EthosProtocol.entitlements](../ios/EthosProtocol/EthosProtocol/EthosProtocol.entitlements)
- Shared API contract: [api-contract.md](../shared/api-contract.md)
- Android architecture decision: [ADR-0003](adr/adr-0003-credentialmanager-android-passkeys.md)
- Diagnostic-data policy: [SECURITY.md](../SECURITY.md)

External references: [Apple AuthenticationServices](https://developer.apple.com/documentation/authenticationservices), [Android Credential Manager](https://developer.android.com/training/sign-in/credential-manager), and [WebAuthn](https://www.w3.org/TR/webauthn/).

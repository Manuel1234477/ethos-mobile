# Ethos-Protocol Mobile API Contract

Base URL: `https://api.ethos-protocol.app/v1` (configurable via env)

## Authentication
All authenticated endpoints require `Authorization: Bearer <jwt>` header.
JWT is obtained via Passkey (WebAuthn) challenge/response flow.

## Anti-Replay Protection (task #121)

Every **mutating** request (POST / DELETE — check-in, deposit, withdraw, 2FA operations, push
registration) must include two additional headers to prevent replay attacks. A captured request
replayed by a network attacker must be rejected by the server.

### Headers

| Header | Format | Description |
|--------|--------|-------------|
| `X-Nonce` | 32-byte random value, hex-encoded (64 chars) | Per-request unique value. The server rejects any request whose nonce has been seen before (within the token's validity window). |
| `X-Timestamp` | Unix epoch seconds as a decimal string | UTC time the request was created. The server rejects requests where `|server_time − request_time| > 300` seconds (5-minute window). |

### Rules
- Both headers are **required** on all mutating endpoints (POST and DELETE).
- GET requests **do not** need these headers (they are read-only and idempotent).
- The nonce must be generated fresh for **every** request — reusing a nonce from a previous
  request will cause the server to reject it even if the timestamp is within window.
- Clients use `SecRandomCopyBytes` (or `CryptoKit.SymmetricKey(size: .bits256)`) on iOS and
  `SecureRandom` on Android to generate the nonce bytes — `Math.random()`, `UUID`, or
  `UInt8.random(in:)` are not acceptable.

### Example mutating request headers
```
POST /vaults/{id}/checkin HTTP/1.1
Authorization: Bearer <jwt>
Content-Type: application/json
X-Nonce: a3f1c2d4e5b6a7f8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2
X-Timestamp: 1753548558
```

### Server-side validation (backend contract)
The server must:
1. Parse `X-Timestamp` and reject if `|server_utc − timestamp| > 300`.
2. Check the nonce has not been seen before (e.g., store in Redis with TTL = token validity + 300 s).
3. Reject (HTTP 400 or 409) if either check fails, with a JSON body `{"error": "replay_detected"}`.

## Endpoints

### Auth
| Method | Path | Description |
|--------|------|-------------|
| POST | `/auth/challenge` | Get WebAuthn challenge |
| POST | `/auth/verify` | Verify passkey assertion, returns `AuthToken` |
| POST | `/auth/register` | Register new passkey credential, returns `AuthToken` directly (#2) — no separate `/auth/verify` call is needed right after registering |
| POST | `/auth/refresh` | Proactively refresh the current session before it expires, returns a new `AuthToken` (#3) |
| POST | `/auth/recover/link` | Link a new passkey to an existing account, once identity is proven via email/backup code ("lost your device" recovery) |
| GET | `/auth/sessions` | List active sessions (devices) holding a valid JWT for the calling account — see §Session/Device List (#208) |
| DELETE | `/auth/sessions/{id}` | Revoke a single session by id ("Sign out this device") — see §Session/Device List (#208) |
| DELETE | `/auth/sessions` | Revoke every session except the caller's current one ("Sign out all other devices") — see §Session/Device List (#208) |

### Vaults
| Method | Path | Description |
|--------|------|-------------|
| GET | `/vaults` | List owner's vaults (supports pagination — see §Pagination) |
| POST | `/vaults` | Create vault |
| GET | `/vaults/{id}` | Get vault detail |
| POST | `/vaults/{id}/checkin` | Check in (extend TTL) |
| POST | `/vaults/{id}/deposit` | Deposit funds |
| POST | `/vaults/{id}/withdraw` | Withdraw funds |
| POST | `/vaults/{id}/beneficiary` | Update vault beneficiary (owner-only) |
| GET | `/vaults/{id}/ttl` | Get TTL remaining |
| POST | `/vaults/{id}/accept` | Beneficiary accepts vault (token required — see §Beneficiary Acceptance) |

#### List Pagination (`GET /vaults`)

Cursor-based. The response body stays a bare JSON array of `Vault` (unchanged,
backward-compatible with clients that don't send pagination params) — the cursor
for the next page is returned via a response header instead of wrapping the body.

Request query parameters (both optional):

| Param | Description |
|-------|-------------|
| `cursor` | Opaque cursor returned by the previous page's `X-Next-Cursor` response header. Omit to request the first page. |
| `limit` | Max vaults to return in this page. Server may cap this. Client default: 50. |

Response headers:

| Header | Description |
|--------|-------------|
| `X-Next-Cursor` | Opaque cursor for the next page. Absent (or empty) when this is the last page. |

Cursors are opaque — clients must not parse or construct them, only round-trip
the value returned by `X-Next-Cursor` back as the `cursor` query param.

### Notifications
| Method | Path | Description |
|--------|------|-------------|
| POST | `/notifications/register` | Register push token |
| DELETE | `/notifications/register` | Unregister push token |

## WebSocket
`wss://api.ethos-protocol.app/v1/ws?vault_id={id}` — real-time vault events for
a single vault. Requires the same `Authorization: Bearer <jwt>` header as REST
requests, sent on the handshake request.

Server → client messages are JSON text frames:

```json
{ "type": "vault_updated", "vault": { /* full Vault object, see below */ } }
```

`type` is open-ended (clients should ignore unrecognized values instead of
erroring, to allow adding new event types without breaking older clients).

Clients are expected to reconnect with backoff on an unexpected drop, and to
fall back to their existing polling mechanism (periodic `GET /vaults/{id}`) if
the socket can't be established after a few attempts — this stream is a
latency optimization, not the source of truth.

Authentication: pass the JWT as a query parameter or `Authorization` header on the initial
handshake request. The server closes the connection with code 4401 if authentication fails.

### WebSocket Message Schema (#110)

All messages are JSON objects with a `type` discriminator field.

#### Server → Client messages

**`vault_updated`** — emitted whenever server-side vault state changes (TTL refresh, check-in,
deposit, withdrawal, beneficiary change, status transition).
```json
{
  "type": "vault_updated",
  "vault_id": "string",
  "vault": { /* full Vault object — see §Models */ }
}
```

**`vault_expired`** — emitted when a vault transitions to `expired` status.
```json
{
  "type": "vault_expired",
  "vault_id": "string",
  "expired_at": "ISO8601"
}
```

**`vault_released`** — emitted when funds are released to the beneficiary.
```json
{
  "type": "vault_released",
  "vault_id": "string",
  "released_at": "ISO8601",
  "amount": 0
}
```

**`ping`** — server keepalive, no action required from clients (clients may reply with `pong`).
```json
{ "type": "ping" }
```

**`subscribed`** — server acknowledgement of a client `subscribe` request.
```json
{
  "type": "subscribed",
  "vault_ids": ["string", "..."]
}
```

**`error`** — server signals a recoverable error (e.g. invalid vault_id on connect).
```json
{
  "type": "error",
  "code": "string",
  "message": "string"
}
```

#### Client → Server messages

**`pong`** — reply to server `ping` (optional but recommended to maintain connection).
```json
{ "type": "pong" }
```

**`subscribe`** — sent immediately after connect to subscribe to additional vault IDs on the same connection, supporting N vaults over a single WebSocket rather than N connections.
```json
{
  "type": "subscribe",
  "vault_ids": ["string", "..."]
}
```
The server routes subsequent `vault_updated`/`vault_expired`/`vault_released` events for all listed vault IDs over this connection. Clients connecting to a single vault via the URL query parameter need not send `subscribe`. The server acknowledges with a `subscribed` message.

#### Connection lifecycle
- Reconnect with exponential backoff (base 1 s, max 30 s) on any non-4401 close — see
  §Backoff/Jitter Formula (#254) for the exact formula both clients implement.
- On `vault_updated`, merge the embedded `vault` object into the local vault list in-place
  (do not full-reload from REST).
- On `vault_expired` / `vault_released`, trigger a local notification if the app is backgrounded.
- Client SHOULD send a `pong` text frame in response to every `ping` to signal liveness to the server.
- Client SHOULD also send periodic `ping` frames (interval: 30 s) to detect silently-dead TCP connections before the OS closes the socket. If the send fails, the client MUST treat it as a connection drop and reconnect with backoff.
- If the server closes the socket with code 4401, do NOT reconnect — re-authenticate first.

### Backoff/Jitter Formula (#254)

Both platforms use full-jitter exponential backoff for WebSocket reconnects:

```
base_delay = 1 s
max_delay  = 30 s
capped     = min(max_delay, base_delay * 2^attempt)
actual     = random_uniform(0, capped)
```

- `attempt` is 0-based and resets to 0 on the first message successfully received from a new connection.
- Full jitter (not additive) spreads reconnect storms across the full `[0, capped)` window instead of clustering near `capped`.
- iOS: `ReconnectBackoff.delay(forAttempt:)` in `VaultEventSocket.swift`.
- Android: `ReconnectBackoff.delayForAttempt()` in `VaultEventSocket.kt`.

---

## Session/Device List (#208)

Visibility and control over which devices currently hold a valid JWT for the account,
addressing the gap where token storage was entirely server-side and invisible to the user
(e.g. no way to remotely sign out a lost phone).

### `GET /auth/sessions`

Response: `200` with a JSON array of `Session` (see §Models). The session for the device
making the request has `is_current: true`. Ordered most-recently-active first.

### `DELETE /auth/sessions/{id}`

Revokes the session identified by `id` — the corresponding JWT is invalidated server-side.
Revoking a session other than the caller's own signs that device out ("Sign out this
device"); revoking the caller's own current session is equivalent to a normal sign-out.
Response: `204 No Content` on success; `404` if `id` does not belong to the caller's account.

### `DELETE /auth/sessions`

Revokes every session for the account **except** the one making the request ("Sign out all
other devices"). Response: `204 No Content`.

### Platform requirement: biometric gate

Both `DELETE /auth/sessions/{id}` and `DELETE /auth/sessions` are destructive, remote-facing
actions and must be gated behind a biometric (or device passcode) prompt client-side before
the request is sent — the same `BiometricService`/`BiometricHelper` used for withdrawals and
2FA disable.

#### Reconciling a poll/push disagreement (#223, #606)

Every `Vault` carries a monotonically increasing server-assigned `version` (see
§Models → Vault). A client can therefore tell whether a `vault_updated` push or a
concurrent `GET /vaults/{id}` poll response reflects newer server state, even when
the two arrive out of order. The rule both clients implement is
**highest-version-wins**: an update (from either source) is applied only if its
`version` is strictly greater than the `version` already applied for that vault;
otherwise it is discarded.

This closes the out-of-order race where a `GET /vaults/{id}` started before a
`vault_updated` push, but completed after it, would otherwise overwrite the newer
pushed state with older data. Concretely:
- A poll response and a push are merged through the exact same code path
  (iOS: `VaultStore.applyUpdate`; Android: `VaultViewModel.updateVaultInPlace`),
  which compares `version` before writing.
- An update whose `version` is less than or equal to the last applied `version`
  for that vault is ignored — it never overwrites in-memory state.
- A vault not yet present locally is always accepted (nothing to compare against).
- This applies to every field on `Vault`, including `ttl_remaining` — see
  `TTLCountdown`/`TtlCountdown`'s reconciliation logic (#221), which likewise
  only accepts a fresh value whose `version` is newer.
- If a server response omits `version` (older server), clients fall back to
  last-applied-wins for backward compatibility.

---

## Beneficiary Acceptance (#109)

**Decision: token is required.**

`POST /vaults/{id}/accept` requires a `token` in the request body. The token is embedded in the
acceptance deep-link URL as a query parameter:
`https://ethos-protocol.app/vaults/{id}/accept?token={token}`

**Rationale:** the token is a server-issued one-time secret that proves the request originated
from the specific acceptance link the owner shared, not from an arbitrary authenticated session.
Without it, any authenticated user could call the endpoint for any vault they know the ID of.

**Request body:**
```json
{
  "vault_id": "string",
  "token": "string"
}
```
Response: `204 No Content` on success; `401` if token is invalid/expired; `404` if vault not found.

**Platform alignment:**
- iOS: already implemented in `APIClient.acceptBeneficiary(vaultID:token:)` — no change needed.
- Android: updated in this change (#109) — `ApiClient.acceptBeneficiary(vaultId, token)` now
  forwards the token. The token is extracted from the `/vaults/{id}/accept` HTTPS deep-link URL
  in `MainActivity.extractBeneficiaryAccept()` and threaded through navigation to
  `AcceptanceViewModel.accept(vaultId, token)`.

---

## Request Signing (`X-Request-Signature`, #607)

Android attaches an `X-Request-Signature` header to mutating requests. The MAC is
computed over a canonical string that binds the signature to the specific request,
so a captured signature cannot be replayed against another endpoint:

```
canonical = METHOD + "\n"
          + PATH + "\n"
          + hex(SHA256(body)) + "\n"
          + X-Nonce + "\n"
          + X-Timestamp
signature = hex(HMAC-SHA256(signing_key, canonical))
```

- `METHOD` is the uppercase HTTP method (e.g. `POST`).
- `PATH` is the request path **without** the query string (e.g. `/vaults/{id}/checkin`).
- `body` is the exact request body bytes; for bodyless requests use the SHA-256 of the
  empty string.
- `X-Nonce` / `X-Timestamp` are the same values sent in the anti-replay headers (see
  §Anti-Replay Protection), so a signature is only valid within the same replay window.

**Signing key (#607):** the HMAC key is **not** the bearer token. It is a separate
server-issued session signing secret, delivered alongside the `AuthToken` and never sent
on the wire. The bearer token is already transmitted on every request, so keying the MAC
with it would add no secret a network observer lacks. If no signing secret is available,
the client omits the header entirely rather than falling back to the bearer token.

---

## Logging Redaction Policy (#111)

The following data **must never appear in logs, logcat, os_log, or any other diagnostic
output, in any build configuration (debug or release) on either platform:**

1. `Authorization: Bearer <jwt>` headers or raw JWT strings
2. 2FA secrets, OTPs, provisioning URIs, or TOTP seeds
3. Vault balances, deposit/withdrawal amounts
4. Beneficiary wallet addresses or owner wallet addresses
5. Acceptance tokens (the one-time token from `/accept?token=…`)
6. Full request/response bodies for any authenticated endpoint

**Permitted to log (debug builds only, never release):**
- HTTP method, path, and status code (no query strings that carry tokens)
- Non-sensitive model identifiers (vault ID, status enum value)
- Timing / latency metrics

**Platform enforcement:**

*Android (`ApiClient.kt`):*
Ktor's `Logging` plugin is configured with
`level = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.NONE`.
`LogLevel.INFO` only logs method + URL + status, not request/response bodies — bearer tokens,
2FA secrets, and vault balances are therefore never written to logcat in any build.
See `ApiClient.kt` lines 39–43 for the enforcing configuration.

*iOS (`APIClient.swift`):*
`URLSession` is used directly with no logging plugin. No request/response body logging
is present anywhere in `APIClient.swift`. Any future addition of a logging interceptor
**must** apply the same `DEBUG`-only guard and `INFO`-level-only (method+URL+status)
restriction described above for Android. See `APIClient.swift` — MARK: Logging Redaction Audit.

---

## Pagination (#112)

**Contract: cursor-based pagination for `GET /vaults`.**

Cursor pagination is chosen over offset/limit because vault lists can change between requests
(vaults can expire, be created, or change status), making offset-based pages unstable.

### Request
```
GET /vaults?limit={n}&after={cursor}
```
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `limit` | integer | no | 20 | Max vaults per page (1–100) |
| `after` | string | no | — | Opaque cursor from previous response's `next_cursor` |

### Response
```json
{
  "vaults": [ /* array of Vault objects */ ],
  "next_cursor": "string | null",
  "has_more": true
}
```
- `next_cursor` is `null` (and `has_more` is `false`) when the caller has received all vaults.
- Cursors are opaque server-issued strings; clients must not construct or modify them.
- A request without `after` always returns the first page.

### Platform implementation
- Both clients expose a `listVaults(limit:after:)` method that returns a `VaultPage` result
  containing the vault array plus the cursor for the next call.
- Callers accumulate pages by calling `listVaults(after: page.nextCursor)` until
  `page.hasMore == false`.
- The existing `listVaults()` (no-arg) overload fetches the first page with default limit and
  returns only the vault array for backward compatibility with callers that don't need pagination.

---

## Models

### Vault
```json
{
  "id": "string",
  "owner": "string",
  "beneficiary": "string",
  "balance": 0,
  "check_in_interval": 0,
  "last_check_in": "ISO8601",
  "ttl_remaining": 0,
  "status": "active|expired|released|paused",
  "asset_code": "XLM",
  "asset_issuer": "string | null",
  "version": 0
}
```
`version` (#606) is a server-assigned, monotonically increasing integer bumped on
every mutation of the vault row. Clients use it to discard stale poll/push
responses — see §Reconciling a poll/push disagreement (#223, #606). It is optional
in responses for backward compatibility; when absent, clients fall back to
last-applied-wins.
`asset_code`/`asset_issuer` (#222) identify which Stellar asset `balance` is
denominated in, the same way `ACCEPTED_ASSETS` entries do elsewhere in this API:
`asset_code` alone (native XLM) or `asset_code` + `asset_issuer` (any other
asset). Both fields are optional and absent responses default to native XLM
(`asset_code: "XLM"`, `asset_issuer: null`) — every vault today holds XLM, so
this is purely preparatory: it lets a future non-XLM vault be represented
without a breaking schema change, and both clients already decode it
defensively for exactly that reason.

### VaultPage (#112 — paginated list response)
```json
{
  "vaults": [ /* Vault objects */ ],
  "next_cursor": "string | null",
  "has_more": false
}
```

### AuthChallenge
```json
{
  "challenge": "base64url",
  "expires_at": "ISO8601",
  "existing_credential_ids": ["base64url"]
}
```
`existing_credential_ids` lists the credential IDs already registered to the account this
challenge is for (empty for a brand-new account). Clients pass these back as
`excludeCredentials`/`excludedCredentials` on the registration request so the platform
authenticator refuses to create a second passkey for an account that already has one on
that device.

### AuthToken
```json
{ "token": "string", "expires_at": "ISO8601" }
```
Returned by `/auth/verify`, `/auth/register` (#2), and `/auth/refresh` (#3). Clients schedule a
proactive refresh against `expires_at`, rather than waiting to be rejected with a 401.

### PasskeyRegisterRequest (#1)
```json
{ "credential_id": "base64url", "public_key": "base64url", "client_data_json": "base64url" }
```
`public_key` is the WebAuthn COSE_Key (RFC 9052) extracted from the attestation object's
`authData`, base64url-encoded — **not** the raw CBOR attestation object. Both iOS
(`PasskeyService.extractCOSEPublicKey`) and Android (`PasskeyService.kt`'s
`extractCosePublicKey`/`cosePublicKeyBytes`) extract and send byte-identical COSE_Key data.

### AuthRefreshRequest (#3)
No request body. Requires the current (possibly near-expiry, not-yet-expired) `Authorization:
Bearer <jwt>` header. Response: `AuthToken`. `401` if the current token is no longer valid — the
client falls back to its normal delete-and-reauth behavior in that case.

**Refresh margin (#209):** both clients treat a token as due for proactive refresh once it is
within **60 seconds** of `expires_at`. iOS (`AuthStore.refreshLeadTime`) schedules a one-shot
timer for `expires_at - 60s` the moment a token is stored, independent of whether a request is
in flight. Android (`TokenProvider.isNearExpiry`, default `threshold = 60s`) additionally runs a
periodic check (`AuthViewModel`'s scheduled-refresh loop, every 30s while signed in) so a token
is refreshed even if the app is foregrounded but idle, not just before the next API call.

### RecoverAccessLinkRequest
```json
{
  "email": "string",
  "backup_code": "string",
  "credential_id": "base64url",
  "public_key": "base64url",
  "client_data_json": "base64url"
}
```
`email`/`backup_code` prove ownership of the existing account; the server verifies them
before attaching the new passkey (`credential_id`/`public_key`/`client_data_json`, from a
normal WebAuthn registration ceremony against a `/auth/challenge` obtained for this
account) rather than issuing a session directly. Clients call `POST /auth/verify`
afterwards to authenticate with the newly linked passkey.

**Expiry (#211):** the same applies to Android's `RecoveryCompleteRequest`/`recovery_token`,
issued by `POST /auth/recovery/initiate` with a limited lifetime. If the recovery token/proof
has expired by the time the client completes the ceremony, the server responds `401` with a
human-readable message in the body: `{"error": "<message>"}` (e.g. `"Your recovery code has
expired. Please request a new one."`). Clients must surface this message as-is rather than the
generic "sign in again" copy normally shown for a `401`, and let the user request a fresh code
instead of leaving them on a dead-end error.

### BeneficiaryUpdateRequest
```json
{ "beneficiary": "string" }
```
Response: the updated `Vault` object (see above), reflecting the new `beneficiary` value.

### BeneficiaryAcceptRequest (#109)
```json
{ "vault_id": "string", "token": "string" }
```
Response: `204 No Content`.

### WebSocketMessage (#110)
See §WebSocket Message Schema above for the full discriminated-union schema.

### Session (#208)
```json
{
  "id": "string",
  "device_name": "string",
  "platform": "ios|android",
  "created_at": "ISO8601",
  "last_active_at": "ISO8601",
  "is_current": true
}
```
`device_name` is a human-readable label (e.g. "iPhone 15 Pro", "Pixel 8") the server derives
from the device that registered the session. `is_current` marks the session belonging to the
device making the `GET /auth/sessions` request.

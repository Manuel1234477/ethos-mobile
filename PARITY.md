# Feature Parity — iOS vs Android

This document tracks the implementation status of every user-facing feature across
the iOS and Android clients. Update it whenever a platform-specific change is made
(see [Contributing](#contributing)).

Last audited: 2026-08-31

---

## Status legend

| Symbol | Meaning |
|--------|---------|
| ✅ | Implemented and tested |
| ⚠️ | Partial — works but known gaps / untested edge cases |
| ❌ | Not implemented |
| 🚧 | In progress |

---

## Feature matrix

| Feature | iOS | Android | Notes |
|---------|-----|---------|-------|
| **Authentication** | | | |
| Passkey sign-in (WebAuthn) | ✅ | ✅ | iOS: ASAuthorizationPlatformPublicKeyCredentialProvider; Android: CredentialManager |
| Passkey registration | ✅ | ✅ | |
| Sign out | ✅ | ✅ | |
| **Vault management** | | | |
| List vaults | ✅ | ✅ | |
| Create vault | ✅ | ✅ | |
| Vault detail view | ✅ | ✅ | |
| Check-in | ✅ | ✅ | |
| Biometric confirmation for check-in | ✅ | ✅ | iOS: LocalAuthentication; Android: BiometricPrompt |
| Typed-confirmation dialog for destructive actions | ✅ | ✅ | Guardrail added ahead of any delete/archive endpoint (#220); iOS: `DestructiveConfirmationView`; Android: `DestructiveConfirmationDialog` — neither is wired to a real action yet |
| **Funds** | | | |
| Deposit | ✅ | ❌ | Android has no DepositScreen (#87) |
| Withdraw | ✅ | ❌ | Android has no WithdrawScreen (#87) |
| Balance display (formatted XLM) | ✅ | ✅ | `Vault.formattedBalance` now renders `assetCode` rather than a hardcoded "XLM" label (#222); Deposit/Withdraw screens on both platforms still hardcode "XLM" in their labels and validation copy — out of scope for #222, tracked below |
| **Beneficiary management** | | | |
| View beneficiary | ✅ | ✅ | |
| Update beneficiary | ✅ | ❌ | Android has no ManageBeneficiaryScreen (#87) |
| Beneficiary acceptance deep link | ✅ | ⚠️ | Android parses the deep link but does not call acceptBeneficiary() (#87/#109) |
| Beneficiary acceptance token forwarded | ✅ | ❌ | Android AcceptanceViewModel ignores the token param (#109) |
| **Stellar address validation** | | | |
| StrKey / CRC16-XModem checksum | ✅ | 🚧 | Android StellarAddress utility added in #113 |
| Validated in create-vault flow | ✅ | 🚧 | Android CreateVaultDialog wired in #113 |
| **Two-factor authentication** | | | |
| Enable 2FA (TOTP / SMS / Email) | ✅ | ✅ | |
| Disable 2FA | ✅ | ✅ | |
| Verify 2FA | ✅ | ✅ | |
| Correct copy: TOTP initial setup | ✅ | ✅ | Shows provisioning URI + secret |
| Correct copy: TOTP re-verify (no provisioning data) | ✅ | 🚧 | Fixed in #115; Android was showing "Scan URI" with no URI |
| Correct copy: SMS — "code sent to phone" | ✅ | ✅ | |
| Correct copy: Email — "code sent to email" | ✅ | ✅ | |
| OTP cooldown survives process death | ❌ | ✅ | Android persists the failure count and an absolute cooldown deadline in `SavedStateHandle` (#172); iOS `OTPRateLimiter` is still in-memory only |
| **Push notifications** | | | |
| APNs / FCM device token registration | ✅ | ✅ | |
| TTL expiry warning notification | ✅ | ✅ | Bodies now include a truncated vault ID + TTL remaining instead of generic copy (#233); Android's server-pushed reminder includes the ID but not TTL — the FCM payload doesn't carry `ttl_remaining` today, unlike iOS's locally-scheduled reminders which already have it in hand |
| Check-in reminder (scaled lead time) | ✅ | ❌ | Android NotificationHelper sends a generic reminder, no lead-time scaling (no tracking issue yet) |
| Notification delivery analytics (scheduled/delivered/suppressed log) | ✅ | ✅ | Debug-only screen for support/QA triage (#235); iOS: `NotificationDebugView`; Android: `NotificationDebugScreen` |
| Push token registration retry/backoff on failure | ✅ | ✅ | Previously fire-and-forget on both platforms; now retries with backoff and persists a pending token to retry on next foreground (#234) |
| WebSocket/push duplicate notification dedup | ✅ | ✅ | A `vault_expired`/`vault_released` event applied via WebSocket suppresses a same-event push banner delivered shortly after (#232) |
| Actionable "Check In" notification action | ✅ | ❌ | Android does not set up a CHECK_IN notification action (no tracking issue yet) |
| **Offline support** | | | |
| Network connectivity monitor | ✅ | ✅ | |
| Offline read cache (SHA-256 keyed) | ✅ | ✅ | |
| Offline check-in queue + WorkManager retry | ✅ | ✅ | iOS: PendingCheckInStore + CheckInSyncTask; Android: PendingActionDao + WorkManager |
| Offline queue badge / notification | ✅ | ✅ | iOS: queuedCheckInCount in Stores; Android: BadgeService broadcasts queue updates | |
| **Widget** | | | |
| Home-screen vault TTL widget | ✅ | ✅ | iOS: WidgetKit TTLWidget; Android: VaultStatusWidget (Glance) |
| TTL-aware refresh policy | ✅ | ❌ | Android widget polls at a fixed interval; no urgency scaling (no tracking issue yet) |
| Widget urgency selection (which vault to surface) | ❌ | ❌ | Both platforms always show the first vault (no tracking issue yet) |
| **WebSocket / real-time** | | | |
| Live vault updates via WebSocket | ✅ | ✅ | iOS: VaultEventSocket.swift; Android: VaultEventSocket.kt, both wired into their vault store/ViewModel with reconnect backoff |
| **Background refresh** | | | |
| Background app refresh (TTL polling) | ✅ | ✅ | iOS: BGAppRefreshTask; Android: WorkManager |
| **Universal / deep links** | | | |
| Vault invitation link | ✅ | ✅ | |
| Beneficiary acceptance link | ✅ | ⚠️ | See beneficiary token gap above |
| Vault action links (check-in, withdraw, …) | ✅ | ✅ | |
| Deep-link input validation (path traversal, length) | ✅ | ✅ | |
| **iCloud / cross-device sync** | | | |
| iCloud KV vault ↔ credential sync | ✅ | ❌ | Android has no equivalent cloud sync |

---

## Known gaps (summary)

These are the divergences identified during the audit that led to issue #116.
Each gap has a tracking issue; fix it on the lagging platform and update this table.

| Gap | Platform missing feature | Tracking |
|-----|--------------------------|---------|
| Deposit / Withdraw screens | Android | #87 |
| Manage Beneficiary screen | Android | #87 |
| Beneficiary acceptance token forwarded in deep link | Android | #87 / #109 |
| TOTP re-verify copy ("Scan URI" shown without URI) | Android | #115 |
| Stellar address validation (StrKey + checksum) | Android | #113 / #71 |
| Check-in reminder lead-time scaling | Android | TBD |
| Actionable push notification action (CHECK_IN) | Android | TBD |
| Offline check-in queue | iOS | TBD |
| TTL-aware widget refresh policy | Android | TBD |
| Widget urgency / vault selection | Both | TBD |
| iCloud / cross-device sync | Android | TBD |
| Deposit/Withdraw amount labels and validation messages hardcode "XLM" instead of the vault's `assetCode` (iOS: DepositView/WithdrawView; Android: DepositScreen/WithdrawScreen, VaultViewModel.deposit/withdraw) | Both | #222 (audit only — every vault is XLM today, so this is a documented follow-up, not a current bug) |

---

## Contributing

When you open a PR that adds, changes, or removes a user-facing feature on
**either** platform, update the table above before requesting review:

1. Find the affected row(s).
2. Change the status symbol for the platform you modified.
3. Add a "Notes" entry if the change is partial or has known caveats.
4. If you close a gap listed in the "Known gaps" section, remove or update that row.

The PR template will prompt you to do this automatically.

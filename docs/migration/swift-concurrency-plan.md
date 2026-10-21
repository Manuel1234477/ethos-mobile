# Swift concurrency adoption plan

Tracks issue #500.

## Current state

- 38 Swift files use `async`.
- 25 files still use completion handlers or `DispatchQueue`. The largest are
  `Sources/App/AppDelegate.swift`, `Sources/Services/AppAttestService.swift`,
  `Sources/Services/CertificatePinning.swift`, `Sources/Services/NotificationService.swift`,
  `Sources/Services/SilentNotificationService.swift`, `Sources/Services/SpotlightService.swift`
  and `Sources/Services/VaultEventSocket.swift`.
- 64 places already use `@MainActor` or `actor`.

## Plan, in order

1. Services called only from `async` code: move `NotificationService` and `SpotlightService` to
   `async` functions first. Their callers already await.
2. `VaultEventSocket`: wrap the socket callbacks in an `AsyncStream`, keeping the current API for
   the callers that have not moved yet.
3. `AppDelegate` and `CertificatePinning` last. Both run at launch and in delegate callbacks, so
   they need the most review.
4. `Sendable`: add it to types that cross actor boundaries as each service moves.
5. Actor isolation: keep UI-facing types on `@MainActor`, as they already are.

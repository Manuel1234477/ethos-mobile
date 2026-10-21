# iOS Combine migration plan

Tracks issue #495.

## Where Combine is used today

Three files import Combine:

- `ios/EthosProtocol/Sources/Services/BiometricTimeoutIndicatorService.swift`
- `ios/EthosProtocol/Sources/Services/SessionLockService.swift`
- `ios/EthosProtocol/Sources/ViewModels/Stores.swift`

Most async work already uses `async`/`await` (see `docs/migration/swift-concurrency-plan.md`).

## Plan

1. Leave `Stores.swift` as is. It is the state layer for the view models, and it is the piece
   that would benefit most from `@Observable` once the deployment target allows it.
2. Keep `SessionLockService` and `BiometricTimeoutIndicatorService` on Combine until their
   timers are moved to `async` sequences; changing them first would widen the review.
3. Add a Combine test only where a publisher is changed, so tests track real behaviour.

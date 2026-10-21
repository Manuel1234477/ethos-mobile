# iOS background scheduling audit

Tracks issue #493.

## Finding

The premise that sync uses timer-based wakeups does not match the code.

- Background sync is already scheduled with `BGTaskScheduler`:
  `ios/EthosProtocol/Sources/Services/BackgroundRefreshService.swift` submits a
  `BGAppRefreshTaskRequest` (earliest start one hour out).
- The check-in sync is already a `BGProcessingTaskRequest`:
  `ios/EthosProtocol/Sources/Services/CheckInSyncTask.swift`.
- The only repeating `Timer` in the services is the UI countdown in
  `BiometricTimeoutIndicatorService.swift`, which is not a sync.

## Remaining work

1. Decide whether the one-hour refresh should move to `BGProcessingTaskRequest` with
   `requiresNetworkConnectivity`, so the OS can run it when the device is charging.
2. Confirm the widget refresh interval, which is set in `BackgroundTaskConfiguration.swift`,
   does not overlap with those requests.

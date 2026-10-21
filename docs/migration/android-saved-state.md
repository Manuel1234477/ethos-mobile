# Android SavedStateHandle audit

Tracks issue #497.

## Finding

Process-death state is already kept with `SavedStateHandle` in the places that need it:

- `android/app/src/main/java/com/ethosprotocol/ui/DeepLinkViewModel.kt`
- `android/app/src/main/java/com/ethosprotocol/services/DeepLinkRateLimiter.kt`, which takes
  the handle as a parameter for its cooldowns, as the TwoFactorViewModel OTP limit does.

## Remaining work

1. Check the other view models in `ui/ViewModels.kt` (`AuthViewModel`, `SessionsViewModel`,
   `TwoFactorViewModel`) for state that is lost on process death, and move only that state to
   `SavedStateHandle`.
2. Add a restore test for each view model that changes.

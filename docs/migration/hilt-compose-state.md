# Hilt with Compose: current state

Tracks issue #499.

## Finding

The integration is already in place:

- Nine view models carry `@HiltViewModel` (`android/app/src/main/java/com/ethosprotocol/ui/`),
  including `AuthViewModel`, `SessionsViewModel`, `TwoFactorViewModel`, `DeepLinkViewModel` and
  `NotificationPreferencesViewModel`.
- Compose screens obtain them through Hilt rather than by building them by hand.

## Remaining work

1. Add a short pattern note to `docs/` that shows `hiltViewModel()` in a composable and the
   `@HiltViewModel` class beside it.
2. Check that every screen in `ui/screens/` gets its view model through `hiltViewModel()`.

# Android Compose migration plan

Tracks issue #494.

## Current state

- The app's screens are already Compose: `android/app/src/main/java/com/ethosprotocol/ui/screens/`
  (for example `Screens.kt` and `NotificationPreferencesScreen.kt`).
- The only XML layouts left are the five home-screen widget layouts in
  `android/app/src/main/res/layout/` (`vault_widget*.xml`).

## Decision

- Widgets stay on XML. Home-screen widgets are rendered by RemoteViews, which does not run Compose.
  Moving them needs a separate widget library, so it is out of scope here.
- No app screen needs migrating. The migration strategy is therefore: keep screens in Compose and
  keep the widget XML as the only XML.

## Next steps

1. When a new widget library is evaluated, start from the five `vault_widget*.xml` files.
2. Keep new screens in Compose and add them to `ui/screens/`.
3. Keep the `@HiltViewModel` pattern used in `ui/ViewModels.kt` for Compose screens.

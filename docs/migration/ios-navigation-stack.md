# iOS NavigationStack audit

Tracks issue #496.

## Finding

The app does not use `NavigationView`. A search of `ios/` finds no use of it, so there is no
deprecated navigation API to replace.

## Remaining work

1. Check the iOS deployment target. `NavigationStack` needs iOS 16, so the migration only applies
   once the target is raised.
2. When a screen uses `NavigationLink` with a destination closure, move it to
   `navigationDestination(for:)` on a `NavigationStack` path.

# ADR-0004: iCloud Vault-to-Passkey Associations Are Scoped by Server Account ID

## Status

Accepted

## Date

2024-01-01

## Context

`ICloudSyncService` maintains a mapping between Ethos vaults and their associated passkeys in the iCloud key-value store. This mapping allows a user to sign in to a vault using a passkey that was previously registered on any device sharing the same Apple ID.

The association store was originally keyed only by vault ID. Two behaviors combined to create a security concern:

1. `ICloudSyncService.clearLocalAssociations()` removes only the local copy of the association and deliberately leaves the iCloud key-value store intact (see lines 55-60). This was intentional so that associations survive sign-out and remain available on other devices.
2. `credentialID(for:)` reads the remote (iCloud) copy of the association first, before falling back to any local copy.

Because the iCloud key-value store is shared across all devices signed in to the same Apple ID, and because entries were keyed only by vault ID, a second Ethos account signing in on the same device under the same Apple ID could inherit a vault-to-passkey mapping that belonged to the previous account. If the vault IDs collided (or if the second account had access to a vault with the same identifier), the second account could be offered a passkey credential that was registered by the first account, potentially allowing it to authenticate against a vault it does not own.

This is a cross-account data leakage risk on shared Apple IDs, which are common in family and shared-device scenarios.

## Decision

We will key iCloud vault-to-passkey associations by **server account ID in addition to vault ID**. Concretely:

- The association store key becomes a composite of the server account ID and the vault ID, rather than the vault ID alone.
- `credentialID(for:)` looks up associations using the composite key for the currently signed-in account, so it can only ever return a credential that was registered by that same account.
- On sign-out, `ICloudSyncService` clears that account's entries from the association store (both local and iCloud key-value store), so a subsequent account signing in under the same Apple ID cannot observe or inherit the previous account's mappings.

## Rationale

- **Account isolation on shared Apple IDs.** The Apple ID is a device-level identity, not an Ethos-level identity. Multiple Ethos accounts can legitimately share one Apple ID. Scoping by server account ID ensures that passkey associations are only visible to the account that created them.
- **Preserves cross-device convenience.** Associations remain in the iCloud key-value store and continue to sync across devices for the same account, so the original purpose of leaving the remote copy intact is retained — but now only for the owning account.
- **Defense in depth.** Even if vault IDs collide across accounts, the composite key prevents one account from resolving another account's credential.
- **Explicit lifecycle.** Clearing an account's entries on sign-out makes the lifecycle of the association explicit and auditable, rather than relying on the absence of collisions.

## Consequences

### Positive

- Eliminates the cross-account inheritance of vault-to-passkey mappings on shared Apple IDs.
- Maintains cross-device passkey availability for the owning account.
- Makes the association lifecycle explicit and easier to reason about during security review.

### Negative

- Association keys are larger and include the server account ID, requiring a migration path for any existing entries keyed only by vault ID.
- Sign-out now performs additional cleanup work against the iCloud key-value store, which may fail or be delayed when the device is offline; failures must be handled gracefully and retried.
- Existing users may need to re-register passkey associations once after upgrading, since legacy vault-ID-only entries will not match the new composite key.

### Neutral

- The server account ID must be available to `ICloudSyncService` at the time associations are read and written; callers must supply it as part of the sync context.

## Alternatives Considered

- **Clear the entire iCloud key-value store on sign-out.** Rejected: this would remove associations belonging to other accounts sharing the Apple ID and break cross-device passkey availability for them.
- **Key by Apple ID only.** Rejected: the Apple ID is shared by design in the scenario of concern, so it does not provide account isolation.
- **Keep vault-ID-only keys and rely on vault ID uniqueness.** Rejected: vault IDs are not guaranteed to be globally unique across accounts, and relying on that assumption is fragile.

## References

- Issue #618: Define Behavior for iCloud Associations When Accounts Change on a Shared Apple ID
- `ICloudSyncService.clearLocalAssociations()`
- `ICloudSyncService.credentialID(for:)`
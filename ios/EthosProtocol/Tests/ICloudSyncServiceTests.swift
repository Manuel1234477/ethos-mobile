import XCTest
@testable import EthosProtocol

// MARK: - Manual QA Checklist for iCloud Sync
// NOTE: The following test (`test_toggle_enablesSync`) is skipped in CI because
// NSUbiquitousKeyValueStore requires an iCloud entitlement and a signed-in iCloud account,
// unavailable in the bare, unsigned SPM test bundle on CI.
//
// Manual testing on a real device should verify:
// 1. Enable iCloud sync toggle on device (Settings > iCloud > Keychain)
// 2. Create a vault on Device A while sync is ON
// 3. Verify vault appears on Device B after sync completes
// 4. Create another vault on Device B
// 5. Verify both vaults appear on Device A after sync completes
// 6. Disable sync on Device A, create a vault
// 7. Verify new vault does NOT appear on Device B
// 8. Re-enable sync on Device A
// 9. Verify previously created vault now appears on Device B (one-way sync from A to cloud)

final class ICloudSyncServiceTests: XCTestCase {

    override func setUp() {
        super.setUp()
        // Reset state before each test
        ICloudSyncService.shared.isSyncEnabled = false
        UserDefaults.standard.removeObject(forKey: "com.ethosprotocol.vault_associations")
    }

    func test_toggle_enablesSync() throws {
        // NSUbiquitousKeyValueStore silently no-ops without an iCloud entitlement
        // and a signed-in iCloud account — neither is available in the bare,
        // unsigned SPM test bundle this runs in on CI (every read then just
        // returns the default `false`, regardless of what was set).
        try XCTSkipIf(ProcessInfo.processInfo.environment["CI"] != nil,
                      "NSUbiquitousKeyValueStore requires an iCloud entitlement + signed-in account, unavailable in CI")
        ICloudSyncService.shared.isSyncEnabled = true
        XCTAssertTrue(ICloudSyncService.shared.isSyncEnabled)
    }

    func test_toggle_disablesSync() {
        ICloudSyncService.shared.isSyncEnabled = true
        ICloudSyncService.shared.isSyncEnabled = false
        XCTAssertFalse(ICloudSyncService.shared.isSyncEnabled)
    }

    func test_saveAndRetrieve_credentialForVault() {
        ICloudSyncService.shared.save(vaultID: "vault-abc", credentialID: "cred-xyz")
        XCTAssertEqual(ICloudSyncService.shared.credentialID(for: "vault-abc"), "cred-xyz")
    }

    func test_missingVault_returnsNil() {
        XCTAssertNil(ICloudSyncService.shared.credentialID(for: "nonexistent-\(UUID())"))
    }

    // MARK: - #10 Clear Local State on Sign-Out

    func test_clearLocalAssociations_removesLocallySavedAssociation() {
        ICloudSyncService.shared.save(vaultID: "vault-to-clear", credentialID: "cred-to-clear")
        XCTAssertEqual(ICloudSyncService.shared.credentialID(for: "vault-to-clear"), "cred-to-clear")

        ICloudSyncService.shared.clearLocalAssociations()

        XCTAssertNil(ICloudSyncService.shared.credentialID(for: "vault-to-clear"))
    }

    func test_restoreFromICloud_mergesIntoLocal() {
        // Pre-populate local storage
        ICloudSyncService.shared.save(vaultID: "local-vault", credentialID: "local-cred")
        // Enable sync so restore reads remote (will be empty in unit test, but merge must not wipe local)
        ICloudSyncService.shared.isSyncEnabled = true
        ICloudSyncService.shared.restoreFromICloud()
        // Local entry must survive the merge
        XCTAssertEqual(ICloudSyncService.shared.credentialID(for: "local-vault"), "local-cred")
    }

    func test_restoreFromICloud_doesNothingWhenSyncDisabled() {
        // Pre-populate local storage
        ICloudSyncService.shared.save(vaultID: "vault-1", credentialID: "cred-1")
        // Don't enable sync
        ICloudSyncService.shared.isSyncEnabled = false
        // Restore should be a no-op
        ICloudSyncService.shared.restoreFromICloud()
        // Entry must remain unchanged
        XCTAssertEqual(ICloudSyncService.shared.credentialID(for: "vault-1"), "cred-1")
    }

    func test_credentialID_prefers_remoteWhenSyncEnabled() {
        // This tests the logic that remoteAssociations take precedence when sync is on.
        // First, save locally only
        ICloudSyncService.shared.save(vaultID: "shared-vault", credentialID: "local-version")
        // Enable sync (in a real scenario, remote would have been populated)
        ICloudSyncService.shared.isSyncEnabled = true
        // The test here is that the getter checks remote first; since remote is empty
        // in CI (no iCloud), it will fall back to local. This documents the precedence.
        XCTAssertEqual(ICloudSyncService.shared.credentialID(for: "shared-vault"), "local-version")
    }

    func test_associationMerge_multipleVaults() {
        // Test that multiple vault associations can be saved and retrieved
        ICloudSyncService.shared.save(vaultID: "vault-a", credentialID: "cred-a")
        ICloudSyncService.shared.save(vaultID: "vault-b", credentialID: "cred-b")
        ICloudSyncService.shared.save(vaultID: "vault-c", credentialID: "cred-c")

        XCTAssertEqual(ICloudSyncService.shared.credentialID(for: "vault-a"), "cred-a")
        XCTAssertEqual(ICloudSyncService.shared.credentialID(for: "vault-b"), "cred-b")
        XCTAssertEqual(ICloudSyncService.shared.credentialID(for: "vault-c"), "cred-c")
    }

    func test_multipleAssociations_allRetrievable() {
        ICloudSyncService.shared.save(vaultID: "v1", credentialID: "c1")
        ICloudSyncService.shared.save(vaultID: "v2", credentialID: "c2")
        XCTAssertEqual(ICloudSyncService.shared.credentialID(for: "v1"), "c1")
        XCTAssertEqual(ICloudSyncService.shared.credentialID(for: "v2"), "c2")
    }

    func test_overwrite_updatesCredential() {
        ICloudSyncService.shared.save(vaultID: "v1", credentialID: "old-cred")
        ICloudSyncService.shared.save(vaultID: "v1", credentialID: "new-cred")
        XCTAssertEqual(ICloudSyncService.shared.credentialID(for: "v1"), "new-cred")
    }

    func test_conflictResolution_mostRecentWriteWins() throws {
        // NSUbiquitousKeyValueStore silently no-ops without an iCloud entitlement
        // and a signed-in iCloud account — neither is available in the bare,
        // unsigned SPM test bundle this runs in on CI, so the manually-seeded
        // "remote" value below is never actually readable back.
        try XCTSkipIf(ProcessInfo.processInfo.environment["CI"] != nil,
                      "NSUbiquitousKeyValueStore requires an iCloud entitlement + signed-in account, unavailable in CI")
        // Simulate a conflict where an older local write is present
        let oldTimestamp = Date().timeIntervalSince1970 - 100
        let newTimestamp = Date().timeIntervalSince1970

        // Save old value locally
        ICloudSyncService.shared.save(vaultID: "vault-conflict", credentialID: "old-cred")

        // Manually simulate a newer remote value by manually encoding and setting it
        let remoteAssoc = ICloudSyncService.VaultAssociation(credentialID: "new-cred", timestamp: newTimestamp)
        var remoteData: [String: ICloudSyncService.VaultAssociation] = ["vault-conflict": remoteAssoc]
        if let data = try? JSONEncoder().encode(remoteData) {
            NSUbiquitousKeyValueStore.default.set(data, forKey: "com.ethosprotocol.vault_associations")
            NSUbiquitousKeyValueStore.default.synchronize()
        }

        // Enable sync and restore
        ICloudSyncService.shared.isSyncEnabled = true
        ICloudSyncService.shared.restoreFromICloud()

        // Newer remote value should win
        XCTAssertEqual(ICloudSyncService.shared.credentialID(for: "vault-conflict"), "new-cred")
    }

    func test_conflictResolution_localNeverWriteWins() {
        // Simulate a scenario where local write is more recent than remote
        let oldRemoteTimestamp = Date().timeIntervalSince1970 - 100

        // Manually set an old remote value
        let remoteAssoc = ICloudSyncService.VaultAssociation(credentialID: "old-remote-cred", timestamp: oldRemoteTimestamp)
        var remoteData: [String: ICloudSyncService.VaultAssociation] = ["vault-conflict-2": remoteAssoc]
        if let data = try? JSONEncoder().encode(remoteData) {
            NSUbiquitousKeyValueStore.default.set(data, forKey: "com.ethosprotocol.vault_associations")
            NSUbiquitousKeyValueStore.default.synchronize()
        }

        // Now save a newer local value
        ICloudSyncService.shared.save(vaultID: "vault-conflict-2", credentialID: "new-local-cred")

        // Enable sync and restore
        ICloudSyncService.shared.isSyncEnabled = true
        ICloudSyncService.shared.restoreFromICloud()

        // Newer local value should win
        XCTAssertEqual(ICloudSyncService.shared.credentialID(for: "vault-conflict-2"), "new-local-cred")
    }

    // MARK: - #205 Simultaneous Multi-Device Check-In Conflict Resolution
    //
    // Exercises `ICloudSyncService.merge(local:remote:)` directly, rather than through
    // `NSUbiquitousKeyValueStore`, so these run reliably in CI (no iCloud entitlement needed).

    func test_merge_simultaneousDevices_disjointVaults_keepsBoth() {
        // Device A queued an offline check-in/association for vault-1 while Device B,
        // also offline, queued one for vault-2. Neither should be dropped once both sync.
        let deviceA: [String: ICloudSyncService.VaultAssociation] = [
            "vault-1": .init(credentialID: "cred-a", timestamp: 100)
        ]
        let deviceB: [String: ICloudSyncService.VaultAssociation] = [
            "vault-2": .init(credentialID: "cred-b", timestamp: 101)
        ]
        let merged = ICloudSyncService.merge(local: deviceA, remote: deviceB)
        XCTAssertEqual(merged["vault-1"]?.credentialID, "cred-a")
        XCTAssertEqual(merged["vault-2"]?.credentialID, "cred-b")
    }

    func test_merge_simultaneousDevices_sameVault_newerTimestampWins() {
        // Both devices raced to check in on the same vault; the server-accepted-order
        // proxy (persist timestamp) must decide deterministically, not "whichever device
        // pushed to iCloud last".
        let older: [String: ICloudSyncService.VaultAssociation] = [
            "vault-shared": .init(credentialID: "device-a-cred", timestamp: 200)
        ]
        let newer: [String: ICloudSyncService.VaultAssociation] = [
            "vault-shared": .init(credentialID: "device-b-cred", timestamp: 205)
        ]
        XCTAssertEqual(ICloudSyncService.merge(local: older, remote: newer)["vault-shared"]?.credentialID, "device-b-cred")
        // Order-independent: the newer entry wins regardless of which side is "local" vs "remote".
        XCTAssertEqual(ICloudSyncService.merge(local: newer, remote: older)["vault-shared"]?.credentialID, "device-b-cred")
    }

    func test_pushAssociations_doesNotClobberNewerRemoteEntryForUntouchedVault() throws {
        // Simulates the double check-in race end-to-end: Device A pushes its local state
        // (which knows nothing about vault-2) after Device B already pushed a newer entry
        // for vault-2. Device A's push must not delete vault-2's association from iCloud.
        try XCTSkipIf(ProcessInfo.processInfo.environment["CI"] != nil,
                  "NSUbiquitousKeyValueStore requires an iCloud entitlement + signed-in account, unavailable in CI")
        let deviceBAssoc = ICloudSyncService.VaultAssociation(credentialID: "device-b-cred", timestamp: Date().timeIntervalSince1970)
        if let data = try? JSONEncoder().encode(["vault-2": deviceBAssoc]) {
            NSUbiquitousKeyValueStore.default.set(data, forKey: "com.ethosprotocol.vault_associations")
            NSUbiquitousKeyValueStore.default.synchronize()
        }

        ICloudSyncService.shared.isSyncEnabled = true
        ICloudSyncService.shared.save(vaultID: "vault-1", credentialID: "device-a-cred")

        XCTAssertEqual(ICloudSyncService.shared.credentialID(for: "vault-1"), "device-a-cred")
        XCTAssertEqual(ICloudSyncService.shared.credentialID(for: "vault-2"), "device-b-cred")
    }
}

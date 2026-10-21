import XCTest
@testable import EthosProtocol

// MARK: - Keychain Tests with Real Host

final class KeychainServiceHostedTests: XCTestCase {

    func test_saveAndLoadToken() throws {
        // This test requires a real app host process with keychain-access-group
        // entitlement — it runs in the hosted EthosProtocolTests target only,
        // not in the bare SPM test bundle which doesn't have app host/entitlements.
        KeychainService.shared.saveToken("test-token-123")
        XCTAssertEqual(KeychainService.shared.loadToken(), "test-token-123")
    }

    func test_deleteToken_returnsNil() {
        KeychainService.shared.saveToken("to-delete")
        KeychainService.shared.deleteToken()
        XCTAssertNil(KeychainService.shared.loadToken())
    }

    func test_saveAndLoadToken_roundTrip_withSpecialCharacters() {
        let token = "test-token-with-special-chars-!@#$%^&*()"
        KeychainService.shared.saveToken(token)
        XCTAssertEqual(KeychainService.shared.loadToken(), token)
        KeychainService.shared.deleteToken()
    }

    // MARK: - #621 Constant-Time PIN Verification

    func test_verifyPIN_returnsTrueForCorrectPIN() throws {
        let pin = "482913"
        try KeychainService.shared.savePIN(pin)
        defer { KeychainService.shared.deletePIN() }

        XCTAssertTrue(KeychainService.shared.verifyPIN(pin),
                      "verifyPIN should accept the PIN that was stored")
    }

    func test_verifyPIN_returnsFalseForIncorrectPIN() throws {
        let pin = "482913"
        try KeychainService.shared.savePIN(pin)
        defer { KeychainService.shared.deletePIN() }

        XCTAssertFalse(KeychainService.shared.verifyPIN("000000"),
                       "verifyPIN should reject a PIN that does not match")
        XCTAssertFalse(KeychainService.shared.verifyPIN("482914"),
                       "verifyPIN should reject a PIN differing in the last digit")
        XCTAssertFalse(KeychainService.shared.verifyPIN(""),
                       "verifyPIN should reject an empty PIN")
    }

    // MARK: - #622 Shared Keychain Access Group

    func test_saveToken_isReadableThroughSharedAccessGroup() throws {
        // The widget reads the token through the shared keychain-access-group
        // declared in EthosProtocol.entitlements / TTLWidget.entitlements.
        // Writing via the app and reading back through an explicit query that
        // sets kSecAttrAccessGroup proves the item lands in the shared group
        // rather than the app's default group.
        let token = "shared-group-token-\(UUID().uuidString)"
        KeychainService.shared.saveToken(token)
        defer { KeychainService.shared.deleteToken() }

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: KeychainService.service,
            kSecAttrAccount as String: KeychainService.tokenAccount,
            kSecAttrAccessGroup as String: KeychainService.accessGroup,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        XCTAssertEqual(status, errSecSuccess,
                       "Item written by the app must be readable through the shared access group (status \(status))")

        let data = try XCTUnwrap(result as? Data)
        XCTAssertEqual(String(data: data, encoding: .utf8), token)
    }

    // MARK: - #623 Update/Add Keychain Writes and Status Surfacing

    func test_saveToken_returnsSuccessOnAddPath() throws {
        KeychainService.shared.deleteToken()
        let status = KeychainService.shared.saveToken("add-path-token")
        defer { KeychainService.shared.deleteToken() }

        XCTAssertEqual(status, errSecSuccess, "Saving a new token should succeed via SecItemAdd")
        XCTAssertEqual(KeychainService.shared.loadToken(), "add-path-token")
    }

    func test_saveToken_returnsSuccessOnUpdatePath() throws {
        KeychainService.shared.saveToken("initial-token")
        defer { KeychainService.shared.deleteToken() }

        let status = KeychainService.shared.saveToken("updated-token")

        XCTAssertEqual(status, errSecSuccess, "Overwriting an existing token should succeed via SecItemUpdate")
        XCTAssertEqual(KeychainService.shared.loadToken(), "updated-token",
                       "The update path must not drop the token")
    }

    func test_saveToken_surfacesFailureStatusAndPreservesExistingToken() throws {
        // Force a failure by making the item unreadable/unwritable is not portable,
        // so instead assert the contract: a non-success status is returned to the
        // caller rather than swallowed, and the previously stored token survives.
        KeychainService.shared.saveToken("surviving-token")
        defer { KeychainService.shared.deleteToken() }

        // A save with an empty account/key is rejected by the service and must
        // report a non-success OSStatus instead of silently dropping the token.
        let status = KeychainService.shared.saveToken("", account: "")

        XCTAssertNotEqual(status, errSecSuccess,
                          "A failed save must surface a non-errSecSuccess OSStatus to the caller")
        XCTAssertEqual(KeychainService.shared.loadToken(), "surviving-token",
                       "A failed save must not delete the previously stored token")
    }
}

// MARK: - #619 Salted PIN KDF Tests with Real Host

final class KeychainServicePINKDFHostedTests: XCTestCase {

    override func setUp() {
        super.setUp()
        KeychainService.shared.deletePINHash()
    }

    override func tearDown() {
        KeychainService.shared.deletePINHash()
        super.tearDown()
    }

    func test_hashPIN_verifyPIN_roundTrip() {
        KeychainService.shared.savePINHash(for: "123456")
        XCTAssertTrue(KeychainService.shared.verifyPIN("123456"))
        XCTAssertFalse(KeychainService.shared.verifyPIN("654321"))
    }

    func test_hashPIN_usesDistinctSaltsForSamePIN() {
        KeychainService.shared.savePINHash(for: "123456")
        let first = KeychainService.shared.loadPINHashRecord()
        KeychainService.shared.savePINHash(for: "123456")
        let second = KeychainService.shared.loadPINHashRecord()

        XCTAssertNotNil(first)
        XCTAssertNotNil(second)
        XCTAssertNotEqual(first?.salt, second?.salt, "Each PIN hash must use a fresh random salt")
        XCTAssertNotEqual(first?.hash, second?.hash, "Same PIN with different salts must yield different hashes")
    }

    func test_hashPIN_isNotPlainSHA256() {
        KeychainService.shared.savePINHash(for: "123456")
        let record = KeychainService.shared.loadPINHashRecord()
        XCTAssertNotNil(record)
        // A salted KDF output must not equal the legacy unsalted SHA-256 of the PIN.
        XCTAssertNotEqual(record?.hash, KeychainService.shared.legacyUnsaltedSHA256("123456"))
    }

    func test_verifyPIN_migratesLegacyUnsaltedHash_onSuccessfulUnlock() {
        // Simulate an existing install with the old unsalted SHA-256 hash.
        KeychainService.shared.saveLegacyPINHash(for: "123456")
        XCTAssertTrue(KeychainService.shared.verifyPIN("123456"))

        let migrated = KeychainService.shared.loadPINHashRecord()
        XCTAssertNotNil(migrated)
        XCTAssertNotNil(migrated?.salt, "Migrated hash must carry a per-PIN salt")
        XCTAssertNotEqual(migrated?.hash, KeychainService.shared.legacyUnsaltedSHA256("123456"),
                          "Legacy unsalted hash must be replaced by a salted KDF hash")
        XCTAssertTrue(KeychainService.shared.verifyPIN("123456"), "Migrated hash must still verify the PIN")
    }

    func test_verifyPIN_doesNotMigrate_onFailedUnlock() {
        KeychainService.shared.saveLegacyPINHash(for: "123456")
        XCTAssertFalse(KeychainService.shared.verifyPIN("000000"))

        let record = KeychainService.shared.loadPINHashRecord()
        XCTAssertNil(record?.salt, "A failed unlock must not migrate the legacy hash")
        XCTAssertEqual(record?.hash, KeychainService.shared.legacyUnsaltedSHA256("123456"))
    }
}

// MARK: - #24 Sign-Out Push Token Unregistration Tests with Real Host

@MainActor
final class AuthStoreSignOutHostedTests: XCTestCase {

    func test_signOut_unregistersPersistedPushToken() async {
        // Runs in the hosted EthosProtocolTests target, which has real Keychain
        // access — see AuthStoreSignOutTests in the SPM bundle for the same
        // assertions, skipped there in CI.
        KeychainService.shared.saveToken("auth-token-abc")
        KeychainService.shared.savePushToken("push-token-abc")

        let store = AuthStore()
        var unregisteredToken: String?
        store.unregisterPushToken = { token in unregisteredToken = token }

        await store.signOut()

        XCTAssertEqual(unregisteredToken, "push-token-abc")
        XCTAssertNil(KeychainService.shared.loadPushToken())
        XCTAssertNil(KeychainService.shared.loadToken())
        XCTAssertFalse(store.isAuthenticated)
    }
}

// MARK: - Notification Tests with Real Host

final class NotificationServiceHostedTests: XCTestCase {

    func test_scheduleTTLWarning_doesNotThrow_forActiveVault() throws {
        // UNUserNotificationCenter requires a real app host process — this test
        // runs in the hosted EthosProtocolTests target only, not in the bare SPM
        // test bundle which has no real app process or notification entitlements.
        XCTAssertNoThrow(
            NotificationService.shared.scheduleTTLWarning(vaultID: "vault-test", ttlRemaining: 3_600)
        )
    }

    func test_scheduleTTLWarning_removesExistingNotification_beforeAddingNew() throws {
        // Schedule twice for same vault; should not crash or duplicate.
        NotificationService.shared.scheduleTTLWarning(vaultID: "vault-dup", ttlRemaining: 7_200)
        XCTAssertNoThrow(
            NotificationService.shared.scheduleTTLWarning(vaultID: "vault-dup", ttlRemaining: 3_600)
        )
    }

    func test_scheduleTTLWarning_verifyPendingNotificationRequests() async throws {
        // A simulator never actually grants notification authorization (no user to tap
        // Allow), and `xcrun simctl privacy grant notifications` fails outright on the CI
        // runner ("Operation not permitted") — without authorization, a scheduled request
        // never actually lands in pendingNotificationRequests(). See ios-ci.yml.
        try XCTSkipIf(ProcessInfo.processInfo.environment["CI"] != nil,
                      "Notification authorization cannot be granted in this CI environment")
        let vaultID = "vault-verify-\(UUID().uuidString)"
        NotificationService.shared.scheduleTTLWarning(vaultID: vaultID, ttlRemaining: 3_600)

        let pendingRequests = await UNUserNotificationCenter.current().pendingNotificationRequests()
        let ttlWarningRequests = pendingRequests.filter { $0.identifier.contains(vaultID) }

        XCTAssertFalse(ttlWarningRequests.isEmpty, "Should have scheduled a notification for the vault")
        if let request = ttlWarningRequests.first {
            if let trigger = request.trigger as? UNTimeIntervalNotificationTrigger {
                XCTAssertTrue(trigger.timeInterval > 0, "Trigger should have positive time interval")
            }
        }
    }

    func test_scheduleTTLWarning_withTriggerIntervalValidation() async throws {
        // See test_scheduleTTLWarning_verifyPendingNotificationRequests above.
        try XCTSkipIf(ProcessInfo.processInfo.environment["CI"] != nil,
                      "Notification authorization cannot be granted in this CI environment")
        let vaultID = "vault-trigger-\(UUID().uuidString)"
        NotificationService.shared.scheduleTTLWarning(vaultID: vaultID, ttlRemaining: 3_600)

        let pendingRequests = await UNUserNotificationCenter.current().pendingNotificationRequests()
        let ttlRequests = pendingRequests.filter { $0.identifier.contains("ttl-warning") && $0.identifier.contains(vaultID) }

        XCTAssertEqual(ttlRequests.count, 1, "Should have exactly one TTL warning notification")
        if let request = ttlRequests.first, let trigger = request.trigger as? UNTimeIntervalNotificationTrigger {
            XCTAssertEqual(trigger.timeInterval, 5, "TTL warning should trigger in 5 seconds")
            XCTAssertFalse(trigger.repeats, "TTL warning should not repeat")
        }
    }

    // #233: body content includes a truncated vault ID and TTL — but never
    // anything that could be a balance or beneficiary address.
    func test_scheduleTTLWarning_bodyIncludesTruncatedVaultIdAndTTL() async throws {
        try XCTSkipIf(ProcessInfo.processInfo.environment["CI"] != nil,
                      "Notification authorization cannot be granted in this CI environment")
        let vaultID = "vault-body-content-\(UUID().uuidString)"
        NotificationService.shared.scheduleTTLWarning(vaultID: vaultID, ttlRemaining: 3_600)

        let pendingRequests = await UNUserNotificationCenter.current().pendingNotificationRequests()
        let request = pendingRequests.first { $0.identifier.contains(vaultID) }

        XCTAssertNotNil(request)
        let body = request?.content.body ?? ""
        XCTAssertTrue(body.contains(String(vaultID.prefix(12))), "body should identify the vault")
        XCTAssertTrue(body.contains("1h"), "3600s remaining should render as 1h")
    }

    func test_scheduleCheckInReminder_bodyIncludesTruncatedVaultIdAndTTL() async throws {
        try XCTSkipIf(ProcessInfo.processInfo.environment["CI"] != nil,
                      "Notification authorization cannot be granted in this CI environment")
        let vaultID = "vault-checkin-content-\(UUID().uuidString)"
        NotificationService.shared.scheduleCheckInReminder(
            vaultID: vaultID, vaultName: vaultID, ttlRemaining: 604_800, checkInInterval: 2_592_000)

        let pendingRequests = await UNUserNotificationCenter.current().pendingNotificationRequests()
        let request = pendingRequests.first { $0.identifier.contains("checkin-primary-\(vaultID)") }

        XCTAssertNotNil(request)
        let body = request?.content.body ?? ""
        XCTAssertTrue(body.contains(String(vaultID.prefix(12))), "body should identify the vault")
    }

    func test_removeAllPendingNotifications_clearsScheduledRequests() async {
        NotificationService.shared.scheduleTTLWarning(vaultID: "vault-to-clear-\(UUID().uuidString)", ttlRemaining: 3_600)

        NotificationService.shared.removeAllPendingNotifications()

        let pendingRequests = await UNUserNotificationCenter.current().pendingNotificationRequests()
        XCTAssertTrue(pendingRequests.isEmpty, "No notifications should remain pending after removeAllPendingNotifications()")
    }
}

// MARK: - #10 Sign-Out Local State Clearing Tests (Hosted)
//
// AuthStore.signOut() touches Keychain and UNUserNotificationCenter, both of which need a
// real app host process (see the notes above) — so these run here rather than in the bare
// SPM bundle in Tests/EthosProtocolTests.swift.

@MainActor
final class SignOutClearsLocalStateTests: XCTestCase {

    override func setUp() {
        super.setUp()
        ICloudSyncService.shared.isSyncEnabled = false
    }

    func test_signOut_clearsCredentialID() async {
        KeychainService.shared.saveCredentialID("cred-to-clear")
        await AuthStore().signOut()
        XCTAssertNil(KeychainService.shared.loadCredentialID())
    }

    func test_signOut_clearsOfflineCache_noResidualVaultDataReadable() async {
        let cacheKey = "https://api.ethos-protocol.app/v1/vaults"
        OfflineCache.shared.save(Data(#"[{"id":"vault-1","balance":50000000}]"#.utf8), for: cacheKey)
        XCTAssertNotNil(OfflineCache.shared.load(for: cacheKey), "Precondition: cached vault data should be present before sign-out")

        await AuthStore().signOut()

        XCTAssertNil(OfflineCache.shared.load(for: cacheKey), "Vault data cached before sign-out must not be readable afterward")
    }

    func test_signOut_clearsICloudLocalAssociation() async {
        ICloudSyncService.shared.save(vaultID: "vault-to-forget", credentialID: "cred-to-forget")
        await AuthStore().signOut()
        XCTAssertNil(ICloudSyncService.shared.credentialID(for: "vault-to-forget"))
    }

    func test_signOut_clearsPendingNotifications() async {
        NotificationService.shared.scheduleTTLWarning(vaultID: "vault-signout-\(UUID().uuidString)", ttlRemaining: 3_600)

        await AuthStore().signOut()

        let pendingRequests = await UNUserNotificationCenter.current().pendingNotificationRequests()
        XCTAssertTrue(pendingRequests.isEmpty, "No notifications from the signed-out session should remain pending")
    }

    func test_signOut_resetsLockState() async {
        let store = AuthStore()
        store.isAuthenticated = true
        store.isLocked = true

        await store.signOut()

        XCTAssertFalse(store.isLocked)
        XCTAssertFalse(store.isAuthenticated)
    }
}

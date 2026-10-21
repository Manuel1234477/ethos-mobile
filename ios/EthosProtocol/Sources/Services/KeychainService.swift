import Security
import Foundation
import CommonCrypto

final class KeychainService {
    static let shared = KeychainService()
    private init() {}

    private let tokenKey = "com.ethosprotocol.auth_token"
    private let tokenExpiryKey = "com.ethosprotocol.auth_token_expiry"
    private let credentialKey = "com.ethosprotocol.passkey_credential"
    private let pushTokenKey = "com.ethosprotocol.push_token"
    /// A device token seen (via APNs callback) but not yet confirmed registered
    /// with the server — set when registration fails after retrying, cleared
    /// once it succeeds. See NotificationService's retry-on-foreground (#234).
    private let pendingPushTokenKey = "com.ethosprotocol.pending_push_token"
    private let pinKey = "com.ethosprotocol.biometric_fallback_pin"
    private let pinSetupKey = "com.ethosprotocol.pin_setup_complete"

    /// Shared keychain access group declared in both EthosProtocol.entitlements and
    /// TTLWidget.entitlements (#622). Every SecItem query sets this so the widget
    /// extension can read items written by the app.
    private let accessGroup = "$(AppIdentifierPrefix)com.ethosprotocol.shared"

    /// `expiresAt`, when provided, is persisted alongside the token so AuthStore can
    /// schedule a proactive refresh (#3) against `AuthToken.expiresAt` even across an
    /// app relaunch, not just for the lifetime of the in-memory session that fetched it.
    @discardableResult
    func saveToken(_ token: String, expiresAt: Date? = nil) -> OSStatus {
        // The auth token must be readable by BackgroundRefreshService's BGAppRefreshTask and by
        // the TTLWidget extension's timeline provider, both of which can run while the device is
        // still locked. `.WhenUnlockedThisDeviceOnly` would make `loadToken()` silently return nil
        // in that case (the request goes out with no Authorization header, and the background
        // TTL check / widget just fail quietly) — so this needs the AfterFirstUnlock variant.
        let tokenStatus = save(token, forKey: tokenKey, accessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        guard tokenStatus == errSecSuccess else { return tokenStatus }
        if let expiresAt {
            return save(String(expiresAt.timeIntervalSince1970), forKey: tokenExpiryKey,
                        accessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        }
        return tokenStatus
    }

    func loadToken() -> String? {
        load(forKey: tokenKey)
    }

    func loadTokenExpiry() -> Date? {
        guard let raw = load(forKey: tokenExpiryKey), let interval = TimeInterval(raw) else { return nil }
        return Date(timeIntervalSince1970: interval)
    }

    func deleteToken() {
        delete(forKey: tokenKey)
        delete(forKey: tokenExpiryKey)
    }

    @discardableResult
    func saveCredentialID(_ id: String) -> OSStatus {
        save(id, forKey: credentialKey)
    }

    func loadCredentialID() -> String? {
        load(forKey: credentialKey)
    }

    func deleteCredentialID() {
        delete(forKey: credentialKey)
    }

    /// Tracks the device push token last successfully registered with the server,
    /// so AuthStore.signOut() knows what to unregister without needing a fresh
    /// UIApplication device-token callback at sign-out time.
    @discardableResult
    func savePushToken(_ token: String) -> OSStatus {
        save(token, forKey: pushTokenKey)
    }

    func loadPushToken() -> String? {
        load(forKey: pushTokenKey)
    }

    func deletePushToken() {
        delete(forKey: pushTokenKey)
    }

    /// #234: a device token that failed registration after retrying, to be
    /// retried again the next time the app comes to the foreground.
    @discardableResult
    func savePendingPushToken(_ token: String) -> OSStatus {
        save(token, forKey: pendingPushTokenKey)
    }

    func loadPendingPushToken() -> String? {
        load(forKey: pendingPushTokenKey)
    }

    func deletePendingPushToken() {
        delete(forKey: pendingPushTokenKey)
    }

    @discardableResult
    func savePIN(_ pin: String) -> OSStatus {
        let status = save(hashPIN(pin), forKey: pinKey, accessible: kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
        if status == errSecSuccess {
            UserDefaults.standard.set(true, forKey: pinSetupKey)
        }
        return status
    }

    func verifyPIN(_ pin: String) -> Bool {
        guard let storedHash = load(forKey: pinKey) else { return false }
        // #621: constant-time comparison so verification doesn't short-circuit on the
        // first differing byte and leak timing information about the stored digest.
        return constantTimeEquals(Array(storedHash.utf8), Array(hashPIN(pin).utf8))
    }

    /// XOR-accumulates over both byte arrays without early exit. A length mismatch
    /// returns false immediately (lengths are public: both are SHA-256 hex digests).
    private func constantTimeEquals(_ lhs: [UInt8], _ rhs: [UInt8]) -> Bool {
        guard lhs.count == rhs.count else { return false }
        var difference: UInt8 = 0
        for index in 0..<lhs.count {
            difference |= lhs[index] ^ rhs[index]
        }
        return difference == 0
    }

    func isPINSetup() -> Bool {
        UserDefaults.standard.bool(forKey: pinSetupKey)
    }

    func deletePIN() {
        delete(forKey: pinKey)
        UserDefaults.standard.removeObject(forKey: pinSetupKey)
    }

    private func hashPIN(_ pin: String) -> String {
        let data = pin.data(using: .utf8) ?? Data()
        var digest = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        _ = data.withUnsafeBytes { buffer in
            CC_SHA256(buffer.baseAddress, CC_LONG(data.count), &digest)
        }
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    /// #623: updates an existing item instead of delete-then-add, so a failed write
    /// can't leave the keychain empty. Returns the OSStatus so callers can surface
    /// a failed save rather than silently signing the user out.
    @discardableResult
    private func save(_ value: String, forKey key: String, accessible: CFString = kSecAttrAccessibleWhenUnlockedThisDeviceOnly) -> OSStatus {
        let data = Data(value.utf8)
        let baseQuery: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrAccount: key,
            kSecAttrAccessGroup: accessGroup
        ]

        var existsQuery = baseQuery
        existsQuery[kSecReturnData] = false
        existsQuery[kSecMatchLimit] = kSecMatchLimitOne
        let existsStatus = SecItemCopyMatching(existsQuery as CFDictionary, nil)

        if existsStatus == errSecSuccess {
            let attributes: [CFString: Any] = [
                kSecValueData: data,
                kSecAttrAccessible: accessible
            ]
            return SecItemUpdate(baseQuery as CFDictionary, attributes as CFDictionary)
        }

        var addQuery = baseQuery
        addQuery[kSecValueData] = data
        addQuery[kSecAttrAccessible] = accessible
        return SecItemAdd(addQuery as CFDictionary, nil)
    }

    private func load(forKey key: String) -> String? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrAccount: key,
            kSecAttrAccessGroup: accessGroup,
            kSecReturnData: true,
            kSecMatchLimit: kSecMatchLimitOne
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func delete(forKey key: String) {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrAccount: key,
            kSecAttrAccessGroup: accessGroup
        ]
        SecItemDelete(query as CFDictionary)
    }
}

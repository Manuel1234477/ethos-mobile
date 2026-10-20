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

    /// `expiresAt`, when provided, is persisted alongside the token so AuthStore can
    /// schedule a proactive refresh (#3) against `AuthToken.expiresAt` even across an
    /// app relaunch, not just for the lifetime of the in-memory session that fetched it.
    func saveToken(_ token: String, expiresAt: Date? = nil) {
        // The auth token must be readable by BackgroundRefreshService's BGAppRefreshTask and by
        // the TTLWidget extension's timeline provider, both of which can run while the device is
        // still locked. `.WhenUnlockedThisDeviceOnly` would make `loadToken()` silently return nil
        // in that case (the request goes out with no Authorization header, and the background
        // TTL check / widget just fail quietly) — so this needs the AfterFirstUnlock variant.
        save(token, forKey: tokenKey, accessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        if let expiresAt {
            save(String(expiresAt.timeIntervalSince1970), forKey: tokenExpiryKey,
                 accessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        }
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

    // MARK: - Issue #476: per-account scoped token storage
    //
    // The "active" token/expiry (above) stays a single fixed slot so the rest
    // of the app (AuthStore, APIClient, background refresh, the TTL widget)
    // keeps reading/writing it exactly as before. These scoped variants let
    // AccountManager archive an outgoing account's token before overwriting
    // the active slot with an incoming account's, instead of discarding it.

    func saveToken(_ token: String, expiresAt: Date?, accountId: String) {
        save(token, forKey: scopedTokenKey(accountId), accessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        if let expiresAt {
            save(String(expiresAt.timeIntervalSince1970), forKey: scopedTokenExpiryKey(accountId),
                 accessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        }
    }

    func loadToken(accountId: String) -> String? {
        load(forKey: scopedTokenKey(accountId))
    }

    func loadTokenExpiry(accountId: String) -> Date? {
        guard let raw = load(forKey: scopedTokenExpiryKey(accountId)), let interval = TimeInterval(raw) else { return nil }
        return Date(timeIntervalSince1970: interval)
    }

    func deleteToken(accountId: String) {
        delete(forKey: scopedTokenKey(accountId))
        delete(forKey: scopedTokenExpiryKey(accountId))
    }

    private func scopedTokenKey(_ accountId: String) -> String { "\(tokenKey).\(accountId)" }
    private func scopedTokenExpiryKey(_ accountId: String) -> String { "\(tokenExpiryKey).\(accountId)" }

    func saveCredentialID(_ id: String) {
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
    func savePushToken(_ token: String) {
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
    func savePendingPushToken(_ token: String) {
        save(token, forKey: pendingPushTokenKey)
    }

    func loadPendingPushToken() -> String? {
        load(forKey: pendingPushTokenKey)
    }

    func deletePendingPushToken() {
        delete(forKey: pendingPushTokenKey)
    }

    func savePIN(_ pin: String) {
        save(hashPIN(pin), forKey: pinKey, accessible: kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
        UserDefaults.standard.set(true, forKey: pinSetupKey)
    }

    func verifyPIN(_ pin: String) -> Bool {
        guard let storedHash = load(forKey: pinKey) else { return false }
        return storedHash == hashPIN(pin)
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

    private func save(_ value: String, forKey key: String, accessible: CFString = kSecAttrAccessibleWhenUnlockedThisDeviceOnly) {
        let data = Data(value.utf8)
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrAccount: key,
            kSecValueData: data,
            kSecAttrAccessible: accessible
        ]
        SecItemDelete(query as CFDictionary)
        SecItemAdd(query as CFDictionary, nil)
    }

    private func load(forKey key: String) -> String? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrAccount: key,
            kSecReturnData: true,
            kSecMatchLimit: kSecMatchLimitOne
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func delete(forKey key: String) {
        let query: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrAccount: key]
        SecItemDelete(query as CFDictionary)
    }
}

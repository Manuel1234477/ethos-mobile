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
        let status = save(derivePINRecord(pin), forKey: pinKey, accessible: kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
        if status == errSecSuccess {
            UserDefaults.standard.set(true, forKey: pinSetupKey)
        }
        return status
    }

    /// Verifies `pin` against the stored record. Returns `true` on a match.
    ///
    /// #619: records written before this change are a bare unsalted SHA-256 hex
    /// digest. They are still accepted here (so users aren't locked out), but the
    /// caller should check `needsPINRehash()` after a successful verify and call
    /// `migratePINIfNeeded(_:)` to upgrade the record to the salted KDF format.
    func verifyPIN(_ pin: String) -> Bool {
        guard let stored = load(forKey: pinKey) else { return false }
        if let record = PINRecord(serialized: stored) {
            return constantTimeEquals(record.verifier, deriveVerifier(pin: pin, salt: record.salt))
        }
        // Legacy unsalted SHA-256 record.
        return constantTimeEquals(stored, legacySHA256(pin))
    }

    /// True when the stored PIN record is still in the legacy unsalted SHA-256
    /// format and should be rehashed with the salted KDF (#619).
    func needsPINRehash() -> Bool {
        guard let stored = load(forKey: pinKey) else { return false }
        return PINRecord(serialized: stored) == nil
    }

    /// Rehashes a legacy PIN record with the salted KDF. Call only after
    /// `verifyPIN(_:)` has returned `true` for `pin` — this is the migration
    /// path used by PINAuthenticationService on the next successful unlock.
    /// Returns `true` if a migration was performed.
    @discardableResult
    func migratePINIfNeeded(_ pin: String) -> Bool {
        guard needsPINRehash(), verifyPIN(pin) else { return false }
        savePIN(pin)
        return true
    }

    func isPINSetup() -> Bool {
        UserDefaults.standard.bool(forKey: pinSetupKey)
    }

    func deletePIN() {
        delete(forKey: pinKey)
        UserDefaults.standard.removeObject(forKey: pinSetupKey)
    }

    // MARK: - PIN hashing (#619)

    /// PBKDF2-HMAC-SHA256 iteration count. A 4–6 digit PIN has at most 10^6
    /// candidates, so the KDF cost is what makes an offline brute force of a
    /// leaked Keychain record expensive.
    private static let pinKDFIterations: UInt32 = 600_000
    private static let pinSaltByteCount = 16
    private static let pinVerifierByteCount = 32
    /// Version prefix for the salted record format: `v1$<saltHex>$<verifierHex>`.
    private static let pinRecordVersion = "v1"

    /// Serialized salted PIN record stored in the Keychain under `pinKey`.
    private struct PINRecord {
        let salt: Data
        let verifier: Data

        init(salt: Data, verifier: Data) {
            self.salt = salt
            self.verifier = verifier
        }

        init?(serialized: String) {
            let parts = serialized.split(separator: "$", omittingEmptySubsequences: false)
            guard parts.count == 3,
                  parts[0] == Substring(KeychainService.pinRecordVersion),
                  let salt = Data(hexString: String(parts[1])),
                  let verifier = Data(hexString: String(parts[2])),
                  salt.count == KeychainService.pinSaltByteCount,
                  verifier.count == KeychainService.pinVerifierByteCount else { return nil }
            self.salt = salt
            self.verifier = verifier
        }

        var serialized: String {
            "\(KeychainService.pinRecordVersion)$\(salt.hexString)$\(verifier.hexString)"
        }
    }

    /// Builds a fresh salted record for `pin` with a new random salt.
    private func derivePINRecord(_ pin: String) -> String {
        var salt = Data(count: Self.pinSaltByteCount)
        let status = salt.withUnsafeMutableBytes { buffer in
            SecRandomCopyBytes(kSecRandomDefault, buffer.count, buffer.baseAddress!)
        }
        if status != errSecSuccess {
            // Fall back to the system RNG rather than ever storing an all-zero salt.
            salt = Data((0..<Self.pinSaltByteCount).map { _ in UInt8.random(in: 0...255) })
        }
        let verifier = deriveVerifier(pin: pin, salt: salt)
        return PINRecord(salt: salt, verifier: verifier).serialized
    }

    /// PBKDF2-HMAC-SHA256 over the UTF-8 PIN bytes.
    private func deriveVerifier(pin: String, salt: Data) -> Data {
        let password = Array(pin.utf8)
        var derived = [UInt8](repeating: 0, count: Self.pinVerifierByteCount)
        let status = salt.withUnsafeBytes { saltBuffer -> Int32 in
            guard let saltBase = saltBuffer.bindMemory(to: UInt8.self).baseAddress else {
                return Int32(kCCParamError)
            }
            return CCKeyDerivationPBKDF(
                CCPBKDFAlgorithm(kCCPBKDF2),
                password, password.count,
                saltBase, saltBuffer.count,
                CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                Self.pinKDFIterations,
                &derived, derived.count
            )
        }
        guard status == kCCSuccess else { return Data() }
        return Data(derived)
    }

    /// Legacy unsalted SHA-256 hex digest, kept only to verify pre-#619 records.
    private func legacySHA256(_ pin: String) -> String {
        let data = pin.data(using: .utf8) ?? Data()
        var digest = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        _ = data.withUnsafeBytes { buffer in
            CC_SHA256(buffer.baseAddress, CC_LONG(data.count), &digest)
        }
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    /// Length-independent comparison to avoid leaking match position via timing.
    private func constantTimeEquals(_ lhs: String, _ rhs: String) -> Bool {
        let a = Array(lhs.utf8)
        let b = Array(rhs.utf8)
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<a.count { diff |= a[i] ^ b[i] }
        return diff == 0
    }

    private func constantTimeEquals(_ lhs: Data, _ rhs: Data) -> Bool {
        guard lhs.count == rhs.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<lhs.count { diff |= lhs[i] ^ rhs[i] }
        return diff == 0
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

private extension Data {
    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }

    init?(hexString: String) {
        let chars = Array(hexString.utf8)
        guard chars.count % 2 == 0 else { return nil }
        var bytes = [UInt8]()
        bytes.reserveCapacity(chars.count / 2)
        var index = 0
        while index < chars.count {
            guard let high = Data.hexValue(chars[index]),
                  let low = Data.hexValue(chars[index + 1]) else { return nil }
            bytes.append(high << 4 | low)
            index += 2
        }
        self = Data(bytes)
    }

    private static func hexValue(_ c: UInt8) -> UInt8? {
        switch c {
        case 0x30...0x39: return c - 0x30          // 0-9
        case 0x61...0x66: return c - 0x61 + 10     // a-f
        case 0x41...0x46: return c - 0x41 + 10     // A-F
        default: return nil
        }
    }
}

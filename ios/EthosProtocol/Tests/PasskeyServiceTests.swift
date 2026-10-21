import XCTest
import AuthenticationServices
@testable import EthosProtocol

// MARK: - #1 COSE Public Key Extraction Tests

final class COSEPublicKeyExtractionTests: XCTestCase {

    func test_extractCOSEPublicKey_returnsExactCOSEKeyBytes_notTheWholeAttestationObject() throws {
        let x = Data(repeating: 0x11, count: 32)
        let y = Data(repeating: 0x22, count: 32)
        let coseKey = cborES256COSEKey(x: x, y: y)
        let credentialID = Data([0xCA, 0xFE])
        let authData = cborAuthData(credentialID: credentialID, coseKey: coseKey)
        let attestationObject = cborAttestationObject(authData: authData)

        let publicKey = try PasskeyService.extractCOSEPublicKey(fromAttestationObject: attestationObject)

        // Exact-bytes regression (#1): the wire value sent under `public_key` must be
        // the extracted COSE_Key, never the raw attestation object it was carved out
        // of — this is the documented contract (shared/api-contract.md's
        // PasskeyRegisterRequest, docs/mobile-passkey-flow.md).
        XCTAssertEqual(publicKey, coseKey.base64URLEncodedString())
        XCTAssertNotEqual(publicKey, attestationObject.base64URLEncodedString())
    }

    func test_extractCOSEPublicKey_ignoresTrailingExtensionsAfterCOSEKey() throws {
        // WebAuthn authData may have an extensions CBOR item after the credentialPublicKey
        // (when the ED flag is set). Extraction must stop at the end of the COSE_Key item,
        // not swallow trailing bytes into the extracted public key.
        let x = Data(repeating: 0x33, count: 32)
        let y = Data(repeating: 0x44, count: 32)
        let coseKey = cborES256COSEKey(x: x, y: y)
        let credentialID = Data([0x01, 0x02, 0x03])
        var authData = cborAuthData(credentialID: credentialID, coseKey: coseKey)
        authData.append(contentsOf: [0xA0]) // trailing CBOR item (empty map) simulating extensions

        let publicKey = try PasskeyService.extractCOSEPublicKey(fromAttestationObject: cborAttestationObject(authData: authData))

        XCTAssertEqual(publicKey, coseKey.base64URLEncodedString())
    }

    func test_extractCOSEPublicKey_missingAttestedCredentialData_throws() {
        // AT flag (0x40) unset — no attested credential data present.
        let authData = Data(repeating: 0, count: 40)
        let attestationObject = cborAttestationObject(authData: authData)
        XCTAssertThrowsError(try PasskeyService.extractCOSEPublicKey(fromAttestationObject: attestationObject))
    }

    func test_extractCOSEPublicKey_nilAttestationObject_throws() {
        XCTAssertThrowsError(try PasskeyService.extractCOSEPublicKey(fromAttestationObject: nil))
    }

    func test_extractCOSEPublicKey_malformedCBOR_throws() {
        let garbage = Data([0xFF, 0xFF, 0xFF])
        XCTAssertThrowsError(try PasskeyService.extractCOSEPublicKey(fromAttestationObject: garbage))
    }
}

// MARK: - #213 Attestation Format Extraction Tests

final class AttestationFormatExtractionTests: XCTestCase {

    func test_attestationFormat_returnsFmtValue() {
        let authData = cborAuthData(credentialID: Data([0x01]), coseKey: cborES256COSEKey(x: Data(repeating: 0, count: 32), y: Data(repeating: 0, count: 32)))
        let attestationObject = cborAttestationObject(authData: authData)

        XCTAssertEqual(PasskeyService.attestationFormat(fromAttestationObject: attestationObject), "none")
    }

    func test_attestationFormat_nilAttestationObject_returnsNil() {
        XCTAssertNil(PasskeyService.attestationFormat(fromAttestationObject: nil))
    }

    func test_attestationFormat_malformedCBOR_returnsNil() {
        let garbage = Data([0xFF, 0xFF, 0xFF])
        XCTAssertNil(PasskeyService.attestationFormat(fromAttestationObject: garbage))
    }
}

// MARK: - #213 Passkey Diagnostics Logger Tests

final class PasskeyDiagnosticsLoggerTests: XCTestCase {

    override func tearDown() {
        PasskeyDiagnosticsLogger.shared.clearLog()
        super.tearDown()
    }

    func test_logRegistrationFailure_recordsAttachmentAndFormat_neverCredentialMaterial() {
        PasskeyDiagnosticsLogger.shared.clearLog()

        PasskeyDiagnosticsLogger.shared.logRegistrationFailure(
            authenticatorAttachment: "platform",
            attestationFormat: "packed",
            reason: "registrationFailed"
        )

        let events = PasskeyDiagnosticsLogger.shared.getLoggedEvents()
        XCTAssertEqual(events.count, 1)
        XCTAssertEqual(events[0].authenticatorAttachment, "platform")
        XCTAssertEqual(events[0].attestationFormat, "packed")
    }

    func test_clearLog_removesAllEvents() {
        PasskeyDiagnosticsLogger.shared.logRegistrationFailure(
            authenticatorAttachment: "platform", attestationFormat: nil, reason: "ceremonyFailed"
        )
        PasskeyDiagnosticsLogger.shared.clearLog()

        XCTAssertTrue(PasskeyDiagnosticsLogger.shared.getLoggedEvents().isEmpty)
    }
}

// MARK: - #4 Registration/Persistence Atomicity Tests

final class PasskeyServiceRegistrationAtomicityTests: XCTestCase {

    override func tearDown() {
        // PasskeyService.shared is a true singleton — restore its production seams so a
        // leaked spy from this test doesn't affect any other test in the process.
        PasskeyService.shared.registerWithBackend = { credentialID, publicKey, clientDataJSON in
            try await APIClient.shared.registerPasskey(credentialID: credentialID, publicKey: publicKey, clientDataJSON: clientDataJSON)
        }
        PasskeyService.shared.persistCredentialID = { KeychainService.shared.saveCredentialID($0) }
        super.tearDown()
    }

    func test_completeRegistration_persistsCredentialID_immediatelyAfterBackendSuccess() async throws {
        var callOrder: [String] = []
        PasskeyService.shared.registerWithBackend = { credentialID, _, _ in
            callOrder.append("backend:\(credentialID)")
            return AuthToken(token: "tok", expiresAt: Date().addingTimeInterval(3_600))
        }
        PasskeyService.shared.persistCredentialID = { credentialID in
            callOrder.append("persist:\(credentialID)")
        }

        let token = try await PasskeyService.shared.completeRegistration(
            credentialID: "cred-123", publicKey: "pub-key", clientDataJSON: "client-data"
        )

        XCTAssertEqual(token.token, "tok")
        // Credential-ID persistence must happen immediately after the backend call
        // succeeds — in the same synchronous continuation, never deferred to the
        // caller (#4) — so there is no window where the app could be killed with the
        // backend registered but no local record of the credential ID.
        XCTAssertEqual(callOrder, ["backend:cred-123", "persist:cred-123"])
    }

    func test_completeRegistration_whenBackendCallFails_neverPersistsCredentialID() async {
        enum FakeError: Error { case backendRejected }
        var persistCalled = false
        PasskeyService.shared.registerWithBackend = { _, _, _ in throw FakeError.backendRejected }
        PasskeyService.shared.persistCredentialID = { _ in persistCalled = true }

        do {
            _ = try await PasskeyService.shared.completeRegistration(
                credentialID: "cred-456", publicKey: "pub-key", clientDataJSON: "client-data"
            )
            XCTFail("Expected completeRegistration to throw when the backend call fails")
        } catch {
            // expected
        }

        XCTAssertFalse(persistCalled, "A failed backend registration must never leave an orphaned local credential ID")
    }
}

// MARK: - #624 Opaque Server-Issued User Handle Tests

final class PasskeyServiceUserHandleTests: XCTestCase {

    override func tearDown() {
        // PasskeyService.shared is a true singleton — restore its production seams so a
        // leaked spy from this test doesn't affect any other test in the process.
        PasskeyService.shared.fetchUserHandle = { try await APIClient.shared.fetchPasskeyUserHandle() }
        PasskeyService.shared.persistUserHandle = { KeychainService.shared.saveUserHandle($0) }
        PasskeyService.shared.loadPersistedUserHandle = { KeychainService.shared.loadUserHandle() }
        super.tearDown()
    }

    func test_userHandle_isOpaqueServerIssued64ByteValue_notDerivedFromUsername() async throws {
        let serverHandle = Data((0..<64).map { UInt8($0) })
        PasskeyService.shared.fetchUserHandle = { serverHandle }

        let handle = try await PasskeyService.shared.resolveUserHandle(username: "alice@example.com")

        // The WebAuthn user handle must be the opaque value issued by the server (#624),
        // never a value derived from the username.
        XCTAssertEqual(handle, serverHandle)
        XCTAssertEqual(handle.count, 64)
        XCTAssertNotEqual(handle, Data("alice@example.com".utf8))
        XCTAssertNotEqual(handle, Data("alice@example.com".utf8).sha256())
    }

    func test_resolveUserHandle_persistsServerIssuedHandle() async throws {
        let serverHandle = Data(repeating: 0x5A, count: 64)
        var persisted: Data?
        PasskeyService.shared.fetchUserHandle = { serverHandle }
        PasskeyService.shared.persistUserHandle = { persisted = $0 }

        _ = try await PasskeyService.shared.resolveUserHandle(username: "bob")

        XCTAssertEqual(persisted, serverHandle)
    }

    func test_resolveUserHandle_reusesPersistedHandleAcrossSessions_withoutRefetching() async throws {
        let persistedHandle = Data(repeating: 0x7E, count: 64)
        var fetchCalled = false
        PasskeyService.shared.loadPersistedUserHandle = { persistedHandle }
        PasskeyService.shared.fetchUserHandle = {
            fetchCalled = true
            return Data(repeating: 0x00, count: 64)
        }

        let handle = try await PasskeyService.shared.resolveUserHandle(username: "carol")

        XCTAssertEqual(handle, persistedHandle)
        XCTAssertFalse(fetchCalled, "A persisted user handle must be reused across sessions, not re-fetched")
    }

    func test_migration_keepsExistingHandleForAlreadyRegisteredPasskey() async throws {
        // An account whose passkey was registered before #624 already has a handle
        // stored locally; migration must not change it.
        let existingHandle = Data(repeating: 0x42, count: 64)
        var fetchCalled = false
        var persisted: Data?
        PasskeyService.shared.loadPersistedUserHandle = { existingHandle }
        PasskeyService.shared.fetchUserHandle = {
            fetchCalled = true
            return Data(repeating: 0x99, count: 64)
        }
        PasskeyService.shared.persistUserHandle = { persisted = $0 }

        let handle = try await PasskeyService.shared.resolveUserHandle(username: "dave")

        XCTAssertEqual(handle, existingHandle)
        XCTAssertFalse(fetchCalled, "Migration must not issue a new handle for an already-registered passkey")
        XCTAssertNil(persisted, "Migration must not overwrite the existing handle")
    }

    func test_resolveUserHandle_rejectsServerHandleThatIsNot64Bytes() async {
        PasskeyService.shared.fetchUserHandle = { Data(repeating: 0x01, count: 32) }

        do {
            _ = try await PasskeyService.shared.resolveUserHandle(username: "erin")
            XCTFail("Expected resolveUserHandle to reject a non-64-byte server handle")
        } catch {
            // expected
        }
    }
}

// MARK: - #8 Excluded Credentials Tests

@available(iOS 17.4, *)
final class PasskeyServiceExcludedCredentialsTests: XCTestCase {

    func test_excludedCredentialDescriptors_mapsEachIDToADescriptor() {
        let idA = Data([0x01, 0x02, 0x03]).base64URLEncodedString()
        let idB = Data([0xAA, 0xBB]).base64URLEncodedString()

        let descriptors = PasskeyService.excludedCredentialDescriptors(from: [idA, idB])

        XCTAssertEqual(descriptors.count, 2)
        XCTAssertEqual(descriptors[0].credentialID, Data([0x01, 0x02, 0x03]))
        XCTAssertEqual(descriptors[1].credentialID, Data([0xAA, 0xBB]))
    }

    func test_excludedCredentialDescriptors_emptyInput_returnsEmpty() {
        XCTAssertTrue(PasskeyService.excludedCredentialDescriptors(from: []).isEmpty)
    }

    func test_excludedCredentialDescriptors_skipsUndecodableIDs() {
        let valid = Data([0x01]).base64URLEncodedString()
        let descriptors = PasskeyService.excludedCredentialDescriptors(from: [valid, "not valid base64url!!"])

        XCTAssertEqual(descriptors.count, 1)
        XCTAssertEqual(descriptors[0].credentialID, Data([0x01]))
    }
}

// MARK: - #7 ASAuthorizationError → PasskeyError Mapping Tests

final class PasskeyErrorMappingTests: XCTestCase {

    func test_canceled_mapsToUserCancelled() {
        let mapped = PasskeyError.map(ASAuthorizationError(.canceled), fallback: .registrationFailed)
        XCTAssertEqual(mapped, .userCancelled)
    }

    func test_notInteractive_mapsToNotInteractive() {
        let mapped = PasskeyError.map(ASAuthorizationError(.notInteractive), fallback: .registrationFailed)
        XCTAssertEqual(mapped, .notInteractive)
    }

    func test_matchedExcludedCredential_mapsToCredentialAlreadyExists() {
        guard #available(iOS 18.0, *) else { return }
        let mapped = PasskeyError.map(ASAuthorizationError(.matchedExcludedCredential), fallback: .registrationFailed)
        XCTAssertEqual(mapped, .credentialAlreadyExists)
    }

    func test_unknownAuthorizationErrorCode_fallsBackToProvidedDefault_registration() {
        let mapped = PasskeyError.map(ASAuthorizationError(.unknown), fallback: .registrationFailed)
        XCTAssertEqual(mapped, .registrationFailed)
    }

    func test_failedAuthorizationErrorCode_fallsBackToProvidedDefault_authentication() {
        let mapped = PasskeyError.map(ASAuthorizationError(.failed), fallback: .authenticationFailed)
        XCTAssertEqual(mapped, .authenticationFailed)
    }

    func test_nonAuthorizationError_fallsBackToProvidedDefault() {
        struct SomeOtherError: Error {}
        let mapped = PasskeyError.map(SomeOtherError(), fallback: .registrationFailed)
        XCTAssertEqual(mapped, .registrationFailed)
    }

    // MARK: - #210 No biometric hardware / not enrolled

    private func makeUnderlyingLAError(_ code: LAError.Code) -> NSError {
        let underlying = NSError(domain: LAErrorDomain, code: code.rawValue)
        return NSError(domain: ASAuthorizationErrorDomain,
                        code: ASAuthorizationError.failed.rawValue,
                        userInfo: [NSUnderlyingErrorKey: underlying])
    }

    func test_biometryNotEnrolled_mapsToBiometricUnavailable() {
        let mapped = PasskeyError.map(makeUnderlyingLAError(.biometryNotEnrolled), fallback: .registrationFailed)
        XCTAssertEqual(mapped, .biometricUnavailable)
    }

    func test_biometryNotAvailable_mapsToBiometricUnavailable() {
        let mapped = PasskeyError.map(makeUnderlyingLAError(.biometryNotAvailable), fallback: .registrationFailed)
        XCTAssertEqual(mapped, .biometricUnavailable)
    }

    func test_passcodeNotSet_mapsToBiometricUnavailable() {
        let mapped = PasskeyError.map(makeUnderlyingLAError(.passcodeNotSet), fallback: .registrationFailed)
        XCTAssertEqual(mapped, .biometricUnavailable)
    }

    func test_biometricUnavailable_hasActionableCopy() {
        let message = PasskeyError.biometricUnavailable.errorDescription ?? ""
        XCTAssertTrue(message.localizedCaseInsensitiveContains("passcode") ||
                      message.localizedCaseInsensitiveContains("Face ID") ||
                      message.localizedCaseInsensitiveContains("Touch ID"))
    }

    func test_allCases_haveDistinctNonEmptyDescriptions() {
        let cases: [PasskeyError] = [.registrationFailed, .authenticationFailed, .userCancelled, .notInteractive, .credentialAlreadyExists]
        let descriptions = cases.compactMap { $0.errorDescription }

        XCTAssertEqual(descriptions.count, cases.count, "Every case should have a description")
        XCTAssertEqual(Set(descriptions).count, cases.count, "Each case should have distinct copy")
    }
}

// MARK: - CBOR Test Fixtures
//
// Minimal CBOR (RFC 8949) encoder used only to build test fixtures, deliberately
// independent of the CBORReader decoder under test so a passing test validates the
// decoder against a spec-compliant encoding, not just against itself. Mirrors the
// Android test fixture builder (CborTestUtils.kt) so both platforms are exercised
// against conceptually the same wire format.

private func cborHeader(_ majorType: Int, _ length: Int) -> Data {
    let mt = majorType << 5
    if length < 24 { return Data([UInt8(mt | length)]) }
    if length < 256 { return Data([UInt8(mt | 24), UInt8(length)]) }
    return Data([UInt8(mt | 25), UInt8((length >> 8) & 0xFF), UInt8(length & 0xFF)])
}

private func cborUInt(_ value: Int) -> Data { cborHeader(0, value) }
private func cborNegInt(_ value: Int) -> Data { cborHeader(1, -value - 1) } // value must be negative
private func cborBytes(_ value: Data) -> Data { cborHeader(2, value.count) + value }
private func cborText(_ value: String) -> Data {
    let bytes = Data(value.utf8)
    return cborHeader(3, bytes.count) + bytes
}

/// Builds a synthetic COSE_Key (ES256/EC2) CBOR map: {1: 2, 3: -7, -1: 1, -2: x, -3: y}.
private func cborES256COSEKey(x: Data, y: Data) -> Data {
    var result = cborHeader(5, 5)
    result += cborUInt(1) + cborUInt(2)     // kty: EC2
    result += cborUInt(3) + cborNegInt(-7)  // alg: ES256
    result += cborNegInt(-1) + cborUInt(1)  // crv: P-256
    result += cborNegInt(-2) + cborBytes(x)
    result += cborNegInt(-3) + cborBytes(y)
    return result
}

/// Builds a synthetic authData blob (WebAuthn §6.1) containing attested credential data,
/// with the given COSE_Key bytes as the credentialPublicKey.
private func cborAuthData(credentialID: Data, coseKey: Data) -> Data {
    Data(repeating: 0xAB, count: 32)  // rpIdHash (arbitrary, unchecked by the code under test)
        + Data([0x41])                 // flags: AT (attested credential data present) bit set
        + Data([0, 0, 0, 0])            // signCount
        + Data(repeating: 0, count: 16) // aaguid (arbitrary)
        + Data([UInt8(credentialID.count >> 8), UInt8(credentialID.count & 0xFF)])
        + credentialID
        + coseKey
}

private func cborAttestationObject(authData: Data) -> Data {
    cborHeader(5, 3)
        + cborText("fmt") + cborText("none")
        + cborText("attStmt") + cborHeader(5, 0)
        + cborText("authData") + cborBytes(authData)
}

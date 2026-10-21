import XCTest
@testable import EthosProtocol

final class LogRedactorTests: XCTestCase {

    // MARK: - redactString

    /// `redactString` must replace the token portion of a Bearer credential
    /// so that the raw token value does not appear in logs.
    func testBearerTokenRedacted() {
        let input = "Authorization: Bearer abc123.def456.ghi789"
        let output = LogRedactor.redactString(input)

        XCTAssertFalse(output.contains("abc123"),
            "Raw Bearer token 'abc123' must not appear in redacted output")
        XCTAssertFalse(output.contains("def456"),
            "Raw Bearer token segment 'def456' must not appear in redacted output")
        XCTAssertTrue(output.contains("Bearer [REDACTED]"),
            "Output should contain the 'Bearer [REDACTED]' placeholder")
    }

    /// Bearer redaction must be case-insensitive.
    func testBearerTokenRedactedCaseInsensitive() {
        let input = "authorization: bearer MYSECRETTOKEN"
        let output = LogRedactor.redactString(input)

        XCTAssertFalse(output.contains("MYSECRETTOKEN"),
            "Token must be redacted regardless of 'bearer' casing")
    }

    /// Nonce values embedded in a log string (e.g. "x-nonce: abc123def") must
    /// have their value replaced with `[REDACTED]`.
    func testNonceRedacted() {
        let input = "x-nonce: a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"
        let output = LogRedactor.redactString(input)

        XCTAssertFalse(
            output.contains("a1b2c3d4e5f6"),
            "Nonce value must not appear in redacted output"
        )
        XCTAssertTrue(output.contains("[REDACTED]"),
            "Output should contain the [REDACTED] placeholder in place of the nonce")
    }

    /// Strings that contain no sensitive patterns must pass through unchanged.
    func testNonSensitiveStringUnchanged() {
        let input = "GET /vaults HTTP/1.1 -> 200 OK"
        let output = LogRedactor.redactString(input)

        XCTAssertEqual(output, input,
            "Non-sensitive strings must pass through LogRedactor.redactString unchanged")
    }

    // MARK: - redactHeaders

    /// The `authorization` header value must be replaced with `[REDACTED]`.
    func testNonceHeaderRedacted() {
        let headers = ["Authorization": "Bearer super-secret-token-value"]
        let redacted = LogRedactor.redactHeaders(headers)

        XCTAssertEqual(redacted["Authorization"], "[REDACTED]",
            "Authorization header value must be replaced with [REDACTED]")
        XCTAssertFalse(
            (redacted["Authorization"] ?? "").contains("super-secret-token-value"),
            "Raw token must not survive header redaction"
        )
    }

    /// Header name matching must be case-insensitive.
    func testHeaderRedactionCaseInsensitive() {
        let headers = [
            "AUTHORIZATION": "Bearer token-abc",
            "X-Nonce": "deadbeef",
            "X-OTP": "123456",
            "x-2fa-token": "totp-secret"
        ]
        let redacted = LogRedactor.redactHeaders(headers)

        for key in headers.keys {
            XCTAssertEqual(redacted[key], "[REDACTED]",
                "Header '\(key)' should be redacted regardless of its casing")
        }
    }

    /// Non-sensitive headers (e.g. `Content-Type`) must pass through unchanged.
    func testNonSensitiveHeadersUnchanged() {
        let headers = [
            "Content-Type": "application/json",
            "Accept": "application/json",
            "X-Timestamp": "1700000000"
        ]
        let redacted = LogRedactor.redactHeaders(headers)

        XCTAssertEqual(redacted["Content-Type"], "application/json",
            "Content-Type must not be redacted")
        XCTAssertEqual(redacted["Accept"], "application/json",
            "Accept must not be redacted")
        XCTAssertEqual(redacted["X-Timestamp"], "1700000000",
            "X-Timestamp must not be redacted")
    }

    /// A mix of sensitive and non-sensitive headers — only sensitive ones get redacted.
    func testMixedHeaders() {
        let headers = [
            "Content-Type": "application/json",
            "Authorization": "Bearer abc.def.ghi",
            "X-Nonce": "0011223344556677",
            "Accept": "application/json"
        ]
        let redacted = LogRedactor.redactHeaders(headers)

        XCTAssertEqual(redacted["Content-Type"], "application/json")
        XCTAssertEqual(redacted["Accept"], "application/json")
        XCTAssertEqual(redacted["Authorization"], "[REDACTED]")
        XCTAssertEqual(redacted["X-Nonce"], "[REDACTED]")
    }

    // MARK: - DecodingFailureLogger key-pattern redaction (#625)

    /// Keys that merely *contain* a sensitive substring (e.g. `access_token`,
    /// `refresh_token`, `totp_secret`, `recovery_token`) must be redacted, not
    /// just exact matches like `token`.
    func testDecodingFailureLoggerRedactsKeySubstrings() {
        DecodingFailureLogger.clear()
        let body = """
        {"access_token":"AAA-ACCESS-VALUE","refresh_token":"BBB-REFRESH-VALUE",\
        "totp_secret":"CCC-TOTP-VALUE","recovery_token":"DDD-RECOVERY-VALUE"}
        """
        let logged = DecodingFailureLogger.log(
            endpoint: "/auth/refresh",
            statusCode: 200,
            body: body,
            error: "unexpected shape"
        )

        XCTAssertFalse(logged.contains("AAA-ACCESS-VALUE"),
            "access_token value must be redacted")
        XCTAssertFalse(logged.contains("BBB-REFRESH-VALUE"),
            "refresh_token value must be redacted")
        XCTAssertFalse(logged.contains("CCC-TOTP-VALUE"),
            "totp_secret value must be redacted")
        XCTAssertFalse(logged.contains("DDD-RECOVERY-VALUE"),
            "recovery_token value must be redacted")

        let events = DecodingFailureLogger.getLoggedEvents()
        XCTAssertEqual(events.count, 1)
        let eventText = String(describing: events.first!)
        XCTAssertFalse(eventText.contains("AAA-ACCESS-VALUE"))
        XCTAssertFalse(eventText.contains("BBB-REFRESH-VALUE"))
        XCTAssertFalse(eventText.contains("CCC-TOTP-VALUE"))
        XCTAssertFalse(eventText.contains("DDD-RECOVERY-VALUE"))
    }

    // MARK: - DecodingFailureLogger contract-listed fields (#626)

    /// Fields listed in `shared/api-contract.md` §Logging Redaction Policy
    /// (balances, amounts, addresses, acceptance tokens) must be redacted.
    func testDecodingFailureLoggerRedactsContractListedFields() {
        DecodingFailureLogger.clear()
        let body = """
        {"vaults":[{"balance":"123456789","amount":"987654321",\
        "beneficiary":"BENEFICIARY-ADDR-VALUE","owner":"OWNER-ADDR-VALUE",\
        "address":"ADDRESS-VALUE","acceptance_token":"ACCEPTANCE-TOKEN-VALUE"}]}
        """
        let logged = DecodingFailureLogger.log(
            endpoint: "/vaults",
            statusCode: 200,
            body: body,
            error: "unexpected shape"
        )

        for value in [
            "123456789",
            "987654321",
            "BENEFICIARY-ADDR-VALUE",
            "OWNER-ADDR-VALUE",
            "ADDRESS-VALUE",
            "ACCEPTANCE-TOKEN-VALUE"
        ] {
            XCTAssertFalse(logged.contains(value),
                "Contract-listed sensitive value '\(value)' must not appear in the logged string")
        }
    }

    // MARK: - Debug-only gating (#627)

    /// Diagnostic logging must be restricted to debug builds. In release
    /// configurations both loggers must be no-ops and their in-memory logs
    /// must stay empty.
    func testDiagnosticLoggersAreNoOpsInRelease() {
        DecodingFailureLogger.clear()
        PasskeyDiagnosticsLogger.clear()

        let decodingLogged = DecodingFailureLogger.log(
            endpoint: "/auth/refresh",
            statusCode: 200,
            body: "{\"access_token\":\"RELEASE-TOKEN-VALUE\"}",
            error: "unexpected shape"
        )
        PasskeyDiagnosticsLogger.logRegistrationFailure(
            reason: "RELEASE-PASSKEY-FAILURE",
            context: ["credentialId": "RELEASE-CREDENTIAL-ID"]
        )

        #if DEBUG
        XCTAssertFalse(decodingLogged.isEmpty,
            "In DEBUG builds the decoding failure should be logged")
        XCTAssertEqual(DecodingFailureLogger.getLoggedEvents().count, 1)
        XCTAssertEqual(PasskeyDiagnosticsLogger.getLoggedEvents().count, 1)
        #else
        XCTAssertTrue(decodingLogged.isEmpty,
            "In release builds DecodingFailureLogger.log must be a no-op")
        XCTAssertTrue(DecodingFailureLogger.getLoggedEvents().isEmpty,
            "In release builds DecodingFailureLogger must not retain events")
        XCTAssertTrue(PasskeyDiagnosticsLogger.getLoggedEvents().isEmpty,
            "In release builds PasskeyDiagnosticsLogger must not retain events")
        #endif
    }

    // MARK: - Bounded in-memory growth (#628)

    /// Both loggers must retain only the most recent `maxEntries` events so a
    /// repeatedly failing endpoint cannot grow memory for the life of the process.
    func testDecodingFailureLoggerCapsEventLog() {
        DecodingFailureLogger.clear()
        let total = 1000
        for index in 0..<total {
            _ = DecodingFailureLogger.log(
                endpoint: "/vaults",
                statusCode: 200,
                body: "{\"index\":\(index)}",
                error: "unexpected shape"
            )
        }

        let events = DecodingFailureLogger.getLoggedEvents()
        XCTAssertEqual(events.count, DecodingFailureLogger.maxEntries,
            "Event log must be capped at maxEntries")
        XCTAssertLessThanOrEqual(events.count, total)

        // The newest entry must be retained and the oldest dropped.
        let eventText = String(describing: events)
        XCTAssertTrue(eventText.contains("\(total - 1)"),
            "The most recent event must be retained after the cap is applied")
        XCTAssertFalse(eventText.contains("\"index\":0,"),
            "The oldest event must be evicted once the cap is exceeded")
    }

    func testPasskeyDiagnosticsLoggerCapsEventLog() {
        PasskeyDiagnosticsLogger.clear()
        let total = 1000
        for index in 0..<total {
            PasskeyDiagnosticsLogger.logRegistrationFailure(
                reason: "failure-\(index)",
                context: ["attempt": "\(index)"]
            )
        }

        let events = PasskeyDiagnosticsLogger.getLoggedEvents()
        XCTAssertEqual(events.count, PasskeyDiagnosticsLogger.maxEntries,
            "Event log must be capped at maxEntries")
        XCTAssertLessThanOrEqual(events.count, total)

        let eventText = String(describing: events)
        XCTAssertTrue(eventText.contains("failure-\(total - 1)"),
            "The most recent event must be retained after the cap is applied")
        XCTAssertFalse(eventText.contains("failure-0,"),
            "The oldest event must be evicted once the cap is exceeded")
    }
}

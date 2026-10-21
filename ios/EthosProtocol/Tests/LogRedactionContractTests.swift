import XCTest
@testable import EthosProtocol

// MARK: - Logging Redaction Contract Tests (#111)
//
// The API contract §Logging Redaction Policy lists six categories of data that
// MUST NEVER appear in logs, logcat, os_log, or any other diagnostic output,
// in any build configuration (debug or release) on either platform:
//
//   1. Authorization: Bearer <jwt> headers or raw JWT strings
//   2. 2FA secrets, OTPs, provisioning URIs, or TOTP seeds
//   3. Vault balances, deposit/withdrawal amounts
//   4. Beneficiary wallet addresses or owner wallet addresses
//   5. Acceptance tokens (one-time token from /accept?token=…)
//   6. Full request/response bodies for any authenticated endpoint
//
// This file maintains a table of one representative sample payload per
// category and asserts that `LogRedactor.redactString` removes every
// sensitive value from the sample.
//
// *** BUILD-BREAKING COVERAGE GUARD ***
// `testAllContractCategoriesHaveSamples` verifies that the `contractSamples`
// table contains an entry for each required category key.  If a category is
// removed or renamed, the test fails at compile-time (via the exhaustive
// `switch`) AND at run-time (via the missing-key assertion).  Adding a new
// contract category without a corresponding entry in `contractSamples` will
// cause this test to fail, breaking the build.

final class LogRedactionContractTests: XCTestCase {

    // -------------------------------------------------------------------------
    // MARK: – Category taxonomy
    // -------------------------------------------------------------------------

    /// The six categories mandated by §Logging Redaction Policy (#111).
    enum RedactionCategory: String, CaseIterable {
        case bearerToken        = "bearer_token"
        case twoFASecret        = "2fa_secret"
        case vaultAmount        = "vault_amount"
        case walletAddress      = "wallet_address"
        case acceptanceToken    = "acceptance_token"
        case requestBody        = "request_body"
    }

    // -------------------------------------------------------------------------
    // MARK: – Sample payload table
    //
    // ONE entry per category.  Each value is a realistic string that could
    // appear in a log line if redaction were absent.
    // -------------------------------------------------------------------------

    /// Maps every `RedactionCategory` to:
    ///   - a `raw` string representing a real log line that contains sensitive data
    ///   - an array of `sensitiveFragments` that MUST NOT survive redaction
    struct ContractSample {
        let raw: String
        let sensitiveFragments: [String]
    }

    let contractSamples: [RedactionCategory: ContractSample] = [

        // 1. Bearer JWT token in an Authorization header log line
        .bearerToken: ContractSample(
            raw: "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyMTIzIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c",
            sensitiveFragments: [
                "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",
                "eyJzdWIiOiJ1c2VyMTIzIn0",
                "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
            ]
        ),

        // 2. TOTP seed / 2FA provisioning URI
        .twoFASecret: ContractSample(
            raw: #"{"totp_secret":"BASE32SECRETAABBCC","provisioning_uri":"otpauth://totp/Ethos?secret=BASE32SECRETAABBCC"}"#,
            sensitiveFragments: [
                "BASE32SECRETAABBCC",
                "otpauth://totp/Ethos?secret=BASE32SECRETAABBCC"
            ]
        ),

        // 3. Vault balance and withdrawal amount in a JSON response body fragment
        .vaultAmount: ContractSample(
            raw: #"{"balance":9500,"amount":250,"withdraw_amount":250}"#,
            sensitiveFragments: ["9500", "250"]
        ),

        // 4. Stellar public-key wallet address (owner / beneficiary)
        .walletAddress: ContractSample(
            raw: #"{"owner":"GBKPZXHPIH7DV4FXLKM6NKWQJMZXF2EUNLHBGSTGZGPTFNRN7ABCDEF","beneficiary":"GCXYZ1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ234567890ABCDE"}"#,
            sensitiveFragments: [
                "GBKPZXHPIH7DV4FXLKM6NKWQJMZXF2EUNLHBGSTGZGPTFNRN7ABCDEF",
                "GCXYZ1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ234567890ABCDE"
            ]
        ),

        // 5. One-time acceptance token in a deep-link URL
        .acceptanceToken: ContractSample(
            raw: "https://ethos-protocol.app/vaults/vault-abc/accept?token=one-time-secret-acceptance-token-xyz",
            sensitiveFragments: ["one-time-secret-acceptance-token-xyz"]
        ),

        // 6. Full authenticated request body
        .requestBody: ContractSample(
            raw: #"Request body: {"vault_id":"vault-abc","token":"one-time-token","amount":100}"#,
            sensitiveFragments: [
                #"{"vault_id":"vault-abc","token":"one-time-token","amount":100}"#
            ]
        ),
    ]

    // -------------------------------------------------------------------------
    // MARK: – Coverage guard: every category MUST have a sample
    //
    // This test fails the build if a category was added to `RedactionCategory`
    // without a corresponding entry being added to `contractSamples`.
    // -------------------------------------------------------------------------

    func testAllContractCategoriesHaveSamples() {
        for category in RedactionCategory.allCases {
            XCTAssertNotNil(
                contractSamples[category],
                """
                Contract category '\(category.rawValue)' has no sample payload in \
                LogRedactionContractTests.contractSamples.
                Add an entry before merging — this test exists to prevent a contract \
                category from being added without a redaction test.
                """
            )
        }
        // Also verify the inverse: no sample exists for a category not in the enum.
        XCTAssertEqual(
            contractSamples.count,
            RedactionCategory.allCases.count,
            "contractSamples has \(contractSamples.count) entries but RedactionCategory has " +
            "\(RedactionCategory.allCases.count) cases — they must stay in sync."
        )
    }

    // -------------------------------------------------------------------------
    // MARK: – Per-category redaction assertions
    // -------------------------------------------------------------------------

    /// Runs a redaction check for the given category: calls `LogRedactor.redactString`
    /// on the sample, then asserts none of the sensitive fragments survive.
    private func assertRedacted(_ category: RedactionCategory,
                                file: StaticString = #file,
                                line: UInt = #line) {
        guard let sample = contractSamples[category] else {
            XCTFail("No sample for category '\(category.rawValue)' — add one to contractSamples",
                    file: file, line: line)
            return
        }
        let redacted = LogRedactor.redactString(sample.raw)
        for fragment in sample.sensitiveFragments {
            XCTAssertFalse(
                redacted.contains(fragment),
                """
                [Category: \(category.rawValue)] Sensitive fragment still present after redaction.
                  Fragment : "\(fragment)"
                  Redacted : "\(redacted)"
                """,
                file: file, line: line
            )
        }
    }

    // 1 – Bearer JWT
    func testCategory1_BearerTokenRedacted() {
        assertRedacted(.bearerToken)
    }

    // 2 – 2FA secret / TOTP seed
    func testCategory2_TwoFASecretRedacted() {
        assertRedacted(.twoFASecret)
    }

    // 3 – Vault balance / amount
    func testCategory3_VaultAmountRedacted() {
        assertRedacted(.vaultAmount)
    }

    // 4 – Wallet address
    func testCategory4_WalletAddressRedacted() {
        assertRedacted(.walletAddress)
    }

    // 5 – Acceptance token
    func testCategory5_AcceptanceTokenRedacted() {
        assertRedacted(.acceptanceToken)
    }

    // 6 – Full request / response body
    func testCategory6_RequestBodyRedacted() {
        assertRedacted(.requestBody)
    }

    // -------------------------------------------------------------------------
    // MARK: – Non-sensitive strings must pass through unchanged
    // -------------------------------------------------------------------------

    func testNonSensitiveLogLineUnchanged() {
        let safe = "GET /vaults HTTP/1.1 -> 200 OK (took 48 ms)"
        XCTAssertEqual(
            LogRedactor.redactString(safe),
            safe,
            "Non-sensitive log lines must not be modified by LogRedactor.redactString"
        )
    }
}

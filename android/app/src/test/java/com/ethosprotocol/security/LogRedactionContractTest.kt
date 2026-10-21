package com.ethosprotocol.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

// ============================================================================
// Logging Redaction Contract Tests (#111)
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
// category and asserts that [LogRedactor.redactString] removes every
// sensitive value from the sample.
//
// *** BUILD-BREAKING COVERAGE GUARD ***
// [testAllContractCategoriesHaveSamples] verifies that [CONTRACT_SAMPLES]
// contains an entry for each required category key.  If a category is removed
// or its key renamed, the test fails at run-time.  Adding a new contract
// category without a corresponding entry will also break the build.
// ============================================================================

class LogRedactionContractTest {

    // -------------------------------------------------------------------------
    // Category taxonomy
    // -------------------------------------------------------------------------

    /**
     * The six categories mandated by §Logging Redaction Policy (#111).
     *
     * The [key] string is used as the lookup key in [CONTRACT_SAMPLES] and in
     * assertion messages, so it should be stable.
     */
    enum class RedactionCategory(val key: String) {
        BEARER_TOKEN("bearer_token"),
        TWO_FA_SECRET("2fa_secret"),
        VAULT_AMOUNT("vault_amount"),
        WALLET_ADDRESS("wallet_address"),
        ACCEPTANCE_TOKEN("acceptance_token"),
        REQUEST_BODY("request_body");

        companion object {
            val ALL: List<RedactionCategory> = entries.toList()
        }
    }

    // -------------------------------------------------------------------------
    // Sample payload table
    //
    // ONE entry per category.  Each value is a realistic string that could
    // appear in a log line if redaction were absent.
    // -------------------------------------------------------------------------

    /**
     * Represents a single test-case for one redaction category.
     *
     * @param raw               The raw log string that contains sensitive data.
     * @param sensitiveFragments Sub-strings that MUST NOT appear in the output
     *                          of [LogRedactor.redactString].
     */
    data class ContractSample(
        val raw: String,
        val sensitiveFragments: List<String>
    )

    companion object {
        /**
         * Maps every [RedactionCategory.key] to a [ContractSample].
         *
         * Keep this table in sync with [RedactionCategory] — the coverage
         * guard test will fail if any category is missing.
         */
        val CONTRACT_SAMPLES: Map<String, ContractSample> = mapOf(

            // 1. Bearer JWT token in an Authorization header log line
            RedactionCategory.BEARER_TOKEN.key to ContractSample(
                raw = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyMTIzIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c",
                sensitiveFragments = listOf(
                    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",
                    "eyJzdWIiOiJ1c2VyMTIzIn0",
                    "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
                )
            ),

            // 2. TOTP seed / 2FA provisioning URI
            RedactionCategory.TWO_FA_SECRET.key to ContractSample(
                raw = """{"totp_secret":"BASE32SECRETAABBCC","provisioning_uri":"otpauth://totp/Ethos?secret=BASE32SECRETAABBCC"}""",
                sensitiveFragments = listOf(
                    "BASE32SECRETAABBCC",
                    "otpauth://totp/Ethos?secret=BASE32SECRETAABBCC"
                )
            ),

            // 3. Vault balance and withdrawal amount in a JSON response body fragment
            RedactionCategory.VAULT_AMOUNT.key to ContractSample(
                raw = """{"balance":9500,"amount":250,"withdraw_amount":250}""",
                sensitiveFragments = listOf("9500", "250")
            ),

            // 4. Stellar public-key wallet address (owner / beneficiary)
            RedactionCategory.WALLET_ADDRESS.key to ContractSample(
                raw = """{"owner":"GBKPZXHPIH7DV4FXLKM6NKWQJMZXF2EUNLHBGSTGZGPTFNRN7ABCDEF","beneficiary":"GCXYZ1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ234567890ABCDE"}""",
                sensitiveFragments = listOf(
                    "GBKPZXHPIH7DV4FXLKM6NKWQJMZXF2EUNLHBGSTGZGPTFNRN7ABCDEF",
                    "GCXYZ1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ234567890ABCDE"
                )
            ),

            // 5. One-time acceptance token in a deep-link URL
            RedactionCategory.ACCEPTANCE_TOKEN.key to ContractSample(
                raw = "https://ethos-protocol.app/vaults/vault-abc/accept?token=one-time-secret-acceptance-token-xyz",
                sensitiveFragments = listOf("one-time-secret-acceptance-token-xyz")
            ),

            // 6. Full authenticated request body
            RedactionCategory.REQUEST_BODY.key to ContractSample(
                raw = """Request body: {"vault_id":"vault-abc","token":"one-time-token","amount":100}""",
                sensitiveFragments = listOf(
                    """{"vault_id":"vault-abc","token":"one-time-token","amount":100}"""
                )
            )
        )
    }

    // -------------------------------------------------------------------------
    // Coverage guard: every category MUST have a sample
    //
    // This test fails if a category was added to RedactionCategory without a
    // corresponding entry being added to CONTRACT_SAMPLES.
    // -------------------------------------------------------------------------

    /**
     * Verifies that [CONTRACT_SAMPLES] contains an entry for every value in
     * [RedactionCategory].  This is a build-breaking check: if a new category
     * is added to the enum without a corresponding sample being added to the
     * table, this test fails and blocks the merge.
     */
    @Test
    fun testAllContractCategoriesHaveSamples() {
        for (category in RedactionCategory.ALL) {
            assertNotNull(
                "Contract category '${category.key}' has no sample payload in " +
                "LogRedactionContractTest.CONTRACT_SAMPLES. " +
                "Add an entry before merging — this test exists to prevent a contract " +
                "category from being added without a redaction test.",
                CONTRACT_SAMPLES[category.key]
            )
        }
        // Inverse: no orphaned samples whose key has no matching category.
        assertEquals(
            "CONTRACT_SAMPLES has ${CONTRACT_SAMPLES.size} entries but RedactionCategory has " +
            "${RedactionCategory.ALL.size} cases — they must stay in sync.",
            RedactionCategory.ALL.size,
            CONTRACT_SAMPLES.size
        )
    }

    // -------------------------------------------------------------------------
    // Per-category redaction assertions
    // -------------------------------------------------------------------------

    /**
     * Runs a redaction check for the given [category]: calls
     * [LogRedactor.redactString] on the sample, then asserts none of the
     * sensitive fragments survive.
     */
    private fun assertRedacted(category: RedactionCategory) {
        val sample = CONTRACT_SAMPLES[category.key]
            ?: error("No sample for category '${category.key}' — add one to CONTRACT_SAMPLES")

        val redacted = LogRedactor.redactString(sample.raw)
        for (fragment in sample.sensitiveFragments) {
            assertFalse(
                "[Category: ${category.key}] Sensitive fragment still present after redaction.\n" +
                "  Fragment : \"$fragment\"\n" +
                "  Redacted : \"$redacted\"",
                redacted.contains(fragment)
            )
        }
    }

    // 1 – Bearer JWT
    @Test
    fun `category 1 - bearer token is redacted`() = assertRedacted(RedactionCategory.BEARER_TOKEN)

    // 2 – 2FA secret / TOTP seed
    @Test
    fun `category 2 - 2FA secret is redacted`() = assertRedacted(RedactionCategory.TWO_FA_SECRET)

    // 3 – Vault balance / amount
    @Test
    fun `category 3 - vault amount is redacted`() = assertRedacted(RedactionCategory.VAULT_AMOUNT)

    // 4 – Wallet address
    @Test
    fun `category 4 - wallet address is redacted`() = assertRedacted(RedactionCategory.WALLET_ADDRESS)

    // 5 – Acceptance token
    @Test
    fun `category 5 - acceptance token is redacted`() = assertRedacted(RedactionCategory.ACCEPTANCE_TOKEN)

    // 6 – Full request / response body
    @Test
    fun `category 6 - request body is redacted`() = assertRedacted(RedactionCategory.REQUEST_BODY)

    // -------------------------------------------------------------------------
    // Non-sensitive strings must pass through unchanged
    // -------------------------------------------------------------------------

    @Test
    fun `non-sensitive log line passes through unchanged`() {
        val safe = "GET /vaults -> 200 OK (took 48 ms)"
        assertEquals(
            "Non-sensitive log lines must not be modified by LogRedactor.redactString",
            safe,
            LogRedactor.redactString(safe)
        )
    }
}

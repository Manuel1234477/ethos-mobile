package com.ethosprotocol.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for [LogRedactor] covering Stellar address redaction.
 *
 * Fixtures mirror shared/stellar-address-fixtures.json so the Android and iOS
 * redactors are validated against the same inputs.
 */
class LogRedactorTest {

    private val gAddress = "GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF"
    private val mAddress =
        "MAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB2M"

    @Test
    fun redactsStellarGAddress() {
        val input = "failed request for beneficiary $gAddress"
        val output = LogRedactor.redact(input)

        assertFalse("G-address must not appear in redacted output", output.contains(gAddress))
        assertTrue("surrounding context is preserved", output.contains("failed request for beneficiary"))
    }

    @Test
    fun redactsStellarMAddress() {
        val input = "failed request for owner $mAddress"
        val output = LogRedactor.redact(input)

        assertFalse("M-address must not appear in redacted output", output.contains(mAddress))
        assertTrue("surrounding context is preserved", output.contains("failed request for owner"))
    }

    @Test
    fun redactsBothAddressesInSingleLine() {
        val input = "beneficiary=$gAddress owner=$mAddress"
        val output = LogRedactor.redact(input)

        assertFalse(output.contains(gAddress))
        assertFalse(output.contains(mAddress))
    }
}

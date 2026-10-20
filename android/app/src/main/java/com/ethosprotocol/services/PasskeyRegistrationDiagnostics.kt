package com.ethosprotocol.services

import java.util.Collections

/**
 * Logs client-side diagnostic signal for passkey registration failures, for support triage
 * of "passkey sign-in doesn't work" reports. Mirrors iOS's `PasskeyDiagnosticsLogger`.
 *
 * Only the authenticator attachment type and attestation format are recorded — see
 * SECURITY.md for the full scope of what is/isn't logged. No public key material,
 * signatures, challenge bytes, or credential IDs are ever logged here.
 */
object PasskeyRegistrationDiagnostics {

    data class Entry(
        val authenticatorAttachment: String,
        val attestationFormat: String?,
        val reason: String,
        val timestampMillis: Long
    )

    /**
     * Guidance shown when the device has no biometric enrollment, so passkey auth cannot
     * proceed. [canOpenSettings] indicates whether the platform exposes a settings intent
     * the user can be sent to in order to enroll.
     */
    data class BiometricEnrollmentGuidance(
        val title: String,
        val message: String,
        val settingsActionLabel: String,
        val canOpenSettings: Boolean
    )

    /** Maximum number of diagnostic entries retained; oldest entries are dropped first. */
    const val MAX_EVENTS = 100

    private val eventLog: MutableList<Entry> = Collections.synchronizedList(mutableListOf())

    /**
     * Logs a registration failure. [attestationFormat] is the WebAuthn `fmt` value (e.g.
     * "packed", "none") when it could be parsed from the attestation object, or `null` when
     * the ceremony failed before one was produced.
     */
    fun logFailure(authenticatorAttachment: String, attestationFormat: String?, reason: String, now: Long = System.currentTimeMillis()) {
        synchronized(eventLog) {
            eventLog.add(Entry(authenticatorAttachment, attestationFormat, reason, now))
            while (eventLog.size > MAX_EVENTS) {
                eventLog.removeAt(0)
            }
        }
    }

    /**
     * Detects whether biometric enrollment is missing for the given authenticator state.
     * [biometricHardwareAvailable] is false when the device has no biometric sensor at all;
     * [biometricEnrolled] is false when the sensor exists but no biometric is enrolled.
     * Enrollment is considered missing only when hardware is present but nothing is enrolled,
     * since a device without hardware cannot be fixed via settings.
     */
    fun isBiometricEnrollmentMissing(biometricHardwareAvailable: Boolean, biometricEnrolled: Boolean): Boolean {
        return biometricHardwareAvailable && !biometricEnrolled
    }

    /**
     * Builds the setup guidance shown when biometric enrollment is missing. Returns `null`
     * when enrollment is present (or hardware is absent), so callers can gate the guidance
     * screen on a single check.
     */
    fun buildEnrollmentGuidance(
        biometricHardwareAvailable: Boolean,
        biometricEnrolled: Boolean,
        canOpenSettings: Boolean
    ): BiometricEnrollmentGuidance? {
        if (!isBiometricEnrollmentMissing(biometricHardwareAvailable, biometricEnrolled)) {
            return null
        }
        return BiometricEnrollmentGuidance(
            title = "Set up biometrics to use passkeys",
            message = "Passkey sign-in needs a biometric enrolled on this device. " +
                "Add a fingerprint or face in your device settings, then try again.",
            settingsActionLabel = "Open device settings",
            canOpenSettings = canOpenSettings
        )
    }

    /**
     * Whether passkey auth should be retried after the user returns from enrolling a
     * biometric. Retry is only meaningful once enrollment is no longer missing.
     */
    fun shouldRetryAfterEnrollment(biometricHardwareAvailable: Boolean, biometricEnrolled: Boolean): Boolean {
        return !isBiometricEnrollmentMissing(biometricHardwareAvailable, biometricEnrolled)
    }

    fun getLoggedEvents(): List<Entry> = synchronized(eventLog) { eventLog.toList() }

    fun clearLog() {
        synchronized(eventLog) { eventLog.clear() }
    }
}

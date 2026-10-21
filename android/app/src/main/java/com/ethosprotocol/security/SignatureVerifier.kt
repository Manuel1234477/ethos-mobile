package com.ethosprotocol.security

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest
import java.util.Base64

// ---------------------------------------------------------------------------
// Result type
// ---------------------------------------------------------------------------

/**
 * Outcome of an APK signature verification check.
 *
 * - [Valid]         – the signing certificate matches [SignatureVerifier.EXPECTED_CERT_SHA256].
 * - [Mismatch]      – the certificate was read successfully but its SHA-256 digest differs
 *                     from the expected value. The [actual] digest is provided for diagnostic
 *                     purposes (log it, never show it to end users).
 * - [Unreadable]    – the signing certificate could not be read at all (e.g. a
 *                     [PackageManager] failure). This is *not* a tamper signal and
 *                     must never be reported as a mismatch.
 * - [NotConfigured] – [SignatureVerifier.EXPECTED_CERT_SHA256] is blank; the check is skipped.
 *                     This is the expected state for debug builds and CI.
 */
sealed class SignatureResult {
    object Valid : SignatureResult()
    data class Mismatch(val actual: String) : SignatureResult()
    data class Unreadable(val cause: Throwable) : SignatureResult()
    object NotConfigured : SignatureResult()
}

/**
 * Outcome of reading the APK's signing certificate digest.
 *
 * - [Readable]   – the digest was computed successfully.
 * - [Unreadable] – the certificate could not be retrieved; [cause] explains why.
 */
sealed class SignatureDigest {
    data class Readable(val value: String) : SignatureDigest()
    data class Unreadable(val cause: Throwable) : SignatureDigest()
}

// ---------------------------------------------------------------------------
// Verifier
// ---------------------------------------------------------------------------

/**
 * Verifies the APK's signing certificate against a hard-coded expected SHA-256
 * digest at runtime. This provides a non-blocking warning when the app has been
 * repackaged and re-signed by a third party (i.e. sideloaded from outside the
 * Play Store).
 *
 * ### Configuration
 * Set [EXPECTED_CERT_SHA256] to the Base64-encoded SHA-256 digest of your
 * release signing certificate's DER-encoded bytes before shipping a production
 * build. Compute it with:
 *
 * ```bash
 * keytool -exportcert -alias <key-alias> -keystore release.jks \
 *   | openssl dgst -sha256 -binary | openssl enc -base64
 * ```
 *
 * Leaving [EXPECTED_CERT_SHA256] empty disables the check (returns [SignatureResult.NotConfigured]),
 * which is the correct behaviour for debug builds and CI where no release keystore is present.
 */
class SignatureVerifier {

    companion object {
        /**
         * Expected Base64-encoded SHA-256 digest of the release signing certificate.
         *
         * Single-sourced from `shared/cert-pins.json`, which is also the source for
         * `.well-known/assetlinks.json` and is cross-checked against the Play App
         * Signing certificate by CI. Do not edit this value by hand; regenerate it
         * from the shared pin file instead.
         *
         * Empty string = check disabled (debug/CI-safe default).
         */
        const val EXPECTED_CERT_SHA256: String = "REPLACE_WITH_RELEASE_CERT_SHA256_FROM_SHARED_CERT_PINS_JSON"
    }

    /**
     * Verifies the current APK's signing certificate.
     *
     * @param context Any [Context] — used to access [PackageManager].
     * @return [SignatureResult.NotConfigured] if [EXPECTED_CERT_SHA256] is blank;
     *         [SignatureResult.Unreadable] if the certificate could not be read;
     *         [SignatureResult.Valid] if the digest matches; [SignatureResult.Mismatch]
     *         if it does not.
     */
    fun verify(context: Context): SignatureResult {
        if (EXPECTED_CERT_SHA256.isBlank()) return SignatureResult.NotConfigured

        return when (val digest = getSignatureSha256(context.packageManager, context.packageName)) {
            is SignatureDigest.Unreadable -> SignatureResult.Unreadable(digest.cause)
            is SignatureDigest.Readable ->
                if (digest.value == EXPECTED_CERT_SHA256) {
                    SignatureResult.Valid
                } else {
                    SignatureResult.Mismatch(digest.value)
                }
        }
    }
}

// ---------------------------------------------------------------------------
// Helper — package-level so it can be called from tests with a mock PM
// ---------------------------------------------------------------------------

/**
 * Returns the Base64-encoded SHA-256 digest of the first signing certificate
 * for [packageName] as a [SignatureDigest.Readable], or a
 * [SignatureDigest.Unreadable] carrying the cause if the certificate cannot be
 * retrieved. Callers must distinguish the two: an unreadable certificate is an
 * environment failure, not evidence of tampering.
 *
 * Uses the `GET_SIGNING_CERTIFICATES` API on Android P+ (API 28) and falls
 * back to the deprecated `GET_SIGNATURES` flag on older releases. Only the
 * *first* certificate in the chain is inspected; multi-signer APKs are not
 * supported by the Play signing pipeline used by this project.
 */
fun getSignatureSha256(pm: PackageManager, packageName: String): SignatureDigest {
    return try {
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures ?: emptyArray()
        }

        if (signatures.isEmpty()) {
            return SignatureDigest.Unreadable(
                IllegalStateException("No signing certificates found for $packageName")
            )
        }

        val certBytes = signatures[0].toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(certBytes)
        SignatureDigest.Readable(Base64.getEncoder().encodeToString(digest))
    } catch (e: Exception) {
        SignatureDigest.Unreadable(e)
    }
}

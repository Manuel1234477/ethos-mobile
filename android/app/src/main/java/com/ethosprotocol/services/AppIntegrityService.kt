package com.ethosprotocol.services

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.ethosprotocol.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * #274 — Play Integrity API token generation for Android.
 *
 * Provides device/app attestation tokens for mutating API requests, beyond the
 * heuristic root-detection checks in [IntegrityChecker].
 *
 * ## Platform support
 * The Play Integrity API (replacing the deprecated SafetyNet Attestation API)
 * is available on Android devices with Google Play Services. The token returned
 * is a signed JWT that the backend verifies against Google's Play Integrity
 * verification server, confirming:
 *   - The APK is the genuine, unmodified release build distributed via Play.
 *   - The device meets Android's basic integrity requirements.
 *   - (On supported devices) The device passes CTS device integrity checks.
 *
 * ## Backend treatment of failed / missing attestation
 *
 * * **Mutating requests (POST / DELETE)**: The backend MUST block the request
 *   and return HTTP 403 when `X-Attestation-Token` is absent or when the token
 *   fails server-side verification against the Play Integrity API. This applies
 *   to all vault-mutation, check-in, 2FA, and push-registration endpoints.
 * * **Read requests (GET)**: The backend SHOULD allow the request but record
 *   the missing/failed attestation as a security event (warn-on-reads policy).
 *
 * ## Header contract (shared/api-contract.md §App Attestation)
 *
 * | Header                   | Value                                         |
 * |--------------------------|----------------------------------------------|
 * | `X-Attestation-Token`    | The signed JWT returned by Play Integrity     |
 * | `X-Attestation-Provider` | `"playintegrity"`                             |
 *
 * The nonce embedded in the token is derived from the per-request challenge
 * returned by the server's `/auth/challenge` endpoint, so each token is
 * bound to a single request and cannot be replayed.
 *
 * ## Testability
 * All Play Services calls are delegated through [IntegrityTokenProvider] so
 * unit tests can inject a stub without a real Play Services connection.
 */
class AppIntegrityService(
    private val context: Context,
    // Overridable in tests.
    internal var tokenProvider: IntegrityTokenProvider = PlayIntegrityTokenProvider(context)
) {
    companion object {
        private const val TAG = "AppIntegrityService"
        const val PROVIDER_PLAY_INTEGRITY = "playintegrity"
    }

    /**
     * Generates a Play Integrity token bound to [nonce].
     *
     * [nonce] should be an opaque, request-specific value derived from the
     * server challenge (e.g. Base64URL-encoded bytes from `/auth/challenge`).
     * The Play Integrity API requires it to be at minimum 16 bytes and no more
     * than 500 bytes after Base64 encoding.
     *
     * @return [AttestationToken] on success, or [AttestationToken.Unavailable]
     *         when Play Services are not available, or throws on hard failure.
     */
    suspend fun generateToken(nonce: String): AttestationToken {
        return try {
            val token = tokenProvider.requestToken(nonce)
            AttestationToken.Success(token = token, provider = PROVIDER_PLAY_INTEGRITY)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Play Integrity token generation failed", e)
            }
            AttestationToken.Failed(e)
        }
    }

    /**
     * #594 — Evaluates a [SignatureVerifier.SignatureResult] and decides whether
     * the tamper warning should be shown.
     *
     * Only an actual [SignatureVerifier.SignatureResult.Mismatch] indicates
     * tampering. An [SignatureVerifier.SignatureResult.Unreadable] result means
     * the signature could not be read (e.g. a `PackageManager` failure) and must
     * NOT be surfaced to the user as a tamper warning; the cause is logged for
     * diagnostics instead.
     *
     * @return `true` if the tamper dialog should be shown, `false` otherwise.
     */
    fun shouldShowTamperWarning(result: SignatureVerifier.SignatureResult): Boolean {
        return when (result) {
            is SignatureVerifier.SignatureResult.Match -> false
            is SignatureVerifier.SignatureResult.Mismatch -> {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Signature mismatch: expected=${result.expected}, actual=${result.actual}")
                }
                true
            }
            is SignatureVerifier.SignatureResult.Unreadable -> {
                Log.w(TAG, "Signature unreadable; skipping tamper warning", result.cause)
                false
            }
        }
    }
}

// ── Result type ────────────────────────────────────────────────────────────────

/**
 * Result of a Play Integrity attestation attempt.
 *
 * [Success.token] is the signed JWT to pass in the `X-Attestation-Token` header.
 * [Success.provider] is always [AppIntegrityService.PROVIDER_PLAY_INTEGRITY].
 * [Failed] wraps the underlying exception for diagnostics.
 * [Unavailable] means the platform cannot produce a token (no Play Services).
 */
sealed class AttestationToken {
    data class Success(val token: String, val provider: String) : AttestationToken()
    data class Failed(val error: Throwable) : AttestationToken()
    object Unavailable : AttestationToken()
}

// ── Provider interface ─────────────────────────────────────────────────────────

/**
 * Abstraction over the Play Integrity API to allow test doubles.
 */
interface IntegrityTokenProvider {
    /** Requests an integrity token bound to [nonce]. Suspends until the token is ready. */
    suspend fun requestToken(nonce: String): String
}

// ── Production implementation ──────────────────────────────────────────────────

/**
 * Production [IntegrityTokenProvider] backed by the real Play Integrity API.
 *
 * Requires `com.google.android.play:integrity` on the classpath (added via
 * build.gradle.kts). The API is available on any device running Android 5.0+
 * (API 21) with Google Play Services 3.3+.
 */
class PlayIntegrityTokenProvider(private val context: Context) : IntegrityTokenProvider {

    override suspend fun requestToken(nonce: String): String =
        suspendCancellableCoroutine { continuation ->
            val manager = IntegrityManagerFactory.create(context.applicationContext)
            val request = IntegrityTokenRequest.builder()
                .setNonce(nonce)
                .build()
            val task = manager.requestIntegrityToken(request)
            task.addOnSuccessListener { response ->
                continuation.resume(response.token())
            }
            task.addOnFailureListener { exception ->
                continuation.resumeWithException(exception)
            }
        }
}

package com.ethosprotocol.services

import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.RetryPolicy
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.api.withRetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers a device's FCM token with the server, retrying with backoff on
 * failure (#234) — `PushService.onNewToken` previously fired the request and
 * ignored the outcome entirely, so a network blip or server 5xx could
 * silently stop the app from receiving push notifications until the next
 * token rotation.
 *
 * Registration is idempotent server-side (re-registering the same token is a
 * no-op), which is why — unlike ApiClient's general policy of never
 * auto-retrying a mutation — retrying this one specifically is safe.
 *
 * If every retry attempt fails, the token is persisted as "pending" via
 * [TokenProvider.pendingPushToken] rather than dropped, so [retryPendingIfNeeded]
 * can pick it back up the next time the app foregrounds (see MainActivity's
 * `onResume`) instead of silently waiting on Firebase to redeliver the token.
 */
@Singleton
class PushTokenRegistrar @Inject constructor(
    private val apiClient: ApiClient,
    private val tokenProvider: TokenProvider
) {
    var retryPolicy: RetryPolicy = RetryPolicy.networkDefault

    // Injectable for testing, so the retry path can be exercised against a
    // mock that fails a controlled number of times instead of a real network call.
    var registerCall: suspend (String) -> ApiResult<Unit> = { apiClient.registerPushToken(it) }

    /** Fire-and-forget entry point for `PushService.onNewToken`. */
    fun register(token: String, scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        scope.launch { registerSuspending(token) }
    }

    suspend fun registerSuspending(token: String) {
        try {
            withRetry(retryPolicy, isRetryable = { e -> isRetryableFailure(e) }) {
                when (val result = registerCall(token)) {
                    is ApiResult.Success -> Unit
                    else -> throw RegistrationFailedException(result)
                }
            }
            // Persisted only on success so sign-out unregisters a token the
            // server actually has on file for this device.
            tokenProvider.pushToken = token
            tokenProvider.pendingPushToken = null
        } catch (e: RegistrationFailedException) {
            if (isRetryableFailure(e)) {
                // Transient (network / 5xx): keep the token pending so
                // retryPendingIfNeeded can pick it back up on next foreground.
                tokenProvider.pendingPushToken = token
            } else {
                // Terminal (4xx): the server rejected this token, so retrying
                // is futile. Clear the pending state instead of looping.
                tokenProvider.pendingPushToken = null
            }
        }
    }

    /**
     * Retry only transient failures: network errors and 5xx responses (or an
     * unknown/zero status). A 4xx means the server rejected the token, so
     * retrying with backoff would just produce futile requests.
     */
    private fun isRetryableFailure(e: Throwable): Boolean {
        val result = (e as? RegistrationFailedException)?.result ?: return false
        return when (result) {
            is ApiResult.NetworkUnavailable -> true
            is ApiResult.Error -> result.code == 0 || result.code >= 500
            is ApiResult.Success -> false
        }
    }

    /** Call when the app foregrounds (MainActivity.onResume) (#234). */
    fun retryPendingIfNeeded(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        val pending = tokenProvider.pendingPushToken ?: return
        register(pending, scope)
    }

    private class RegistrationFailedException(val result: ApiResult<Unit>) : Exception()
}

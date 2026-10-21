package com.ethosprotocol

import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.RetryPolicy
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.services.PushTokenRegistrar
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Covers #234: registration retries with backoff on failure, and — if every
 * attempt fails — persists the token as "pending" so a later foreground can
 * retry it, instead of silently dropping it (the previous fire-and-forget
 * behavior in PushService.onNewToken).
 */
class PushTokenRegistrarTest {

    /** In-memory token holder matching the real [TokenProvider] contract. */
    private class FakeTokenProvider : TokenProvider {
        override var token: String? = null
        override var pushToken: String? = null
        override var pendingPushToken: String? = null
        override fun clear() { token = null }
    }

    private fun fastPolicy(maxAttempts: Int) = RetryPolicy(
        maxAttempts = maxAttempts,
        baseDelayMillis = 1,
        sleep = { }
    )

    @Test
    fun `registerSuspending succeeds after transient failures, saves token and clears pending`() = runTest {
        val tokenProvider = FakeTokenProvider()
        tokenProvider.pendingPushToken = "stale-token"
        val registrar = PushTokenRegistrar(apiClient = mockk(), tokenProvider = tokenProvider)
        registrar.retryPolicy = fastPolicy(maxAttempts = 3)
        var attempts = 0
        registrar.registerCall = { _ ->
            attempts++
            if (attempts < 3) ApiResult.Error("boom", 500) else ApiResult.Success(Unit)
        }

        registrar.registerSuspending("token-abc")

        assertEquals(3, attempts)
        assertEquals("token-abc", tokenProvider.pushToken)
        assertNull("pending token should be cleared on success", tokenProvider.pendingPushToken)
    }

    @Test
    fun `registerSuspending exhausts retries, persists pending token`() = runTest {
        val tokenProvider = FakeTokenProvider()
        val registrar = PushTokenRegistrar(apiClient = mockk(), tokenProvider = tokenProvider)
        registrar.retryPolicy = fastPolicy(maxAttempts = 3)
        registrar.registerCall = { ApiResult.NetworkUnavailable }

        registrar.registerSuspending("token-xyz")

        assertNull("should not be marked registered on failure", tokenProvider.pushToken)
        assertEquals("a failed registration must be persisted for a later foreground retry",
            "token-xyz", tokenProvider.pendingPushToken)
    }

    @Test
    fun `registerSuspending does not retry a 400 and clears pending token`() = runTest {
        val tokenProvider = FakeTokenProvider()
        tokenProvider.pendingPushToken = "stale-token"
        val registrar = PushTokenRegistrar(apiClient = mockk(), tokenProvider = tokenProvider)
        registrar.retryPolicy = fastPolicy(maxAttempts = 3)
        var attempts = 0
        registrar.registerCall = { _ ->
            attempts++
            ApiResult.Error("bad request", 400)
        }

        registrar.registerSuspending("token-400")

        assertEquals("a 400 must not be retried", 1, attempts)
        assertNull("rejected token must not be marked registered", tokenProvider.pushToken)
        assertNull("rejected token must not stay pending", tokenProvider.pendingPushToken)
    }

    @Test
    fun `registerSuspending does not retry a 401 and clears pending token`() = runTest {
        val tokenProvider = FakeTokenProvider()
        tokenProvider.pendingPushToken = "stale-token"
        val registrar = PushTokenRegistrar(apiClient = mockk(), tokenProvider = tokenProvider)
        registrar.retryPolicy = fastPolicy(maxAttempts = 3)
        var attempts = 0
        registrar.registerCall = { _ ->
            attempts++
            ApiResult.Error("unauthorized", 401)
        }

        registrar.registerSuspending("token-401")

        assertEquals("a 401 must not be retried", 1, attempts)
        assertNull("rejected token must not be marked registered", tokenProvider.pushToken)
        assertNull("rejected token must not stay pending", tokenProvider.pendingPushToken)
    }

    @Test
    fun `registerSuspending retries 500 up to maxAttempts and leaves pending token on exhaustion`() = runTest {
        val tokenProvider = FakeTokenProvider()
        val registrar = PushTokenRegistrar(apiClient = mockk(), tokenProvider = tokenProvider)
        registrar.retryPolicy = fastPolicy(maxAttempts = 3)
        var attempts = 0
        registrar.registerCall = { _ ->
            attempts++
            ApiResult.Error("server error", 500)
        }

        registrar.registerSuspending("token-500")

        assertEquals("a 500 should be retried up to maxAttempts", 3, attempts)
        assertNull("should not be marked registered on failure", tokenProvider.pushToken)
        assertEquals("token-500", tokenProvider.pendingPushToken)
    }

    @Test
    fun `registerSuspending retries NetworkUnavailable up to maxAttempts and leaves pending token on exhaustion`() = runTest {
        val tokenProvider = FakeTokenProvider()
        val registrar = PushTokenRegistrar(apiClient = mockk(), tokenProvider = tokenProvider)
        registrar.retryPolicy = fastPolicy(maxAttempts = 3)
        var attempts = 0
        registrar.registerCall = { _ ->
            attempts++
            ApiResult.NetworkUnavailable
        }

        registrar.registerSuspending("token-net")

        assertEquals("a network error should be retried up to maxAttempts", 3, attempts)
        assertNull("should not be marked registered on failure", tokenProvider.pushToken)
        assertEquals("token-net", tokenProvider.pendingPushToken)
    }

    @Test
    fun `retryPendingIfNeeded does nothing when no token is pending`() = runTest {
        val tokenProvider = FakeTokenProvider()
        val registrar = PushTokenRegistrar(apiClient = mockk(), tokenProvider = tokenProvider)
        var wasCalled = false
        registrar.registerCall = { wasCalled = true; ApiResult.Success(Unit) }

        registrar.retryPendingIfNeeded(scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))

        assertFalse(wasCalled)
    }

    @Test
    fun `retryPendingIfNeeded retries the pending token and clears it on success`() = runTest {
        val tokenProvider = FakeTokenProvider()
        tokenProvider.pendingPushToken = "pending-token"
        val registrar = PushTokenRegistrar(apiClient = mockk(), tokenProvider = tokenProvider)
        registrar.retryPolicy = fastPolicy(maxAttempts = 1)
        var registered: String? = null
        registrar.registerCall = { token -> registered = token; ApiResult.Success(Unit) }

        registrar.retryPendingIfNeeded(scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))

        assertEquals("pending-token", registered)
        assertEquals("pending-token", tokenProvider.pushToken)
        assertNull(tokenProvider.pendingPushToken)
    }
}

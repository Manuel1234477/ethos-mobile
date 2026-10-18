package com.ethosprotocol

import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.NetworkMonitor
import com.ethosprotocol.api.OfflineCache
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.models.AuthToken
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ApiClientTest {

    private val tokenProvider: TokenProvider = mockk(relaxed = true)
    private val networkMonitor: NetworkMonitor = mockk { every { isConnected } returns true }
    private val offlineCache: OfflineCache = mockk(relaxed = true)

    @Test
    fun `near-expiry token is refreshed before the request goes out`() = runTest {
        every { tokenProvider.token } returns "old-token"
        every { tokenProvider.isNearExpiry() } returns true

        var refreshWasCalled = false
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/auth/refresh") {
                refreshWasCalled = true
                respond(
                    content = """{"token":"new-token","expires_at":"2026-08-01T00:00:00Z"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond(
                    content = "[]",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        }
        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)

        apiClient.listVaults()

        assertTrue(refreshWasCalled)
        verify { tokenProvider.setSession(AuthToken(token = "new-token", expiresAt = "2026-08-01T00:00:00Z")) }
    }

    @Test
    fun `fresh token skips the refresh call`() = runTest {
        every { tokenProvider.token } returns "good-token"
        every { tokenProvider.isNearExpiry() } returns false

        var refreshWasCalled = false
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/auth/refresh") refreshWasCalled = true
            respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)

        apiClient.listVaults()

        assertFalse(refreshWasCalled)
    }

    @Test
    fun `refresh failure clears the stored token instead of retrying with the stale one`() = runTest {
        every { tokenProvider.token } returns "old-token"
        every { tokenProvider.isNearExpiry() } returns true

        val engine = MockEngine { respond(content = "", status = HttpStatusCode.Unauthorized) }
        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)

        apiClient.listVaults()

        verify { tokenProvider.clear() }
    }

    @Test
    fun `concurrent requests near expiry share a single refresh call`() = runTest {
        every { tokenProvider.token } returns "old-token"
        every { tokenProvider.isNearExpiry() } returns true

        var refreshCallCount = 0
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/auth/refresh") {
                refreshCallCount++
                respond(
                    content = """{"token":"new-token","expires_at":"2026-08-01T00:00:00Z"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond(
                    content = "[]",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        }
        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)

        val jobs = List(5) { async { apiClient.listVaults() } }
        jobs.awaitAll()

        assertEquals(1, refreshCallCount)
        verify(exactly = 1) { tokenProvider.setSession(AuthToken(token = "new-token", expiresAt = "2026-08-01T00:00:00Z")) }
    }

    @Test
    fun `waiting callers proceed only after the in-flight refresh completes`() = runTest {
        every { tokenProvider.token } returns "old-token"
        every { tokenProvider.isNearExpiry() } returns true

        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/auth/refresh") {
                respond(
                    content = """{"token":"new-token","expires_at":"2026-08-01T00:00:00Z"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond(
                    content = "[]",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        }
        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)

        val jobs = List(3) { async { apiClient.listVaults() } }
        val results = jobs.awaitAll()

        // Every caller — leader and waiters alike — must see the request succeed rather
        // than proceeding with a stale/cleared token captured before the refresh started.
        results.forEach { assertTrue(it is ApiResult.Success) }
        verify(exactly = 1) { tokenProvider.setSession(AuthToken(token = "new-token", expiresAt = "2026-08-01T00:00:00Z")) }
    }

    @Test
    fun `401 on a normal request clears the stored token`() = runTest {
        every { tokenProvider.token } returns "old-token"
        every { tokenProvider.isNearExpiry() } returns false

        val engine = MockEngine { respond(content = "", status = HttpStatusCode.Unauthorized) }
        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)

        val result = apiClient.listVaults()

        assertTrue(result is ApiResult.Error)
        verify { tokenProvider.clear() }
    }

    @Test
    fun `401 with no body falls back to a generic Unauthorized message`() = runTest {
        every { tokenProvider.token } returns null
        every { tokenProvider.isNearExpiry() } returns false

        val engine = MockEngine { respond(content = "", status = HttpStatusCode.Unauthorized) }
        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)

        val result = apiClient.listVaults()

        assertEquals("Unauthorized", (result as ApiResult.Error).message)
    }

    // #211: an expired recovery token/proof on completeRecovery must surface a clear,
    // actionable message — not the generic "Unauthorized" shown for a rejected session.
    @Test
    fun `401 with an error body on completeRecovery surfaces the server message`() = runTest {
        every { tokenProvider.token } returns null
        every { tokenProvider.isNearExpiry() } returns false

        val engine = MockEngine {
            respond(
                content = """{"error":"Your recovery code has expired. Please request a new one."}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)

        val result = apiClient.completeRecovery(
            com.ethosprotocol.models.RecoveryCompleteRequest(
                recoveryToken = "expired-token",
                credentialId = "cred",
                publicKey = "pk",
                clientDataJson = "cdj"
            )
        )

        assertEquals(
            "Your recovery code has expired. Please request a new one.",
            (result as ApiResult.Error).message
        )
    }

    // #597: IDs must be validated at the boundary and percent-encoded in request paths.

    @Test
    fun `vault IDs containing path or query metacharacters are rejected`() = runTest {
        every { tokenProvider.token } returns "good-token"
        every { tokenProvider.isNearExpiry() } returns false

        val engine = MockEngine {
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)

        val badIds = listOf("abc/def", "abc?x=1", "..", "../other", "abc%2Fdef", "abc#frag", "abc def")
        for (badId in badIds) {
            try {
                apiClient.getVault(badId)
                fail("Expected IllegalArgumentException for vault id '$badId'")
            } catch (e: IllegalArgumentException) {
                // expected
            }
            try {
                apiClient.checkIn(badId, com.ethosprotocol.models.CheckInRequest(proof = "p"))
                fail("Expected IllegalArgumentException for vault id '$badId'")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `valid vault ID is percent-encoded as a single path segment`() = runTest {
        every { tokenProvider.token } returns "good-token"
        every { tokenProvider.isNearExpiry() } returns false

        val observedPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            observedPaths.add(request.url.encodedPath)
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)

        // A valid ID that still requires encoding (space is not a path/query metacharacter
        // but must be percent-encoded so it cannot alter the target endpoint).
        val vaultId = "vault 1"
        apiClient.getVault(vaultId)

        assertEquals(1, observedPaths.size)
        assertEquals("/vaults/vault%201", observedPaths[0])
    }

    // #599: limit must be clamped to the contract's 1..100 range.

    @Test
    fun `listVaults clamps limit to the 1 to 100 range`() = runTest {
        every { tokenProvider.token } returns "good-token"
        every { tokenProvider.isNearExpiry() } returns false

        val observedLimits = mutableListOf<String?>()
        val engine = MockEngine { request ->
            observedLimits.add(request.url.parameters["limit"])
            respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)

        apiClient.listVaults(limit = 0)
        apiClient.listVaults(limit = 1)
        apiClient.listVaults(limit = 100)
        apiClient.listVaults(limit = 101)
        apiClient.listVaults(limit = -5)
        apiClient.listVaults(limit = 1000)

        assertEquals(listOf("1", "1", "100", "100", "1", "100"), observedLimits)
    }

    // #600: a cancelled leader must not propagate CancellationException to followers.

    @Test
    fun `cancelled single-flight leader does not cancel the follower`() = runTest {
        every { tokenProvider.token } returns "good-token"
        every { tokenProvider.isNearExpiry() } returns false

        val firstRequestStarted = CompletableDeferred<Unit>()
        var requestCount = 0
        val engine = MockEngine {
            requestCount++
            if (requestCount == 1) {
                firstRequestStarted.complete(Unit)
                // Suspend until the leader is cancelled.
                kotlinx.coroutines.awaitCancellation()
            }
            respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)

        val leader = launch { apiClient.listVaults() }
        firstRequestStarted.await()

        val follower = async { apiClient.listVaults() }
        // Let the follower join the in-flight request.
        yield()

        leader.cancelAndJoin()

        val result = try {
            follower.await()
        } catch (e: CancellationException) {
            fail("Follower received CancellationException from the cancelled leader: $e")
            return@runTest
        }

        assertNotNull(result)
        assertTrue("Follower should still receive a successful result, got $result", result is ApiResult.Success)
    }
}

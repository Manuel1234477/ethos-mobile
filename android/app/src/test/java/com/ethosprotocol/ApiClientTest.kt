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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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

    // #596: cursors are opaque server-issued strings; reserved characters must be
    // percent-encoded so the next page isn't silently skipped or repeated.
    @Test
    fun `listVaults percent-encodes a cursor containing reserved characters`() = runTest {
        every { tokenProvider.token } returns "good-token"
        every { tokenProvider.isNearExpiry() } returns false

        val cursor = "a&b+c=d#e"
        var capturedUrl: io.ktor.http.Url? = null
        val engine = MockEngine { request ->
            capturedUrl = request.url
            respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)

        apiClient.listVaults(limit = 10, after = cursor)

        val url = requireNotNull(capturedUrl)
        // The raw query must not contain the unencoded reserved characters.
        assertFalse(url.encodedQuery.contains("after=$cursor"))
        // After decoding, the cursor must round-trip unchanged.
        assertEquals(cursor, url.parameters["after"])
        assertEquals("10", url.parameters["limit"])
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

    // #607: the signing key must be derived from the server-issued session secret,
    // never from the bearer token that already travels on every request.
    @Test
    fun `request signature is not keyed with the raw bearer token`() = runTest {
        every { tokenProvider.token } returns "bearer-token"
        every { tokenProvider.isNearExpiry() } returns false
        every { tokenProvider.signingSecret } returns "session-signing-secret"

        var signature: String? = null
        val engine = MockEngine { request ->
            signature = request.headers["X-Request-Signature"]
            respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)

        apiClient.listVaults()

        val actual = assertNotNull(signature).let { signature!! }
        val keyedWithBearer = hmacSha256("bearer-token", "anything")
        assertNotEquals(keyedWithBearer, actual)
        assertFalse(actual.contains("bearer-token"))
    }

    // #607: the canonical string must bind method, path and body hash so a captured
    // signature cannot be replayed against a different endpoint.
    @Test
    fun `request signature binds method path and body hash`() = runTest {
        every { tokenProvider.token } returns "bearer-token"
        every { tokenProvider.isNearExpiry() } returns false
        every { tokenProvider.signingSecret } returns "session-signing-secret"

        var signature: String? = null
        val engine = MockEngine { request ->
            signature = request.headers["X-Request-Signature"]
            respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)

        apiClient.listVaults()

        val actual = assertNotNull(signature).let { signature!! }
        val expected = hmacSha256(
            "session-signing-secret",
            "GET\n/vaults\n${sha256Hex("")}"
        )
        assertEquals(expected, actual)
    }

    // #607: a signature minted for one path must not validate for another.
    @Test
    fun `signature captured for one path is rejected against a different path`() = runTest {
        every { tokenProvider.token } returns "bearer-token"
        every { tokenProvider.isNearExpiry() } returns false
        every { tokenProvider.signingSecret } returns "session-signing-secret"

        val signatures = mutableMapOf<String, String>()
        val engine = MockEngine { request ->
            request.headers["X-Request-Signature"]?.let { signatures[request.url.encodedPath] = it }
            respond(
                content = "[]",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, "https://test", engine)

        apiClient.listVaults()
        apiClient.listVaults()

        val vaultsSignature = assertNotNull(signatures["/vaults"]).let { signatures["/vaults"]!! }
        val replayed = hmacSha256(
            "session-signing-secret",
            "GET\n/vaults\n${sha256Hex("")}"
        )
        assertEquals(replayed, vaultsSignature)

        // The same signature must not match the canonical string for a different path.
        val otherPath = hmacSha256(
            "session-signing-secret",
            "GET\n/vaults/other\n${sha256Hex("")}"
        )
        assertNotEquals(otherPath, vaultsSignature)
    }

    private fun hmacSha256(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(data: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    // #592: pinning must fail closed. If the platform TrustManager cannot be obtained,
    // engine construction must throw rather than silently running without pinning.
    @Test
    fun `null system TrustManager fails closed instead of skipping pinning`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            ApiClient.sslManager(systemTm = null)
        }

        assertTrue(failure.message.orEmpty().contains("TrustManager"))
    }
}

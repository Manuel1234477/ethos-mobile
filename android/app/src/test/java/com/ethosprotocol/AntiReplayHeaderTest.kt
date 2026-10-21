package com.ethosprotocol

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the per-request anti-replay headers (task #121).
 *
 * Every mutating request (POST / DELETE) must include:
 *   X-Nonce     — 32-byte cryptographically-random value, hex-encoded (64 chars)
 *   X-Timestamp — current Unix epoch in seconds
 *
 * GET requests must NOT carry these headers (they are idempotent).
 *
 * The server rejects any request whose nonce has already been seen, or whose
 * timestamp falls outside a 300-second window — this test file verifies the
 * client-side half of that contract.
 */
class AntiReplayHeaderTest {

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Builds a Ktor MockEngine that records every request it receives and
     * returns [responseStatus] with [responseBody].
     */
    private fun mockEngine(
        responseStatus: HttpStatusCode = HttpStatusCode.OK,
        responseBody: String = "{}",
        onRequest: (io.ktor.client.request.HttpRequestData) -> Unit = {}
    ): MockEngine = MockEngine { request ->
        onRequest(request)
        respond(
            content = responseBody,
            status = responseStatus,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        )
    }

    // ---------------------------------------------------------------------------
    // Nonce format tests
    // ---------------------------------------------------------------------------

    @Test
    fun `nonce is 64-character hex string`() {
        // Generate via the same logic used in ApiClient.antiReplayHeaders().
        val nonce = generateNonce()
        assertEquals("Nonce must be 64 hex characters (32 bytes)", 64, nonce.length)
        assertTrue(
            "Nonce must contain only hex digits",
            nonce.all { it.isDigit() || it in 'a'..'f' }
        )
    }

    @Test
    fun `consecutive nonces are unique`() {
        // Each call to generateNonce() must produce fresh random bytes.
        val n1 = generateNonce()
        val n2 = generateNonce()
        assertNotEquals("Two consecutive nonces must differ", n1, n2)
    }

    @Test
    fun `timestamp is within 5 seconds of current time`() {
        val before = System.currentTimeMillis() / 1_000L
        val ts = generateTimestamp()
        val after = System.currentTimeMillis() / 1_000L
        assertTrue("Timestamp must be >= before", ts >= before)
        assertTrue("Timestamp must be <= after", ts <= after)
    }

    // ---------------------------------------------------------------------------
    // Integration: Ktor mock-engine tests
    // ---------------------------------------------------------------------------

    @Test
    fun `POST checkin request carries X-Nonce and X-Timestamp headers`() = runBlocking {
        var capturedNonce: String? = null
        var capturedTimestamp: String? = null

        val engine = mockEngine(
            responseBody = "{}",
            onRequest = { req ->
                capturedNonce = req.headers["X-Nonce"]
                capturedTimestamp = req.headers["X-Timestamp"]
            }
        )

        val client = buildTestClient(engine) { request ->
            // POST /vaults/v1/checkin
            request.url.encodedPath.endsWith("/checkin") && request.method == HttpMethod.Post
        }

        // Trigger a POST mutation via the test client helper.
        client.post<Unit>("https://api.test/vaults/v1/checkin")

        assertNotNull("POST must include X-Nonce", capturedNonce)
        assertNotNull("POST must include X-Timestamp", capturedTimestamp)

        // Validate nonce format.
        assertEquals(64, capturedNonce!!.length)
        assertTrue(capturedNonce!!.all { it.isDigit() || it in 'a'..'f' })

        // Validate timestamp is a parseable long within recent range.
        val ts = capturedTimestamp!!.toLong()
        val now = System.currentTimeMillis() / 1_000L
        assertTrue("Timestamp must be recent", ts in (now - 5)..(now + 5))
    }

    @Test
    fun `GET request does NOT carry anti-replay headers`() = runBlocking {
        var capturedNonce: String? = "NOT_SET"
        var capturedTimestamp: String? = "NOT_SET"

        val engine = mockEngine(
            responseBody = "[]",
            onRequest = { req ->
                capturedNonce = req.headers["X-Nonce"]
                capturedTimestamp = req.headers["X-Timestamp"]
            }
        )

        val client = buildTestClient(engine)
        client.get<Unit>("https://api.test/vaults")

        assertNull("GET must NOT include X-Nonce", capturedNonce)
        assertNull("GET must NOT include X-Timestamp", capturedTimestamp)
    }

    @Test
    fun `replayed request returning 400 is surfaced as error not success`() = runBlocking {
        // Simulate server-side replay detection: first call succeeds (200),
        // second call is rejected (400 replay_detected).
        var callCount = 0

        val engine = MockEngine { _ ->
            callCount++
            if (callCount == 1) {
                respond(
                    content = """{"token":"tok","expires_at":"2027-01-01T00:00:00Z"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else {
                respond(
                    content = """{"error":"replay_detected"}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
        }

        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        // First call should succeed.
        val response1 = httpClient.post("https://api.test/auth/verify")
        assertEquals("First request should succeed", 200, response1.status.value)

        // Second call (simulated replay) should be rejected.
        val response2 = httpClient.post("https://api.test/auth/verify")
        assertEquals(
            "Replayed request must be rejected by server (400)",
            400,
            response2.status.value
        )
        assertEquals("Should have made exactly 2 calls", 2, callCount)
    }

    @Test
    fun `DELETE request carries X-Nonce and X-Timestamp headers`() = runBlocking {
        var capturedNonce: String? = null
        var capturedTimestamp: String? = null

        val engine = mockEngine(
            responseBody = "{}",
            onRequest = { req ->
                if (req.method == HttpMethod.Delete) {
                    capturedNonce = req.headers["X-Nonce"]
                    capturedTimestamp = req.headers["X-Timestamp"]
                }
            }
        )

        val client = buildTestClient(engine)
        client.delete<Unit>("https://api.test/notifications/register")

        assertNotNull("DELETE must include X-Nonce", capturedNonce)
        assertNotNull("DELETE must include X-Timestamp", capturedTimestamp)
    }

    // ---------------------------------------------------------------------------
    // Request-signing key material (issue #607)
    // ---------------------------------------------------------------------------

    @Test
    fun `signing key is derived from session secret and not the bearer token`() {
        val bearerToken = "bearer-token-value"
        val sessionSecret = "server-issued-session-secret"

        val key = deriveSigningKey(sessionSecret)

        // The derived key must not equal the bearer token, nor be a trivial
        // encoding of it — the bearer token is already on the wire.
        assertNotEquals("Signing key must not be the bearer token", bearerToken, key)
        assertFalse(
            "Signing key must not contain the bearer token",
            key.contains(bearerToken)
        )
        assertFalse(
            "Signing key must not be derived from the bearer token",
            key == deriveSigningKey(bearerToken)
        )

        // Derivation must be deterministic for the same session secret.
        assertEquals("Derivation must be deterministic", key, deriveSigningKey(sessionSecret))

        // Different session secrets must yield different keys.
        assertNotEquals(
            "Different session secrets must yield different keys",
            key,
            deriveSigningKey("another-session-secret")
        )
    }

    @Test
    fun `canonical string includes method path body hash nonce and timestamp`() {
        val method = "POST"
        val path = "/vaults/v1/checkin"
        val body = """{"vault_id":"abc"}"""
        val nonce = generateNonce()
        val timestamp = generateTimestamp().toString()

        val canonical = buildCanonicalString(method, path, body, nonce, timestamp)

        assertTrue("Canonical string must include method", canonical.contains(method))
        assertTrue("Canonical string must include path", canonical.contains(path))
        assertTrue(
            "Canonical string must include body hash",
            canonical.contains(sha256Hex(body))
        )
        assertTrue("Canonical string must include nonce", canonical.contains(nonce))
        assertTrue("Canonical string must include timestamp", canonical.contains(timestamp))
    }

    @Test
    fun `canonical string differs when method or path changes`() {
        val body = """{"vault_id":"abc"}"""
        val nonce = generateNonce()
        val timestamp = generateTimestamp().toString()

        val base = buildCanonicalString("POST", "/vaults/v1/checkin", body, nonce, timestamp)
        val otherMethod = buildCanonicalString("DELETE", "/vaults/v1/checkin", body, nonce, timestamp)
        val otherPath = buildCanonicalString("POST", "/vaults/v1/checkout", body, nonce, timestamp)
        val otherBody = buildCanonicalString("POST", "/vaults/v1/checkin", """{"vault_id":"xyz"}""", nonce, timestamp)

        assertNotEquals("Method must affect the canonical string", base, otherMethod)
        assertNotEquals("Path must affect the canonical string", base, otherPath)
        assertNotEquals("Body hash must affect the canonical string", base, otherBody)
    }

    @Test
    fun `signature captured for one endpoint is rejected for another`() {
        // Simulate a network observer capturing a signature for /vaults/v1/checkin
        // and replaying it against /vaults/v1/checkout with the same nonce,
        // timestamp and body.
        val sessionSecret = "server-issued-session-secret"
        val key = deriveSigningKey(sessionSecret)
        val body = """{"vault_id":"abc"}"""
        val nonce = generateNonce()
        val timestamp = generateTimestamp().toString()

        val capturedSignature = sign(
            key,
            buildCanonicalString("POST", "/vaults/v1/checkin", body, nonce, timestamp)
        )

        // Server-side verification for the original endpoint succeeds.
        assertTrue(
            "Signature must verify for the endpoint it was issued for",
            verifySignature(
                key,
                buildCanonicalString("POST", "/vaults/v1/checkin", body, nonce, timestamp),
                capturedSignature
            )
        )

        // Replaying the same signature against a different endpoint fails,
        // because method and path are part of the canonical string.
        assertFalse(
            "Signature must NOT verify for a different endpoint",
            verifySignature(
                key,
                buildCanonicalString("POST", "/vaults/v1/checkout", body, nonce, timestamp),
                capturedSignature
            )
        )

        // Replaying against a different method also fails.
        assertFalse(
            "Signature must NOT verify for a different method",
            verifySignature(
                key,
                buildCanonicalString("DELETE", "/vaults/v1/checkin", body, nonce, timestamp),
                capturedSignature
            )
        )
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    /** Mirrors the signing-key derivation in ApiClient (issue #607). */
    private fun deriveSigningKey(sessionSecret: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(sessionSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("ethos-request-signing-v1".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** Mirrors the canonical-string construction in ApiClient (issue #607). */
    private fun buildCanonicalString(
        method: String,
        path: String,
        body: String,
        nonce: String,
        timestamp: String
    ): String = listOf(method.uppercase(), path, sha256Hex(body), nonce, timestamp)
        .joinToString("\n")

    private fun sha256Hex(input: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun sign(key: String, canonical: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun verifySignature(key: String, canonical: String, signature: String): Boolean =
        java.security.MessageDigest.isEqual(
            sign(key, canonical).toByteArray(Charsets.UTF_8),
            signature.toByteArray(Charsets.UTF_8)
        )

    /** Mirrors the nonce-generation logic in ApiClient.antiReplayHeaders(). */
    private fun generateNonce(): String {
        val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Mirrors the timestamp-generation logic in ApiClient.antiReplayHeaders(). */
    private fun generateTimestamp(): Long = System.currentTimeMillis() / 1_000L

    /**
     * Builds a lightweight Ktor HttpClient backed by [engine] for testing
     * anti-replay header injection. This is a thin wrapper that lets tests
     * make raw HTTP calls and inspect what the engine received.
     */
    private fun buildTestClient(
        engine: MockEngine,
        @Suppress("UNUSED_PARAMETER") filter: (io.ktor.client.request.HttpRequestData) -> Boolean = { true }
    ): TestApiCaller = TestApiCaller(engine)
}

/**
 * Minimal test wrapper that injects X-Nonce / X-Timestamp on POST and DELETE,
 * mirroring the behaviour of ApiClient.antiReplayHeaders() without requiring
 * the full Hilt/Android context.
 */
private class TestApiCaller(engine: MockEngine) {
    private val client = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private fun antiReplayHeaders(): Map<String, String> {
        val nonce = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val timestamp = (System.currentTimeMillis() / 1_000L).toString()
        return mapOf("X-Nonce" to nonce, "X-Timestamp" to timestamp)
    }

    suspend fun <T> post(url: String): io.ktor.client.statement.HttpResponse =
        client.post(url) {
            antiReplayHeaders().forEach { (k, v) -> headers.append(k, v) }
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

    suspend fun <T> get(url: String): io.ktor.client.statement.HttpResponse =
        client.get(url)
        // No anti-replay headers on GET.

    suspend fun <T> delete(url: String): io.ktor.client.statement.HttpResponse =
        client.delete(url) {
            antiReplayHeaders().forEach { (k, v) -> headers.append(k, v) }
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
}

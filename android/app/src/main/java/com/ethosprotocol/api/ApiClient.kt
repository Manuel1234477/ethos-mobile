package com.ethosprotocol.api

import android.util.Log
import com.ethosprotocol.BuildConfig
import com.ethosprotocol.models.*
import com.ethosprotocol.models.TwoFactorStatus
import com.ethosprotocol.models.Enable2FARequest
import com.ethosprotocol.models.Enable2FAResponse
import com.ethosprotocol.models.Verify2FARequest
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import android.util.Base64
import android.os.SystemClock
import com.ethosprotocol.utils.PerformanceMonitor
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

sealed class ApiResult<out T> {
    // cachedAt is non-null only when data was served from OfflineCache rather than fetched
    // live, so callers can surface how stale it is (e.g. VaultViewModel/OfflineBanner).
    data class Success<T>(val data: T, val cachedAt: Long? = null) : ApiResult<T>()
    data class Error(val message: String, val code: Int = 0) : ApiResult<Nothing>()
    object NetworkUnavailable : ApiResult<Nothing>()
}

@Singleton
class ApiClient(
    private val tokenProvider: TokenProvider,
    private val networkMonitor: NetworkMonitor,
    private val offlineCache: OfflineCache,
    private val baseUrl: String,
    // Overridable so tests can substitute a MockEngine instead of hitting real Android
    // networking; production callers (AppModule) get the real Android engine, pre-configured
    // with certificate pinning, for free.
    //
    // #117: Certificate / public-key pinning for api.ethos-protocol.app. PinningTrustManager
    // wraps the system TrustManager and additionally verifies that at least one certificate
    // in the chain matches a pinned SPKI SHA-256 hash. See CertificatePinning.kt for the
    // rotation strategy.
    //
    // This has to be configured here, on the engine instance itself, rather than via
    // HttpClient(engine) { engine { sslManager = ... } } below: that `engine { }` builder
    // DSL is only available when HttpClient is constructed from an engine *factory*
    // (HttpClient(Android) { ... }), not from an already-built HttpClientEngine instance —
    // which is what's injected here for testability.
    engine: HttpClientEngine = Android.create {
        sslManager = { httpsURLConnection ->
            val systemTm = getSystemTrustManager()
                ?: throw IllegalStateException(
                    "Unable to obtain the system X509TrustManager; refusing to proceed " +
                        "without certificate pinning (#592)."
                )
            val pinner = CertificatePinner()
            val pinningTm = PinningTrustManager(pinner, systemTm)
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(pinningTm), null)
            httpsURLConnection.sslSocketFactory = sslContext.socketFactory
        }
    },
    private val retryPolicy: RetryPolicy = RetryPolicy.networkDefault,
    private val timeoutConfig: ApiTimeoutConfig = ApiTimeoutConfig.fromEnvironment(),
    private val circuitBreaker: ApiCircuitBreaker = ApiCircuitBreaker()
) {
    companion object {
        private const val TAG = "ApiClient"
        private const val API_VERSION = "2026-09-24"
        private val USER_AGENT = "EthosProtocol/${BuildConfig.VERSION_NAME} (Android)"
    }

    private val requestDedupMutex = Mutex()
    private val inFlightGetRequests = mutableMapOf<String, CompletableDeferred<ApiResult<Any?>>>()

    // internal (not private): VaultEventSocket reuses this same client/connection pool
    // to open the `/ws` connection documented in shared/api-contract.md, rather than
    // standing up a second HttpClient.
    internal val client = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        install(Logging) {
            // Logging Redaction Policy (#111, #279) — see shared/api-contract.md §Logging Redaction Policy.
            // Full request/response bodies (bearer token, 2FA secrets, vault balances, beneficiary
            // addresses, acceptance tokens) must never be written to logcat in any build.
            // LogLevel.INFO logs only HTTP method + URL + status — no body, no sensitive headers.
            // LogLevel.NONE in release ensures zero leakage even if a future log level change
            // is accidentally introduced in debug code that ships to release.
            //
            // If this level is ever raised to LogLevel.HEADERS or LogLevel.ALL (debug only),
            // wrap output through LogRedactor.redactHeaders() / LogRedactor.redactString()
            // (see com.ethosprotocol.security.LogRedactor) before any write to logcat.
            level = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.NONE
        }
        // No timeouts were configured previously, so a stalled connection (e.g. dead wifi
        // captive portal) could hang a request — and the caller's loading state — forever.
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutConfig.requestTimeoutMillis
            connectTimeoutMillis = timeoutConfig.connectTimeoutMillis
            socketTimeoutMillis = timeoutConfig.socketTimeoutMillis
        }
        install(WebSockets)
    }

    // Derives the `wss://.../ws?vault_id={id}` URL from [baseUrl] (documented in
    // shared/api-contract.md) for VaultEventSocket.
    internal fun webSocketUrl(vaultId: String): String {
        val wsBase = baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        return "$wsBase/ws?vault_id=${vaultPathSegment(vaultId)}"
    }

    // #597: Vault IDs are interpolated into request paths, so an ID containing `/`, `?`
    // or `..` would silently retarget the request at a different endpoint. Validate the
    // format once here (the boundary every ID passes through) and percent-encode the
    // segment so it can never escape its path position.
    private fun vaultPathSegment(id: String): String {
        require(VaultDeepLinkParser.isValidVaultId(id)) { "Invalid vault ID" }
        return encodeURLPathPart(id)
    }

    // Auth
    suspend fun getChallenge(): ApiResult<AuthChallenge> = get("/auth/challenge")
    suspend fun verifyPasskey(req: PasskeyVerifyRequest): ApiResult<AuthToken> = post("/auth/verify", req)
    suspend fun registerPasskey(req: PasskeyRegisterRequest): ApiResult<AuthToken> = post("/auth/register", req)
    suspend fun refreshToken(): ApiResult<AuthToken> = post("/auth/refresh", Unit, skipTokenRefresh = true)

    // Account recovery ("lost your device?") — shared contract with iOS's #5.
    suspend fun initiateRecovery(req: RecoveryInitiateRequest): ApiResult<RecoveryInitiateResponse> =
        post("/auth/recovery/initiate", req)
    suspend fun completeRecovery(req: RecoveryCompleteRequest): ApiResult<Unit> =
        post("/auth/recovery/complete", req)

    // Sessions (#208)
    suspend fun listSessions(): ApiResult<List<Session>> = get("/auth/sessions")

    // "Sign out this device" for a specific session (may be the caller's own current session).
    suspend fun revokeSession(id: String): ApiResult<Unit> =
        delete("/auth/sessions/${vaultPathSegment(id)}", Unit)

    // "Sign out all other devices" — revokes every session except the one making this call.
    suspend fun revokeOtherSessions(): ApiResult<Unit> = delete("/auth/sessions", Unit)

    // Vaults
    suspend fun listVaults(): ApiResult<List<Vault>> = get("/vaults")

    /**
     * Paginated variant of listVaults (#112).
     * Pass [after] = page.nextCursor to fetch subsequent pages.
     * Continue while [VaultPage.hasMore] == true.
     */
    suspend fun listVaults(limit: Int = 20, after: String? = null): ApiResult<VaultPage> {
        // #599: shared/api-contract.md §Pagination defines limit as 1..100; clamp rather
        // than send a value the server must reject or silently reinterpret.
        val clampedLimit = limit.coerceIn(1, 100)
        // Cursors are opaque server-issued strings, so they must be percent-encoded:
        // a raw cursor containing '&', '+', '=' or '#' would otherwise alter the query
        // and silently skip or repeat results on the next page.
        val path = URLBuilder("/vaults").apply {
            parameters.append("limit", clampedLimit.toString())
            if (after != null) parameters.append("after", after)
        }.buildString()
        return get(path)
    }
    suspend fun getVault(id: String): ApiResult<Vault> = get("/vaults/${vaultPathSegment(id)}")
    suspend fun createVault(req: CreateVaultRequest, idempotencyKey: String? = null): ApiResult<Vault> =
        post("/vaults", req, idempotencyKey = idempotencyKey)
    suspend fun checkIn(vaultId: String, idempotencyKey: String? = null): ApiResult<Unit> =
        post("/vaults/${vaultPathSegment(vaultId)}/checkin", Unit, idempotencyKey = idempotencyKey)
    suspend fun deposit(vaultId: String, amount: Long): ApiResult<Vault> =
        post("/vaults/${vaultPathSegment(vaultId)}/deposit", mapOf("amount" to amount))
    suspend fun withdraw(vaultId: String, amount: Long): ApiResult<Vault> =
        post("/vaults/${vaultPathSegment(vaultId)}/withdraw", mapOf("amount" to amount))

    // Beneficiary
    // token: the acceptance token parsed from the /accept deep-link URL
    // (e.g. https://ethos-protocol.app/vaults/{id}/accept?token=<token>).
    // Required by the server — see shared/api-contract.md §POST /vaults/{id}/accept.
    suspend fun acceptBeneficiary(vaultId: String, token: String): ApiResult<Unit> =
        post("/vaults/${vaultPathSegment(vaultId)}/accept", BeneficiaryAcceptRequest(vaultId = vaultId, token = token))
    suspend fun updateBeneficiary(vaultId: String, newBeneficiary: String): ApiResult<Vault> =
        post("/vaults/${vaultPathSegment(vaultId)}/beneficiary", BeneficiaryUpdateRequest(newBeneficiary))

    // 2FA
    suspend fun get2FAStatus(vaultId: String): ApiResult<TwoFactorStatus> =
        get("/vaults/${vaultPathSegment(vaultId)}/2fa/status")
    suspend fun enable2FA(vaultId: String, req: Enable2FARequest): ApiResult<Enable2FAResponse> =
        post("/vaults/${vaultPathSegment(vaultId)}/2fa/enable", req)
    /** #226: `req.trustDevice` = true asks server to issue a 30-day device trust token. */
    suspend fun verify2FA(vaultId: String, req: Verify2FARequest): ApiResult<Verify2FAResponse> =
        post("/vaults/${vaultPathSegment(vaultId)}/2fa/verify", req)
    suspend fun disable2FA(vaultId: String): ApiResult<Unit> =
        post("/vaults/${vaultPathSegment(vaultId)}/2fa/disable", Unit)
    suspend fun challenge2FA(vaultId: String): ApiResult<TwoFactorStatus> =
        post("/vaults/${vaultPathSegment(vaultId)}/2fa/challenge", Unit)
    /** #226: Call after verify2FA with trustDevice=true to request a device trust token. */
    suspend fun trustDevice(vaultId: String): ApiResult<TrustDeviceResponse> =
        post("/vaults/${vaultPathSegment(vaultId)}/2fa/trust-device", TrustDeviceRequest())
    /** #224: Generates 8 fresh one-time backup codes (invalidates prior set). */
    suspend fun generateBackupCodes(vaultId: String): ApiResult<BackupCodesResponse> =
        post("/vaults/${vaultPathSegment(vaultId)}/2fa/backup-codes/generate", Unit)
    /** #224: Returns whether backup codes exist + count, never the code values. */
    suspend fun getBackupCodesStatus(vaultId: String): ApiResult<BackupCodesStatus> =
        get("/vaults/${vaultPathSegment(vaultId)}/2fa/backup-codes/status")
    /** #225: Atomically initiates a method switch; old method stays active until new one is verified. */
    suspend fun switch2FAMethod(vaultId: String, req: Switch2FARequest): ApiResult<Enable2FAResponse> =
        post("/vaults/${vaultPathSegment(vaultId)}/2fa/switch", req)

    // Push
    suspend fun registerPushToken(token: String): ApiResult<Unit> =
        post("/notifications/register", PushRegistration(token = token))
    suspend fun unregisterPushToken(token: String): ApiResult<Unit> =
        delete("/notifications/register", PushRegistration(token = token))

    // #231: Persist notification preferences server-side so they survive reinstall.
    suspend fun updateNotificationPreferences(preferences: com.ethosprotocol.models.NotificationPreferences): ApiResult<Unit> =
        post("/notifications/preferences", preferences)

    // Internals
    private suspend inline fun <reified T> get(path: String): ApiResult<T> {
        ensureFreshToken()
        if (!networkMonitor.isConnected) {
            val cached = offlineCache.load(path)
            return if (cached != null) ApiResult.Success(Json.decodeFromString(cached.data), cachedAt = cached.timestamp)
            else ApiResult.NetworkUnavailable
        }
        if (!circuitBreaker.allowRequest()) return ApiResult.Error("API temporarily unavailable", 503)
        return runCatching {
            val response = withRetry(retryPolicy, ::isRetryableNetworkError) {
                singleFlightGet(path) {
                    val _apmStart = SystemClock.elapsedRealtime()
                    val result = client.get("$baseUrl$path") {
                        standardHeaders()
                        bearerAuth()
                    }.toApiResult(path)
                    PerformanceMonitor.recordApiCall(
                        path, "GET",
                        (SystemClock.elapsedRealtime() - _apmStart).toDouble(),
                        if (result is ApiResult.Success<*>) 200 else 0
                    )
                    result
                }
            }
            response
        }.getOrElse { e -> ApiErrorMapper.toApiResult(e) { if (BuildConfig.DEBUG) Log.w(TAG, "$path failed", it) } }
    }

    private suspend inline fun <reified B, reified T> post(
        path: String,
        body: B,
        skipTokenRefresh: Boolean = false,
        idempotencyKey: String? = null
    ): ApiResult<T> {
        if (!skipTokenRefresh) ensureFreshToken()
        if (!networkMonitor.isConnected) return ApiResult.NetworkUnavailable
        if (!circuitBreaker.allowRequest()) return ApiResult.Error("API temporarily unavailable", 503)
        return runCatching {
            val _apmStart = SystemClock.elapsedRealtime()
            // Serialize once so the exact bytes that are signed are the bytes that are sent.
            val bodyBytes = Json.encodeToString(
                kotlinx.serialization.serializer(),
                body
            ).toByteArray(Charsets.UTF_8)
            var response = client.post("$baseUrl$path") {
                standardHeaders()
                bearerAuth()
                antiReplayHeaders(HttpMethod.Post, path, bodyBytes, idempotencyKey)
                contentType(ContentType.Application.Json)
                setBody(bodyBytes)
            }
            ApiCompressionMetrics.record(response)
            // #609: documented recovery for `replay_detected` (docs/api-error-codes.md) —
            // regenerate X-Nonce/X-Timestamp and retry the mutation exactly once. The
            // idempotency key is intentionally kept stable across the retry.
            if (response.isReplayDetected()) {
                response = client.post("$baseUrl$path") {
                    standardHeaders()
                    bearerAuth()
                    antiReplayHeaders(HttpMethod.Post, path, bodyBytes, idempotencyKey)
                    contentType(ContentType.Application.Json)
                    setBody(bodyBytes)
                }
                ApiCompressionMetrics.record(response)
            }
            val result = when (response.status.value) {
                in 200..299 -> ApiResult.Success(if (T::class == Unit::class) Unit as T else response.body())
                // #211: a 401 can carry a human-readable reason in its body (e.g. an expired
                // recovery token/proof on completeRecovery) — surface it instead of the
                // generic "Unauthorized" so the caller isn't left with a dead-end message.
                401 -> { tokenProvider.clear(); ApiResult.Error(response.unauthorizedMessage(), 401) }
                429 -> ApiResult.Error(response.retryAfterMessage(), 429)
                else -> ApiResult.Error("Server error ${response.status.value}", response.status.value)
            }
            PerformanceMonitor.recordApiCall(
                path, "POST",
                (SystemClock.elapsedRealtime() - _apmStart).toDouble(),
                response.status.value
            )
            result
        }.getOrElse { e ->
            circuitBreaker.recordFailure()
            ApiErrorMapper.toApiResult(e) { if (BuildConfig.DEBUG) Log.w(TAG, "$path failed", it) }
        }
    }

    private suspend inline fun <reified B, reified T> delete(path: String, body: B): ApiResult<T> {
        ensureFreshToken()
        if (!networkMonitor.isConnected) return ApiResult.NetworkUnavailable
        if (!circuitBreaker.allowRequest()) return ApiResult.Error("API temporarily unavailable", 503)
        return runCatching {
            val _apmStart = SystemClock.elapsedRealtime()
            val bodyBytes = Json.encodeToString(
                kotlinx.serialization.serializer(),
                body
            ).toByteArray(Charsets.UTF_8)
            var response = client.delete("$baseUrl$path") {
                standardHeaders()
                bearerAuth()
                antiReplayHeaders(HttpMethod.Delete, path, bodyBytes)
                contentType(ContentType.Application.Json)
                setBody(bodyBytes)
            }
            ApiCompressionMetrics.record(response)
            // #609: documented recovery for `replay_detected` (docs/api-error-codes.md) —
            // regenerate X-Nonce/X-Timestamp and retry the mutation exactly once.
            if (response.isReplayDetected()) {
                response = client.delete("$baseUrl$path") {
                    standardHeaders()
                    bearerAuth()
                    antiReplayHeaders(HttpMethod.Delete, path, bodyBytes)
                    contentType(ContentType.Application.Json)
                    setBody(bodyBytes)
                }
                ApiCompressionMetrics.record(response)
            }
            // Ktor does not throw on non-2xx responses by default, so the status must be
            // checked explicitly here (as get()/post() already do) — otherwise a failed
            // deletion (401/500/etc.) is silently reported back to callers as success.
            val result = when (response.status.value) {
                in 200..299 -> ApiResult.Success(if (T::class == Unit::class) Unit as T else response.body())
                // #211: a 401 can carry a human-readable reason in its body (e.g. an expired
                // recovery token/proof on completeRecovery) — surface it instead of the
                // generic "Unauthorized" so the caller isn't left with a dead-end message.
                401 -> { tokenProvider.clear(); ApiResult.Error(response.unauthorizedMessage(), 401) }
                429 -> ApiResult.Error(response.retryAfterMessage(), 429)
                else -> ApiResult.Error("Server error ${response.status.value}", response.status.value)
            }
            PerformanceMonitor.recordApiCall(
                path, "DELETE",
                (SystemClock.elapsedRealtime() - _apmStart).toDouble(),
                response.status.value
            )
            result
        }.getOrElse { e ->
            circuitBreaker.recordFailure()
            ApiErrorMapper.toApiResult(e) { if (BuildConfig.DEBUG) Log.w(TAG, "$path failed", it) }
        }
    }

    // Guards ensureFreshToken()'s refresh so concurrent callers near token expiry
    // (e.g. VaultListScreen firing several parallel loads on resume) share a single
    // /auth/refresh call instead of each racing their own — see refreshMutex/inFlightRefresh
    // below.
    private val refreshMutex = Mutex()
    private var inFlightRefresh: CompletableDeferred<Unit>? = null

    // Best-effort: refreshes the stored token when it's near its expiry so the request
    // about to be made uses a live token instead of one about to be rejected with a 401.
    // A refresh failure just falls through and lets the actual request surface the error.
    //
    // Single-flight: if a refresh is already in progress, concurrent callers await that
    // same result instead of each independently calling refreshToken() — otherwise N
    // concurrent requests in the expiry window would fire N simultaneous refreshes, and
    // whichever response lands last (not necessarily the most recent) wins.
    private suspend fun ensureFreshToken() {
        if (tokenProvider.token == null || !tokenProvider.isNearExpiry()) return

        val (deferred, isLeader) = refreshMutex.withLock {
            inFlightRefresh?.let { it to false }
                ?: CompletableDeferred<Unit>().also { inFlightRefresh = it }.let { it to true }
        }

        if (!isLeader) {
            deferred.await()
            return
        }

        try {
            val result = refreshToken()
            if (result is ApiResult.Success) tokenProvider.setSession(result.data)
        } finally {
            refreshMutex.withLock { inFlightRefresh = null }
            deferred.complete(Unit)
        }
    }

    // GET is the only idempotent verb this client issues — retrying POST/DELETE
    // automatically could double-submit a mutation (check-in, withdrawal, 2FA
    // disable, ...), so only get() calls withRetry with this predicate.
    // HttpRequestTimeoutException is checked explicitly because it subclasses
    // CancellationException (so HttpTimeout can cooperate with coroutine
    // cancellation) rather than IOException.
    // #211: reads the `{"error": "<message>"}` body a 401 response may carry, falling back to
    // "Unauthorized" when there's no body (the normal case for a rejected session token).
    private suspend fun HttpResponse.unauthorizedMessage(): String =
        runCatching { Json.decodeFromString<Map<String, String>>(bodyAsText())["error"] }
            .getOrNull() ?: "Unauthorized"

    private fun HttpResponse.retryAfterMessage(): String {
        val retryAfter = headers[HttpHeaders.RetryAfter]
        return if (retryAfter.isNullOrBlank()) "Rate limited. Please retry later."
            else "Rate limited. Retry after $retryAfter seconds."
    }

    // #609: detects the documented `{"error": "replay_detected"}` body on HTTP 400/403
    // (see docs/api-error-codes.md). Used by post()/delete() to regenerate the anti-replay
    // headers and retry a mutating request once. GETs are idempotent and never retried here.
    private suspend fun HttpResponse.isReplayDetected(): Boolean {
        if (status.value != 400 && status.value != 403) return false
        return runCatching {
            Json.decodeFromString<Map<String, String>>(bodyAsText())["error"] == "replay_detected"
        }.getOrDefault(false)
    }

    private fun isRetryableNetworkError(e: Throwable): Boolean =
        e is HttpRequestTimeoutException || e is IOException

    private fun HttpRequestBuilder.bearerAuth() {
        tokenProvider.token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private fun HttpRequestBuilder.standardHeaders() {
        header(HttpHeaders.UserAgent, USER_AGENT)
        header("X-API-Version", API_VERSION)
    }

    private suspend inline fun <reified T> HttpResponse.toApiResult(path: String): ApiResult<T> {
        ApiCompressionMetrics.record(this)
        return when (status.value) {
            in 200..299 -> {
                val body: T = body()
                offlineCache.save(path, Json.encodeToString(kotlinx.serialization.serializer(), body))
                ApiResult.Success(body)
            }
            401 -> { tokenProvider.clear(); ApiResult.Error(unauthorizedMessage(), 401) }
            404 -> ApiResult.Error("Not found", 404)
            else -> ApiResult.Error("Server error ${status.value}", status.value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> singleFlightGet(
        path: String,
        block: suspend () -> ApiResult<T>
    ): ApiResult<T> {
        val key = "GET:$path"
        val (deferred, isLeader) = requestDedupMutex.withLock {
            inFlightGetRequests[key]?.let { it to false } ?: run {
                val created = CompletableDeferred<ApiResult<Any?>>()
                inFlightGetRequests[key] = created
                created to true
            }
        }
        if (!isLeader) return deferred.await() as ApiResult<T>

        try {
            val result = block()
            deferred.complete(result as ApiResult<Any?>)
            return result
        } catch (e: CancellationException) {
            // #600: the leader's coroutine was cancelled (e.g. its screen was dismissed).
            // That cancellation belongs to the leader alone — completing the shared
            // deferred exceptionally would hand every follower a CancellationException
            // for a request it never cancelled. Drop the in-flight entry so followers
            // (and later callers) start a fresh leader instead.
            requestDedupMutex.withLock { inFlightGetRequests.remove(key) }
            throw e
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            requestDedupMutex.withLock { inFlightGetRequests.remove(key) }
        }
    }

    // Anti-replay headers (task #121, see shared/api-contract.md).
    // Applied to every mutating request (POST / DELETE). GET requests are
    // idempotent and do not require replay protection.
    //
    // X-Nonce : 32 cryptographically-random bytes, hex-encoded. The server
    //           stores seen nonces and rejects any duplicate within the token's
    //           validity window, preventing a captured request from being
    //           replayed later.
    // X-Timestamp : current Unix epoch in seconds. The server rejects requests
    //               where |server_time − timestamp| > 300 s (5-minute window),
    //               limiting the replay window to that duration even if the
    //               nonce store is unavailable.
    // idempotencyKey : optional, client-generated identifier that stays the same across
    //                  retries of the same logical action (e.g. a queued check-in resubmitted
    //                  by PendingActionSyncWorker after a crash). Unlike X-Nonce, this is
    //                  intentionally NOT regenerated per attempt, so the server can recognize
    //                  a resubmission as a duplicate of a specific prior attempt rather than a
    //                  brand-new request.
    private fun HttpRequestBuilder.antiReplayHeaders(
        method: HttpMethod,
        path: String,
        bodyBytes: ByteArray?,
        idempotencyKey: String? = null
    ) {
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val timestamp = System.currentTimeMillis() / 1_000L
        header("X-Nonce", nonce)
        header("X-Timestamp", timestamp.toString())
        requestSignature(method, path, bodyBytes, nonce, timestamp, idempotencyKey)
            ?.let { header("X-Request-Signature", it) }
        if (idempotencyKey != null) header("X-Idempotency-Key", idempotencyKey)
    }

    // #607: The HMAC key must NOT be the bearer token. The bearer token already travels on
    // every request, so a network observer that captures it could forge a valid MAC — the
    // signature would add no secrecy. Instead we derive a distinct signing key from the
    // server-issued session secret (TokenProvider.signingSecret), which is never sent on the
    // wire. If the server has not issued a signing secret, we omit the header entirely
    // (issue #56) rather than fall back to the bearer token.
    //
    // The canonical string binds the signature to the specific request so a captured
    // signature cannot be replayed against another endpoint or with a different payload:
    //   METHOD \n PATH \n SHA-256(body) \n nonce \n timestamp \n idempotencyKey
    private fun requestSignature(
        method: HttpMethod,
        path: String,
        bodyBytes: ByteArray?,
        nonce: String,
        timestamp: Long,
        idempotencyKey: String?
    ): String? {
        val signingSecret = tokenProvider.signingSecret ?: return null
        val bodyHash = sha256Hex(bodyBytes ?: ByteArray(0))
        val canonical = listOf(
            method.value,
            path,
            bodyHash,
            nonce,
            timestamp.toString(),
            idempotencyKey.orEmpty()
        ).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

object HpkpTelemetry {
    private val _pinHeadersSeen = AtomicLong(0)
    private val _reportOnlyHeadersSeen = AtomicLong(0)

    val pinHeadersSeen: Long get() = _pinHeadersSeen.get()
    val reportOnlyHeadersSeen: Long get() = _reportOnlyHeadersSeen.get()

    fun record(response: HttpResponse) {
        if (response.headers["Public-Key-Pins"] != null) _pinHeadersSeen.incrementAndGet()
        if (response.headers["Public-Key-Pins-Report-Only"] != null) _reportOnlyHeadersSeen.incrementAndGet()
    }
}

object ApiCompressionMetrics {
    private val _compressedResponses = AtomicLong(0)
    private val _uncompressedResponses = AtomicLong(0)
    private val _compressedBytes = AtomicLong(0)

    val compressedResponses: Long get() = _compressedResponses.get()
    val uncompressedResponses: Long get() = _uncompressedResponses.get()
    val compressedBytes: Long get() = _compressedBytes.get()

    fun record(response: HttpResponse) {
        val encoding = response.headers[HttpHeaders.ContentEncoding]
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
            _compressedResponses.incrementAndGet()
            response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { _compressedBytes.addAndGet(it) }
        } else {
            _uncompressedResponses.incrementAndGet()
        }
    }

    fun reset() {
        _compressedResponses.set(0)
        _uncompressedResponses.set(0)
        _compressedBytes.set(0)
    }
}

data class ApiTimeoutConfig(
    val requestTimeoutMillis: Long,
    val connectTimeoutMillis: Long,
    val socketTimeoutMillis: Long
) {
    companion object {
        fun fromEnvironment() = ApiTimeoutConfig(
            requestTimeoutMillis = System.getProperty("ethos.api.requestTimeoutMillis")?.toLongOrNull() ?: 30_000,
            connectTimeoutMillis = System.getProperty("ethos.api.connectTimeoutMillis")?.toLongOrNull() ?: 15_000,
            socketTimeoutMillis = System.getProperty("ethos.api.socketTimeoutMillis")?.toLongOrNull() ?: 30_000
        )
    }
}

class ApiCircuitBreaker(
    private val failureThreshold: Int = 5,
    private val openMillis: Long = 30_000
) {
    private val failures = AtomicLong(0)
    private val openedUntil = AtomicLong(0)

    fun allowRequest(now: Long = System.currentTimeMillis()): Boolean = now >= openedUntil.get()

    fun recordSuccess() {
        failures.set(0)
        openedUntil.set(0)
    }

    fun recordFailure(now: Long = System.currentTimeMillis()) {
        val count = failures.incrementAndGet()
        if (count >= failureThreshold) openedUntil.set(now + openMillis)
    }
}

object ApiResponseDecompressionMetrics {
    private val _decompressedResponses = AtomicLong(0)

    val decompressedResponses: Long get() = _decompressedResponses.get()

    fun record(response: HttpResponse) {
        val encoding = response.headers[HttpHeaders.ContentEncoding]
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
            _decompressedResponses.incrementAndGet()
        }
    }
}

// ── #117 helper ────────────────────────────────────────────────────────────────

/**
 * Returns the first [X509TrustManager] from the default [TrustManagerFactory],
 * or `null` if none is available. Used by [ApiClient] to provide a delegate for
 * [PinningTrustManager] so standard chain validation still runs before the pin check.
 */
internal fun getSystemTrustManager(): X509TrustManager? {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as java.security.KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
}

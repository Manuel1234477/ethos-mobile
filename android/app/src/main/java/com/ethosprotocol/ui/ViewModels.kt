package com.ethosprotocol.ui

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethosprotocol.BuildConfig
import com.ethosprotocol.api.ApiCallFailedException
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiErrorMapper
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.NetworkMonitor
import com.ethosprotocol.api.OfflineCache
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.models.*
import com.ethosprotocol.models.CreateVaultRequest
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.TwoFactorMethod
import com.ethosprotocol.models.TwoFactorStatus
import com.ethosprotocol.models.Enable2FARequest
import com.ethosprotocol.models.Enable2FAResponse
import com.ethosprotocol.models.Verify2FARequest
import com.ethosprotocol.models.Verify2FAResponse
import com.ethosprotocol.models.Switch2FARequest
import com.ethosprotocol.models.BackupCodesResponse
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.PasskeyException
import com.ethosprotocol.services.PasskeyService
import com.ethosprotocol.services.PendingAction
import com.ethosprotocol.services.PendingActionDao
import com.ethosprotocol.services.PendingActionSyncWorker
import com.ethosprotocol.services.PendingActionType
import com.ethosprotocol.services.VaultAssociationStore
import com.ethosprotocol.services.VaultEventSocket
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

// --- Auth ViewModel ---

@Immutable
data class AuthUiState(
    val isAuthenticated: Boolean = false,
    val isLocked: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    // Set once initiateRecovery() succeeds — its presence means a recovery code has been
    // sent and the UI should show the "finish recovery" step.
    val recoveryToken: String? = null,
    val cooldownRemainingSeconds: Int = 0
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val apiClient: ApiClient,
    private val passkeyService: PasskeyService,
    private val tokenProvider: TokenProvider,
    private val notificationHelper: NotificationHelper,
    private val pendingActionDao: PendingActionDao,
    // #200: cross-device sync of non-secret vault-to-passkey-credential associations.
    private val vaultAssociationStore: VaultAssociationStore
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState(isAuthenticated = tokenProvider.token != null))
    val state = _state.asStateFlow()

    private var backgroundedAtMillis: Long? = null

    // Default re-lock timeout — kept in sync with iOS's equivalent (#12) so both
    // platforms re-prompt biometrics after the same amount of backgrounded time.
    var relockTimeoutMillis: Long = DEFAULT_RELOCK_TIMEOUT_MILLIS

    private var consecutiveFailures = 0
    private var cooldownJob: Job? = null

    // #209: proactive token refresh, scheduled independently of request activity — ApiClient's
    // ensureFreshToken() already covers "refresh before the next API call", but a foregrounded,
    // idle app makes no calls and would otherwise sit on a token until it's rejected. Polling
    // (rather than a one-shot timer at the exact expiry, as AuthStore.swift does) is used because
    // TokenProvider only exposes isNearExpiry(), not the raw expiry instant.
    private var refreshJob: Job? = null

    init {
        if (tokenProvider.token != null) startScheduledRefresh()
    }

    private fun startScheduledRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (true) {
                delay(REFRESH_POLL_INTERVAL_MILLIS)
                if (tokenProvider.token == null) return@launch
                if (!tokenProvider.isNearExpiry()) continue
                when (val result = apiClient.refreshToken()) {
                    is ApiResult.Success -> tokenProvider.setSession(result.data)
                    is ApiResult.Error -> if (result.code == 401) {
                        // ApiClient already cleared the stored token on the 401; reflect that
                        // here so the UI falls back to full re-authentication.
                        _state.update { it.copy(isAuthenticated = false) }
                        return@launch
                    }
                    ApiResult.NetworkUnavailable -> Unit // transient — retry on the next tick
                }
            }
        }
    }

    /** Called from MainActivity.onStop — records when the app left the foreground. */
    fun onAppBackgrounded(now: Long = System.currentTimeMillis()) {
        if (_state.value.isAuthenticated) backgroundedAtMillis = now
    }

    /** Called from MainActivity.onStart — locks the session if it was backgrounded past the timeout. */
    fun onAppForegrounded(now: Long = System.currentTimeMillis()) {
        val backgroundedAt = backgroundedAtMillis ?: return
        backgroundedAtMillis = null
        if (_state.value.isAuthenticated && now - backgroundedAt >= relockTimeoutMillis) {
            _state.update { it.copy(isLocked = true) }
        }
    }

    fun unlock() {
        _state.update { it.copy(isLocked = false) }
    }

    fun signIn(activity: Activity) = viewModelScope.launch {
        if (_state.value.cooldownRemainingSeconds > 0) return@launch
        _state.update { it.copy(isLoading = true, error = null) }
        passkeyService.authenticate(activity)
            .onSuccess {
                consecutiveFailures = 0
                cooldownJob?.cancel()
                _state.update { it.copy(isAuthenticated = true, isLoading = false, cooldownRemainingSeconds = 0) }
                startScheduledRefresh()
            }
            .onFailure { e -> handleAuthFailure(e) }
    }

    private fun startCooldownIfNeeded() {
        if (consecutiveFailures < COOLDOWN_FAILURE_THRESHOLD) return
        // Cap the exponent well below where `shl` would overflow Int — the clamp to
        // COOLDOWN_MAX_SECONDS below makes any exponent past this point equivalent anyway.
        val exponent = (consecutiveFailures - COOLDOWN_FAILURE_THRESHOLD).coerceAtMost(10)
        val seconds = (COOLDOWN_BASE_SECONDS shl exponent).coerceAtMost(COOLDOWN_MAX_SECONDS)
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            for (remaining in seconds downTo 1) {
                _state.update { it.copy(cooldownRemainingSeconds = remaining) }
                delay(1_000)
            }
            _state.update { it.copy(cooldownRemainingSeconds = 0) }
        }
    }

    fun register(activity: Activity, username: String) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        // PasskeyService.register already stores the session token returned by the
        // backend, so there's no need to run a second sign-in ceremony here.
        passkeyService.register(activity, username)
            .onSuccess {
                _state.update { it.copy(isAuthenticated = true, isLoading = false) }
                startScheduledRefresh()
            }
            .onFailure { e -> handleAuthFailure(e) }
    }

    // ── Account recovery ("lost your device?") — mirrors iOS AuthStore.recoverAccess (#211) ──

    fun sendRecoveryCode(username: String) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        when (val result = apiClient.initiateRecovery(RecoveryInitiateRequest(username))) {
            is ApiResult.Success -> _state.update { it.copy(isLoading = false, recoveryToken = result.data.recoveryToken) }
            is ApiResult.Error -> _state.update { it.copy(isLoading = false, error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false, error = "No network") }
        }
    }

    fun finishRecovery(activity: Activity, username: String) = viewModelScope.launch {
        val recoveryToken = _state.value.recoveryToken ?: return@launch
        _state.update { it.copy(isLoading = true, error = null) }
        passkeyService.recoverAccount(activity, username, recoveryToken)
            .onSuccess {
                _state.update { it.copy(isAuthenticated = true, isLoading = false, recoveryToken = null) }
                startScheduledRefresh()
            }
            .onFailure { e ->
                // A rejected recovery token/proof (401) can't be retried as-is — drop back to
                // the "send code" step instead of leaving the user stuck on a dead-end error.
                val recoveryRejected = (e as? ApiCallFailedException)?.code == 401
                val message = if (e is PasskeyException) e.message ?: "Recovery failed. Please try again."
                              else ApiErrorMapper.friendlyMessage(e)
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = message,
                        recoveryToken = if (recoveryRejected) null else it.recoveryToken
                    )
                }
            }
    }

    fun clearRecovery() {
        _state.update { it.copy(recoveryToken = null, error = null) }
    }

    fun signOut() = viewModelScope.launch {
        refreshJob?.cancel()
        refreshJob = null
        // Unregister before clearing the auth token — ApiClient.bearerAuth() reads
        // tokenProvider.token when building the request, so clearing first would send
        // the delete unauthenticated. Best-effort: sign-out proceeds locally either way.
        tokenProvider.pushToken?.let { apiClient.unregisterPushToken(it) }
        tokenProvider.clear()
        tokenProvider.pushToken = null
        // Clear pending actions: queued actions are tied to the authenticated session and
        // should not persist or sync after sign-out (they belong to the previous user's vaults).
        pendingActionDao.deleteAll()
        notificationHelper.cancelQueuedActions()
        // #200: local vault-to-credential associations belong to the previous session;
        // the synced copy is left alone so another signed-in device isn't affected.
        vaultAssociationStore.clearAll()
        backgroundedAtMillis = null
        consecutiveFailures = 0
        cooldownJob?.cancel()
        _state.update { it.copy(isAuthenticated = false, isLocked = false, cooldownRemainingSeconds = 0) }
    }

    private fun handleAuthFailure(e: Throwable) {
        if (BuildConfig.DEBUG) Log.w(TAG, "auth failed", e)
        consecutiveFailures++
        _state.update { it.copy(isLoading = false, error = ApiErrorMapper.friendlyMessage(e)) }
        startCooldownIfNeeded()
    }

    companion object {
        private const val TAG = "AuthViewModel"
        const val DEFAULT_RELOCK_TIMEOUT_MILLIS = 30_000L
        private const val COOLDOWN_FAILURE_THRESHOLD = 3
        private const val COOLDOWN_BASE_SECONDS = 2
        private const val COOLDOWN_MAX_SECONDS = 60
        // #209: matches TokenProvider.isNearExpiry's default 60s threshold — checking every
        // 30s guarantees at least one check lands inside that window before expiry.
        private const val REFRESH_POLL_INTERVAL_MILLIS = 30_000L
    }
}

// --- Sessions ViewModel (#208) ---

@Immutable
data class SessionsUiState(
    val sessions: List<Session> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val apiClient: ApiClient
) : ViewModel() {

    private val _state = MutableStateFlow(SessionsUiState())
    val state = _state.asStateFlow()

    fun load() = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        when (val result = apiClient.listSessions()) {
            is ApiResult.Success -> _state.update { it.copy(sessions = result.data, isLoading = false) }
            is ApiResult.Error -> _state.update { it.copy(isLoading = false, error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false, error = "No network") }
        }
    }

    // Caller (SessionsScreen) is responsible for the biometric prompt before invoking this —
    // mirrors VaultListScreen's check-in confirmation pattern.
    fun revoke(session: Session) = viewModelScope.launch {
        when (val result = apiClient.revokeSession(session.id)) {
            is ApiResult.Success -> _state.update { it.copy(sessions = it.sessions.filter { s -> s.id != session.id }) }
            is ApiResult.Error -> _state.update { it.copy(error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(error = "No network") }
        }
    }

    fun revokeAllOthers() = viewModelScope.launch {
        when (val result = apiClient.revokeOtherSessions()) {
            is ApiResult.Success -> load()
            is ApiResult.Error -> _state.update { it.copy(error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(error = "No network") }
        }
    }
}

// --- TwoFactor ViewModel ---

@Immutable
data class TwoFactorUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val verified: Boolean = false,
    val setupResponse: Enable2FAResponse? = null,
    val status: TwoFactorStatus? = null,
    // #119: Client-side rate limiting for OTP verification attempts.
    val otpFailureCount: Int = 0,
    val otpCooldownSeconds: Int = 0,
    // #224: Backup codes shown once immediately after initial TOTP setup.
    val backupCodes: List<String> = emptyList(),
    val showBackupCodes: Boolean = false,
    // #225: Set when a method-switch flow is in progress.
    val switchResponse: Enable2FAResponse? = null,
    // #226: Set to true after verify when the user opted in to "remember this device".
    val deviceTrustToken: String? = null,
    val deviceTrustExpiresAt: String? = null
) {
    val isOtpBlocked: Boolean get() = otpCooldownSeconds > 0
}

@HiltViewModel
class TwoFactorViewModel @Inject constructor(
    private val apiClient: ApiClient,
    /**
     * #172: OTP rate-limiting state lives here rather than only in [_state] so it survives
     * process death, exactly as [DeepLinkViewModel] does for pending deep links. A plain
     * ViewModel field is cleared when the system reclaims the process, which would reset
     * the failure count to zero and hand an attacker unlimited extra guesses.
     *
     * Defaulted so existing call sites that only need the API client (tests) stay valid;
     * Hilt always supplies a real handle for the ViewModel it creates.
     */
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : ViewModel() {

    private val _state = MutableStateFlow(TwoFactorUiState())
    val state = _state.asStateFlow()

    private var cooldownJob: kotlinx.coroutines.Job? = null

    init {
        // Rehydrate the rate-limiting state saved by a previous instance of this ViewModel.
        // The cooldown is persisted as an absolute deadline, so the remaining seconds are
        // recomputed against the current wall clock however long the process was dead.
        val failures = savedStateHandle.readNumber(KEY_OTP_FAILURE_COUNT)?.toInt() ?: 0
        val remaining = remainingCooldownSeconds()
        if (failures > 0 || remaining > 0) {
            _state.update { it.copy(otpFailureCount = failures, otpCooldownSeconds = remaining) }
        }
        if (remaining > 0) startCooldownTicker(remaining)
    }

    fun loadStatus(vaultId: String) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        when (val result = apiClient.get2FAStatus(vaultId)) {
            is ApiResult.Success -> _state.update { it.copy(status = result.data, isLoading = false) }
            is ApiResult.Error -> _state.update { it.copy(isLoading = false, error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false, error = "No network") }
        }
    }

    fun enable2FA(vaultId: String, method: TwoFactorMethod, phone: String?, email: String?) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        val req = Enable2FARequest(method = method, phone = phone, email = email)
        when (val result = apiClient.enable2FA(vaultId, req)) {
            is ApiResult.Success -> _state.update { it.copy(setupResponse = result.data, isLoading = false) }
            is ApiResult.Error -> _state.update { it.copy(isLoading = false, error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false, error = "No network") }
        }
    }

    fun verify2FA(vaultId: String, otp: String, trustDevice: Boolean = false) = viewModelScope.launch {
        if (_state.value.isOtpBlocked) return@launch
        _state.update { it.copy(isLoading = true, error = null) }
        val req = Verify2FARequest(otp = otp, trustDevice = trustDevice)
        when (val result = apiClient.verify2FA(vaultId, req)) {
            is ApiResult.Success -> {
                // #119: Reset rate-limiting state on success (#172: including the persisted copy)
                cancelCooldown()
                clearPersistedRateLimitState()
                val response = result.data

                // #226: Persist the trust token in state so the screen layer can store it securely.
                val baseUpdate = _state.value.copy(
                    verified = true, isLoading = false,
                    otpFailureCount = 0, otpCooldownSeconds = 0,
                    deviceTrustToken = response.deviceTrustToken,
                    deviceTrustExpiresAt = response.expiresAt
                )
                _state.value = baseUpdate

                // #224: Generate backup codes immediately after a successful initial TOTP setup
                // (detected by setupResponse being present and method == totp).
                val isInitialTotpSetup = _state.value.setupResponse?.method == TwoFactorMethod.totp
                if (isInitialTotpSetup) {
                    generateBackupCodesInternal(vaultId)
                }
            }
            is ApiResult.Error -> {
                val newCount = _state.value.otpFailureCount + 1
                savedStateHandle[KEY_OTP_FAILURE_COUNT] = newCount
                val cooldown = otpCooldownSeconds(newCount)
                _state.update { it.copy(isLoading = false, error = result.message, otpFailureCount = newCount) }
                if (cooldown > 0) startCooldown(cooldown)
            }
            ApiResult.NetworkUnavailable -> {
                _state.update { it.copy(isLoading = false, error = "No network") }
            }
        }
    }

    // MARK: - #224 Backup Codes

    /** Generates 8 fresh one-time backup codes; called automatically after initial TOTP setup. */
    fun generateBackupCodes(vaultId: String) = viewModelScope.launch {
        generateBackupCodesInternal(vaultId)
    }

    private suspend fun generateBackupCodesInternal(vaultId: String) {
        _state.update { it.copy(isLoading = true) }
        when (val result = apiClient.generateBackupCodes(vaultId)) {
            is ApiResult.Success -> _state.update {
                it.copy(isLoading = false, backupCodes = result.data.codes, showBackupCodes = true)
            }
            is ApiResult.Error -> _state.update {
                // Non-fatal: codes can be regenerated later from settings.
                it.copy(isLoading = false, showBackupCodes = false)
            }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false) }
        }
    }

    fun dismissBackupCodes() {
        _state.update { it.copy(showBackupCodes = false, backupCodes = emptyList()) }
    }

    // MARK: - #225 Switch 2FA Method

    /**
     * Initiates a method switch without disabling the current method first.
     * The new method is set up in a pending state; the old one remains active
     * until [verify2FA] confirms the new one — no gap in protection.
     */
    fun switch2FAMethod(
        vaultId: String,
        newMethod: TwoFactorMethod,
        phone: String?,
        email: String?
    ) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        val req = Switch2FARequest(newMethod = newMethod, phone = phone, email = email)
        when (val result = apiClient.switch2FAMethod(vaultId, req)) {
            is ApiResult.Success -> _state.update {
                it.copy(switchResponse = result.data, isLoading = false)
            }
            is ApiResult.Error -> _state.update { it.copy(isLoading = false, error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false, error = "No network") }
        }
    }

    fun clearSwitchResponse() {
        _state.update { it.copy(switchResponse = null) }
    }

    // #120: Require biometric authentication before disabling 2FA (more security-sensitive than check-in)
    fun disable2FA(vaultId: String) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        when (val result = apiClient.disable2FA(vaultId)) {
            is ApiResult.Success -> _state.update { it.copy(status = null, isLoading = false) }
            is ApiResult.Error -> _state.update { it.copy(isLoading = false, error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false, error = "No network") }
        }
    }

    // Called only after successful BiometricHelper authentication in the screen layer.
    // Keeping the biometric prompt in the screen (where a FragmentActivity is available)
    // and the network call here (in the ViewModel) matches the same pattern used for check-in.
    fun disable2FAAfterBiometric(vaultId: String) = disable2FA(vaultId)

    // ── #119 internals ────────────────────────────────────────────────────────

    /**
     * Escalating cooldown schedule matching the iOS OTPRateLimiter:
     *   1–2 failures → no cooldown (grace period)
     *   3 failures   → 30 s
     *   4 failures   → 60 s
     *   5+ failures  → 120 s (capped)
     */
    internal fun otpCooldownSeconds(failures: Int): Int = when {
        failures < 3  -> 0
        failures == 3 -> 30
        failures == 4 -> 60
        else          -> 120
    }

    private fun startCooldown(seconds: Int) {
        cancelCooldown()
        // Persist when the cooldown ends, not how long is left: a countdown value would be
        // meaningless after the process is killed and restarted (#172).
        savedStateHandle[KEY_OTP_COOLDOWN_UNTIL] = System.currentTimeMillis() + seconds * 1_000L
        _state.update { it.copy(otpCooldownSeconds = seconds) }
        startCooldownTicker(seconds)
    }

    /** Ticks [TwoFactorUiState.otpCooldownSeconds] down to 0, driven by the persisted deadline. */
    private fun startCooldownTicker(seconds: Int) {
        cancelCooldown()
        cooldownJob = viewModelScope.launch {
            repeat(seconds) {
                kotlinx.coroutines.delay(1_000)
                val remaining = remainingCooldownSeconds()
                _state.update { it.copy(otpCooldownSeconds = remaining) }
                if (remaining <= 0) return@launch
            }
            _state.update { it.copy(otpCooldownSeconds = remainingCooldownSeconds()) }
        }
    }

    /** Seconds left until the persisted cooldown deadline, or 0 when none is pending. */
    private fun remainingCooldownSeconds(): Int {
        val deadline = savedStateHandle.readNumber(KEY_OTP_COOLDOWN_UNTIL) ?: return 0
        val remainingMillis = deadline.toLong() - System.currentTimeMillis()
        if (remainingMillis <= 0) return 0
        return ((remainingMillis + 999) / 1_000).toInt()
    }

    private fun clearPersistedRateLimitState() {
        savedStateHandle.remove<Any>(KEY_OTP_FAILURE_COUNT)
        savedStateHandle.remove<Any>(KEY_OTP_COOLDOWN_UNTIL)
    }

    /**
     * Reads a numeric value without assuming which box it comes back in: a value that has
     * been through a [SavedStateHandle] save/restore round-trip is not guaranteed to keep
     * the exact `Int`/`Long` type it was written with, and a typed `get<Int>` would then
     * fail with a [ClassCastException] on exactly the process-death path this state exists
     * to survive.
     */
    private fun SavedStateHandle.readNumber(key: String): Number? = get<Any>(key) as? Number

    private fun cancelCooldown() {
        cooldownJob?.cancel()
        cooldownJob = null
    }

    companion object {
        internal const val KEY_OTP_FAILURE_COUNT = "otp_failure_count"
        internal const val KEY_OTP_COOLDOWN_UNTIL = "otp_cooldown_until_millis"
    }

    override fun onCleared() {
        super.onCleared()
        cancelCooldown()
    }
}

// --- Vault ViewModel ---

@Immutable
data class VaultUiState(
    val vaults: List<Vault> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val isOffline: Boolean = false,
    val beneficiaryUpdated: Boolean = false,
    // True for the duration of an in-flight createVault() call, so the UI can disable the
    // submit control and a second tap can't reach the network/queue while the first is
    // still pending — without this, two rapid taps while offline queue two structurally
    // identical CREATE_VAULT actions (CREATE_VAULT has no natural dedupeKey) and both get
    // submitted once connectivity returns, creating two real vaults for one user action.
    val isCreatingVault: Boolean = false
)

private const val PAGE_SIZE = 20

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val apiClient: ApiClient,
    private val notificationHelper: NotificationHelper,
    private val pendingActionDao: PendingActionDao,
    private val vaultEventSocket: VaultEventSocket,
    private val expiringVaultsManager: com.ethosprotocol.services.ExpiringVaultsManager,
    private val offlineCache: OfflineCache,
    private val networkMonitor: NetworkMonitor,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(VaultUiState())
    val state = _state.asStateFlow()

    val expiringVaultsState = expiringVaultsManager.bannerState

    private var nextCursor: String? = null
    private val eventJobs = mutableMapOf<String, Job>()

    init {
        // #428: Observe network reachability. When connectivity is restored after an
        // offline period, immediately:
        //   1. Reload vaults so the list reflects current server state.
        //   2. Trigger PendingActionSyncWorker to drain any queued offline actions.
        // The distinctUntilChanged() in connectivityFlow prevents spurious reloads on
        // repeated true emissions (e.g. capability changes on the same network).
        viewModelScope.launch {
            networkMonitor.connectivityFlow.collect { isOnline ->
                val wasOffline = _state.value.isOffline
                _state.update { it.copy(isOffline = !isOnline) }
                if (isOnline && wasOffline) {
                    // Back online — reload and drain the pending-action queue.
                    load()
                    com.ethosprotocol.services.PendingActionSyncWorker.schedule(context)
                }
            }
        }
    }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        // Invalidate cache on manual refresh to ensure fresh data
        offlineCache.invalidate("/vaults")
        when (val result = apiClient.listVaults(limit = PAGE_SIZE)) {
            is ApiResult.Success -> {
                nextCursor = result.data.nextCursor
                _state.update {
                    it.copy(
                        vaults = result.data.vaults,
                        isLoading = false,
                        isOffline = false,
                        hasMore = result.data.hasMore
                    )
                }
                expiringVaultsManager.updateVaults(result.data.vaults)
                subscribeToEvents(result.data.vaults.map { it.id })
            }
            ApiResult.NetworkUnavailable -> {
                _state.update { it.copy(isLoading = false, isOffline = true) }
            }
            is ApiResult.Error -> {
                _state.update { it.copy(isLoading = false, error = result.message) }
            }
        }
    }

    /**
     * Fetches the next page (via [nextCursor], captured by the preceding [load]) and appends it
     * to the current list, for the "Load more" control in VaultListScreen's LazyColumn.
     */
    fun loadMore() {
        val cursor = nextCursor ?: return
        if (_state.value.isLoadingMore) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            when (val result = apiClient.listVaults(limit = PAGE_SIZE, after = cursor)) {
                is ApiResult.Success -> {
                    nextCursor = result.data.nextCursor
                    val allVaults = _state.value.vaults + result.data.vaults
                    _state.update {
                        it.copy(
                            vaults = allVaults,
                            isLoadingMore = false,
                            hasMore = result.data.hasMore
                        )
                    }
                    expiringVaultsManager.updateVaults(allVaults)
                    subscribeToEvents(result.data.vaults.map { it.id })
                }
                ApiResult.NetworkUnavailable -> {
                    _state.update { it.copy(isLoadingMore = false, isOffline = true) }
                }
                is ApiResult.Error -> {
                    _state.update { it.copy(isLoadingMore = false, error = result.message) }
                }
            }
        }
    }

    /**
     * Fetches all vault pages via cursor-based pagination (#112) and replaces the local list.
     * Accumulates pages until [VaultPage.hasMore] == false.
     */
    fun loadAll(limit: Int = 20) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        val accumulated = mutableListOf<Vault>()
        var cursor: String? = null
        do {
            when (val result = apiClient.listVaults(limit = limit, after = cursor)) {
                is ApiResult.Success -> {
                    accumulated.addAll(result.data.vaults)
                    cursor = result.data.nextCursor
                    if (!result.data.hasMore) {
                        _state.update { it.copy(vaults = accumulated, isLoading = false, isOffline = false) }
                        expiringVaultsManager.updateVaults(accumulated)
                        return@launch
                    }
                }
                ApiResult.NetworkUnavailable -> {
                    _state.update { it.copy(isLoading = false, isOffline = true) }
                    return@launch
                }
                is ApiResult.Error -> {
                    _state.update { it.copy(isLoading = false, error = result.message) }
                    return@launch
                }
            }
        } while (cursor != null)
        _state.update { it.copy(vaults = accumulated, isLoading = false, isOffline = false) }
        expiringVaultsManager.updateVaults(accumulated)
    }

    fun dismissExpiringVaultsBanner() {
        expiringVaultsManager.dismissBanner()
    }

    // Keeps one VaultEventSocket subscription per vault currently in [_state], so
    // check-ins/deposits/withdrawals made elsewhere (another device, an expiry)
    // update this list in place instead of requiring a manual refresh.
    private fun subscribeToEvents(vaultIds: List<String>) {
        val currentIds = vaultIds.toSet()
        eventJobs.keys.filter { it !in currentIds }.forEach { id -> eventJobs.remove(id)?.cancel() }
        currentIds.filterNot { eventJobs.containsKey(it) }.forEach { id ->
            eventJobs[id] = viewModelScope.launch {
                vaultEventSocket.events(id).collect { event ->
                    val updated = event.vault ?: return@collect
                    // Same merge path as a poll response (updateVaultInPlace) — see the
                    // "Reconciling a poll/push disagreement" rule in api-contract.md (#223):
                    // whichever one is received last simply overwrites in place.
                    updateVaultInPlace(updated)
                }
            }
        }
    }

    override fun onCleared() {
        eventJobs.values.forEach { it.cancel() }
        eventJobs.clear()
    }

    fun checkIn(vaultId: String) = viewModelScope.launch {
        // Generated once and reused for both this attempt and any subsequent queued retry, so
        // a resubmission after a process death is identifiable to the server as a duplicate of
        // this specific attempt rather than a brand-new check-in.
        val idempotencyKey = java.util.UUID.randomUUID().toString()
        when (val result = apiClient.checkIn(vaultId, idempotencyKey)) {
            is ApiResult.Success -> refreshSingle(vaultId)
            is ApiResult.Error -> _state.update { it.copy(error = result.message) }
            ApiResult.NetworkUnavailable -> queueAction(
                PendingAction(
                    type = PendingActionType.CHECK_IN,
                    vaultId = vaultId,
                    queuedAt = System.currentTimeMillis(),
                    dedupeKey = "check_in:$vaultId",
                    idempotencyKey = idempotencyKey
                )
            )
        }
    }

    /** Refetches a single vault and patches it into the existing list, instead of reloading all vaults. */
    fun refreshSingle(vaultId: String) = viewModelScope.launch {
        when (val result = apiClient.getVault(vaultId)) {
            is ApiResult.Success -> updateVaultInPlace(result.data)
            is ApiResult.Error -> _state.update { it.copy(error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(error = "No network") }
        }
    }

    // Shared merge point for both a poll response (refreshSingle) and a `vault_updated`
    // push (subscribeToEvents) — see the "Reconciling a poll/push disagreement" rule in
    // api-contract.md (#223): whichever is received last always overwrites in place.
    // Only updates if the vault is actually different to minimize Compose recomposition churn.
    private fun updateVaultInPlace(vault: Vault) {
        _state.update { state ->
            val updated = state.vaults.map { if (it.id == vault.id) vault else it }
            if (updated === state.vaults) state else state.copy(vaults = updated)
        }
    }

    /// Update the beneficiary for a vault (owner-only). On success the vault is
    /// refreshed in place so the UI reflects the new beneficiary immediately — matching the
    /// same pattern used by checkIn(). Mirrors iOS VaultStore.updateBeneficiary.
    /// Uses refreshSingle() instead of load() to avoid redundant full-list fetches (#320).
    fun updateBeneficiary(vaultId: String, newBeneficiary: String) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null, beneficiaryUpdated = false) }
        when (val result = apiClient.updateBeneficiary(vaultId, newBeneficiary)) {
            is ApiResult.Success -> {
                _state.update { it.copy(isLoading = false, beneficiaryUpdated = true) }
                refreshSingle(vaultId)
            }
            is ApiResult.Error -> _state.update { it.copy(isLoading = false, error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false, error = "No network") }
        }
    }

    fun clearBeneficiaryUpdated() {
        _state.update { it.copy(beneficiaryUpdated = false) }
    }

    fun createVault(beneficiary: String, intervalDays: Int) = viewModelScope.launch {
        // Guards against a second tap reaching the network/queue while the first createVault()
        // call is still in flight (see VaultUiState.isCreatingVault).
        if (_state.value.isCreatingVault) return@launch
        _state.update { it.copy(isCreatingVault = true) }

        val req = CreateVaultRequest(beneficiary, intervalDays * 86_400L)
        // Reused for any queued retry, same rationale as checkIn()'s idempotencyKey.
        val idempotencyKey = java.util.UUID.randomUUID().toString()
        when (val result = apiClient.createVault(req, idempotencyKey)) {
            is ApiResult.Success -> {
                _state.update { it.copy(isCreatingVault = false) }
                load()
            }
            is ApiResult.Error -> _state.update { it.copy(isCreatingVault = false, error = result.message) }
            ApiResult.NetworkUnavailable -> {
                queueAction(
                    PendingAction(
                        type = PendingActionType.CREATE_VAULT,
                        payloadJson = Json.encodeToString(kotlinx.serialization.serializer(), req),
                        queuedAt = System.currentTimeMillis(),
                        idempotencyKey = idempotencyKey
                    )
                )
                _state.update { it.copy(isCreatingVault = false) }
            }
        }
    }

    private suspend fun queueAction(action: PendingAction) {
        pendingActionDao.insert(action)
        val queued = pendingActionDao.getAll()
        notificationHelper.showQueuedActions(queued.size)
        PendingActionSyncWorker.schedule(context)
        _state.update { it.copy(error = "Offline — request queued and will retry automatically") }
    }
}

// --- Acceptance ViewModel ---

@Immutable
data class AcceptanceUiState(
    val isLoading: Boolean = false,
    val isAccepted: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AcceptanceViewModel @Inject constructor(
    private val apiClient: ApiClient
) : ViewModel() {

    private val _state = MutableStateFlow(AcceptanceUiState())
    val state = _state.asStateFlow()

    // token: parsed from the /accept deep-link URL (required by the server).
    fun accept(vaultId: String, token: String) = viewModelScope.launch {
        _state.update { it.copy(isLoading = true, error = null) }
        when (val result = apiClient.acceptBeneficiary(vaultId, token)) {
            is ApiResult.Success -> _state.update { it.copy(isLoading = false, isAccepted = true) }
            is ApiResult.Error -> _state.update { it.copy(isLoading = false, error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false, error = "No network. Please try again.") }
        }
    }
}

// --- Deposit ViewModel ---

@Immutable
data class DepositUiState(
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class DepositViewModel @Inject constructor(
    private val apiClient: ApiClient
) : ViewModel() {

    private val _state = MutableStateFlow(DepositUiState())
    val state = _state.asStateFlow()

    /**
     * Validates and submits a deposit for [vaultId].
     *
     * [amountXlm] is the user-entered string in XLM (e.g. "5.0"). It is converted
     * to stroops (1 XLM = 10,000,000 stroops) here so the ViewModel owns the
     * parsing/validation logic and the UI only has to display [state].
     */
    fun deposit(vaultId: String, amountXlm: String) = viewModelScope.launch {
        val stroops = parseStroops(amountXlm)
        if (stroops == null || stroops <= 0) {
            _state.update { it.copy(error = "Enter a valid positive XLM amount.") }
            return@launch
        }
        _state.update { it.copy(isLoading = true, error = null) }
        when (val result = apiClient.deposit(vaultId, stroops)) {
            is ApiResult.Success -> _state.update { it.copy(isLoading = false, isSuccess = true) }
            is ApiResult.Error -> _state.update { it.copy(isLoading = false, error = result.message) }
            ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false, error = "No network") }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Converts an XLM string to stroops, or null if the input is invalid. */
    private fun parseStroops(xlm: String): Long? {
        val value = xlm.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value <= 0.0) return null
        val stroops = value * 10_000_000.0
        if (stroops > Long.MAX_VALUE.toDouble()) return null
        return stroops.toLong()
    }
}

// --- Withdraw ViewModel ---

@Immutable
data class WithdrawUiState(
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class WithdrawViewModel @Inject constructor(
    private val apiClient: ApiClient
) : ViewModel() {

    private val _state = MutableStateFlow(WithdrawUiState())
    val state = _state.asStateFlow()

    /**
     * Validates and submits a withdrawal for [vaultId].
     *
     * Client-side guard: [amountXlm] must parse to a positive stroop count that
     * does not exceed [vaultBalanceStroops]. The matching check runs on the server
     * too; this gate provides immediate UX feedback before the network round-trip.
     * Biometric auth must be completed by the caller before invoking this method.
     */
    fun withdraw(vaultId: String, amountXlm: String, vaultBalanceStroops: Long) = viewModelScope.launch {
        val stroops = parseStroops(amountXlm)
        when {
            stroops == null || stroops <= 0 ->
                _state.update { it.copy(error = "Enter a valid positive XLM amount.") }
            stroops > vaultBalanceStroops ->
                _state.update { it.copy(error = "Amount exceeds available balance.") }
            else -> {
                _state.update { it.copy(isLoading = true, error = null) }
                when (val result = apiClient.withdraw(vaultId, stroops)) {
                    is ApiResult.Success -> _state.update { it.copy(isLoading = false, isSuccess = true) }
                    is ApiResult.Error -> _state.update { it.copy(isLoading = false, error = result.message) }
                    ApiResult.NetworkUnavailable -> _state.update { it.copy(isLoading = false, error = "No network") }
                }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun parseStroops(xlm: String): Long? {
        val value = xlm.toDoubleOrNull() ?: return null
        if (!value.isFinite() || value <= 0.0) return null
        val stroops = value * 10_000_000.0
        if (stroops > Long.MAX_VALUE.toDouble()) return null
        return stroops.toLong()
    }
}

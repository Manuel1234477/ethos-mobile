package com.ethosprotocol.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ethosprotocol.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.models.DestructiveConfirmation
import com.ethosprotocol.models.TwoFactorMethod
import com.ethosprotocol.models.TwoFactorStatus
import com.ethosprotocol.models.Enable2FARequest
import com.ethosprotocol.models.Verify2FARequest
import com.ethosprotocol.models.Switch2FARequest
import com.ethosprotocol.models.StellarAddress
import com.ethosprotocol.services.BiometricHelper
import com.ethosprotocol.services.UsernameValidator
import com.ethosprotocol.services.VaultDeepLinkAction
import com.ethosprotocol.services.AccessibilityService
import com.ethosprotocol.ui.AcceptanceViewModel
import com.ethosprotocol.ui.AuthUiState
import com.ethosprotocol.ui.AuthViewModel
import com.ethosprotocol.ui.NotificationDebugViewModel
import com.ethosprotocol.ui.VaultViewModel
import com.ethosprotocol.ui.TwoFactorViewModel
import com.ethosprotocol.services.NotificationDeliveryLog
import com.ethosprotocol.utils.TrackScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// MARK: - Accessibility Helpers

/**
 * Renders a CircularProgressIndicator that respects the user's reduce-motion accessibility setting.
 * When reduce motion is enabled, shows an empty Box instead of the animated spinner.
 */
@Composable
private fun AccessibleCircularProgressIndicator(modifier: Modifier = Modifier, strokeWidth: Dp = 4.dp) {
    val context = LocalContext.current
    val shouldReduceMotion = AccessibilityService.isReduceMotionEnabled(context)

    if (shouldReduceMotion) {
        Box(modifier = modifier)
    } else {
        CircularProgressIndicator(modifier = modifier, strokeWidth = strokeWidth)
    }
}

// MARK: - Auth Screen

@Composable
fun AuthScreen(vm: AuthViewModel = hiltViewModel()) {
    TrackScreen("Auth")
    val state by vm.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as android.app.Activity
    var showRegister by remember { mutableStateOf(false) }
    var showRecovery by remember { mutableStateOf(false) }

    if (showRegister) {
        RegisterSheet(
            onRegister = { username -> vm.register(activity, username); showRegister = false },
            onDismiss = { showRegister = false }
        )
    }

    AuthScreenContent(
        isLoading = state.isLoading,
        error = state.error,
        cooldownRemainingSeconds = state.cooldownRemainingSeconds,
        onSignIn = { vm.signIn(activity) },
        onRegister = { showRegister = true }
    )
}

/**
 * Stateless content layer extracted so Paparazzi screenshot tests can render it
 * on the JVM without an Activity context or Hilt DI graph.
 */
@Composable
fun AuthScreenContent(
    isLoading: Boolean,
    error: String?,
    cooldownRemainingSeconds: Int = 0,
    onSignIn: () -> Unit,
    onRegister: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).focusRequester(focusRequester),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Lock, contentDescription = "Secure sign-in",
            modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.app_title), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
        if (cooldownRemainingSeconds > 0) {
            Text(
                stringResource(R.string.auth_too_many_attempts, cooldownRemainingSeconds),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = onSignIn,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && cooldownRemainingSeconds == 0
        ) {
            if (isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else { Icon(Icons.Default.Key, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.sign_in_with_passkey)) }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRegister) { Text(stringResource(R.string.create_account)) }
    }
}

@Composable
private fun RegisterSheet(onRegister: (String) -> Unit, onDismiss: () -> Unit) {
    var username by remember { mutableStateOf("") }
    val trimmedUsername = username.trim()
    val isValid = UsernameValidator.isValid(trimmedUsername)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.register_title)) },
        text = {
            Column {
                OutlinedTextField(value = username, onValueChange = { username = it },
                    label = { Text(stringResource(R.string.register_username_label)) }, singleLine = true)
                if (username.isNotBlank() && !isValid) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.register_username_rules, UsernameValidator.MIN_LENGTH, UsernameValidator.MAX_LENGTH),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRegister(trimmedUsername) }, enabled = isValid) { Text(stringResource(R.string.register_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun RecoverySheet(
    state: AuthUiState,
    onSendCode: (String) -> Unit,
    onFinish: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    val codeSent = state.recoveryToken != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recover_title)) },
        text = {
            Column {
                if (!codeSent) {
                    Text(
                        stringResource(R.string.recover_instructions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = username, onValueChange = { username = it },
                        label = { Text(stringResource(R.string.register_username_label)) }, singleLine = true,
                        enabled = !state.isLoading, modifier = Modifier.fillMaxWidth())
                } else {
                    Text(
                        stringResource(R.string.recover_confirmation_instructions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (!codeSent) {
                TextButton(
                    onClick = { onSendCode(username) },
                    enabled = username.isNotBlank() && !state.isLoading
                ) { Text(stringResource(R.string.recover_send_code)) }
            } else {
                TextButton(onClick = { onFinish(username) }, enabled = !state.isLoading) { Text(stringResource(R.string.action_continue)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

// MARK: - Vault List Screen

@Composable
fun VaultListScreen(
    onVaultClick: (String) -> Unit,
    onDebugLogClick: (() -> Unit)? = null,
    vm: VaultViewModel = hiltViewModel()
) {
    TrackScreen("VaultList")
    val state by vm.state.collectAsStateWithLifecycle()
    val expiringState by vm.expiringVaultsState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showCreate by remember { mutableStateOf(false) }
    var pendingCheckIn by remember { mutableStateOf<Vault?>(null) }
    var biometricError by remember { mutableStateOf<String?>(null) }

    // #118 / #485: Non-blocking root warning. The check itself (su-binary/package/mount-point
    // scans, see IntegrityChecker) is file-I/O-bound, so it's deferred off the composition's
    // initial state and run on Dispatchers.Default instead of blocking the first frame of this
    // screen — the list and its data load are not gated on it.
    var showRootWarning by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showRootWarning = withContext(Dispatchers.Default) {
            com.ethosprotocol.services.IntegrityChecker(context).isRooted
        }
    }

    LaunchedEffect(Unit) { vm.load() }

    // #118: Non-blocking root warning dialog.
    if (showRootWarning) {
        AlertDialog(
            onDismissRequest = { showRootWarning = false },
            title = { Text(stringResource(R.string.security_warning_title)) },
            text = {
                Text(
                    stringResource(R.string.security_warning_message)
                )
            },
            confirmButton = {
                TextButton(onClick = { showRootWarning = false }) { Text(stringResource(R.string.security_warning_acknowledge)) }
            }
        )
    }

    if (showCreate) {
        CreateVaultDialog(
            onCreate = { ben, days -> vm.createVault(ben, days); showCreate = false },
            onDismiss = { showCreate = false }
        )
    }

    pendingCheckIn?.let { vault ->
        CheckInConfirmationDialog(
            vault = vault,
            onConfirm = {
                pendingCheckIn = null
                BiometricHelper(context as androidx.fragment.app.FragmentActivity).authenticate(
                    title = stringResource(R.string.checkin_title),
                    subtitle = "Vault ${vault.id.take(12)}… will extend by ${formatInterval(vault.checkInInterval)}",
                    onSuccess = { vm.checkIn(vault.id) },
                    onError = { err -> biometricError = err },
                )
            },
            onDismiss = { pendingCheckIn = null },
        )
    }


    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.vault_list_title)) }, actions = {
                // #235: debug/QA-only entry to the notification delivery log — never
                // shown in a release build (onDebugLogClick is only non-null when
                // MainActivity's nav graph wires it in under BuildConfig.DEBUG).
                onDebugLogClick?.let { onClick ->
                    IconButton(onClick = onClick) { Icon(Icons.Default.BugReport, stringResource(R.string.notification_log_title)) }
                }
                IconButton(onClick = { showCreate = true }) { Icon(Icons.Default.Add, stringResource(R.string.vault_create_button)) }
            })
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading && state.vaults.isEmpty() ->
                    AccessibleCircularProgressIndicator(Modifier.align(Alignment.Center))
                state.vaults.isEmpty() ->
                    Text(stringResource(R.string.vault_list_empty),
                        Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    // Ties the pull gesture to the same VaultViewModel.load() used for the initial
                    // fetch, so state.isLoading naturally drives the pull indicator too.
                    val refreshState = rememberPullToRefreshState()
                    PullToRefreshBox(
                        isRefreshing = state.isLoading,
                        onRefresh = { vm.load() },
                        state = refreshState,
                        modifier = Modifier.fillMaxSize().testTag("vaultListPullToRefresh"),
                        indicator = {
                            PullToRefreshDefaults.Indicator(
                                isRefreshing = state.isLoading,
                                state = refreshState,
                                color = MaterialTheme.colorScheme.tertiary,
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier.align(Alignment.TopCenter)
                            )
                        }
                    ) {
                        LazyColumn(
                            contentPadding = WindowInsets.navigationBars.asPaddingValues()
                        ) {
                            if (state.isOffline) item {
                                OfflineBanner()
                            }
                            if (expiringState.expiredVaults.isNotEmpty() && !expiringState.isDismissed) {
                                item {
                                    ExpiringVaultsBanner(
                                        vaults = expiringState.expiredVaults,
                                        onDismiss = { vm.dismissExpiringVaultsBanner() },
                                        onCheckIn = { vault -> pendingCheckIn = vault }
                                    )
                                }
                            }
                            val errorMsg = biometricError ?: state.error
                            errorMsg?.let { err ->
                                item {
                                    Text(err, color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(16.dp))
                                }
                            }
                            items(state.vaults, key = { it.id }) { vault ->
                                VaultCard(
                                    vault = vault,
                                    onClick = { onVaultClick(vault.id) },
                                    onCheckIn = { pendingCheckIn = vault },
                                )
                            }
                            if (state.hasMore) item {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    if (state.isLoadingMore) {
                                        AccessibleCircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    } else {
                                        OutlinedButton(onClick = { vm.loadMore() }) { Text(stringResource(R.string.vault_list_load_more)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatInterval(seconds: Long): String {
    val days = seconds / 86_400
    return if (days > 0) "$days day${if (days == 1L) "" else "s"}" else "${seconds / 3_600}h"
}

@Composable
private fun CheckInConfirmationDialog(vault: Vault, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.checkin_title)) },
        text = {
            Column {
                Text(stringResource(R.string.checkin_vault_id, vault.id.take(12)),
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.checkin_ttl_extension, formatInterval(vault.checkInInterval)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.checkin_requires_auth),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// MARK: - Destructive Confirmation (#220)

/**
 * Shared confirmation UI for any irreversible, destructive vault action
 * (delete, archive, …) on either platform. The confirm button stays disabled
 * until the user types [requiredText] (typically the vault's own name/ID)
 * exactly — a plain Yes/No tap is not enough given the financial and
 * beneficiary implications of getting this wrong. [onConfirm] is only ever
 * invoked once [DestructiveConfirmation.isConfirmed] is true.
 */
@Composable
fun DestructiveConfirmationDialog(
    title: String,
    message: String,
    requiredText: String,
    confirmLabel: String = "Delete",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var enteredText by remember { mutableStateOf("") }
    val confirmation = DestructiveConfirmation(requiredText, enteredText)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Type \"$requiredText\" to confirm.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = enteredText,
                    onValueChange = { enteredText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                // Guarded via confirmIfMatched, not just `enabled` below — `enabled`
                // only stops a tap from reaching this closure through the button's own
                // click handler; it is not what makes this safe.
                onClick = { confirmation.confirmIfMatched(onConfirm) },
                enabled = confirmation.isConfirmed,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun OfflineBanner(cachedAt: Long? = null) {
    val message = if (cachedAt != null) {
        "Offline — showing cached data (as of ${formatCacheAge(cachedAt)} ago)"
    } else {
        "Offline — showing cached data"
    }
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
        // mergeDescendants groups the icon + label into a single TalkBack stop instead of two,
        // so giving the icon a description adds context without a duplicate announcement.
        Row(
            Modifier.fillMaxWidth().padding(12.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.WifiOff, contentDescription = "Offline",
                tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(8.dp))
            Text(message, color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ExpiringVaultsBanner(
    vaults: List<Vault>,
    onDismiss: () -> Unit,
    onCheckIn: (Vault) -> Unit
) {
    val summary = if (vaults.size == 1) {
        "1 vault expires in less than 24 hours"
    } else {
        "${vaults.size} vaults expire in less than 24 hours"
    }
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Warning, contentDescription = "Expiring Soon",
                    tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(8.dp))
                Text(summary, color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Dismiss", fontSize = MaterialTheme.typography.labelSmall.fontSize)
                }
                if (vaults.size == 1) {
                    Button(onClick = { onCheckIn(vaults[0]) }, modifier = Modifier.weight(1f)) {
                        Text("Check In", fontSize = MaterialTheme.typography.labelSmall.fontSize)
                    }
                }
            }
        }
    }
}

private fun formatCacheAge(cachedAt: Long): String {
    val elapsedSeconds = ((System.currentTimeMillis() - cachedAt) / 1000).coerceAtLeast(0)
    val minutes = elapsedSeconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        days > 0 -> "${days}d"
        hours > 0 -> "${hours}h"
        minutes > 0 -> "${minutes}m"
        else -> "a few seconds"
    }
}

@Composable
private fun VaultCard(
    vault: Vault,
    onClick: () -> Unit,
    onCheckIn: () -> Unit,
    onDeposit: () -> Unit = {},
    onWithdraw: () -> Unit = {}
) {
    val statusColor = vault.status.statusColor()
    val animatedStatusColor by animateColorAsState(statusColor, label = "vaultStatusColor")
    val containerColor by animateColorAsState(
        when (vault.status) {
            VaultStatus.expired -> MaterialTheme.colorScheme.errorContainer
            VaultStatus.released -> MaterialTheme.colorScheme.secondaryContainer
            VaultStatus.paused -> MaterialTheme.colorScheme.surfaceVariant
            VaultStatus.active -> MaterialTheme.colorScheme.surface
        },
        label = "vaultStatusContainer"
    )
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, animatedStatusColor.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // At the largest font scale a full-length id + chip in one row will clip rather than
            // wrap the layout; ellipsize the id (already truncated to 12 chars) so the chip stays visible.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(vault.id.take(12) + "…", style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                StatusChip(vault.status)
            }
            Spacer(Modifier.height(4.dp))
            Text(vault.formattedBalance, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            vault.ttlRemaining?.let { ttl ->
                Spacer(Modifier.height(4.dp))
                VaultCountdownText(ttl)
            }
            if (vault.isExpiringSoon) {
                Spacer(Modifier.height(4.dp))
                // mergeDescendants groups the icon + label into a single TalkBack stop instead of two.
                Row(
                    Modifier.semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = "Warning", tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.checkin_expiring_soon), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (vault.status == com.ethosprotocol.models.VaultStatus.active) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCheckIn, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.checkin_button))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDeposit, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.vault_deposit))
                    }
                    OutlinedButton(onClick = onWithdraw, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.vault_withdraw))
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultCountdownText(initialTtlSeconds: Long) {
    var remainingSeconds by remember(initialTtlSeconds) { mutableLongStateOf(initialTtlSeconds.coerceAtLeast(0L)) }
    LaunchedEffect(initialTtlSeconds) {
        remainingSeconds = initialTtlSeconds.coerceAtLeast(0L)
        while (remainingSeconds > 0L) {
            delay(1_000)
            remainingSeconds = (remainingSeconds - 1L).coerceAtLeast(0L)
        }
    }
    val urgencyAlpha by animateFloatAsState(
        targetValue = if (remainingSeconds < 3_600L) 1f else 0.82f,
        label = "vaultCountdownAlpha"
    )
    val countdownColor = when {
        remainingSeconds == 0L -> MaterialTheme.colorScheme.error
        remainingSeconds < 3_600L -> MaterialTheme.colorScheme.error
        remainingSeconds < 86_400L -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Schedule,
            contentDescription = "Vault countdown",
            tint = countdownColor,
            modifier = Modifier.size(14.dp).alpha(urgencyAlpha)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "Unlocks in ${formatCountdown(remainingSeconds)}",
            color = countdownColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (remainingSeconds < 3_600L) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(urgencyAlpha)
        )
    }
}

private fun formatCountdown(seconds: Long): String {
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    val secs = seconds % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}

@Composable
private fun VaultStatus.statusColor() = when (this) {
    VaultStatus.active -> MaterialTheme.colorScheme.primary
    VaultStatus.expired -> MaterialTheme.colorScheme.error
    VaultStatus.released -> MaterialTheme.colorScheme.secondary
    VaultStatus.paused -> MaterialTheme.colorScheme.outline
}

@Composable
private fun StatusChip(status: VaultStatus) {
    val label = when (status) {
        VaultStatus.active -> "Active"
        VaultStatus.expired -> "Expired"
        VaultStatus.released -> "Released"
        VaultStatus.paused -> "Paused"
    }
    val color = status.statusColor()
    AssistChip(
        onClick = {},
        label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        shape = RoundedCornerShape(50),
        colors = AssistChipDefaults.assistChipColors(
            containerColor = color.copy(alpha = 0.12f),
            labelColor = color
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.55f))
    )
}

// MARK: - Beneficiary Acceptance Screen

@Composable
fun BeneficiaryAcceptanceScreen(
    vaultId: String,
    // token: parsed from the /accept deep-link URL; required by the server (#109).
    token: String,
    onAccepted: () -> Unit,
    onDecline: () -> Unit,
    vm: AcceptanceViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.isAccepted) {
        if (state.isAccepted) onAccepted()
    }

    BeneficiaryAcceptanceScreenContent(
        vaultId = vaultId,
        isLoading = state.isLoading,
        error = state.error,
        onAccept = { vm.accept(vaultId, token) },
        onDecline = onDecline
    )
}

/**
 * Stateless content layer extracted so Paparazzi screenshot tests can render it
 * on the JVM without a Hilt DI graph.
 */
@Composable
fun BeneficiaryAcceptanceScreenContent(
    vaultId: String,
    isLoading: Boolean,
    error: String?,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = "Secure beneficiary acceptance",
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.beneficiary_acceptance_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "You have been named as the beneficiary for the following vault:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = vaultId,
            onValueChange = {},
            label = { Text(stringResource(R.string.beneficiary_vault_id_label)) },
            readOnly = true,
            modifier = Modifier.fillMaxWidth()
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onAccept,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            if (isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.action_accept))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onDecline,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            Text(stringResource(R.string.action_decline))
        }
    }
}

// MARK: - Manage Beneficiary Screen

@Composable
fun ManageBeneficiaryScreen(
    vault: com.ethosprotocol.models.Vault,
    onDone: () -> Unit,
    vm: VaultViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var newBeneficiary by remember { mutableStateOf("") }
    var showConfirmation by remember { mutableStateOf(false) }

    // The server rejects an address that is empty or unchanged. Mirror the same
    // validation used by iOS BeneficiaryUpdate.isValidNewBeneficiary().
    val isAddressValid = newBeneficiary.trim().isNotEmpty() && newBeneficiary.trim() != vault.beneficiary

    LaunchedEffect(state.beneficiaryUpdated) {
        if (state.beneficiaryUpdated) {
            vm.clearBeneficiaryUpdated()
            onDone()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Person, contentDescription = null,
            modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.manage_beneficiary_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.manage_beneficiary_current_label), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(vault.beneficiary,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(16.dp))

        if (showConfirmation) {
            // Confirmation step — mirrors iOS ManageBeneficiaryView.confirmationContent
            Text(stringResource(R.string.manage_beneficiary_change_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.manage_beneficiary_from_label), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(vault.beneficiary,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.manage_beneficiary_to_label), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(newBeneficiary.trim(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace)
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { vm.updateBeneficiary(vault.id, newBeneficiary.trim()) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading
            ) {
                if (state.isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.manage_beneficiary_confirm_change))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showConfirmation = false },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading
            ) { Text(stringResource(R.string.action_back)) }
        } else {
            // Input step
            OutlinedTextField(
                value = newBeneficiary,
                onValueChange = { newBeneficiary = it },
                label = { Text(stringResource(R.string.manage_beneficiary_new_address_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = newBeneficiary.isNotEmpty() && !isAddressValid,
                supportingText = if (newBeneficiary.isNotEmpty() && !isAddressValid) {
                    { Text(stringResource(R.string.manage_beneficiary_error_empty)) }
                } else null
            )
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { showConfirmation = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = isAddressValid
            ) { Text("Continue") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Cancel") }
        }
    }
}

// MARK: - Vault Deep Link Screen

@Composable
fun VaultDeepLinkScreen(
    vaultId: String,
    actionPath: String,
    onDone: () -> Unit,
    onDeposit: (vaultId: String) -> Unit = {},
    onWithdraw: (vaultId: String) -> Unit = {},
    vm: VaultViewModel = hiltViewModel()
) {
    val action = VaultDeepLinkAction.fromPathSegment(actionPath)
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var isProcessing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showBeneficiaryDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.load() }

    val vault = state.vaults.find { it.id == vaultId }
    val (title, description) = when (action) {
        VaultDeepLinkAction.CHECK_IN -> "Check In" to "Confirm check-in for vault ${vaultId.take(12)}…"
        VaultDeepLinkAction.WITHDRAW -> "Withdraw" to "Withdraw funds from vault ${vaultId.take(12)}…"
        VaultDeepLinkAction.VIEW_DETAILS -> "Vault Details" to "View details for vault ${vaultId.take(12)}…"
        VaultDeepLinkAction.MANAGE_BENEFICIARY -> "Manage Beneficiary" to "Update beneficiary for vault ${vaultId.take(12)}…"
        null -> "Vault Link" to "Unrecognised vault action."
    }

    // VIEW_DETAILS early-exit case with full vault card
    if (action == VaultDeepLinkAction.VIEW_DETAILS && vault != null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            VaultCard(vault = vault, onClick = {}, onCheckIn = {})
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
        return
    }

    // Determine display error:
    // - Show "Vault not found" ONLY if loading has completed and vault is still null
    // - Do NOT show "not found" while still loading (prevents false flash)
    val displayError = error ?: if (!state.isLoading && vault == null && action == VaultDeepLinkAction.VIEW_DETAILS) {
        "Vault not found"
    } else {
        null
    }

    val actionLabel = when (action) {
        VaultDeepLinkAction.CHECK_IN -> "Check In"
        VaultDeepLinkAction.WITHDRAW -> "Withdraw"
        VaultDeepLinkAction.MANAGE_BENEFICIARY -> "Manage Beneficiary"
        else -> title
    }

    VaultDeepLinkScreenContent(
        title = title,
        description = description,
        error = displayError,
        actionLabel = actionLabel,
        isProcessing = isProcessing,
        actionEnabled = !isProcessing && (action != VaultDeepLinkAction.CHECK_IN || vault != null),
        isLoading = state.isLoading,
        onAction = {
            when (action) {
                VaultDeepLinkAction.CHECK_IN -> {
                    if (vault == null) {
                        error = "Vault not found"
                        return@VaultDeepLinkScreenContent
                    }
                    isProcessing = true
                    error = null
                    BiometricHelper(context as androidx.fragment.app.FragmentActivity).authenticate(
                        title = "Confirm Check-In",
                        subtitle = "Vault ${vault.id.take(12)}…",
                        onSuccess = {
                            vm.checkIn(vault.id)
                            isProcessing = false
                            onDone()
                        },
                        onError = { err ->
                            error = err
                            isProcessing = false
                        }
                    )
                }
                VaultDeepLinkAction.WITHDRAW -> {
                    if (vault == null) error = "Vault not found"
                    else onWithdraw(vault.id)
                }
                VaultDeepLinkAction.MANAGE_BENEFICIARY -> {
                    error = "Beneficiary management is not yet available in the mobile app."
                }
                else -> Unit
            }
        },
        onDone = onDone
    )

    if (showBeneficiaryDialog && vault != null) {
        ManageBeneficiaryDialog(
            currentBeneficiary = vault.beneficiary,
            onSubmit = { newBeneficiary ->
                showBeneficiaryDialog = false
                isProcessing = true
                error = null
                BiometricHelper(context as androidx.fragment.app.FragmentActivity).authenticate(
                    title = "Confirm Beneficiary Change",
                    subtitle = "Vault ${vault.id.take(12)}… beneficiary will change to ${newBeneficiary.take(12)}…",
                    onSuccess = {
                        vm.updateBeneficiary(vault.id, newBeneficiary)
                        isProcessing = false
                        onDone()
                    },
                    onError = { err ->
                        error = err
                        isProcessing = false
                    }
                )
            },
            onDismiss = { showBeneficiaryDialog = false }
        )
    }

}

/**
 * Stateless content layer for [VaultDeepLinkScreen].
 * Extracted so Paparazzi snapshot tests can render deep-link action screens
 * on the JVM without a ViewModel, BiometricHelper, or Activity context.
 */
@Composable
fun VaultDeepLinkScreenContent(
    title: String,
    description: String,
    error: String?,
    actionLabel: String,
    isProcessing: Boolean,
    actionEnabled: Boolean,
    isLoading: Boolean = false,
    onAction: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (isLoading) {
            Spacer(Modifier.height(16.dp))
            AccessibleCircularProgressIndicator(
                Modifier.align(Alignment.CenterHorizontally).testTag("loading")
            )
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onAction,
            modifier = Modifier.fillMaxWidth(),
            enabled = actionEnabled
        ) {
            Text(if (isProcessing) "Processing…" else actionLabel)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

@Composable
private fun ManageBeneficiaryDialog(
    currentBeneficiary: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var beneficiary by remember { mutableStateOf(currentBeneficiary) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Beneficiary") },
        text = {
            Column {
                Text(
                    "This vault's funds are released to this address if it is never checked into again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = beneficiary, onValueChange = { beneficiary = it },
                    label = { Text("Beneficiary address") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(beneficiary) },
                enabled = beneficiary.isNotBlank() && beneficiary != currentBeneficiary
            ) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun CreateVaultDialog(onCreate: (String, Int) -> Unit, onDismiss: () -> Unit) {
    var beneficiary by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(30f) }

    // Live validation using the shared StrKey spec (shared/stellar-validation-spec.md).
    val isBeneficiaryValid = StellarAddress.isValidPublicKey(beneficiary)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Vault") },
        text = {
            Column {
                OutlinedTextField(
                    value = beneficiary,
                    onValueChange = { beneficiary = it },
                    label = { Text("Beneficiary Stellar address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = beneficiary.isNotEmpty() && !isBeneficiaryValid,
                    supportingText = {
                        if (beneficiary.isNotEmpty() && !isBeneficiaryValid) {
                            Text("Enter a valid Stellar address (56 characters, starting with G).")
                        }
                    }
                )
                Spacer(Modifier.height(12.dp))
                Text("Check-in interval: ${days.toInt()} days",
                    style = MaterialTheme.typography.bodySmall)
                Slider(value = days, onValueChange = { days = it }, valueRange = 1f..365f, steps = 363)
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(beneficiary, days.toInt()) },
                enabled = isBeneficiaryValid) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// MARK: - Deposit Screen

/**
 * The screen is reached either from the vault detail action list or from the
 * deep-link action router (deposit action). It navigates back via [onDone] on
 * success.
 */
@Composable
fun DepositScreen(
    vaultId: String,
    vm: com.ethosprotocol.ui.DepositViewModel = hiltViewModel(),
    onDone: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var amountInput by remember { mutableStateOf("") }

    // Navigate away as soon as the deposit succeeds
    LaunchedEffect(state.isSuccess) {
        if (state.isSuccess) onDone()
    }

    DepositScreenContent(
        vaultId = vaultId,
        amountInput = amountInput,
        isLoading = state.isLoading,
        error = state.error,
        onAmountChange = { amountInput = it },
        onDeposit = { vm.deposit(vaultId, amountInput) },
        onDone = onDone
    )
}

/**
 * Stateless content layer extracted so Paparazzi screenshot tests can render it
 * on the JVM without a Hilt DI graph.
 */
@Composable
fun DepositScreenContent(
    vaultId: String,
    amountInput: String,
    isLoading: Boolean,
    error: String?,
    onAmountChange: (String) -> Unit,
    onDeposit: () -> Unit,
    onDone: () -> Unit
) {
    val isAmountValid = amountInput.toDoubleOrNull()?.let { it > 0 } == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Deposit") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Vault: ${vaultId.take(12)}…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = amountInput,
                onValueChange = onAmountChange,
                label = { Text("Amount (XLM)") },
                placeholder = { Text("0.0000000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                ),
                isError = amountInput.isNotEmpty() && !isAmountValid
            )

            if (amountInput.isNotEmpty() && !isAmountValid) {
                Text(
                    "Enter a valid positive XLM amount.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = onDeposit,
                modifier = Modifier.fillMaxWidth(),
                enabled = isAmountValid && !isLoading
            ) {
                if (isLoading) {
                    AccessibleCircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Deposit")
                }
            }
        }
    }
}

// MARK: - Withdraw Screen

/**
 * The screen enforces:
 *  - Amount must be a positive number in XLM.
 *  - Amount must not exceed the vault's current balance (client-side guard;
 *    the server enforces this too).
 *  - Biometric auth is required before the API call is dispatched.
 *
 * Navigates back via [onDone] on success.
 */
@Composable
fun WithdrawScreen(
    vaultId: String,
    vaultBalanceStroops: Long,
    vm: com.ethosprotocol.ui.WithdrawViewModel = hiltViewModel(),
    onDone: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var amountInput by remember { mutableStateOf("") }
    var biometricError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.isSuccess) {
        if (state.isSuccess) onDone()
    }

    // Formatted balance shown to the user (same formula as Vault.formattedBalance)
    val availableBalance = remember(vaultBalanceStroops) {
        "%.7f XLM".format(vaultBalanceStroops / 10_000_000.0)
    }

    WithdrawScreenContent(
        vaultId = vaultId,
        availableBalance = availableBalance,
        amountInput = amountInput,
        isLoading = state.isLoading,
        error = state.error ?: biometricError,
        onAmountChange = { amountInput = it },
        onWithdraw = {
            // Biometric gate is required before dispatching a withdrawal.
            biometricError = null
            BiometricHelper(context as androidx.fragment.app.FragmentActivity).authenticate(
                title = "Confirm Withdrawal",
                subtitle = "Withdraw $amountInput XLM from vault ${vaultId.take(12)}…",
                onSuccess = { vm.withdraw(vaultId, amountInput, vaultBalanceStroops) },
                onError = { err -> biometricError = err }
            )
        },
        onDone = onDone
    )
}

/**
 * Stateless content layer extracted so Paparazzi screenshot tests can render it
 * on the JVM without a Hilt DI graph.
 */
@Composable
fun WithdrawScreenContent(
    vaultId: String,
    availableBalance: String,
    amountInput: String,
    isLoading: Boolean,
    error: String?,
    onAmountChange: (String) -> Unit,
    onWithdraw: () -> Unit,
    onDone: () -> Unit
) {
    val amountDouble = amountInput.toDoubleOrNull()
    val isAmountValid = amountDouble != null && amountDouble > 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Withdraw") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Vault: ${vaultId.take(12)}…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                "Available: $availableBalance",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = amountInput,
                onValueChange = onAmountChange,
                label = { Text("Amount (XLM)") },
                placeholder = { Text("0.0000000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                ),
                isError = amountInput.isNotEmpty() && !isAmountValid
            )

            if (amountInput.isNotEmpty() && !isAmountValid) {
                Text(
                    "Enter a valid positive XLM amount.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = onWithdraw,
                modifier = Modifier.fillMaxWidth(),
                enabled = isAmountValid && !isLoading
            ) {
                if (isLoading) {
                    AccessibleCircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Withdraw")
                }
            }

            Text(
                "Biometric confirmation is required before the withdrawal is submitted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// MARK: - 2FA Screens

@Composable
fun TwoFactorSetupScreen(
    vaultId: String,
    onComplete: () -> Unit,
    onDismiss: () -> Unit,
    /** #227: Only show methods the server reports as available for this account. */
    availableMethods: List<TwoFactorMethod> = TwoFactorMethod.values().toList()
) {
    val vm: TwoFactorViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    // #227: Default to first available method; fall back to totp if list is somehow empty.
    var selectedMethod by remember {
        mutableStateOf(
            if (availableMethods.contains(TwoFactorMethod.totp)) TwoFactorMethod.totp
            else availableMethods.firstOrNull() ?: TwoFactorMethod.totp
        )
    }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }

    // #224: Show backup codes screen immediately after initial TOTP setup verification.
    if (state.showBackupCodes) {
        BackupCodesScreen(
            codes = state.backupCodes,
            isLoading = state.isLoading,
            onDone = { vm.dismissBackupCodes(); onComplete() }
        )
        return
    }

    if (state.setupResponse != null) {
        TwoFactorVerifyScreen(
            vaultId = vaultId,
            method = selectedMethod,
            provisioningUri = state.setupResponse?.provisioningUri,
            onVerified = { onComplete() },
            onDismiss = onDismiss,
            vm = vm
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enable 2FA") },
        text = {
            Column {
                Text("Authentication Method", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                // #227: Only render available methods.
                availableMethods.forEach { method ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedMethod == method,
                            onClick = { selectedMethod = method }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (method) {
                                TwoFactorMethod.totp -> "Authenticator App (TOTP)"
                                TwoFactorMethod.sms -> "SMS Code"
                                TwoFactorMethod.email -> "Email Code"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                if (selectedMethod == TwoFactorMethod.sms) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = phone, onValueChange = { phone = it },
                        label = { Text("Phone number") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
                if (selectedMethod == TwoFactorMethod.email) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = email, onValueChange = { email = it },
                        label = { Text("Email address") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    vm.enable2FA(vaultId, selectedMethod, phone, email)
                },
                enabled = !state.isLoading && when (selectedMethod) {
                    TwoFactorMethod.totp -> true
                    TwoFactorMethod.sms -> phone.isNotBlank()
                    TwoFactorMethod.email -> email.isNotBlank()
                }
            ) {
                if (state.isLoading) AccessibleAccessibleCircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Continue")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun TwoFactorVerifyScreen(
    vaultId: String,
    method: TwoFactorMethod,
    provisioningUri: String?,
    onVerified: () -> Unit,
    onDismiss: () -> Unit,
    vm: TwoFactorViewModel
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var otp by remember { mutableStateOf("") }
    val isInitialSetup = provisioningUri != null
    // #226: "Remember this device" opt-in.
    var trustDevice by remember { mutableStateOf(false) }

    LaunchedEffect(state.verified) {
        if (state.verified) onVerified()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Decorative: the method-specific instructions below ("Scan the URI…", "A verification
        // code has been sent to your phone/email") already convey which method is active.
        Icon(
            when (method) {
                TwoFactorMethod.totp -> Icons.Default.Lock
                TwoFactorMethod.sms -> Icons.Default.Email
                TwoFactorMethod.email -> Icons.Default.Email
            },
            contentDescription = null, modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        // Title distinguishes initial setup from a subsequent re-verification so
        // the user is not confused about why they are not receiving a "sent" code.
        val titleText = when {
            method == TwoFactorMethod.totp && isInitialSetup -> "Verify Setup"
            method == TwoFactorMethod.totp -> "Re-verify Authenticator"
            else -> "Verify Setup"
        }
        Text(titleText, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        when {
            method == TwoFactorMethod.totp && isInitialSetup -> {
                // Initial TOTP setup: the server returned a provisioning URI — show it.
                Text(
                    "Scan this URI in your authenticator app:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    provisioningUri ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            method == TwoFactorMethod.totp -> {
                // Re-verification: no provisioning data — the user must open their
                // authenticator app and enter the current code. Never say "sent".
                Text(
                    "Enter the 6-digit code from your authenticator app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            method == TwoFactorMethod.sms -> Text(
                "A verification code has been sent to your phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> Text(
                "A verification code has been sent to your email.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = otp, onValueChange = { otp = it },
            label = { Text("6-digit code") }, singleLine = true,
            modifier = Modifier.width(200.dp),
            textStyle = MaterialTheme.typography.headlineSmall,
            enabled = !state.isOtpBlocked
        )

        // #119: Surface cooldown / failure count in the UI.
        Spacer(Modifier.height(8.dp))
        when {
            state.isOtpBlocked -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Timer, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Too many attempts — wait ${state.otpCooldownSeconds}s",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            state.otpFailureCount > 0 -> {
                Text(
                    "${state.otpFailureCount} failed attempt${if (state.otpFailureCount == 1) "" else "s"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            else -> Unit
        }

        // #226: Trust-device opt-in row.
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(
                checked = trustDevice,
                onCheckedChange = { trustDevice = it }
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Remember this device", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Skip 2FA on this device for 30 days",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { vm.verify2FA(vaultId, otp, trustDevice) },
            modifier = Modifier.fillMaxWidth(),
            enabled = otp.length == 6 && !state.isLoading && !state.isOtpBlocked
        ) {
            if (state.isLoading) AccessibleAccessibleCircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Verify")
        }
    }
}

// MARK: - Vault Detail Screen

/**
 * #120: Disable 2FA is guarded by BiometricHelper before calling
 * [TwoFactorViewModel.disable2FAAfterBiometric]. This mirrors the same
 * pattern used for check-in in [VaultListScreen] — the biometric prompt
 * requires a [FragmentActivity] and therefore lives in the screen layer,
 * while the network call is delegated to the ViewModel.
 */
@Composable
fun VaultDetailScreen(
    vaultId: String,
    onBack: () -> Unit,
    twoFactorVm: TwoFactorViewModel = hiltViewModel()
) {
    val state by twoFactorVm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSetup by remember { mutableStateOf(false) }
    var showVerify by remember { mutableStateOf(false) }
    var showSwitch by remember { mutableStateOf(false) }
    var biometricError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vaultId) { twoFactorVm.loadStatus(vaultId) }

    // #224: Show backup codes screen after initial TOTP setup.
    if (state.showBackupCodes) {
        BackupCodesScreen(
            codes = state.backupCodes,
            isLoading = state.isLoading,
            onDone = { twoFactorVm.dismissBackupCodes(); twoFactorVm.loadStatus(vaultId) }
        )
        return
    }

    if (showSetup) {
        TwoFactorSetupScreen(
            vaultId = vaultId,
            onComplete = { showSetup = false; twoFactorVm.loadStatus(vaultId) },
            onDismiss = { showSetup = false },
            availableMethods = state.status?.availableMethods ?: TwoFactorMethod.values().toList()
        )
        return
    }

    // #225: Switch method flow.
    if (showSwitch) {
        val currentMethod = state.status?.method
        if (currentMethod != null) {
            TwoFactorSwitchScreen(
                vaultId = vaultId,
                currentMethod = currentMethod,
                availableMethods = state.status?.availableMethods ?: TwoFactorMethod.values().toList(),
                onComplete = { showSwitch = false; twoFactorVm.loadStatus(vaultId) },
                onDismiss = { showSwitch = false },
                vm = twoFactorVm
            )
            return
        } else {
            showSwitch = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vault ${vaultId.take(12)}…") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Text("Two-Factor Authentication", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            when {
                state.isLoading -> AccessibleCircularProgressIndicator()
                state.error != null && state.status == null -> {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { twoFactorVm.loadStatus(vaultId) }) { Text("Retry") }
                }
                state.status != null -> {
                    val twoFaStatus = state.status!!
                    if (twoFaStatus.enabled) {
                        Text("Status: Enabled (${twoFaStatus.method?.name?.uppercase() ?: "—"})")
                        Text("Verified: ${if (twoFaStatus.verified) "Yes" else "No"}")
                        Spacer(Modifier.height(12.dp))
                        if (!twoFaStatus.verified) {
                            OutlinedButton(onClick = { showVerify = true },
                                modifier = Modifier.fillMaxWidth()) {
                                Text("Verify Now")
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        // #225: Switch method without disabling first.
                        if (twoFaStatus.availableMethods.size > 1) {
                            OutlinedButton(
                                onClick = { showSwitch = true },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Switch Method") }
                            Spacer(Modifier.height(8.dp))
                        }
                        biometricError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                        }
                        // #120: Biometric gate — prompt before the destructive disable call.
                        Button(
                            onClick = {
                                biometricError = null
                                BiometricHelper(context as androidx.fragment.app.FragmentActivity)
                                    .authenticate(
                                        title = "Confirm Disable 2FA",
                                        subtitle = "Biometric or PIN required to disable two-factor authentication",
                                        onSuccess = {
                                            twoFactorVm.disable2FAAfterBiometric(vaultId)
                                        },
                                        onError = { err -> biometricError = err }
                                    )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isLoading
                        ) { Text("Disable 2FA") }
                    } else {
                        Text("Status: Disabled")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { showSetup = true },
                            modifier = Modifier.fillMaxWidth()) {
                            Text("Enable 2FA")
                        }
                    }
                }
                else -> {
                    Text("Loading 2FA status…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// MARK: - Notification Debug Log (#235)

/**
 * Debug-only screen listing the local notification delivery log, for
 * QA/support to answer "did this notification actually get scheduled,
 * delivered, or suppressed?" without backend log correlation. Never shows
 * anything beyond vault ID / event type / timestamp — see
 * [NotificationDeliveryLog]'s doc comment for what
 * is deliberately excluded. Only reachable when [com.ethosprotocol.BuildConfig.DEBUG].
 */
@Composable
fun NotificationDebugScreen(
    onBack: () -> Unit,
    vm: NotificationDebugViewModel = hiltViewModel()
) {
    val deliveryLog = vm.deliveryLog
    var events by remember {
        mutableStateOf(deliveryLog.recentEvents())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notification Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = {
                        deliveryLog.clear()
                        events = deliveryLog.recentEvents()
                    }) { Icon(Icons.Default.Delete, "Clear") }
                }
            )
        }
    ) { padding ->
        if (events.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No notification events logged yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(events) { event ->
                    Column(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                event.kind.name.lowercase().replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(event.source.name.lowercase(), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(event.eventType, style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(event.vaultId.take(12) + "…", style = MaterialTheme.typography.bodySmall)
                            Text(
                                com.ethosprotocol.utils.DateTimeFormatter.formatTime(event.timestampMillis),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

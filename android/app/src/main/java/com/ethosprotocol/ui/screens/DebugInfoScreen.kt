package com.ethosprotocol.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ethosprotocol.BuildConfig
import com.ethosprotocol.api.CacheTelemetry
import com.ethosprotocol.api.NetworkMonitor
import com.ethosprotocol.security.LogRedactor
import com.ethosprotocol.services.PendingActionSyncWorker
import com.ethosprotocol.services.SensitiveClipboard
import com.ethosprotocol.utils.StartupPerformance
import kotlinx.coroutines.flow.map

/**
 * #427 — Debug info screen, shown by long-pressing the app version label in settings.
 *
 * Shows: app version/build, API endpoint, network status, offline-cache hit rate,
 * last sync diagnostics, and cold-start time. A copy-to-clipboard button captures
 * everything as plain text for attaching to bug reports.
 *
 * Only visible in DEBUG builds or when explicitly triggered — no UI entry point
 * ships in release outside of the long-press gesture on the version label.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugInfoScreen(
    networkMonitor: NetworkMonitor,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isOnline by networkMonitor.connectivityFlow
        .map { it }
        .collectAsState(initial = networkMonitor.isConnected)

    val cacheSnapshot = remember { CacheTelemetry.snapshot() }
    val syncDiagnostics = remember { PendingActionSyncWorker.lastSyncDiagnostics(context) }
    val coldStartMs = remember { StartupPerformance.getColdStartTimeMs() }

    val hitRate = remember(cacheSnapshot) {
        val total = cacheSnapshot.hits + cacheSnapshot.misses
        if (total == 0L) "n/a" else "%.0f%%".format(cacheSnapshot.hits * 100.0 / total)
    }
    val cacheKb = remember(cacheSnapshot) {
        "%.1f KB".format(cacheSnapshot.bytesWritten / 1024.0)
    }

    val debugText = remember(isOnline, cacheSnapshot, syncDiagnostics) {
        buildString {
            appendLine("=== Ethos-Protocol Debug Info ===")
            appendLine("Version:        ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Build type:     ${BuildConfig.BUILD_TYPE}")
            appendLine("API endpoint:   ${BuildConfig.API_BASE_URL}")
            appendLine("Network:        ${if (isOnline) "online" else "offline"}")
            appendLine()
            appendLine("--- Offline Cache ---")
            appendLine("Hits:           ${cacheSnapshot.hits}")
            appendLine("Misses:         ${cacheSnapshot.misses}")
            appendLine("Stale served:   ${cacheSnapshot.staleServed}")
            appendLine("Hit rate:       $hitRate")
            appendLine("Written:        $cacheKb")
            appendLine()
            appendLine("--- Last Sync ---")
            if (syncDiagnostics != null) {
                appendLine("At:             ${syncDiagnostics.lastSyncAt}")
                appendLine("Succeeded:      ${syncDiagnostics.succeeded}")
                appendLine("Failed:         ${syncDiagnostics.failed}")
                appendLine("Retrying:       ${syncDiagnostics.isRetrying}")
                appendLine("Queued:         ${syncDiagnostics.queued}")
            } else {
                appendLine("No sync run yet on this device.")
            }
            appendLine()
            appendLine("--- Performance ---")
            appendLine("Cold start:     ${coldStartMs}ms")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug Info") },
                navigationIcon = {
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("debugInfoClose")) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            SensitiveClipboard.copy(context, LogRedactor.redactString(debugText), label = "debug_info")
                        },
                        modifier = Modifier.testTag("debugInfoCopy")
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy to clipboard")
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
                .testTag("debugInfoContent")
        ) {
            DebugSection("Build") {
                DebugRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                DebugRow("Build type", BuildConfig.BUILD_TYPE)
                DebugRow("API endpoint", BuildConfig.API_BASE_URL)
            }
            DebugSection("Network") {
                DebugRow("Status", if (isOnline) "Online" else "Offline")
            }
            DebugSection("Offline Cache") {
                DebugRow("Hits", "${cacheSnapshot.hits}")
                DebugRow("Misses", "${cacheSnapshot.misses}")
                DebugRow("Stale served", "${cacheSnapshot.staleServed}")
                DebugRow("Hit rate", hitRate)
                DebugRow("Written", cacheKb)
            }
            DebugSection("Last Sync") {
                if (syncDiagnostics != null) {
                    DebugRow("At", syncDiagnostics.lastSyncAt)
                    DebugRow("Succeeded", "${syncDiagnostics.succeeded}")
                    DebugRow("Failed", "${syncDiagnostics.failed}")
                    DebugRow("Retrying", "${syncDiagnostics.isRetrying}")
                    DebugRow("Queued", "${syncDiagnostics.queued}")
                } else {
                    Text(
                        "No sync run yet on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            DebugSection("Performance") {
                DebugRow("Cold start", "${coldStartMs}ms")
            }
        }
    }
}

@Composable
private fun DebugSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
    )
    HorizontalDivider()
    Column(modifier = Modifier.padding(vertical = 4.dp), content = content)
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.weight(0.55f)
        )
    }
}

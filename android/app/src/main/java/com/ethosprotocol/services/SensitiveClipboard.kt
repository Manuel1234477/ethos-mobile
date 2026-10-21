package com.ethosprotocol.services

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * #270 — Centralized utility for copying sensitive values to the clipboard with
 * an automatic-clear timer.
 *
 * Any secret the user is allowed to copy — TOTP secrets, vault IDs, provisioning
 * URIs — must go through this utility so the auto-clear policy is applied
 * consistently rather than per-screen. The default delay is 60 seconds, matching
 * iOS's own password-auto-fill clipboard retention: long enough to paste into an
 * authenticator app, short enough to limit exposure if the user forgets to clear it.
 *
 * Usage (in a Composable):
 * ```kotlin
 * val context = LocalContext.current
 * SensitiveClipboard.copy(context, secret, label = "TOTP secret")
 * ```
 */
object SensitiveClipboard {

    /** Seconds the sensitive value remains on the clipboard before auto-clear. */
    const val CLEAR_DELAY_SECONDS: Long = 60L

    private val scope = CoroutineScope(Dispatchers.Main)
    private var clearJob: Job? = null

    /**
     * Copies [value] to the system clipboard under the given [label] and
     * schedules an automatic clear after [CLEAR_DELAY_SECONDS] seconds.
     * A subsequent call before the timer fires cancels the previous timer and
     * restarts it for the new value.
     */
    fun copy(context: Context, value: String, label: String = "Sensitive data") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, value)
        if (shouldMarkSensitive(Build.VERSION.SDK_INT)) {
            // Android 13+ hides sensitive clips from the system clipboard preview.
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)

        // Cancel any in-flight clear and restart the timer.
        clearJob?.cancel()
        clearJob = scope.launch {
            delay(CLEAR_DELAY_SECONDS * 1_000L)
            // Only clear if the clipboard still holds the value we wrote — if the
            // user has copied something else during the window, leave it alone.
            val current = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (shouldClear(current, value)) {
                // Clear by overwriting with an empty string — the label indicates
                // this was intentionally cleared so clipboard history tools can
                // optionally suppress it.
                val clearClip = ClipData.newPlainText("Cleared", "")
                clipboard.setPrimaryClip(clearClip)
            }
        }
    }

    /** True when the platform supports [ClipDescription.EXTRA_IS_SENSITIVE] (API 33+). */
    internal fun shouldMarkSensitive(sdkInt: Int): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU

    /**
     * Returns true only when the clipboard still holds [copiedValue], so the
     * auto-clear never deletes content the user copied after the sensitive value.
     */
    internal fun shouldClear(currentClip: String?, copiedValue: String): Boolean =
        currentClip == copiedValue
}

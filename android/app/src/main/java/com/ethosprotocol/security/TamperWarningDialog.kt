package com.ethosprotocol.security

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * A non-blocking dialog shown when [SignatureVerifier] detects that the APK's
 * signing certificate does not match the expected release certificate.
 *
 * This dialog must only be shown for [SignatureResult.Mismatch]. It is never
 * shown for [SignatureResult.Unreadable] (e.g. a `PackageManager` failure) or
 * [SignatureResult.NotConfigured], since those do not indicate tampering and
 * showing the warning would falsely accuse a legitimate user. Use
 * [shouldShowTamperWarning] to gate the dialog.
 *
 * The dialog is *informational only* — it does not prevent the user from
 * continuing to use the app, consistent with the project's approach for
 * integrity warnings (#118: jailbreak/root detection shows a banner, not a
 * hard block). Users who have sideloaded the app intentionally can dismiss
 * the warning and proceed at their own risk.
 *
 * @param onDismiss Called when the user acknowledges the warning (either by
 *                  tapping the confirm button or by tapping outside the dialog).
 */
@Composable
fun TamperWarningDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Unverified App Installation")
        },
        text = {
            Text(
                text = "This copy of Ethos Protocol does not appear to have been installed " +
                    "from the official app store. It may have been modified or repackaged by " +
                    "a third party.\n\n" +
                    "For your security, we recommend installing the app from the official " +
                    "Google Play Store. If you believe this is a mistake, please contact " +
                    "support@ethos-protocol.app."
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("I Understand")
            }
        }
    )
}

/**
 * Returns `true` only when the tamper warning dialog should be shown.
 *
 * The warning is shown exclusively for [SignatureResult.Mismatch]. An
 * [SignatureResult.Unreadable] result (e.g. the signature could not be read
 * from the `PackageManager`) or a [SignatureResult.NotConfigured] result must
 * never trigger the warning, as neither indicates that the APK was tampered
 * with.
 */
fun shouldShowTamperWarning(result: SignatureResult): Boolean =
    result is SignatureResult.Mismatch

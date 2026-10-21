package com.ethosprotocol

import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #647 — The https App Link for `/accept` must match that exact path only.
 *
 * The manifest previously used `android:pathPrefix="/accept"`, which also matched
 * `/acceptance` and similar paths. This test resolves the URLs against the installed
 * package manifest so it checks the declared intent filter without driving any UI.
 */
@RunWith(AndroidJUnit4::class)
class AcceptAppLinkMatchingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun resolvesToApp(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        return context.packageManager
            .queryIntentActivities(intent, 0)
            .any { it.activityInfo.packageName == context.packageName }
    }

    @Test
    fun acceptPathOpensAcceptFlow() {
        assertTrue(resolvesToApp("https://ethos-protocol.app/accept?vault_id=abc"))
    }

    @Test
    fun acceptanceDoesNotOpenAcceptFlow() {
        assertFalse(resolvesToApp("https://ethos-protocol.app/acceptance?vault_id=abc"))
    }

    @Test
    fun acceptPrefixedPathDoesNotOpenAcceptFlow() {
        assertFalse(resolvesToApp("https://ethos-protocol.app/accept-fake"))
    }
}

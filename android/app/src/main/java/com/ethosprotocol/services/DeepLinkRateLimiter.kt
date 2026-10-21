package com.ethosprotocol.services

import androidx.lifecycle.SavedStateHandle
import android.util.Log
import com.ethosprotocol.BuildConfig

/**
 * Client-side rate limiting for deep-link-triggered API calls.
 *
 * A malicious or malformed deep link opened repeatedly (e.g., via a crafted intent from another app)
 * could trigger repeated API calls (fetch vault, attempt acceptance) faster than a human would
 * naturally re-tap a link. This class throttles those calls per vault ID, regardless of server-side
 * protection, by enforcing a minimum cooldown between consecutive API invocations.
 *
 * This complements the OTP rate limiting in TwoFactorViewModel and follows the same SavedStateHandle
 * persistence pattern (#172) so the cooldown survives process death.
 *
 * Issue #263: Add Rate Limiting on Deep-Link-Triggered API Calls
 */
object DeepLinkRateLimiter {

    private const val TAG = "DeepLinkRateLimiter"
    
    // Minimum time between API calls for the same vault, in milliseconds.
    // 2 seconds is reasonable: fast enough for legitimate human re-taps, slow enough
    // to block most automated attack attempts or accidental double-taps.
    private const val MIN_CALL_INTERVAL_MS = 2_000L
    
    private const val KEY_PREFIX = "deep_link_call_"
    private const val KEY_SUFFIX_TIMESTAMP = "_last_call_ms"

    /**
     * Returns true if a new API call for [vaultId] is allowed, false if it's still in cooldown.
     * Updates the persisted call timestamp on success.
     */
    fun isCallAllowed(savedStateHandle: SavedStateHandle, vaultId: String): Boolean {
        val key = "$KEY_PREFIX$vaultId$KEY_SUFFIX_TIMESTAMP"
        val lastCallMs = readTimestamp(savedStateHandle, key)
        val now = System.currentTimeMillis()
        
        if (lastCallMs == null || now - lastCallMs >= MIN_CALL_INTERVAL_MS) {
            savedStateHandle[key] = now
            return true
        }
        
        val remainingMs = MIN_CALL_INTERVAL_MS - (now - lastCallMs)
        if (BuildConfig.DEBUG) Log.d(TAG, "Rate limit enforced for vault $vaultId: retry after ${remainingMs}ms")
        return false
    }

    /**
     * Returns the number of milliseconds remaining in the cooldown for [vaultId], or 0 if no cooldown is active.
     */
    fun remainingCooldownMs(savedStateHandle: SavedStateHandle, vaultId: String): Long {
        val key = "$KEY_PREFIX$vaultId$KEY_SUFFIX_TIMESTAMP"
        val lastCallMs = readTimestamp(savedStateHandle, key) ?: return 0
        val now = System.currentTimeMillis()
        val elapsed = now - lastCallMs
        
        return if (elapsed < MIN_CALL_INTERVAL_MS) {
            MIN_CALL_INTERVAL_MS - elapsed
        } else {
            0
        }
    }

    /**
     * Clears the persisted cooldown state for [vaultId] (mainly for testing).
     */
    fun clearCooldown(savedStateHandle: SavedStateHandle, vaultId: String) {
        val key = "$KEY_PREFIX$vaultId$KEY_SUFFIX_TIMESTAMP"
        savedStateHandle.remove<Any>(key)
    }

    /**
     * Reads a numeric timestamp without assuming exact type preservation:
     * SavedStateHandle doesn't guarantee Int/Long distinction across parcel round-trips.
     */
    private fun readTimestamp(savedStateHandle: SavedStateHandle, key: String): Long? {
        val value = savedStateHandle.get<Any>(key) as? Number ?: return null
        return value.toLong()
    }
}

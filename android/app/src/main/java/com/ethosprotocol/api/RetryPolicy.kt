package com.ethosprotocol.api

import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

// Retry configuration for idempotent network calls. ApiClient applies this only
// to GET requests — POST/DELETE must never be retried automatically, since a
// retried mutation (check-in, withdrawal, 2FA disable, ...) could double-submit.
data class RetryPolicy(
    val maxAttempts: Int,
    val baseDelayMillis: Long,
    val maxDelayMillis: Long = 30_000,
    val sleep: suspend (Long) -> Unit = { delay(it) },
    // Source of randomness for jitter (see [withRetry]). Injectable so tests can
    // supply a seeded/deterministic Random instead of the real one.
    val random: Random = Random.Default
) {
    companion object {
        val networkDefault = RetryPolicy(maxAttempts = 3, baseDelayMillis = 500)
    }
}

// Retries [operation] with exponential backoff with full jitter — the delay for each
// attempt is chosen uniformly from [0, baseDelayMillis * 2^attempt) — up to
// `policy.maxAttempts` total attempts, but only for errors [isRetryable] accepts.
// Any other error — or exhausting the attempt budget — is rethrown immediately.
//
// The jitter is what keeps many client instances that fail at the same instant (e.g.
// during a server outage) from retrying in synchronized waves against the recovering
// server; without it every instance would compute the exact same delay sequence.
suspend fun <T> withRetry(
    policy: RetryPolicy,
    isRetryable: (Throwable) -> Boolean,
    operation: suspend () -> T
): T {
    var attempt = 0
    while (true) {
        try {
            return operation()
        } catch (e: Throwable) {
            // Cancellation must always propagate: retrying it would break structured
            // concurrency (the cancelled coroutine would keep sleeping and re-running
            // the operation), even if the caller's predicate accepts every error.
            if (e is CancellationException) throw e
            attempt++
            if (attempt >= policy.maxAttempts || !isRetryable(e)) throw e
            val maxDelayMillis = (policy.baseDelayMillis * (1L shl (attempt - 1))).coerceAtMost(policy.maxDelayMillis)
            val delayMillis = if (maxDelayMillis > 0) policy.random.nextLong(0, maxDelayMillis) else 0L
            policy.sleep(delayMillis)
        }
    }
}

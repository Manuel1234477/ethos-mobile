package com.ethosprotocol

import com.ethosprotocol.api.RetryPolicy
import com.ethosprotocol.api.withRetry
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

// Simulated network error types for chaos testing packet-loss scenarios.
sealed class NetworkError(override val message: String) : Exception(message) {
    object TruncatedResponse : NetworkError("Incomplete response body")
    object ConnectionReset : NetworkError("Connection reset by peer")
    object SocketTimeout : NetworkError("Socket timeout")
    object EOF : NetworkError("Unexpected end of stream")
}

class RetryPolicyTest {

    // A Random that always returns the requested upper bound minus one, i.e. the
    // largest value the [0, until) contract allows — lets tests assert an exact
    // upper-bound delay without depending on true randomness.
    private fun maxJitterRandom() = object : Random() {
        override fun nextBits(bitCount: Int): Int = Random.Default.nextBits(bitCount)
        override fun nextLong(from: Long, until: Long): Long = until - 1
    }

    @Test
    fun `withRetry jitters the delay strictly below the deterministic exponential value`() = runTest {
        val delays = mutableListOf<Long>()
        val policy = RetryPolicy(
            maxAttempts = 4,
            baseDelayMillis = 500,
            sleep = { delays.add(it) },
            random = maxJitterRandom()
        )
        var calls = 0

        withRetry(policy, isRetryable = { true }) {
            calls++
            if (calls < 4) throw IOException("boom") else "ok"
        }

        // Deterministic (pre-jitter) sequence would be 500, 1000, 2000. maxJitterRandom
        // always returns until-1, i.e. one less than the deterministic value.
        assertEquals(listOf(499L, 999L, 1_999L), delays)
        assertEquals(4, calls)
    }

    @Test
    fun `withRetry with different random sources produces different delays for the same attempt`() = runTest {
        val delaysA = mutableListOf<Long>()
        val delaysB = mutableListOf<Long>()
        val policyA = RetryPolicy(maxAttempts = 2, baseDelayMillis = 500, sleep = { delaysA.add(it) }, random = Random(1))
        val policyB = RetryPolicy(maxAttempts = 2, baseDelayMillis = 500, sleep = { delaysB.add(it) }, random = Random(2))

        runCatching { withRetry(policyA, isRetryable = { true }) { throw IOException("boom") } }
        runCatching { withRetry(policyB, isRetryable = { true }) { throw IOException("boom") } }

        assertEquals(1, delaysA.size)
        assertEquals(1, delaysB.size)
        assertNotEquals(delaysA.single(), delaysB.single())
    }

    @Test
    fun `withRetry delay never exceeds the deterministic exponential value and never goes negative`() = runTest {
        val baseDelayMillis = 500L
        val maxAttempts = 6
        for (seed in 0 until 500) {
            val delays = mutableListOf<Long>()
            val policy = RetryPolicy(
                maxAttempts = maxAttempts,
                baseDelayMillis = baseDelayMillis,
                sleep = { delays.add(it) },
                random = Random(seed.toLong())
            )

            runCatching {
                withRetry(policy, isRetryable = { true }) { throw IOException("boom") }
            }

            assertEquals(maxAttempts - 1, delays.size)
            delays.forEachIndexed { index, actual ->
                val attempt = index + 1
                val deterministicDelay = baseDelayMillis * (1L shl (attempt - 1))
                assertTrue("seed=$seed attempt=$attempt actual=$actual", actual in 0 until deterministicDelay)
            }
        }
    }

    // MARK: - Chaos Testing: Simulated Packet Loss

    @Test
    fun `withRetry handles truncated response and recovers on retry`() = runTest {
        val delays = mutableListOf<Long>()
        val policy = RetryPolicy(
            maxAttempts = 4,
            baseDelayMillis = 100,
            sleep = { delays.add(it) },
            random = maxJitterRandom()
        )
        var attempts = 0

        val result = withRetry(policy, isRetryable = { error ->
            error is NetworkError.TruncatedResponse
        }) {
            attempts++
            if (attempts < 3) throw NetworkError.TruncatedResponse else "recovered"
        }

        assertEquals("recovered", result)
        assertEquals(3, attempts)
        assertEquals(2, delays.size)
    }

    @Test
    fun `withRetry handles connection reset and recovers on retry`() = runTest {
        val delays = mutableListOf<Long>()
        val policy = RetryPolicy(
            maxAttempts = 4,
            baseDelayMillis = 100,
            sleep = { delays.add(it) },
            random = maxJitterRandom()
        )
        var attempts = 0

        val result = withRetry(policy, isRetryable = { error ->
            error is NetworkError.ConnectionReset
        }) {
            attempts++
            if (attempts < 2) throw NetworkError.ConnectionReset else "connection_restored"
        }

        assertEquals("connection_restored", result)
        assertEquals(2, attempts)
    }

    @Test
    fun `withRetry does not retry non-transient network errors`() = runTest {
        val policy = RetryPolicy(
            maxAttempts = 3,
            baseDelayMillis = 100,
            sleep = {},
            random = Random.Default
        )
        var attempts = 0

        try {
            withRetry(policy, isRetryable = { error ->
                error is NetworkError.ConnectionReset
            }) {
                attempts++
                throw NetworkError.SocketTimeout("timeout")
            }
            fail("Should have thrown SocketTimeout")
        } catch (e: NetworkError.SocketTimeout) {
            assertEquals(1, attempts, "Should not retry non-retryable errors")
        }
    }

    @Test
    fun `withRetry does not double-submit mutating requests`() = runTest {
        val delays = mutableListOf<Long>()
        val policy = RetryPolicy(
            maxAttempts = 3,
            baseDelayMillis = 100,
            sleep = { delays.add(it) },
            random = maxJitterRandom()
        )
        var postCount = 0

        val result = withRetry(policy, isRetryable = { error ->
            // Only retry transient network errors, not idempotency violations
            error is NetworkError.ConnectionReset
        }) {
            postCount++
            if (postCount < 2) throw NetworkError.ConnectionReset else "check_in_recorded"
        }

        assertEquals("check_in_recorded", result)
        assertEquals(2, postCount, "Mutating request should only be submitted twice")
    }

    @Test
    fun `withRetry rethrows CancellationException instead of retrying when cancelled during backoff`() = runTest {
        val sleepStarted = CompletableDeferred<Unit>()
        val neverResume = CompletableDeferred<Unit>()
        val policy = RetryPolicy(
            maxAttempts = 5,
            baseDelayMillis = 100,
            sleep = {
                sleepStarted.complete(Unit)
                neverResume.await()
            },
            random = Random.Default
        )
        var attempts = 0

        val job = launch {
            withRetry(policy, isRetryable = { true }) {
                attempts++
                throw IOException("boom")
            }
        }

        sleepStarted.await()
        job.cancel()
        job.join()

        assertTrue("Job should be cancelled", job.isCancelled)
        assertEquals("Operation should run exactly once", 1, attempts)
    }

    @Test
    fun `withRetry propagates CancellationException to the caller`() = runTest {
        val policy = RetryPolicy(
            maxAttempts = 3,
            baseDelayMillis = 100,
            sleep = {},
            random = Random.Default
        )
        var attempts = 0

        try {
            withRetry(policy, isRetryable = { true }) {
                attempts++
                throw CancellationException("cancelled")
            }
            fail("Should have thrown CancellationException")
        } catch (e: CancellationException) {
            assertEquals(1, attempts, "Should not retry cancellation")
        }
    }

    @Test
    fun `withRetry respects max attempts on persistent socket timeouts`() = runTest {
        val policy = RetryPolicy(
            maxAttempts = 2,
            baseDelayMillis = 100,
            sleep = {},
            random = Random.Default
        )
        var attempts = 0

        try {
            withRetry(policy, isRetryable = { error ->
                error is NetworkError.SocketTimeout
            }) {
                attempts++
                throw NetworkError.SocketTimeout("persistent timeout")
            }
            fail("Should have thrown after exhausting attempts")
        } catch (e: NetworkError.SocketTimeout) {
            assertEquals(2, attempts, "Should respect maxAttempts limit")
        }
    }
}

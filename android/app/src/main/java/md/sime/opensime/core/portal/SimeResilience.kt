package md.sime.opensime.core.portal

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil
import kotlin.random.Random

/**
 * Capacity-one serialized rate gate used only when throttling manual force-refresh/sync actions (`gated = true`).
 *
 * Verified source behavior ported from `RateGate` in `web/server.js:1680-1698`:
 * - Normal navigation, initial login handshake, and first student load pass `gated = false` and bypass [RateGate].
 * - When `gated = true`, concurrent callers queue in FIFO order via [mutex] and space outbound requests by at least
 *   `minIntervalMs + jitterMs` (`3000ms + 200..500ms` by default).
 */
class RateGate(
    val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    val jitterMinMs: Long = DEFAULT_JITTER_MIN_MS,
    val jitterMaxMs: Long = DEFAULT_JITTER_MAX_MS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sleepMillis: suspend (Long) -> Unit = { delay(it) },
    private val randomDouble: () -> Double = { Random.Default.nextDouble() },
) {
    private val mutex = Mutex()
    private var lastCompletedEpochMillis: Long = 0L

    suspend fun wait() {
        mutex.withLock {
            val now = nowMillis()
            val jitterSpan = (jitterMaxMs - jitterMinMs).coerceAtLeast(0L)
            val jitter = jitterMinMs + (randomDouble().coerceIn(0.0, 1.0) * jitterSpan).toLong()
            val baseTime = if (lastCompletedEpochMillis == 0L) now else lastCompletedEpochMillis + minIntervalMs
            val due = baseTime + jitter
            val delayMs = due - now
            if (delayMs > 0L) {
                sleepMillis(delayMs)
            }
            lastCompletedEpochMillis = nowMillis()
        }
    }

    suspend fun reset() {
        mutex.withLock {
            lastCompletedEpochMillis = 0L
        }
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS: Long = 3_000L
        const val DEFAULT_JITTER_MIN_MS: Long = 200L
        const val DEFAULT_JITTER_MAX_MS: Long = 500L
    }
}

/**
 * Circuit breaker that suspends outbound portal traffic after HTTP 429 throttling, login lockout,
 * or HTTP 503 / HTML maintenance responses.
 *
 * Verified source behavior ported from `CircuitBreaker` in `web/server.js:1701-1723`:
 * - [trip] extends the open-circuit deadline by `max(1, waitSeconds)`.
 * - [ensureClosed] fails closed immediately with [SimeRateLimitError] while `remainingSeconds() > 0`.
 */
class CircuitBreaker(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var openUntilEpochMillis: Long = 0L

    fun trip(waitSeconds: Int = SimePortalError.DEFAULT_WAIT_SECONDS) {
        val clampedSeconds = waitSeconds.coerceIn(1, SimePortalError.MAX_WAIT_SECONDS)
        synchronized(lock) {
            val candidateUntil = nowMillis() + clampedSeconds * 1000L
            if (candidateUntil > openUntilEpochMillis) {
                openUntilEpochMillis = candidateUntil
            }
        }
    }

    fun remainingSeconds(): Int {
        synchronized(lock) {
            val diffMs = openUntilEpochMillis - nowMillis()
            if (diffMs <= 0L) return 0
            return ceil(diffMs / 1000.0).toInt().coerceAtLeast(1)
        }
    }

    val isOpen: Boolean
        get() = remainingSeconds() > 0

    fun ensureClosed() {
        val remaining = remainingSeconds()
        if (remaining > 0) {
            throw SimeRateLimitError(
                message = "Portal requests are suspended after throttling",
                waitSeconds = remaining,
            )
        }
    }

    fun reset() {
        synchronized(lock) {
            openUntilEpochMillis = 0L
        }
    }
}

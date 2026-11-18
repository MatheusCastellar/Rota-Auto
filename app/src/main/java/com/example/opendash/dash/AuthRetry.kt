package com.example.opendash.dash

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounded retries on the existing transport, using a monotonic clock. */
internal suspend fun awaitDashAuthentication(
    timeoutMs: Long,
    now: () -> Long,
    pause: suspend (Long) -> Unit,
    current: () -> Boolean,
    confirmed: () -> Boolean,
    retry: () -> Unit,
): Boolean {
    val started = now()
    var nextRetry = started + 2_000L
    while (true) {
        currentCoroutineContext().ensureActive()
        if (!current()) return false
        if (confirmed()) return true
        val time = now()
        if (time - started >= timeoutMs) return false
        if (time >= nextRetry) {
            retry()
            nextRetry = time + 2_000L
        }
        pause(100L)
    }
}

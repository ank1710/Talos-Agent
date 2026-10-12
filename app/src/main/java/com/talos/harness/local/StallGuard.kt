package com.talos.harness.local

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.timeout
import kotlin.time.Duration

/**
 * Collects [source], reporting whether it went silent long enough to count as a
 * stall.
 *
 * This is a **stall** timeout, not a total one. [Flow.timeout] restarts its
 * clock on every emission, so it measures the *gap since the last value*: a
 * generation that is merely slow keeps emitting and is never cut off, while an
 * operation whose next event never comes — a native callback that never fires —
 * is. A total timeout (`withTimeout` around the whole collection) would kill
 * legitimately slow turns, which is exactly the behaviour the local backend
 * must not have.
 *
 * Returns `true` when no value arrived for [window] and the source was
 * cancelled; `false` when [source] completed on its own.
 *
 * Cancellation is never swallowed. [Flow.timeout] signals its own expiry with
 * [TimeoutCancellationException] — which *is* a `CancellationException`, so it
 * has to be caught specifically, or a stalled stream and a cancelled turn could
 * not be told apart. [ensureActive] then re-throws when the surrounding job was
 * cancelled at the same moment, so a cancelled turn still unwinds as
 * cancellation rather than being reported as a stall.
 */
@OptIn(FlowPreview::class)
internal suspend fun <T> Flow<T>.collectWithStall(
    window: Duration,
    onEach: suspend (T) -> Unit,
): Boolean = try {
    timeout(window).collect(onEach)
    false
} catch (e: TimeoutCancellationException) {
    currentCoroutineContext().ensureActive()
    true
}

package com.example.musicfy.ritme.video

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs "Add video"'s length probes (RitmeVideoLookup), whose MediaMetadataRetriever.setDataSource
 * blocks and cannot be interrupted. A probe runs apart in [scope] and is given up waiting for after
 * [timeoutMs]; the caller's giving up (the timeout, or an edit cancelling it) cancels it, so one still
 * waiting for a slot never starts, while one already blocked finishes on its own.
 *
 * At most [maxRunning] run at once, so repeated pastes never pile up blocked IO threads. With 2, a
 * probe stuck on a host that never answers does not make the next paste wait for it (and fail):
 * that one gets the other slot. Two stuck ones make the next wait, bounded, until one returns.
 */
class RitmeProbeGate(
    private val timeoutMs: Long,
    maxRunning: Int = 2,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val slots = Semaphore(maxRunning)

    /** [block]'s value, or null when it failed to answer within [timeoutMs] (waiting for a slot included). */
    suspend fun <T : Any> run(block: suspend CoroutineScope.() -> T?): T? {
        val probe = scope.async { slots.withPermit { block() } }
        return try {
            withTimeoutOrNull(timeoutMs) { probe.await() }
        } finally {
            probe.cancel()
        }
    }
}

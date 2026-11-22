package com.example.musicfy.ritme.sync

import kotlin.math.abs

sealed interface DriftAction {
    data object Ignore : DriftAction
    data class SetSpeed(val speed: Float) : DriftAction
    data class Seek(val positionMs: Long) : DriftAction
}

/**
 * Android drift policy (spec 2.5), evaluated about once a second while playing:
 * - under 40 ms: nothing; a running correction continues until the drift is under 20 ms, then
 *   the speed goes back to 1.0 (hysteresis, so it does not toggle around 40 ms);
 * - 40..1000 ms: 0.97x when ahead of the room, 1.03x when behind (pitch stays 1.0);
 * - over 1000 ms: seek to the expected position, at most once per [MIN_SEEK_INTERVAL_MS] unless
 *   [force] (the room's playback just changed), so a slow network cannot loop seek -> rebuffer -> seek.
 */
object DriftCorrector {
    const val START_CORRECTING_MS = 40L
    const val SETTLED_MS = 20L
    const val SEEK_ABOVE_MS = 1_000L
    const val MIN_SEEK_INTERVAL_MS = 3_000L
    const val SLOWER = 0.97f
    const val FASTER = 1.03f
    const val NORMAL = 1f

    fun evaluate(
        expectedMs: Long,
        actualMs: Long,
        currentSpeed: Float,
        nowMs: Long,
        lastSeekAtMs: Long?,
        force: Boolean = false,
    ): DriftAction {
        val drift = actualMs - expectedMs // > 0: ahead of the room
        val size = abs(drift)
        if (size > SEEK_ABOVE_MS) {
            val canSeek = force || lastSeekAtMs == null || nowMs - lastSeekAtMs >= MIN_SEEK_INTERVAL_MS
            return if (canSeek) DriftAction.Seek(expectedMs) else DriftAction.Ignore
        }
        val correcting = currentSpeed != NORMAL
        if (correcting && size < SETTLED_MS) return DriftAction.SetSpeed(NORMAL)
        if (!correcting && size < START_CORRECTING_MS) return DriftAction.Ignore
        val wanted = if (drift > 0) SLOWER else FASTER
        return if (currentSpeed == wanted) DriftAction.Ignore else DriftAction.SetSpeed(wanted)
    }
}

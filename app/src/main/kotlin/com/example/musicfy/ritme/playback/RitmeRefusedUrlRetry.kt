package com.example.musicfy.ritme.playback

/**
 * Pacing for a Ritme URL entry whose server keeps refusing it (HTTP 4xx: a file deleted or never
 * there). In a room a failed stream is tried again until it plays (spec 4.1), but a dead link would
 * otherwise toast every 5 s for as long as it stays current: it toasts once per entry and is tried
 * again after 5, 15, then every 60 s. Pure; MusicService keeps one and resets it once an entry plays.
 */
class RitmeRefusedUrlRetry {
    data class Step(val toast: Boolean, val delayMs: Long)

    private var mediaId: String? = null
    private var failures = 0

    /** The next try for [mediaId], just refused; another entry starts over. */
    fun next(mediaId: String): Step {
        if (mediaId != this.mediaId) {
            this.mediaId = mediaId
            failures = 0
        }
        val step = Step(toast = failures == 0, delayMs = DELAYS_MS[failures.coerceAtMost(DELAYS_MS.lastIndex)])
        failures++
        return step
    }

    /** The entry played (or the room moved on): its next refusal is news again. */
    fun reset() {
        mediaId = null
        failures = 0
    }

    private companion object {
        val DELAYS_MS = longArrayOf(5_000L, 15_000L, 60_000L)
    }
}

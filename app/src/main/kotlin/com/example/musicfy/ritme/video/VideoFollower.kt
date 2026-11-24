package com.example.musicfy.ritme.video

import kotlin.math.abs

sealed interface FollowAction {
    data object Ignore : FollowAction
    data class SetSpeed(val speed: Float) : FollowAction
    data class Seek(val positionMs: Long) : FollowAction
}

/**
 * How the muted video player follows this device's audio player (spec 4.3, D3). It never looks at
 * the room: the planner and DriftCorrector keep the audio on the room, this keeps the picture on
 * the audio. Evaluated every [TICK_MS]:
 * - under 40 ms: the video runs at the audio's own speed (so it rides along with DriftCorrector's
 *   0.97/1.03); a running correction carries on until under 20 ms, and an overshoot ends it;
 * - 40..400 ms: audio speed × 0.9 when the video is ahead, × 1.1 when behind;
 * - over 400 ms: seek to the audio plus [SEEK_LEAD_MS] (what the video needs to resume), at most
 *   once per [MIN_SEEK_INTERVAL_MS];
 * - audio not advancing (paused, buffering): the exact frame, by seeking whenever over 40 ms.
 * Target: lips within 80 ms of the sound (ITU-R BT.1359 allows about +45/-125 ms).
 */
object VideoFollower {
    const val TICK_MS = 100L
    const val IN_SYNC_MS = 40L
    const val SETTLED_MS = 20L
    const val SEEK_ABOVE_MS = 400L
    const val MIN_SEEK_INTERVAL_MS = 2_000L
    const val SLOW_FACTOR = 0.9f
    const val FAST_FACTOR = 1.1f
    const val SEEK_LEAD_MS = 150L

    fun evaluate(
        audioMs: Long,
        videoMs: Long,
        audioSpeed: Float,
        videoSpeed: Float,
        audioAdvancing: Boolean,
        nowMs: Long,
        lastSeekAtMs: Long?,
    ): FollowAction {
        val drift = videoMs - audioMs // > 0: the picture is ahead of the sound
        val size = abs(drift)
        if (!audioAdvancing) {
            return if (size > IN_SYNC_MS) FollowAction.Seek(audioMs) else FollowAction.Ignore
        }
        if (size > SEEK_ABOVE_MS) {
            val canSeek = lastSeekAtMs == null || nowMs - lastSeekAtMs >= MIN_SEEK_INTERVAL_MS
            return if (canSeek) FollowAction.Seek(audioMs + (SEEK_LEAD_MS * audioSpeed).toLong()) else FollowAction.Ignore
        }
        val slower = audioSpeed * SLOW_FACTOR
        val faster = audioSpeed * FAST_FACTOR
        val correcting = same(videoSpeed, slower) || same(videoSpeed, faster)
        if (size < IN_SYNC_MS) {
            if (same(videoSpeed, audioSpeed)) return FollowAction.Ignore
            if (!correcting || size < SETTLED_MS) return FollowAction.SetSpeed(audioSpeed)
            val opposes = same(videoSpeed, slower) != (drift > 0)
            return if (opposes) FollowAction.SetSpeed(audioSpeed) else FollowAction.Ignore
        }
        val wanted = if (drift > 0) slower else faster
        return if (same(videoSpeed, wanted)) FollowAction.Ignore else FollowAction.SetSpeed(wanted)
    }

    private fun same(a: Float, b: Float): Boolean = abs(a - b) < 0.001f
}

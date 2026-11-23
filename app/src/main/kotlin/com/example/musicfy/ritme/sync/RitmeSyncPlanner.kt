package com.example.musicfy.ritme.sync

import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.protocol.QueueEntry
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.protocol.currentEntry
import com.example.musicfy.ritme.protocol.isLive
import com.example.musicfy.ritme.protocol.positionAt
import kotlin.math.abs

/** What the local player is doing, as the bridge reads it right before planning. */
data class LocalPlayerView(
    /** The room entry the bridge last loaded into the player, or null. */
    val loadedEntryId: String?,
    val positionMs: Long,
    /** Player.isPlaying: ready, wanting to play, and actually advancing. */
    val isAdvancing: Boolean,
    val playWhenReady: Boolean,
    /** The loaded entry reached STATE_ENDED and nothing has restarted it. */
    val ended: Boolean,
    val speed: Float,
)

sealed interface PlayerOp {
    /** Replace the player's item with [entry] at [startMs], paused. */
    data class Load(val entry: QueueEntry, val startMs: Long) : PlayerOp
    data object Play : PlayerOp
    data object Pause : PlayerOp
    data class SeekTo(val positionMs: Long) : PlayerOp
    data class SetSpeed(val speed: Float) : PlayerOp
    /** Nothing playable is current: pause and go back to 1.0x. */
    data object Halt : PlayerOp
}

data class SyncInput(
    val room: RoomState?,
    val serverNow: Long,
    val local: LocalPlayerView,
    /** [SyncPlan.appliedKey] of the previous plan. */
    val lastAppliedKey: String?,
    val localNowMs: Long,
    val lastSeekAtMs: Long?,
    /** Re-sync as if the room had just changed (after a reconnect, or a local "resume"). */
    val force: Boolean = false,
)

data class SyncPlan(val ops: List<PlayerOp>, val wakeAtServerMs: Long?, val appliedKey: String?)

/**
 * Decides how the local player follows the room (spec 2.5, 2.6). Pure: the bridge reads the
 * player into a [LocalPlayerView], runs [plan] on every snapshot, every second and at the start
 * anchor, and executes the ops.
 *
 * - A new current entry is loaded paused; it starts at the anchor (now + 1500 ms on the server),
 *   so every device starts together; a late joiner loads at the room position and plays.
 * - When the room's playback changes (play, pause, seek, anchor reached, [SyncInput.force]) the
 *   player is aligned: a drift over [RESYNC_ON_CHANGE_MS] is seeked away at once.
 * - Otherwise, while the player advances, [DriftCorrector] nudges speed or seeks.
 * - A player that is paused locally while the room plays (audio focus, unplugged headphones, or a
 *   member without control pausing "on this device only") is left alone until the room changes;
 *   an ended track waits for the room to move on.
 */
object RitmeSyncPlanner {
    const val PAUSED_ALIGN_MS = 40L
    const val RESYNC_ON_CHANGE_MS = 250L
    /**
     * The real stream may be shorter than the room's (second-rounded) duration, so a seek near
     * the end is clamped short of it; within this window of the end a paused player is not
     * realigned, or it would be re-seeked every tick.
     */
    const val END_TOLERANCE_MS = 1_500L

    /** Room position at [serverNow]; held at positionMs until a future anchor. */
    fun expectedPosition(room: RoomState, entry: QueueEntry, serverNow: Long): Long {
        val p = room.playback
        if (p.isPlaying && serverNow < p.anchorServerTimeMs && !entry.isLive) {
            return p.positionMs.coerceIn(0, entry.durationMs)
        }
        return room.positionAt(serverNow)
    }

    /** Identifies the room's playback "instruction"; a different key means the room changed. */
    fun appliedKey(room: RoomState, serverNow: Long): String? {
        val entry = room.currentEntry()?.takeIf(RitmeEntries::isPlayable) ?: return null
        val p = room.playback
        val waiting = p.isPlaying && serverNow < p.anchorServerTimeMs
        return "${entry.entryId}|${p.isPlaying}|${p.positionMs}|${p.anchorServerTimeMs}|$waiting"
    }

    fun plan(input: SyncInput): SyncPlan {
        val room = input.room
        val local = input.local
        val entry = room?.currentEntry()
        if (room == null || entry == null || !RitmeEntries.isPlayable(entry)) {
            val halt = local.playWhenReady || local.speed != DriftCorrector.NORMAL
            return SyncPlan(if (halt) listOf(PlayerOp.Halt) else emptyList(), null, null)
        }
        val p = room.playback
        val now = input.serverNow
        val key = appliedKey(room, now)
        val waiting = p.isPlaying && now < p.anchorServerTimeMs
        val changed = input.force || key != input.lastAppliedKey
        val live = entry.isLive
        val expected = expectedPosition(room, entry, now)
        val justLoaded = local.loadedEntryId != entry.entryId
        val drift = local.positionMs - expected
        val ops = mutableListOf<PlayerOp>()

        if (justLoaded) ops += PlayerOp.Load(entry, if (live) 0 else expected)

        when {
            waiting -> {
                if (!justLoaded && local.playWhenReady) ops += PlayerOp.Pause
                if (!justLoaded && !live && abs(local.positionMs - expected) > PAUSED_ALIGN_MS) ops += PlayerOp.SeekTo(expected)
                if (local.speed != DriftCorrector.NORMAL) ops += PlayerOp.SetSpeed(DriftCorrector.NORMAL)
                return SyncPlan(ops, p.anchorServerTimeMs, key)
            }
            !p.isPlaying -> {
                if (!justLoaded && local.playWhenReady) ops += PlayerOp.Pause
                val endWindow = entry.durationMs - END_TOLERANCE_MS
                // Only while the room itself is at the end: a paused seek away from it realigns an ended device.
                val heldAtEnd = expected >= endWindow && (local.ended || local.positionMs >= endWindow)
                if (!justLoaded && !live && !heldAtEnd && abs(drift) > PAUSED_ALIGN_MS) ops += PlayerOp.SeekTo(expected)
                if (local.speed != DriftCorrector.NORMAL) ops += PlayerOp.SetSpeed(DriftCorrector.NORMAL)
            }
            justLoaded -> {
                if (local.speed != DriftCorrector.NORMAL) ops += PlayerOp.SetSpeed(DriftCorrector.NORMAL)
                ops += PlayerOp.Play
            }
            changed -> {
                if (!live && (local.ended || abs(drift) > RESYNC_ON_CHANGE_MS)) ops += PlayerOp.SeekTo(expected)
                if (local.speed != DriftCorrector.NORMAL) ops += PlayerOp.SetSpeed(DriftCorrector.NORMAL)
                if (!local.playWhenReady || local.ended) ops += PlayerOp.Play
            }
            local.ended -> Unit // waiting for the room to move on (TrackEnded or the server's fallback)
            // At the end the expected position is capped at the (second-rounded) duration; the
            // real media may run a little longer, so seeking back would replay the tail.
            local.isAdvancing && !live && expected < entry.durationMs -> {
                when (val action = DriftCorrector.evaluate(expected, local.positionMs, local.speed, input.localNowMs, input.lastSeekAtMs)) {
                    is DriftAction.Seek -> ops += PlayerOp.SeekTo(action.positionMs)
                    is DriftAction.SetSpeed -> ops += PlayerOp.SetSpeed(action.speed)
                    DriftAction.Ignore -> Unit
                }
            }
            else -> Unit // held locally: left alone until the room changes
        }
        return SyncPlan(ops, null, key)
    }
}

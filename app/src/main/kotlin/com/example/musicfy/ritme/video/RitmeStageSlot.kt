package com.example.musicfy.ritme.video

import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.protocol.currentEntry

/**
 * Where the room's video stage is mounted. Only one stage exists at a time: the surface is
 * last-attach-wins, the screen-on flag is the root view's, and every YouTube stage owns a follower.
 */
enum class RitmeStageSlot {
    NONE,
    COVER,
    FULLSCREEN;

    companion object {
        /**
         * Full screen holds the slot while open, also while the player catches up with the next
         * video and there is no target yet (it shows black then, the cover never steps in); else
         * the cover holds it while [coverShown] and there is a target.
         */
        fun of(hasTarget: Boolean, fullscreenOpen: Boolean, coverShown: Boolean): RitmeStageSlot = when {
            fullscreenOpen -> FULLSCREEN
            !hasTarget -> NONE
            coverShown -> COVER
            else -> NONE
        }

        /**
         * Whether open full screen stays open: while this device is in the room and the room's
         * current entry is a playable video. Not tied to the stage target, which is null for a
         * moment at every video-to-video advance (the player still holds the previous item). A
         * reconnect keeps the room and the IN_ROOM phase (only Ended drops them), so it stays too.
         */
        fun fullscreenStays(room: RoomState?, inRoom: Boolean): Boolean {
            if (!inRoom) return false
            val entry = room?.currentEntry() ?: return false
            return RitmeEntries.isVideo(entry) && RitmeEntries.isPlayable(entry)
        }
    }
}

/**
 * Counts the stages keeping one view's screen on, so a handoff that starts the new stage before
 * the old one is disposed never lets the screen sleep. Main thread only.
 */
class ScreenOnHolds {
    private val counts = java.util.WeakHashMap<Any, Int>()

    fun acquire(key: Any) {
        counts[key] = (counts[key] ?: 0) + 1
    }

    /** True when this was the last hold on [key] (or there was none): the screen may sleep. */
    fun release(key: Any): Boolean {
        val left = (counts[key] ?: 0) - 1
        return if (left <= 0) {
            counts.remove(key)
            true
        } else {
            counts[key] = left
            false
        }
    }

    fun isHeld(key: Any): Boolean = (counts[key] ?: 0) > 0
}

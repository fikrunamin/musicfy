package com.example.musicfy.ritme.video

/**
 * Where the room's video stage is mounted. Only one stage exists at a time: the surface is
 * last-attach-wins, the screen-on flag is the root view's, and every YouTube stage owns a follower.
 */
enum class RitmeStageSlot {
    NONE,
    COVER,
    FULLSCREEN;

    companion object {
        /** Full screen takes the stage while open; else the cover holds it while [coverShown]. */
        fun of(hasTarget: Boolean, fullscreenOpen: Boolean, coverShown: Boolean): RitmeStageSlot = when {
            !hasTarget -> NONE
            fullscreenOpen -> FULLSCREEN
            coverShown -> COVER
            else -> NONE
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

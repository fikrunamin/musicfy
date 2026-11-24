package com.example.musicfy.ritme.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.musicfy.ritme.LocalPlayOrder

/** What RitmePlaybackBridge needs from MusicService, and nothing more. Main thread only. */
interface RitmePlayerHost {
    /** The service's current player. */
    val player: ExoPlayer
    val isCasting: Boolean

    fun playOrderQueue(): LocalPlayOrder

    /** Persists the local queue to disk now, while the gate is still closed (survives a death inside a room). */
    fun saveLocalQueue()

    /**
     * Saves the local queue and hands the player to the room: pauses it and switches off
     * everything that changes the queue or the speed locally (runway/infinite queue, automix,
     * crossfade/Crossmix, shuffle, repeat, skip silence, Audio FX speed).
     */
    fun enterRitmeMode(): LocalQueueSnapshot

    /** Replaces the player's item with [item] at [startPositionMs] (null: its default position, a live stream's live edge), prepared and paused. */
    fun loadRitmeItem(item: MediaItem, startPositionMs: Long?)

    /** Puts [snapshot] back, paused, and restores the local settings. */
    fun exitRitmeMode(snapshot: LocalQueueSnapshot)
}

data class LocalQueueSnapshot(
    val title: String?,
    val items: List<MediaItem>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
    val skipSilence: Boolean,
) {
    companion object {
        val EMPTY = LocalQueueSnapshot(null, emptyList(), 0, 0, false, Player.REPEAT_MODE_OFF, false)
    }
}

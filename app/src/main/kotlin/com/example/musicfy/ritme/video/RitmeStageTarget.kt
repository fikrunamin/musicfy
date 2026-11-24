package com.example.musicfy.ritme.video

import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.protocol.currentEntry

/** What the video stage shows (spec 4.2): only for the room's current video entry, once the player holds it. */
sealed interface RitmeStageTarget {
    val entryId: String

    /** Picture from a muted follower player; the sound is MusicService's (spec D1). */
    data class YouTube(override val entryId: String, val videoId: String, val thumbnailUrl: String?) : RitmeStageTarget

    /** MusicService's own player renders it (spec D2). */
    data class Url(override val entryId: String) : RitmeStageTarget

    companion object {
        /**
         * The stage for [room]'s current entry, or null: songs, unplayable entries, and while the
         * player ([loadedMediaId]) still holds the previous item, so a new video never shows over
         * the old item's sound.
         */
        fun of(room: RoomState?, loadedMediaId: String): RitmeStageTarget? {
            val entry = room?.currentEntry()?.takeIf(RitmeEntries::isPlayable) ?: return null
            return when (entry.kind) {
                MediaKind.YOUTUBE_VIDEO ->
                    if (loadedMediaId == entry.videoId) YouTube(entry.entryId, entry.videoId, entry.thumbnailUrl) else null
                MediaKind.URL ->
                    if (loadedMediaId == RitmeEntries.urlMediaId(entry.entryId)) Url(entry.entryId) else null
                MediaKind.YOUTUBE_MUSIC, MediaKind.UNKNOWN -> null
            }
        }
    }
}

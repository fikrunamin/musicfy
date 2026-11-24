package com.example.musicfy.ritme.playback

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.example.musicfy.extensions.toMediaItem
import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.QueueEntry
import com.example.musicfy.ritme.protocol.isLive

/** How a URL entry plays, worked out without Android types (unit-tested). */
data class RitmeUrlSpec(val mediaId: String, val url: String, val mimeType: String?, val live: Boolean)

/** The player item for a room entry (spec 4.4): YouTube kinds as songs, URL entries as their own stream. */
object RitmeItems {
    /** Every device keeps the same distance behind the live edge, so they stay a few seconds apart at most. */
    const val LIVE_TARGET_OFFSET_MS = 10_000L
    const val LIVE_MIN_SPEED = 0.97f
    const val LIVE_MAX_SPEED = 1.03f

    /** Used when the system WebView's user agent cannot be read (no WebView installed). */
    const val FALLBACK_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /**
     * Whether the library's facts about a song (its isVideo flag) may be updated from the player item
     * [mediaId]. Never in a room: a room item's video type comes from the room entry's kind
     * (YOUTUBE_MUSIC / YOUTUBE_VIDEO), which says nothing about the song saved in the library; and a
     * URL item is not a library song at all.
     */
    fun syncsLibraryFlags(mediaId: String?, roomActive: Boolean): Boolean =
        !roomActive && !RitmeEntries.isUrlMediaId(mediaId)

    fun urlSpec(entry: QueueEntry): RitmeUrlSpec? {
        if (entry.kind != MediaKind.URL || !RitmeEntries.isPlayable(entry)) return null
        val url = entry.url ?: return null
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        val mimeType = when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            // a plain file: the extractors find out what it is
            else -> null
        }
        return RitmeUrlSpec(RitmeEntries.urlMediaId(entry.entryId), url, mimeType, entry.isLive)
    }

    fun mediaItemFor(entry: QueueEntry): MediaItem {
        val metadata = RitmeEntries.toMediaMetadata(entry)
        val spec = urlSpec(entry) ?: return metadata.toMediaItem()
        val builder = MediaItem.Builder()
            .setMediaId(spec.mediaId)
            .setUri(spec.url.toUri())
            .setMimeType(spec.mimeType)
            // the UI reads its title and "artist" (the site) from the tag, like any song
            .setTag(metadata)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(metadata.title)
                    .setDisplayTitle(metadata.title)
                    .setArtist(entry.artist)
                    .setSubtitle(entry.artist)
                    .setMediaType(androidx.media3.common.MediaMetadata.MEDIA_TYPE_VIDEO)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            )
        if (spec.live) {
            builder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
                    .setMinPlaybackSpeed(LIVE_MIN_SPEED)
                    .setMaxPlaybackSpeed(LIVE_MAX_SPEED)
                    .build(),
            )
        }
        return builder.build()
    }
}

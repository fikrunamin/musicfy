package com.example.musicfy.ritme.entries

import com.example.musicfy.models.MediaMetadata
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.MediaUrlRules
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.StreamType
import com.example.musicfy.ritme.protocol.QueueEntry
import com.music.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_UGC
import java.net.URI
import java.text.Normalizer

/** What Create sends: [first] in the Create itself, [overflow] as Add batches after the Welcome. */
data class InitialQueue(
    val first: List<NewEntry>,
    val startIndex: Int,
    val startPositionMs: Long,
    val overflow: List<List<NewEntry>>,
    val skipped: Int,
) {
    val total: Int get() = first.size + overflow.sumOf { it.size }

    companion object {
        val EMPTY = InitialQueue(emptyList(), 0, 0, emptyList(), 0)
    }
}

data class AddBatches(val batches: List<List<NewEntry>>, val count: Int, val skipped: Int)

/**
 * Converts between the app's songs and room entries. Applies the server's NewEntry rules
 * (ritme-server RoomLogic.isValid) up front, so an invalid song is skipped here instead of
 * failing a whole Add with INVALID or shifting Create's startIndex.
 */
object RitmeEntries {
    const val MAX_BATCH = 200
    const val MAX_QUEUE = 500
    const val MAX_TEXT = 300
    const val MAX_DURATION_MS = 86_400_000L

    /** Same rule as the server's /api/video (2a Decision 10): a length of 12 h or more is a live stream's age. */
    const val LIVE_FROM_SECONDS = 12 * 3600
    private const val MAX_THUMBNAIL_LENGTH = 512

    /**
     * The player's media id for a URL entry: never a YouTube id, so MusicService sends it down its
     * own route (no resolver, no song cache) and skips lyrics, history and scrobbling for it.
     */
    const val URL_MEDIA_ID_PREFIX = "RITME_URL:"

    fun urlMediaId(entryId: String): String = URL_MEDIA_ID_PREFIX + entryId

    fun isUrlMediaId(mediaId: String?): Boolean = mediaId?.startsWith(URL_MEDIA_ID_PREFIX) == true

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val THUMBNAIL_HOST_SUFFIXES = listOf(".ytimg.com", ".googleusercontent.com", ".ggpht.com")

    // Copied from ritme-server RoomLogic.cleanText/hasVisible: a title the server sees as blank fails the whole Add.
    private val INVISIBLE = Regex("""[\p{Cc}\p{Cs}\u200B\u202A-\u202E\u2066-\u2069\uFEFF\u2028\u2029]""")
    private val NOT_VISIBLE_TYPES = setOf(
        Character.FORMAT.toInt(),
        Character.NON_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        Character.SPACE_SEPARATOR.toInt(),
        Character.CONTROL.toInt(),
    )
    private val BLANK_LETTERS = setOf(0x115F, 0x1160, 0x3164, 0xFFA0, 0x2800)

    /** Same verdict as the server's `hasVisible(cleanText(text))`. */
    fun hasVisibleText(text: String): Boolean =
        Normalizer.normalize(text, Normalizer.Form.NFC).replace(INVISIBLE, "").trim().codePoints().anyMatch {
            !Character.isWhitespace(it) && Character.getType(it) !in NOT_VISIBLE_TYPES && it !in BLANK_LETTERS
        }

    /** At most [MAX_TEXT] chars (the server counts UTF-16 units), never ending on half a surrogate pair. */
    private fun truncate(text: String): String {
        if (text.length <= MAX_TEXT) return text
        val cut = if (Character.isHighSurrogate(text[MAX_TEXT - 1])) MAX_TEXT - 1 else MAX_TEXT
        return text.substring(0, cut)
    }

    /** The thumbnail if the server accepts it for a YouTube entry, else null. */
    fun safeThumbnail(url: String?): String? {
        if (url == null || url.length > MAX_THUMBNAIL_LENGTH || !url.startsWith("https://")) return null
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return null
        val allowed = host == "i.ytimg.com" || THUMBNAIL_HOST_SUFFIXES.any { host.endsWith(it) }
        return url.takeIf { allowed }
    }

    /**
     * A YOUTUBE_MUSIC entry for [song], or null for local files and anything the server would refuse.
     * An unknown length (duration <= 0) becomes durationMs = 0, which the protocol treats as live
     * (accepted, see Decision 12).
     */
    fun toNewEntry(song: MediaMetadata?): NewEntry? {
        if (song == null || song.localUri != null || !VIDEO_ID.matches(song.id)) return null
        val title = truncate(song.title.trim())
        if (!hasVisibleText(title)) return null
        val artist = truncate(song.artists.joinToString(", ") { it.name }.trim())
        return NewEntry(
            videoId = song.id,
            title = title,
            artist = artist,
            thumbnailUrl = safeThumbnail(song.thumbnailUrl),
            durationMs = (song.duration.toLong() * 1000).coerceIn(0, MAX_DURATION_MS),
            kind = MediaKind.YOUTUBE_MUSIC,
        )
    }

    /**
     * A YOUTUBE_VIDEO entry from a video's info ("Add video", spec 4.6), or YOUTUBE_MUSIC for a
     * music.youtube.com link; null if the server would refuse it. A length that is missing, 0 or at
     * least [LIVE_FROM_SECONDS] means live (durationMs = 0).
     */
    fun toVideoEntry(
        videoId: String,
        title: String,
        channel: String,
        thumbnailUrl: String?,
        durationSeconds: Int?,
        music: Boolean = false,
    ): NewEntry? {
        if (!VIDEO_ID.matches(videoId)) return null
        val cleanTitle = truncate(title.trim())
        if (!hasVisibleText(cleanTitle)) return null
        val seconds = durationSeconds?.takeIf { it in 1 until LIVE_FROM_SECONDS }
        return NewEntry(
            videoId = videoId,
            title = cleanTitle,
            artist = truncate(channel.trim()),
            thumbnailUrl = safeThumbnail(thumbnailUrl),
            durationMs = seconds?.times(1000L) ?: 0,
            kind = if (music) MediaKind.YOUTUBE_MUSIC else MediaKind.YOUTUBE_VIDEO,
        )
    }

    /**
     * A URL entry (spec 4.6): artist = the site's domain, no picture (spec 4.7), the title falls back
     * to the domain; null when MediaUrlRules refuses the url.
     *
     * Limitation (until #3's probe exists): a duration of 0 counts as live, so a play after a pause
     * seeks the entry to the live edge. A direct .m3u8/.mpd link that is really VOD is added with
     * durationMs = 0 and therefore restarts from 0 on every play after a pause.
     */
    fun toUrlEntry(
        url: String,
        title: String,
        domain: String,
        durationMs: Long = 0,
        streamType: StreamType? = null,
        referrer: String? = null,
    ): NewEntry? {
        if (!MediaUrlRules.isAcceptableMediaUrl(url)) return null
        val cleanTitle = truncate(title.trim()).takeIf(::hasVisibleText)
            ?: truncate(domain.trim()).takeIf(::hasVisibleText)
            ?: return null
        return NewEntry(
            videoId = "",
            title = cleanTitle,
            artist = truncate(domain.trim()),
            thumbnailUrl = null,
            durationMs = durationMs.coerceIn(0, MAX_DURATION_MS),
            kind = MediaKind.URL,
            url = url,
            streamType = streamType?.takeUnless { it == StreamType.UNKNOWN },
            referrer = referrer?.takeIf(MediaUrlRules::isAcceptableReferrer),
        )
    }

    /** A room's starting queue of ready-made entries (a shared video, spec 4.6): at most [MAX_QUEUE], from the start. */
    fun initialQueueOf(entries: List<NewEntry>): InitialQueue {
        val kept = entries.take(MAX_QUEUE)
        return InitialQueue(kept.take(MAX_BATCH), 0, 0, kept.drop(MAX_BATCH).chunked(MAX_BATCH), 0)
    }

    /**
     * The room's starting queue: the current song and everything after it in play order (history
     * stays behind), shareable songs only, at most [MAX_QUEUE]. The position carries over only when
     * the current song itself is shared.
     */
    fun buildInitialQueue(songs: List<MediaMetadata?>, currentIndex: Int, positionMs: Long): InitialQueue {
        if (songs.isEmpty()) return InitialQueue.EMPTY
        val considered = songs.drop(currentIndex.coerceIn(0, songs.size - 1))
        val mapped = considered.map(::toNewEntry)
        val entries = mapped.filterNotNull().take(MAX_QUEUE)
        return InitialQueue(
            first = entries.take(MAX_BATCH),
            startIndex = 0,
            startPositionMs = if (mapped.first() != null) positionMs.coerceAtLeast(0) else 0,
            overflow = entries.drop(MAX_BATCH).chunked(MAX_BATCH),
            skipped = mapped.count { it == null },
        )
    }

    /**
     * [songs] as Add batches of at most [MAX_BATCH]: shareable songs only, at most [limit] (and never
     * more than [MAX_QUEUE]); [AddBatches.skipped] counts the songs that cannot be shared.
     */
    fun toAddBatches(songs: List<MediaMetadata?>, limit: Int = MAX_QUEUE): AddBatches {
        val mapped = songs.map(::toNewEntry)
        val entries = mapped.filterNotNull().take(limit.coerceIn(0, MAX_QUEUE))
        return AddBatches(entries.chunked(MAX_BATCH), entries.size, mapped.count { it == null })
    }

    /** True if at least one of [songs] can go into a room (not a local file, nothing the server refuses). */
    fun hasShareable(songs: List<MediaMetadata?>): Boolean = songs.any { toNewEntry(it) != null }

    /**
     * YouTube kinds play by videoId (YOUTUBE_VIDEO: audio here, picture from the video stage);
     * URL entries play their url when it passes the server's MediaUrlRules (spec 4.5). UNKNOWN never.
     */
    fun isPlayable(entry: QueueEntry): Boolean = when (entry.kind) {
        MediaKind.YOUTUBE_MUSIC, MediaKind.YOUTUBE_VIDEO -> VIDEO_ID.matches(entry.videoId)
        MediaKind.URL -> entry.videoId.isEmpty() && entry.url?.let(MediaUrlRules::isAcceptableMediaUrl) == true
        MediaKind.UNKNOWN -> false
    }

    /** Entries watched rather than listened to: they get the video stage and 16:9 rows. */
    fun isVideo(entry: QueueEntry): Boolean = entry.kind == MediaKind.YOUTUBE_VIDEO || entry.kind == MediaKind.URL

    fun toMediaMetadata(entry: QueueEntry): MediaMetadata = MediaMetadata(
        id = if (entry.kind == MediaKind.URL) urlMediaId(entry.entryId) else entry.videoId,
        title = entry.title,
        artists = listOf(MediaMetadata.Artist(id = null, name = entry.artist)),
        duration = (entry.durationMs / 1000).toInt(),
        // A URL entry's picture could come from any host: it is never loaded (spec 4.7, R8).
        thumbnailUrl = if (entry.kind == MediaKind.URL) null else entry.thumbnailUrl,
        // Marks the item as a video (isVideoSong), so the cover never goes looking for "its official
        // video" by title, which found some other video for non-music entries (spec 1.2).
        musicVideoType = if (entry.kind == MediaKind.YOUTUBE_VIDEO) MUSIC_VIDEO_TYPE_UGC else null,
    )
}

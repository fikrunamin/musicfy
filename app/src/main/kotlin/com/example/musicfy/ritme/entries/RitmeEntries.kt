package com.example.musicfy.ritme.entries

import com.example.musicfy.models.MediaMetadata
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.QueueEntry
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
    private const val MAX_THUMBNAIL_LENGTH = 512

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
        val title = song.title.trim().take(MAX_TEXT)
        if (!hasVisibleText(title)) return null
        val artist = song.artists.joinToString(", ") { it.name }.trim().take(MAX_TEXT)
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

    fun toAddBatches(songs: List<MediaMetadata?>): AddBatches {
        val mapped = songs.map(::toNewEntry)
        val entries = mapped.filterNotNull().take(MAX_QUEUE)
        return AddBatches(entries.chunked(MAX_BATCH), entries.size, mapped.count { it == null })
    }

    /** Android plays YouTube kinds by videoId (YOUTUBE_VIDEO audio-only); URL and UNKNOWN are not supported yet. */
    fun isPlayable(entry: QueueEntry): Boolean =
        (entry.kind == MediaKind.YOUTUBE_MUSIC || entry.kind == MediaKind.YOUTUBE_VIDEO) && VIDEO_ID.matches(entry.videoId)

    fun toMediaMetadata(entry: QueueEntry): MediaMetadata = MediaMetadata(
        id = entry.videoId,
        title = entry.title,
        artists = listOf(MediaMetadata.Artist(id = null, name = entry.artist)),
        duration = (entry.durationMs / 1000).toInt(),
        thumbnailUrl = entry.thumbnailUrl,
    )
}

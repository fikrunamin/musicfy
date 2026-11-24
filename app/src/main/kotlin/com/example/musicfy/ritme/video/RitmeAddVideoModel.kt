package com.example.musicfy.ritme.video

import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.NewEntry

/** A YouTube video "Add video" can preview: from a link's info or a search result. */
data class VideoChoice(
    val videoId: String,
    val title: String,
    val channel: String,
    /** Null for live streams and unknown lengths. */
    val durationSeconds: Int?,
    val thumbnailUrl: String?,
    val live: Boolean,
)

/** What "Add video" asks the network (RitmeVideoLookup on Android, a fake in tests). */
interface VideoLookup {
    /** The video's title, channel, length and picture; null if not found. */
    suspend fun info(videoId: String): VideoChoice?

    /** U3: videos for [query] (YouTube's general search, else YouTube Music's videos); empty on failure. */
    suspend fun search(query: String): List<VideoChoice>

    /** A progressive file's length; null when it could not be read (in time). */
    suspend fun probeDurationMs(url: String): Long?
}

sealed interface AddVideoState {
    data object Empty : AddVideoState
    data object Loading : AddVideoState

    /** [editableTitle]: URL entries only (default: the file name), sent as the entry's title. */
    data class Preview(val entry: NewEntry, val editableTitle: Boolean) : AddVideoState
    data class Results(val items: List<VideoChoice>) : AddVideoState
    data class Problem(val kind: AddVideoProblem) : AddVideoState
}

enum class AddVideoProblem {
    PAGE, UNSAFE, UNRECOGNIZED, NOT_FOUND, NO_RESULTS,

    /** A progressive file whose length could not be read (in time): worth trying again. */
    LENGTH_UNREADABLE,
}

/**
 * "Add video" (spec 4.6): pasted text becomes a preview (a link), results (words) or a problem.
 * Nothing is ever added from here: the sheet's buttons do that, from a preview only. A video that
 * cannot be found is a problem, never an entry with a made-up title (spec 8).
 */
class RitmeAddVideoModel(private val lookup: VideoLookup) {

    suspend fun resolve(text: String): AddVideoState {
        if (text.isBlank()) return AddVideoState.Empty
        return when (val link = RitmeVideoLinks.classify(text)) {
            null -> lookup.search(text.trim())
                .takeIf { it.isNotEmpty() }
                ?.let { AddVideoState.Results(it) }
                ?: AddVideoState.Problem(AddVideoProblem.NO_RESULTS)
            is VideoLink.YouTube -> lookup.info(link.videoId)
                ?.let { preview(it.copy(videoId = link.videoId), music = link.music) }
                ?: AddVideoState.Problem(AddVideoProblem.NOT_FOUND)
            is VideoLink.Direct -> {
                // HLS/DASH: no file length to read; 0 = live (Decision 13). A file whose length
                // could not be read is never added as live (joiners at 0:00, seeks ignored, "LIVE"
                // on its row): it is a problem the adder can try again.
                val durationMs: Long = if (link.streaming) {
                    0L
                } else {
                    lookup.probeDurationMs(link.url) ?: return AddVideoState.Problem(AddVideoProblem.LENGTH_UNREADABLE)
                }
                RitmeEntries.toUrlEntry(link.url, link.fileName, link.domain, durationMs)
                    ?.let { AddVideoState.Preview(it, editableTitle = true) }
                    ?: AddVideoState.Problem(AddVideoProblem.UNSAFE)
            }
            is VideoLink.Rejected -> AddVideoState.Problem(
                when (link.problem) {
                    VideoLink.Problem.PAGE -> AddVideoProblem.PAGE
                    VideoLink.Problem.UNSAFE -> AddVideoProblem.UNSAFE
                    VideoLink.Problem.UNRECOGNIZED -> AddVideoProblem.UNRECOGNIZED
                },
            )
        }
    }

    /** A search result tapped: its preview. Results already carry everything, so no info call. */
    fun choose(choice: VideoChoice): AddVideoState = preview(choice, music = false)

    private fun preview(choice: VideoChoice, music: Boolean): AddVideoState =
        RitmeEntries.toVideoEntry(choice.videoId, choice.title, choice.channel, choice.thumbnailUrl, choice.durationSeconds, music)
            ?.let { AddVideoState.Preview(it, editableTitle = false) }
            ?: AddVideoState.Problem(AddVideoProblem.NOT_FOUND)

    companion object {
        /**
         * What the buttons send for [state]: a preview's entry, with [title] applied to a URL entry
         * (checked again by the server's rules; blank falls back to the domain). Null without a preview.
         */
        fun entryOf(state: AddVideoState, title: String): NewEntry? {
            val preview = state as? AddVideoState.Preview ?: return null
            val entry = preview.entry
            if (!preview.editableTitle || entry.kind != MediaKind.URL) return entry
            val url = entry.url ?: return null
            return RitmeEntries.toUrlEntry(url, title, entry.artist, entry.durationMs, entry.streamType, entry.referrer)
        }
    }
}

package com.example.musicfy.ritme.video

import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.media.MediaUrlClassifier
import com.example.musicfy.ritme.media.ProbeVerdict
import com.example.musicfy.ritme.media.Refusal
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.StreamType

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

    /** An HLS/DASH link checked like the browser's finds (spec 4.6, 4.7 step 4); null when it could not be checked. */
    suspend fun probeStream(url: String): ProbeVerdict?
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

    /** A stream with DRM keys (U12). */
    PROTECTED,

    /** A stream whose host refuses it without a sign-in (U12). */
    NEEDS_LOGIN,
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
            is VideoLink.Direct -> direct(link)
            is VideoLink.Rejected -> AddVideoState.Problem(
                when (link.problem) {
                    VideoLink.Problem.PAGE -> AddVideoProblem.PAGE
                    VideoLink.Problem.UNSAFE -> AddVideoProblem.UNSAFE
                    VideoLink.Problem.UNRECOGNIZED -> AddVideoProblem.UNRECOGNIZED
                },
            )
        }
    }

    private suspend fun direct(link: VideoLink.Direct): AddVideoState {
        if (!link.streaming) {
            // A file whose length could not be read is never added as live (joiners at 0:00, seeks
            // ignored, "LIVE" on its row): it is a problem the adder can try again.
            val durationMs = lookup.probeDurationMs(link.url) ?: return AddVideoState.Problem(AddVideoProblem.LENGTH_UNREADABLE)
            return urlPreview(RitmeEntries.toUrlEntry(link.url, link.fileName, link.domain, durationMs))
        }
        // HLS/DASH (#3): checked like the browser's finds. A finished stream keeps its length, so a
        // play after a pause resumes instead of jumping to the "live edge"; DRM and sign-in-only
        // streams are refused (U12). Not checkable: live (0), as in #2b.
        val verdict = lookup.probeStream(link.url)
        when (verdict?.refusal) {
            Refusal.DRM -> return AddVideoState.Problem(AddVideoProblem.PROTECTED)
            Refusal.NEEDS_LOGIN -> return AddVideoState.Problem(AddVideoProblem.NEEDS_LOGIN)
            Refusal.NOT_MEDIA -> return AddVideoState.Problem(AddVideoProblem.PAGE)
            else -> Unit
        }
        val checked = verdict?.takeIf { it.refusal == null }
        // Decision 10: a link that turns out to be a file without a readable length is never live.
        if (checked != null && checked.streamType == StreamType.FILE && (checked.durationMs ?: 0L) <= 0L) {
            return AddVideoState.Problem(AddVideoProblem.LENGTH_UNREADABLE)
        }
        val durationMs = if (checked == null || checked.live) 0L else checked.durationMs ?: 0L
        val streamType = checked?.streamType?.takeUnless { it == StreamType.UNKNOWN } ?: MediaUrlClassifier.streamTypeOf(link.url)
        return urlPreview(RitmeEntries.toUrlEntry(link.url, link.fileName, link.domain, durationMs, streamType))
    }

    private fun urlPreview(entry: NewEntry?): AddVideoState =
        entry?.let { AddVideoState.Preview(it, editableTitle = true) } ?: AddVideoState.Problem(AddVideoProblem.UNSAFE)

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

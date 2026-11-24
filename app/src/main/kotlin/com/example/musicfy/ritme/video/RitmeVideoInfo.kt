package com.example.musicfy.ritme.video

import com.example.musicfy.ritme.entries.RitmeEntries
import com.music.innertube.models.PlaylistPanelVideoRenderer
import com.music.innertube.models.SongItem
import com.music.innertube.models.Thumbnail
import com.music.innertube.models.response.PlayerResponse
import com.music.innertube.pages.NextPage
import com.music.innertube.pages.WebVideoItem

/**
 * Turns YouTube's answers into [VideoChoice]s, and decides which answer to trust (pure; the network
 * calls are passed in).
 *
 * A link's info comes from YouTube Music's get_queue first. For an ordinary (non-music, UGC) upload
 * get_queue answers HTTP 200 with an unplayable stub: a renderer with only `videoId`, a placeholder
 * `thumbnail`, `unplayableText` and `navigationEndpoint` (no title, byline or length), which
 * YouTube.queue drops. Then the WEB client's `player` answer is asked: without a PO token it is
 * UNPLAYABLE, but its `videoDetails` still has the title, author and length. Same order and rules as
 * ritme-server's /api/video (InnerTubeVideoParser, then InnerTubePlayerParser). Metadata only:
 * `streamingData` is never read here.
 */
object RitmeVideoInfo {

    /** get_queue's item for [videoId] if usable: that very video, with a title the server would accept. */
    fun fromQueue(renderers: List<PlaylistPanelVideoRenderer>, videoId: String): VideoChoice? {
        val renderer = renderers.firstOrNull { it.videoId == videoId } ?: return null
        val song = NextPage.fromPlaylistPanelVideoRenderer(renderer) ?: return null
        // NextPage takes the largest picture (853 wide for a music video); a row needs far less.
        return fromSong(song).copy(thumbnailUrl = thumbnail(renderer.thumbnail.thumbnails) ?: song.thumbnail)
            .takeIf { RitmeEntries.hasVisibleText(it.title) }
    }

    /**
     * The picture sent with a video entry: every member loads it, and rows show it at 78 × 44 dp. So
     * the first one at least [THUMBNAIL_MIN_WIDTH] wide (YouTube lists them small to large), not the
     * largest (maxres is 1920 × 1080); when none is that wide, the largest there is (the last one
     * when no widths are given).
     */
    fun thumbnail(thumbnails: List<Thumbnail>): String? =
        (
            thumbnails.firstOrNull { (it.width ?: 0) >= THUMBNAIL_MIN_WIDTH }
                ?: thumbnails.asReversed().maxByOrNull { it.width ?: 0 }
            )?.url

    /** The WEB player's videoDetails for [videoId] if usable (present, that video, a visible title). */
    fun fromPlayer(response: PlayerResponse, videoId: String): VideoChoice? {
        val details = response.videoDetails ?: return null
        if (details.videoId != videoId) return null
        val title = details.title?.takeIf(RitmeEntries::hasVisibleText) ?: return null
        return choice(
            videoId = videoId,
            title = title,
            channel = details.author.orEmpty(),
            // a string ("627"); for a live stream "0", or the stream's age (121601512)
            seconds = details.lengthSeconds.toLongOrNull(),
            thumbnailUrl = thumbnail(details.thumbnail.thumbnails),
            liveHint = details.isLive == true,
        )
    }

    fun fromWeb(item: WebVideoItem): VideoChoice =
        choice(item.id, item.title, item.channel, item.durationSeconds?.toLong(), item.thumbnailUrl, liveHint = item.live)

    fun fromSong(song: SongItem): VideoChoice = choice(
        videoId = song.id,
        title = song.title,
        channel = song.artists.joinToString { it.name },
        seconds = song.duration?.toLong(),
        thumbnailUrl = song.thumbnail,
        liveHint = false,
    )

    /** get_queue first; when it fails or has no usable video, the WEB player. Null only when both fail. */
    suspend fun info(
        videoId: String,
        queue: suspend () -> Result<List<PlaylistPanelVideoRenderer>>,
        player: suspend () -> Result<PlayerResponse>,
    ): VideoChoice? =
        queue().getOrNull()?.let { fromQueue(it, videoId) }
            ?: player().getOrNull()?.let { fromPlayer(it, videoId) }

    /** U3: YouTube's general search; YouTube Music's videos when it fails or finds nothing. */
    suspend fun search(
        web: suspend () -> Result<List<WebVideoItem>>,
        music: suspend () -> Result<List<SongItem>>,
    ): List<VideoChoice> =
        web().getOrNull()?.takeIf { it.isNotEmpty() }?.map(::fromWeb)
            ?: music().getOrNull()?.map(::fromSong).orEmpty()

    const val THUMBNAIL_MIN_WIDTH = 320

    /**
     * Lengths the way RitmeEntries.toVideoEntry takes them (2a Decision 10): missing, 0 or at least
     * 12 h is live, so the preview says LIVE exactly when the entry will be live.
     */
    private fun choice(videoId: String, title: String, channel: String, seconds: Long?, thumbnailUrl: String?, liveHint: Boolean): VideoChoice {
        val length = seconds?.takeIf { it in 1 until RitmeEntries.LIVE_FROM_SECONDS }?.toInt()
        val live = liveHint || length == null
        return VideoChoice(videoId, title, channel, if (live) null else length, thumbnailUrl, live)
    }
}

package com.example.musicfy.ritme.video

import com.example.musicfy.ritme.browser.RitmeProbes
import com.example.musicfy.ritme.media.ProbeVerdict
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.YouTubeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Add video"'s network side (spec 4.6). YouTube is asked without the user's login: get_queue and
 * the general search as YouTube Music's client does, the WEB player fallback as the WEB client,
 * which sends no cookie (YouTubeClient.WEB.loginSupported is false). Pasted files and streams are
 * checked on this device only, never by the server: a file's length by RitmeFileLength, HLS/DASH
 * by RitmeProbes ([webOrigin]: the room's web client, for the CORS check).
 */
class RitmeVideoLookup(private val webOrigin: String) : VideoLookup {

    override suspend fun info(videoId: String): VideoChoice? = withContext(Dispatchers.IO) {
        RitmeVideoInfo.info(
            videoId,
            queue = { YouTube.queueRenderers(listOf(videoId)) },
            // Metadata only: RitmeVideoInfo.fromPlayer reads videoDetails, never streamingData.
            player = { YouTube.player(videoId, client = YouTubeClient.WEB) },
        )
    }

    override suspend fun search(query: String): List<VideoChoice> = withContext(Dispatchers.IO) {
        RitmeVideoInfo.search(
            web = { YouTube.searchWebVideos(query) },
            music = { YouTube.search(query, YouTube.SearchFilter.FILTER_VIDEO).map { it.items.filterIsInstance<SongItem>() } },
        )
    }

    override suspend fun probeDurationMs(url: String): Long? = RitmeFileLength.read(url)

    // The prober refuses a url with a #fragment, which an entry never keeps anyway.
    override suspend fun probeStream(url: String): ProbeVerdict =
        RitmeProbes.probe(url.substringBefore('#'), pageUrl = null, webOrigin = webOrigin)
}

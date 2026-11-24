package com.example.musicfy.ritme.video

import android.media.MediaMetadataRetriever
import com.example.musicfy.ritme.playback.RitmeAddressGuard
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.YouTubeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.URI

/**
 * "Add video"'s network side (spec 4.6). YouTube is asked without the user's login: get_queue and
 * the general search as YouTube Music's client does, the WEB player fallback as the WEB client,
 * which sends no cookie (YouTubeClient.WEB.loginSupported is false).
 */
class RitmeVideoLookup : VideoLookup {

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

    /**
     * Decision 13: a progressive file's length, read on this (the adder's) device by
     * MediaMetadataRetriever, which sends no cookies or headers. At most [PROBE_TIMEOUT_MS]; null on
     * any failure. Only when every address the host has is public (RitmeAddressGuard): the probe does
     * not go through the room's guarded OkHttp client, so a host on this device's own network is
     * never probed (its entry is simply added without a length).
     */
    override suspend fun probeDurationMs(url: String): Long? {
        // setDataSource blocks and cannot be interrupted: it runs apart, one probe at a time
        // ([probeGate], so repeated pastes never pile up blocked IO threads), and is given up waiting
        // for after the timeout. A caller that gives up (an edit cancels it, or the timeout) cancels
        // its probe: one still waiting for its turn never starts, one already blocked finishes and
        // releases the retriever on its own.
        val probe = probeScope.async {
            probeGate.withPermit {
                runCatching {
                    val host = URI(url).host ?: return@runCatching null
                    if (!InetAddress.getAllByName(host).all(RitmeAddressGuard::isPublic)) return@runCatching null
                    ensureActive()
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(url, emptyMap())
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
                    } finally {
                        retriever.release()
                    }
                }.getOrNull()
            }
        }
        return try {
            withTimeoutOrNull(PROBE_TIMEOUT_MS) { probe.await() }
        } finally {
            probe.cancel()
        }
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 8_000L
        val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val probeGate = Semaphore(1)
    }
}

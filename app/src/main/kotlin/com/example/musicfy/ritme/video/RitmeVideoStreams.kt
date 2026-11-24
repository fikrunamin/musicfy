package com.example.musicfy.ritme.video

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.content.getSystemService
import androidx.media3.common.MimeTypes
import com.example.musicfy.ui.player.OfficialMusicVideo
import com.example.musicfy.ui.player.YouTubeVideoUrlCache
import com.example.musicfy.utils.VideoCodec
import com.example.musicfy.utils.VideoFormatPolicy
import com.example.musicfy.utils.YTPlayerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The follower's stream (spec 4.3, U10, R2): 720p on Wi-Fi, 480p metered, a codec this device decodes in hardware. */
object RitmeVideoStreams {

    fun maxHeight(context: Context): Int =
        if (context.getSystemService<ConnectivityManager>()?.isActiveNetworkMetered == true) METERED_HEIGHT else UNMETERED_HEIGHT

    private const val METERED_HEIGHT = 480
    private const val UNMETERED_HEIGHT = 720

    /** avc → vp9 → av1, keeping those with a hardware decoder; AVC alone when none can be read. */
    val hardwareCodecs: List<VideoCodec> by lazy {
        val decoders = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder && isHardware(it) }
        }.getOrDefault(emptyList())
        val types = decoders.flatMap { info -> info.supportedTypes.map { it.lowercase() } }.toSet()
        listOfNotNull(
            VideoCodec.AVC.takeIf { MimeTypes.VIDEO_H264 in types },
            VideoCodec.VP9.takeIf { MimeTypes.VIDEO_VP9 in types },
            VideoCodec.AV1.takeIf { MimeTypes.VIDEO_AV1 in types },
        ).ifEmpty { listOf(VideoCodec.AVC) }
    }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            val name = info.name.lowercase()
            !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
        }

    /** The video-only stream URL, or null when no client gives one (the audio plays on regardless). */
    suspend fun resolve(context: Context, videoId: String): String? =
        resolve(videoId, maxHeight(context)) { id, height ->
            withContext(Dispatchers.IO) {
                YTPlayerUtils.resolveVideoStreamUrl(id, VideoFormatPolicy(height, hardwareCodecs)).getOrNull()
            }
        }

    /** [resolve] with the network part passed in: a cached URL, else [fetch]'s, which is then cached. */
    internal suspend fun resolve(videoId: String, maxHeight: Int, fetch: suspend (String, Int) -> String?): String? {
        // its own key: the cover's video background caches its own (any codec, near 720p) choice
        val key = key(videoId, maxHeight)
        YouTubeVideoUrlCache.get(key)?.let { return it.streamUrl }
        val url = fetch(videoId, maxHeight) ?: return null
        YouTubeVideoUrlCache.put(key, OfficialMusicVideo(videoId, url))
        return url
    }

    /** The follower failed on [url] (expired after ~6 h, or bound to an earlier IP): the next resolve asks again. */
    fun invalidate(videoId: String, url: String) {
        // by URL, not by today's height: the network may have turned metered since it was resolved;
        // a null entry reads as a miss in [resolve]
        for (height in HEIGHTS) {
            val key = key(videoId, height)
            if (YouTubeVideoUrlCache.get(key)?.streamUrl == url) YouTubeVideoUrlCache.put(key, null)
        }
    }

    /** One fresh resolve after the follower fails on a stream, then the stage says the video is unavailable. */
    fun canRetry(retriesUsed: Int): Boolean = retriesUsed < MAX_RETRIES

    private const val MAX_RETRIES = 1
    private val HEIGHTS = listOf(METERED_HEIGHT, UNMETERED_HEIGHT)

    internal fun key(videoId: String, maxHeight: Int): String = "ritme:$videoId:$maxHeight"
}

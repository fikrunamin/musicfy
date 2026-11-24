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
        if (context.getSystemService<ConnectivityManager>()?.isActiveNetworkMetered == true) 480 else 720

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
    suspend fun resolve(context: Context, videoId: String): String? {
        val maxHeight = maxHeight(context)
        // its own key: the cover's video background caches its own (any codec, near 720p) choice
        val key = "ritme:$videoId:$maxHeight"
        YouTubeVideoUrlCache.get(key)?.let { return it.streamUrl }
        val url = withContext(Dispatchers.IO) {
            YTPlayerUtils.resolveVideoStreamUrl(videoId, VideoFormatPolicy(maxHeight, hardwareCodecs)).getOrNull()
        } ?: return null
        YouTubeVideoUrlCache.put(key, OfficialMusicVideo(videoId, url))
        return url
    }
}

package com.example.musicfy.utils

import com.music.innertube.models.response.PlayerResponse

private typealias Format = PlayerResponse.StreamingData.Format

enum class VideoCodec {
    AVC, VP9, AV1;

    companion object {
        /** The codec of a YouTube format's mimeType, e.g. `video/mp4; codecs="avc1.4d401f"`; null for audio. */
        fun of(mimeType: String): VideoCodec? {
            val codecs = mimeType.substringAfter("codecs=\"", "").lowercase()
            return when {
                codecs.startsWith("avc1") -> AVC
                codecs.startsWith("vp9") || codecs.startsWith("vp09") -> VP9
                codecs.startsWith("av01") -> AV1
                else -> null
            }
        }
    }
}

/** Ritme's video follower (spec 4.3, U10): at most [maxHeight], in the first of [codecs] that has a stream. */
data class VideoFormatPolicy(val maxHeight: Int, val codecs: List<VideoCodec>)

object VideoFormatChooser {
    /**
     * The tallest adaptive video-only format up to the cap, in the first codec of the policy that has
     * one (the caller lists codecs this device decodes in hardware, best first); else the tallest up
     * to the cap in any codec; else the smallest there is; else a muxed format.
     */
    fun choose(adaptive: List<Format>?, muxed: List<Format>?, policy: VideoFormatPolicy): Format? {
        val video = adaptive.orEmpty().filter { !it.isAudio && it.height != null }
        val fitting = video.filter { it.height!! <= policy.maxHeight }
        val best = compareBy<Format>({ it.height }, { -it.bitrate })
        for (codec in policy.codecs) {
            fitting.filter { VideoCodec.of(it.mimeType) == codec }.maxWithOrNull(best)?.let { return it }
        }
        return fitting.maxWithOrNull(best)
            ?: video.minByOrNull { it.height!! }
            ?: muxed.orEmpty().firstOrNull { !it.isAudio }
    }
}

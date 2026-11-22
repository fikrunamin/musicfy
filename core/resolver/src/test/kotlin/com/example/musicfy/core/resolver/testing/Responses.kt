package com.example.musicfy.core.resolver.testing

import com.music.innertube.models.ResponseContext
import com.music.innertube.models.Thumbnails
import com.music.innertube.models.response.PlayerResponse

object Responses {
    fun audioFormat(
        itag: Int = 251,
        url: String? = "https://rr1.googlevideo.com/videoplayback?id=1",
        bitrate: Int = 160_000,
        audioQuality: String = "AUDIO_QUALITY_MEDIUM",
        signatureCipher: String? = null,
    ) = PlayerResponse.StreamingData.Format(
        itag = itag,
        url = url,
        mimeType = "audio/webm; codecs=\"opus\"",
        bitrate = bitrate,
        width = null,
        height = null,
        contentLength = 3_000_000L,
        quality = "tiny",
        fps = null,
        qualityLabel = null,
        averageBitrate = bitrate,
        audioQuality = audioQuality,
        approxDurationMs = "200000",
        audioSampleRate = 48_000,
        audioChannels = 2,
        loudnessDb = null,
        lastModified = null,
        signatureCipher = signatureCipher,
        cipher = null,
        audioTrack = null,
    )

    fun ok(url: String, expiresInSeconds: Int = 21_540) = okWithFormats(listOf(audioFormat(url = url)), expiresInSeconds)

    fun okWithFormats(formats: List<PlayerResponse.StreamingData.Format>, expiresInSeconds: Int = 21_540) = PlayerResponse(
        responseContext = ResponseContext(visitorData = null, serviceTrackingParams = null),
        playabilityStatus = PlayerResponse.PlayabilityStatus(status = "OK", reason = null),
        playerConfig = null,
        streamingData = PlayerResponse.StreamingData(formats = null, adaptiveFormats = formats, expiresInSeconds = expiresInSeconds),
        videoDetails = PlayerResponse.VideoDetails(
            videoId = "vid",
            title = "Title",
            author = "Artist",
            channelId = "UCchannel",
            lengthSeconds = "200",
            musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
            viewCount = null,
            thumbnail = Thumbnails(thumbnails = emptyList()),
        ),
        playbackTracking = null,
    )

    fun status(status: String, reason: String? = null) = PlayerResponse(
        responseContext = ResponseContext(visitorData = null, serviceTrackingParams = null),
        playabilityStatus = PlayerResponse.PlayabilityStatus(status = status, reason = reason),
        playerConfig = null,
        streamingData = null,
        videoDetails = null,
        playbackTracking = null,
    )
}

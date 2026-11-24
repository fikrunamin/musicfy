package com.example.musicfy.core.resolver

import com.example.musicfy.utils.VideoCodec
import com.example.musicfy.utils.VideoFormatChooser
import com.example.musicfy.utils.VideoFormatPolicy
import com.music.innertube.models.response.PlayerResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoFormatChooserTest {

    private fun video(itag: Int, height: Int, codecs: String, bitrate: Int = 1_000_000) = PlayerResponse.StreamingData.Format(
        itag = itag,
        url = "https://rr1.googlevideo.com/videoplayback?itag=$itag",
        mimeType = "video/${if (codecs.startsWith("vp9")) "webm" else "mp4"}; codecs=\"$codecs\"",
        bitrate = bitrate,
        width = height * 16 / 9,
        height = height,
        contentLength = null,
        quality = "hd",
        fps = 30,
        qualityLabel = "${height}p",
        averageBitrate = bitrate,
        audioQuality = null,
        approxDurationMs = "200000",
        audioSampleRate = null,
        audioChannels = null,
        loudnessDb = null,
        lastModified = null,
        signatureCipher = null,
        cipher = null,
        audioTrack = null,
    )

    private val all = listOf(
        video(134, 360, "avc1.4d401e"), video(135, 480, "avc1.4d401f"), video(136, 720, "avc1.4d401f"), video(137, 1080, "avc1.640028"),
        video(247, 720, "vp9"), video(398, 720, "av01.0.05M.08"),
    )
    private val everyCodec = listOf(VideoCodec.AVC, VideoCodec.VP9, VideoCodec.AV1)

    @Test
    fun theFirstCodecAtTheTallestHeightUnderTheCap() {
        assertEquals(136, VideoFormatChooser.choose(all, null, VideoFormatPolicy(720, everyCodec))?.itag)
    }

    @Test
    fun aMeteredNetworkIsCappedAt480() {
        assertEquals(135, VideoFormatChooser.choose(all, null, VideoFormatPolicy(480, everyCodec))?.itag)
    }

    @Test
    fun codecsWithoutAHardwareDecoderAreSkipped() {
        assertEquals(247, VideoFormatChooser.choose(all, null, VideoFormatPolicy(720, listOf(VideoCodec.VP9)))?.itag)
        assertEquals(398, VideoFormatChooser.choose(all, null, VideoFormatPolicy(720, listOf(VideoCodec.AV1, VideoCodec.AVC)))?.itag)
    }

    @Test
    fun nothingUnderTheCapTakesTheSmallestThereIs() {
        val tall = listOf(video(137, 1080, "avc1.640028"), video(264, 1440, "avc1.640032"))
        assertEquals(137, VideoFormatChooser.choose(tall, null, VideoFormatPolicy(720, everyCodec))?.itag)
    }

    @Test
    fun noAdaptiveVideoFallsBackToAMuxedFormat() {
        val muxed = video(18, 360, "avc1.42001E, mp4a.40.2")
        assertEquals(18, VideoFormatChooser.choose(emptyList(), listOf(muxed), VideoFormatPolicy(720, everyCodec))?.itag)
        assertNull(VideoFormatChooser.choose(null, null, VideoFormatPolicy(720, everyCodec)))
    }

    @Test
    fun codecsAreReadFromTheMimeType() {
        assertEquals(VideoCodec.AVC, VideoCodec.of("video/mp4; codecs=\"avc1.4d401f\""))
        assertEquals(VideoCodec.VP9, VideoCodec.of("video/webm; codecs=\"vp9\""))
        assertEquals(VideoCodec.VP9, VideoCodec.of("video/mp4; codecs=\"vp09.00.40.08\""))
        assertEquals(VideoCodec.AV1, VideoCodec.of("video/mp4; codecs=\"av01.0.08M.08\""))
        assertNull(VideoCodec.of("audio/webm; codecs=\"opus\""))
    }
}

package com.example.musicfy.core.resolver

import com.example.musicfy.constants.AudioQuality
import com.example.musicfy.core.resolver.testing.FakeBackend
import com.example.musicfy.core.resolver.testing.FakeBotGuardRuntime
import com.example.musicfy.core.resolver.testing.FakeJsRuntime
import com.example.musicfy.core.resolver.testing.FakeNSolverSession
import com.example.musicfy.core.resolver.testing.Responses
import com.example.musicfy.core.resolver.testing.TestPlaybackError
import com.example.musicfy.core.resolver.testing.TestResolver
import com.example.musicfy.utils.BotDetectionMitigator
import com.example.musicfy.utils.YTPlayerUtils
import com.example.musicfy.utils.cipher.CipherDeobfuscator
import com.example.musicfy.utils.sabr.EjsNTransformSolver
import com.music.innertube.YouTube
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_CREATOR
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_61_48
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_65_10
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.music.innertube.models.YouTubeClient.Companion.IPADOS
import com.music.innertube.models.YouTubeClient.Companion.MOBILE
import com.music.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.music.innertube.models.YouTubeClient.Companion.VISIONOS
import com.music.innertube.models.YouTubeClient.Companion.WEB
import com.music.innertube.models.YouTubeClient.Companion.WEB_REMIX
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class YTPlayerUtilsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val backend = FakeBackend()
    private val botGuard = FakeBotGuardRuntime()
    private val rotated = mutableListOf<String>()

    @Before
    fun setUp() {
        YouTube.cookie = null
        YouTube.dataSyncId = null
        YouTube.visitorData = "visitor-0"
        YTPlayerUtils.resetForTests()
        CipherDeobfuscator.resetForTests()
        EjsNTransformSolver.resetForTests()
        BotDetectionMitigator.reset()
        TestResolver.writePlayerJsCache(tmp.root)
    }

    private fun install(js: FakeJsRuntime = FakeJsRuntime()) =
        TestResolver.install(tmp.root, jsRuntime = js, botGuard = botGuard, backend = backend, rotated = rotated)

    @Test
    fun visionOsStreamNeedsNoPoTokenAndNoJavaScript() = runBlocking {
        val js = FakeJsRuntime()
        install(js)
        val url = "https://rr1.googlevideo.com/videoplayback?id=1&c=VISIONOS"
        backend.respond = { client -> if (client == VISIONOS) Responses.ok(url) else null }

        val data = YTPlayerUtils.playerResponseForPlayback("vid", audioQuality = AudioQuality.HIGH, networkMetered = { false }).getOrThrow()

        assertEquals("VISIONOS", data.streamClient)
        assertEquals(url, data.streamUrl)
        assertEquals(listOf(VISIONOS), backend.playerCalls.map { it.client })
        assertEquals(listOf(url), backend.validated)
        assertEquals(0, botGuard.sessionsCreated)
        assertEquals(0, js.cipherLoads + js.nSolverLoads)
    }

    @Test
    fun guestFallsThroughEveryNonLoginClientThenRotatesAndReportsTheReason() = runBlocking {
        install()
        backend.respond = { Responses.status("UNPLAYABLE", "Video unavailable") }

        val result = YTPlayerUtils.playerResponseForPlayback("vid", audioQuality = AudioQuality.HIGH, networkMetered = { false })

        // MAIN_CLIENT once, then STREAM_FALLBACK_CLIENTS minus the login-only WEB_CREATOR and TVHTML5.
        val order = listOf(
            VISIONOS, VISIONOS, WEB_REMIX, WEB, IPADOS,
            ANDROID_VR_1_43_32, ANDROID_VR_1_61_48, ANDROID_VR_1_65_10, ANDROID_VR_NO_AUTH,
            TVHTML5_SIMPLY_EMBEDDED_PLAYER, ANDROID_CREATOR, MOBILE,
        )
        assertEquals(order + order, backend.playerCalls.map { it.client })
        val error = result.exceptionOrNull()
        assertTrue("expected the platform playback error, got $error", error is TestPlaybackError)
        assertEquals("Video unavailable", (error as TestPlaybackError).reason)
        assertEquals(1, backend.visitorDataRefreshes)
        assertEquals(listOf("visitor-1"), rotated)
    }

    @Test
    fun webClientGetsALazyPoTokenAndAnNTransformedUrl() = runBlocking {
        val js = FakeJsRuntime(nSolver = { FakeNSolverSession(mapOf("abc" to "xyz")) })
        install(js)
        backend.respond = { client ->
            if (client == WEB_REMIX) Responses.ok("https://rr1.googlevideo.com/videoplayback?id=1&n=abc")
            else Responses.status("UNPLAYABLE", "nope")
        }

        val data = YTPlayerUtils.playerResponseForPlayback("vid", audioQuality = AudioQuality.HIGH, networkMetered = { false }).getOrThrow()

        assertEquals("WEB_REMIX", data.streamClient)
        assertEquals("https://rr1.googlevideo.com/videoplayback?id=1&n=xyz&pot=pot-vid", data.streamUrl)
        assertEquals("https://music.youtube.com", data.streamHeaders["Origin"])
        assertEquals(listOf(VISIONOS, VISIONOS, WEB_REMIX), backend.playerCalls.map { it.client })
        assertEquals("pot-visitor-0", backend.playerCalls.last().poToken)
        assertEquals(20_000, backend.playerCalls.last().signatureTimestamp)
        assertEquals(1, botGuard.sessionsCreated)
        assertEquals(listOf("visitor-0", "vid"), botGuard.minted)
        assertEquals(1, js.nSolverLoads)
    }

    @Test
    fun autoQualityOnAMeteredNetworkPrefersTheMediumRung() {
        install()
        val response = Responses.okWithFormats(
            listOf(
                Responses.audioFormat(itag = 251, url = "u251", bitrate = 160_000),
                Responses.audioFormat(itag = 140, url = "u140", bitrate = 130_000),
            ),
        )
        assertEquals(140, YTPlayerUtils.selectAudioFormatCandidates(response, AudioQuality.AUTO, networkMetered = true).first().itag)
        assertEquals(251, YTPlayerUtils.selectAudioFormatCandidates(response, AudioQuality.AUTO, networkMetered = false).first().itag)
    }
}

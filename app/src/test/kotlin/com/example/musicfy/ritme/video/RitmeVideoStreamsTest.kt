package com.example.musicfy.ritme.video

import com.example.musicfy.ui.player.OfficialMusicVideo
import com.example.musicfy.ui.player.YouTubeVideoUrlCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class RitmeVideoStreamsTest {

    /** A fresh video id per test: the cache is process-wide. */
    private val videoId = "v" + UUID.randomUUID().toString().take(10)

    /** Hands out stream1, stream2, ... and counts the calls. */
    private class FakeResolver {
        var calls = 0
        suspend fun fetch(id: String, height: Int): String? {
            calls++
            return "https://rr.example/$id/$height/stream$calls"
        }
    }

    @Test
    fun aResolvedUrlIsCachedUnderItsOwnHeight() = runBlocking {
        val resolver = FakeResolver()
        val first = RitmeVideoStreams.resolve(videoId, 720, resolver::fetch)
        val again = RitmeVideoStreams.resolve(videoId, 720, resolver::fetch)

        assertEquals(first, again)
        assertEquals(1, resolver.calls)
        assertEquals(first, YouTubeVideoUrlCache.get("ritme:$videoId:720")?.streamUrl)
    }

    @Test
    fun anInvalidatedUrlIsResolvedAgainInsteadOfReused() = runBlocking {
        val resolver = FakeResolver()
        val dead = RitmeVideoStreams.resolve(videoId, 720, resolver::fetch)!!

        RitmeVideoStreams.invalidate(videoId, dead)
        val fresh = RitmeVideoStreams.resolve(videoId, 720, resolver::fetch)

        assertEquals(2, resolver.calls)
        assertEquals("https://rr.example/$videoId/720/stream2", fresh)
        assertEquals(fresh, YouTubeVideoUrlCache.get("ritme:$videoId:720")?.streamUrl)
    }

    @Test
    fun invalidatingFindsTheUrlWhicheverHeightItWasCachedFor() = runBlocking {
        // resolved on a metered network (480); the stage does not know the height when it fails
        val resolver = FakeResolver()
        val dead = RitmeVideoStreams.resolve(videoId, 480, resolver::fetch)!!

        RitmeVideoStreams.invalidate(videoId, dead)

        assertNull(YouTubeVideoUrlCache.get("ritme:$videoId:480"))
    }

    @Test
    fun invalidatingLeavesANewerUrlAndOtherKeysAlone() = runBlocking {
        val resolver = FakeResolver()
        RitmeVideoStreams.resolve(videoId, 480, resolver::fetch)
        val newer = YouTubeVideoUrlCache.get("ritme:$videoId:480")!!.streamUrl
        YouTubeVideoUrlCache.put("self:$videoId", OfficialMusicVideo(videoId, "https://rr.example/old"))

        // a late failure report for a URL that was already replaced
        RitmeVideoStreams.invalidate(videoId, "https://rr.example/old")

        assertEquals(newer, YouTubeVideoUrlCache.get("ritme:$videoId:480")?.streamUrl)
        assertEquals("https://rr.example/old", YouTubeVideoUrlCache.get("self:$videoId")?.streamUrl)
    }

    @Test
    fun theFollowerGetsOneFreshResolveBeforeTheUnavailableNote() {
        assertTrue(RitmeVideoStreams.canRetry(retriesUsed = 0))
        assertFalse(RitmeVideoStreams.canRetry(retriesUsed = 1))
        assertFalse(RitmeVideoStreams.canRetry(retriesUsed = 2))
    }
}

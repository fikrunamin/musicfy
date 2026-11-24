package com.example.musicfy.ritme.browser

import com.example.musicfy.ritme.media.MediaFormat
import com.example.musicfy.ritme.media.ProbeVerdict
import com.example.musicfy.ritme.media.Refusal
import com.example.musicfy.ritme.protocol.StreamType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RitmeBrowserModelTest {
    private val page = "https://example.com/film/1"
    private val hls = "https://cdn.example.com/v/master.m3u8"
    private val variant = "https://cdn.example.com/v/720p/index.m3u8"
    private val mp4 = "https://cdn.example.com/v/film.mp4"

    private class Fakes(val length: Long? = null) {
        val verdicts = mutableMapOf<String, ProbeVerdict>()
        val probed = mutableListOf<String>()
        val lengthAsked = mutableListOf<Pair<String, String?>>()
        val probe: suspend (String, String?) -> ProbeVerdict = { url, _ ->
            probed += url
            verdicts[url] ?: ProbeVerdict.refused(url, Refusal.FAILED)
        }
        val fileLength: suspend (String, String?) -> Long? = { url, referrer ->
            lengthAsked += url to referrer
            length
        }
    }

    private fun CoroutineScope.model(fakes: Fakes) =
        RitmeBrowserModel(fakes.probe, fakes.fileLength, this, clock = { 1_000L }).also { it.pageStarted(page) }

    private fun hlsVerdict() = ProbeVerdict(hls, StreamType.HLS, MediaFormat.HLS, durationMs = 1_200_000, variants = listOf(variant))

    private fun fileVerdict(durationMs: Long?, needsReferrer: Boolean = false) =
        ProbeVerdict(mp4, StreamType.FILE, MediaFormat.MP4, durationMs = durationMs, needsReferrer = needsReferrer, webPlayable = !needsReferrer)

    private fun RitmeBrowserModel.request(url: String) = onRequest(url, "GET", emptyMap())

    @Test
    fun eachFindIsCheckedOnceAndOnlyWhenTheListAsks() = runBlocking<Unit> {
        val fakes = Fakes().apply {
            verdicts[hls] = hlsVerdict()
            verdicts[mp4] = fileVerdict(60_000)
        }
        val model = model(fakes)
        model.request(hls)
        model.request(mp4)
        assertTrue(fakes.probed.isEmpty())
        model.probeAll().joinAll()
        assertEquals(setOf(hls, mp4), fakes.probed.toSet())
        assertTrue(model.probeAll().isEmpty())
        assertEquals(2, fakes.probed.size)
        assertEquals(60_000L, model.verdictOf(mp4)?.durationMs)
    }

    @Test
    fun aMastersVariantsAndPagesThatAreNotVideosLeaveTheList() = runBlocking<Unit> {
        val embed = "https://example.com/embed/1.mp4"
        val fakes = Fakes().apply {
            verdicts[hls] = hlsVerdict()
            verdicts[embed] = ProbeVerdict.refused(embed, Refusal.NOT_MEDIA)
        }
        val model = model(fakes)
        model.request(hls)
        model.request(variant)
        model.request(embed)
        assertEquals(3, model.items().size)
        model.probeAll().joinAll()
        assertEquals(listOf(hls), model.items().map { it.candidate.url })
    }

    @Test
    fun aProbeThatThrowsBecomesAFailedVerdict() = runBlocking<Unit> {
        val model = RitmeBrowserModel(
            { _, _ -> throw IllegalStateException("boom") },
            { _, _ -> null },
            this,
            clock = { 1_000L },
        ).also { it.pageStarted(page) }
        model.request(mp4)
        model.probeAll().joinAll()
        assertEquals(Refusal.FAILED, model.verdictOf(mp4)?.refusal)
    }

    @Test
    fun anAnswerAboutAnOldPageIsIgnored() = runBlocking<Unit> {
        val answer = CompletableDeferred<ProbeVerdict>()
        val model = RitmeBrowserModel({ _, _ -> answer.await() }, { _, _ -> null }, this)
        model.pageStarted(page)
        model.request(mp4)
        val jobs = model.probeAll()
        model.pageStarted("https://example.com/other")
        answer.complete(fileVerdict(60_000))
        jobs.joinAll()
        assertTrue(model.probes.value.isEmpty())
        assertTrue(model.items().isEmpty())
    }

    @Test
    fun aFilesLengthComesFromThePageThenFromTheFile() = runBlocking<Unit> {
        // the page's <video> knows it: the file is not read
        val fromPage = Fakes(length = 30_000).apply { verdicts[mp4] = fileVerdict(null) }
        val first = model(fromPage)
        first.onReport("""{"v":1,"page":"$page","videos":[{"srcs":["$mp4"],"blob":false,"duration":61.5,"w":0,"h":0,"area":0,"playing":false}]}""", mainFrame = true, origin = "https://example.com")
        first.probeAll().joinAll()
        assertEquals(61_500L, first.verdictOf(mp4)?.durationMs)
        assertTrue(fromPage.lengthAsked.isEmpty())
        // read from the file, with the Referer when the host needs it
        val fromFile = Fakes(length = 30_000).apply { verdicts[mp4] = fileVerdict(null, needsReferrer = true) }
        val second = model(fromFile)
        second.request(mp4)
        second.probeAll().joinAll()
        assertEquals(30_000L, second.verdictOf(mp4)?.durationMs)
        assertEquals(listOf(mp4 to "https://example.com/"), fromFile.lengthAsked)
        // neither: refused, to be tried again
        val unknown = Fakes(length = null).apply { verdicts[mp4] = fileVerdict(null) }
        val third = model(unknown)
        third.request(mp4)
        third.probeAll().joinAll()
        assertEquals(Refusal.LENGTH_UNKNOWN, third.verdictOf(mp4)?.refusal)
    }

    @Test
    fun drmOnThePageRefusesItsFinds() = runBlocking<Unit> {
        val fakes = Fakes().apply { verdicts[mp4] = fileVerdict(60_000) }
        val model = model(fakes)
        model.request(mp4)
        model.onProtectedMedia()
        model.probeAll().joinAll()
        assertEquals(Refusal.DRM, model.verdictOf(mp4)?.refusal)
    }


    @Test
    fun aCleanPlaylistOnAnEmePageKeepsItsVerdict() = runBlocking<Unit> {
        val fakes = Fakes().apply { verdicts[hls] = hlsVerdict() }
        val model = model(fakes)
        model.request(hls)
        model.onProtectedMedia()
        model.probeAll().joinAll()
        assertNull(model.verdictOf(hls)?.refusal)
        assertNull(model.items().single().let { (it.state as ProbeState.Done).verdict.refusal })
    }

    @Test
    fun aFailedFileOnAnEmePageIsDrmNotRetry() = runBlocking<Unit> {
        val model = model(Fakes())
        model.request(mp4)
        model.onProtectedMedia()
        model.probeAll().joinAll()
        assertEquals(Refusal.DRM, model.verdictOf(mp4)?.refusal)
    }

    @Test
    fun emeArrivingAfterTheProbeStillApplies() = runBlocking<Unit> {
        val fakes = Fakes().apply { verdicts[mp4] = fileVerdict(60_000) }
        val model = model(fakes)
        model.request(mp4)
        model.probeAll().joinAll()
        assertNull(model.verdictOf(mp4)?.refusal)
        model.onProtectedMedia()
        assertEquals(Refusal.DRM, model.verdictOf(mp4)?.refusal)
        // a new document clears the flag
        model.pageStarted("https://example.com/next")
        assertNull(model.verdictOf(mp4))
    }

    @Test
    fun notMediaStaysHiddenAndNeedsLoginStaysOnAnEmePage() = runBlocking<Unit> {
        val login = "https://cdn.example.com/v/secret.mp4"
        val fakes = Fakes().apply { verdicts[login] = ProbeVerdict.refused(login, Refusal.NEEDS_LOGIN) }
        val model = model(fakes)
        model.request(login)
        model.onProtectedMedia()
        model.probeAll().joinAll()
        assertEquals(Refusal.NEEDS_LOGIN, model.verdictOf(login)?.refusal)
    }

    @Test
    fun retryChecksAgain() = runBlocking<Unit> {
        val fakes = Fakes()
        val model = model(fakes)
        model.request(mp4)
        model.probeAll().joinAll()
        assertEquals(Refusal.FAILED, model.verdictOf(mp4)?.refusal)
        fakes.verdicts[mp4] = fileVerdict(60_000)
        model.retry(mp4)!!.join()
        assertNull(model.verdictOf(mp4)?.refusal)
        assertEquals(2, fakes.probed.size)
    }

    @Test
    fun thePillCountsSureFindsAndAYouTubeVideo() = runBlocking<Unit> {
        val model = model(Fakes())
        model.onReport("""{"v":1,"page":"$page","og":["https://example.com/player/1"]}""", mainFrame = true, origin = "https://example.com")
        assertEquals(1, model.items().size)
        assertEquals(0, model.offers())
        model.request(hls)
        assertEquals(1, model.offers())
        model.pageStarted("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals(1, model.offers())
    }
}

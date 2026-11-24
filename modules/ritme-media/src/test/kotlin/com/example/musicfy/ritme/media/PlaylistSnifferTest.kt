package com.example.musicfy.ritme.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSnifferTest {

    private fun detect(text: String, type: String? = null) = PlaylistSniffer.detect(text.toByteArray(), type)

    @Test
    fun theFirstBytesSayWhatAResponseIs() {
        assertEquals(MediaFormat.HLS, detect("#EXTM3U\n#EXT-X-VERSION:3\n"))
        assertEquals(MediaFormat.HLS, detect("\uFEFF  #EXTM3U\n"))
        assertEquals(MediaFormat.DASH, detect("<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">"))
        assertEquals(MediaFormat.MP4, PlaylistSniffer.detect(mp4Head(0, 1000, 1000), null))
        assertEquals(MediaFormat.WEBM, PlaylistSniffer.detect(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 1, 2), null))
        assertEquals(MediaFormat.HTML, detect("<!DOCTYPE html><html><body>Not found</body></html>", "video/mp4"))
        assertEquals(MediaFormat.HLS, detect("??", "application/vnd.apple.mpegurl; charset=utf-8"))
        assertEquals(MediaFormat.DASH, detect("??", "application/dash+xml"))
        assertEquals(MediaFormat.VIDEO, detect("??", "video/quicktime"))
        assertEquals(MediaFormat.UNKNOWN, detect("G@\u0011", "video/mp2t"))
        assertEquals(MediaFormat.UNKNOWN, detect("{\"a\":1}", "application/json"))
    }

    @Test
    fun aMasterPlaylistListsItsVariantsAbsolute() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720
            https://cdn2.example.com/720p/index.m3u8
        """.trimIndent()
        val info = PlaylistSniffer.parseHls(master, "https://cdn.example.com/v/master.m3u8?token=x")
        assertTrue(info.master)
        assertEquals(listOf("https://cdn.example.com/v/360p/index.m3u8", "https://cdn2.example.com/720p/index.m3u8"), info.variants)
        assertEquals(720, info.maxHeight)
        assertNull(info.durationMs)
        assertFalse(info.drm)
    }

    @Test
    fun aFinishedMediaPlaylistAddsUpItsSegments() {
        val vod = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10.0,\ns0.ts\n#EXTINF:10,\ns1.ts\n#EXTINF:4.5,\ns2.ts\n#EXT-X-ENDLIST\n"
        val info = PlaylistSniffer.parseHls(vod, "https://cdn.example.com/v/index.m3u8")
        assertFalse(info.master)
        assertTrue(info.ended)
        assertEquals(24_500L, info.durationMs)
        assertFalse(PlaylistSniffer.parseHls(vod.replace("#EXT-X-ENDLIST\n", ""), "https://cdn.example.com/v/index.m3u8").ended)
    }

    @Test
    fun keysTellClearAesFromDrm() {
        fun keyed(line: String) = PlaylistSniffer.parseHls("#EXTM3U\n$line\n#EXTINF:4,\ns.ts\n#EXT-X-ENDLIST", "https://cdn.example.com/i.m3u8")
        keyed("#EXT-X-KEY:METHOD=AES-128,URI=\"https://keys.example.com/k\"").let { assertTrue(it.aes128); assertFalse(it.drm) }
        assertTrue(keyed("#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key\",KEYFORMAT=\"com.apple.streamingkeydelivery\"").drm)
        assertTrue(keyed("#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"").drm)
        assertTrue(keyed("#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,KEYFORMAT=\"com.microsoft.playready\"").drm)
        keyed("#EXT-X-KEY:METHOD=NONE").let { assertFalse(it.aes128); assertFalse(it.drm) }
    }

    @Test
    fun dashManifestsSayLiveLengthDrmAndHeight() {
        val static = """<?xml version="1.0"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT1H2M3.5S">
              <Period><AdaptationSet mimeType="video/mp4"><Representation id="1" width="1920" height="1080"/><Representation id="2" height="720"/></AdaptationSet></Period>
            </MPD>"""
        assertEquals(DashInfo(live = false, durationMs = 3_723_500, drm = false, maxHeight = 1080), PlaylistSniffer.parseDash(static))
        val live = static.replace("type=\"static\"", "type=\"dynamic\"")
        assertEquals(DashInfo(live = true, durationMs = null, drm = false, maxHeight = 1080), PlaylistSniffer.parseDash(live))
        val protected = static.replace("<Representation id=\"1\"", "<ContentProtection schemeIdUri=\"urn:mpeg:dash:mp4protection:2011\" value=\"cenc\"/><Representation id=\"1\"")
        assertTrue(PlaylistSniffer.parseDash(protected).drm)
        assertTrue(PlaylistSniffer.parseDash(static.replace("<Period>", "<Period><dash:ContentProtection/>")).drm)
    }

    @Test
    fun isoDurations() {
        assertEquals(600_000L, PlaylistSniffer.isoDurationMs("PT10M"))
        assertEquals(86_401_000L, PlaylistSniffer.isoDurationMs("P1DT1S"))
        assertEquals(500L, PlaylistSniffer.isoDurationMs("pt0.5s"))
        assertNull(PlaylistSniffer.isoDurationMs("PT"))
        assertNull(PlaylistSniffer.isoDurationMs("10 minutes"))
    }

    @Test
    fun anMp4HeadGivesItsLengthAndItsProtection() {
        assertEquals(90_500L, PlaylistSniffer.mp4DurationMs(mp4Head(0, 1000, 90_500)))
        assertEquals(7_200_000L, PlaylistSniffer.mp4DurationMs(mp4Head(1, 90_000, 648_000_000)))
        assertNull(PlaylistSniffer.mp4DurationMs(mp4Head(0, 1000, 0xFFFFFFFFL)))
        assertNull(PlaylistSniffer.mp4DurationMs(mp4Head(0, 0, 1000)))
        assertNull(PlaylistSniffer.mp4DurationMs("#EXTM3U".toByteArray()))
        assertFalse(PlaylistSniffer.mp4Encrypted(mp4Head(0, 1000, 1000)))
        assertTrue(PlaylistSniffer.mp4Encrypted(mp4Head(0, 1000, 1000, extraBox = "pssh")))
    }

    @Test
    fun plainTextNamedLikeBoxesIsNotProtection() {
        val text = "competence sentence tenc encv enca pssh".toByteArray(Charsets.ISO_8859_1)
        val udta = mp4WithMoov(mp4Box("udta", mp4Box("meta", text)), mp4Box("free", text))
        assertFalse(PlaylistSniffer.mp4Encrypted(udta))
        assertFalse(PlaylistSniffer.mp4Encrypted(mp4Head(0, 1000, 1000) + mp4Box("free", "competence sentence".toByteArray())))
    }

    @Test
    fun realProtectionBoxesAreFound() {
        assertTrue(PlaylistSniffer.mp4Encrypted(mp4WithMoov(mp4Box("pssh", ByteArray(24)))))
        val encv = mp4Box("encv", ByteArray(78))
        val stsd = mp4Box("stsd", byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1) + encv)
        val tree = mp4Box("trak", mp4Box("mdia", mp4Box("minf", mp4Box("stbl", stsd))))
        assertTrue(PlaylistSniffer.mp4Encrypted(mp4WithMoov(tree)))
        val clear = mp4Box("stsd", byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1) + mp4Box("avc1", ByteArray(78)))
        assertFalse(PlaylistSniffer.mp4Encrypted(mp4WithMoov(mp4Box("trak", mp4Box("mdia", mp4Box("minf", mp4Box("stbl", clear)))))))
        val schm = mp4Box("schm", byteArrayOf(0, 0, 0, 0) + "cenc".toByteArray() + ByteArray(4))
        assertTrue(PlaylistSniffer.mp4Encrypted(mp4WithMoov(mp4Box("sinf", schm))))
        assertFalse(PlaylistSniffer.mp4Encrypted(mp4WithMoov(mp4Box("sinf", mp4Box("schm", byteArrayOf(0, 0, 0, 0) + "xxxx".toByteArray() + ByteArray(4))))))
    }

    @Test
    fun aTruncatedContainerIsStillWalked() {
        val whole = mp4WithMoov(mp4Box("pssh", ByteArray(24)))
        assertTrue(PlaylistSniffer.mp4Encrypted(whole.copyOf(whole.size - 10)))
    }

    @Test
    fun malformedInputNeverThrows() {
        val bad = "x".repeat(400).replace("x", "9")
        val cases: List<Pair<String, () -> Unit>> = listOf(
            "empty detect" to { assertEquals(MediaFormat.UNKNOWN, PlaylistSniffer.detect(ByteArray(0), null)) },
            "empty mp4 duration" to { assertNull(PlaylistSniffer.mp4DurationMs(ByteArray(0))) },
            "empty mp4 enc" to { assertFalse(PlaylistSniffer.mp4Encrypted(ByteArray(0))) },
            "unknown mvhd version" to { assertNull(PlaylistSniffer.mp4DurationMs(mp4Head(7, 1000, 1000))) },
            "empty hls" to { PlaylistSniffer.parseHls("", "https://a/b.m3u8").let { assertFalse(it.master); assertNull(it.durationMs) } },
            "garbage hls" to { PlaylistSniffer.parseHls("\u0000\uFFFF#EXTINF:\n#EXT-X-STREAM-INF", "not a url") },
            "unresolvable variant" to {
                assertEquals(emptyList<String>(), PlaylistSniffer.parseHls("#EXT-X-STREAM-INF:BANDWIDTH=1\nhttp://[bad host/x", "https://a/b.m3u8").variants)
            },
            "empty dash" to { assertEquals(DashInfo(false, null, false, null), PlaylistSniffer.parseDash("")) },
            "garbage dash" to { PlaylistSniffer.parseDash("<<<>>>\u0000 height=") },
            "unterminated MPD" to { assertFalse(PlaylistSniffer.parseDash("<MPD type=\"dynamic\"").live) },
            "huge EXTINF" to { assertNull(PlaylistSniffer.parseHls("#EXTINF:$bad.5,\ns.ts\n#EXT-X-ENDLIST", "https://a/b.m3u8").durationMs) },
            "huge ISO" to { assertNull(PlaylistSniffer.isoDurationMs("PT${bad}S")) },
            "huge ISO days" to { assertNull(PlaylistSniffer.isoDurationMs("P${bad}D")) },
        )
        for ((name, case) in cases) runCatching(case).onFailure { throw AssertionError("case '$name' failed", it) }
        for (version in 0..1) {
            val head = mp4Head(version, 1000, 1000)
            for (n in 0..head.size) {
                val prefix = head.copyOf(n)
                PlaylistSniffer.detect(prefix, null)
                PlaylistSniffer.mp4DurationMs(prefix)
                PlaylistSniffer.mp4Encrypted(prefix)
            }
        }
    }

    @Test
    fun overflowingDurationsAreNull() {
        assertNull(PlaylistSniffer.isoDurationMs("P8D"))
        assertEquals(604_800_000L, PlaylistSniffer.isoDurationMs("P7D"))
        assertNull(PlaylistSniffer.isoDurationMs("PT9999999999999999999S"))
        val hls = "#EXTM3U\n#EXTINF:400000,\na.ts\n#EXTINF:400000,\nb.ts\n"
        assertNull(PlaylistSniffer.parseHls(hls, "https://a/b.m3u8").durationMs)
        assertNull(PlaylistSniffer.mp4DurationMs(mp4Head(1, 1, Long.MAX_VALUE / 2000)))
    }

    @Test
    fun dashAcceptsSingleQuotesAndIgnoresCommentedProtection() {
        val mpd = "<MPD type='dynamic' mediaPresentationDuration='PT5S'><Representation height='480'/></MPD>"
        assertEquals(DashInfo(live = true, durationMs = null, drm = false, maxHeight = 480), PlaylistSniffer.parseDash(mpd))
        assertEquals(5_000L, PlaylistSniffer.parseDash(mpd.replace("dynamic", "static")).durationMs)
        val commented = "<MPD><!-- <ContentProtection schemeIdUri='x'/> --></MPD>"
        assertFalse(PlaylistSniffer.parseDash(commented).drm)
        assertTrue(PlaylistSniffer.parseDash("<MPD><!-- c --><ContentProtection/></MPD>").drm)
    }

    @Test
    fun anHtmlPageMentioningMpdIsHtml() {
        assertEquals(MediaFormat.HTML, detect("<!DOCTYPE html><html><body>use &lt;MPD or <MPD here</body></html>"))
        assertEquals(MediaFormat.HTML, detect("\uFEFF\n<html><MPD>"))
    }

    @Test
    fun manyUnterminatedCommentOpenersStayFast() {
        val text = "<MPD type=\"static\"><Representation height=\"720\"/>" + "<!--".repeat(50_000)
        val started = System.nanoTime()
        val info = PlaylistSniffer.parseDash(text)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        assertEquals(720, info.maxHeight)
        assertFalse(info.drm)
    }
}

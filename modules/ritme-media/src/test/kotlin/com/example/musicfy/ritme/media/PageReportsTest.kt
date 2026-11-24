package com.example.musicfy.ritme.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageReportsTest {

    private val full = """
        {"v":1,"page":"https://example.com/film/1","title":"Film | Example","ogTitle":"Film","site":"Example TV",
         "og":["https://cdn.example.com/og.mp4","javascript:alert(1)"],
         "ld":[{"url":"https://cdn.example.com/ld.m3u8","duration":"PT2M","name":"Klip"},{"url":null,"duration":null,"name":null}],
         "videos":[{"srcs":["https://cdn.example.com/a.mp4"],"blob":false,"duration":61.5,"w":1280,"h":720,"area":230400,"playing":true},
                   {"srcs":[],"blob":true,"duration":-1,"w":0,"h":0,"area":0,"playing":false}],
         "eme":true}
    """.trimIndent()

    @Test
    fun aFullReportIsRead() {
        val report = PageReports.parse(full, mainFrame = true)!!
        assertEquals("https://example.com/film/1", report.frameUrl)
        assertTrue(report.mainFrame)
        assertEquals("Film | Example", report.title)
        assertEquals("Film", report.ogTitle)
        assertEquals("Example TV", report.siteName)
        assertEquals(listOf("https://cdn.example.com/og.mp4"), report.ogVideos)
        assertEquals(listOf(PageLdVideo("https://cdn.example.com/ld.m3u8", 120_000, "Klip"), PageLdVideo(null, null, null)), report.ldVideos)
        assertEquals(PageVideo(listOf("https://cdn.example.com/a.mp4"), false, 61_500, false, 1280, 720, 230_400, true), report.videos[0])
        assertEquals(PageVideo(emptyList(), true, null, true, 0, 0, 0, false), report.videos[1])
        assertTrue(report.eme)
    }

    @Test
    fun theScriptShipsWithTheModuleAndTalksOnlyToItsBridge() {
        val script = PageMediaScript.source
        assertTrue(script.contains("window.${PageMediaScript.BRIDGE_NAME}"))
        assertTrue(script.contains("postMessage"))
        assertTrue(!script.contains("XMLHttpRequest") && !script.contains("fetch("))
    }

    @Test
    fun anythingElseIsDropped() {
        assertNull(PageReports.parse("not json", true))
        assertNull(PageReports.parse("[1,2]", true))
        assertNull(PageReports.parse("""{"v":2}""", true))
        assertNull(PageReports.parse("""{"v":"1"}""", true))
        assertNull(PageReports.parse("""{"v":1,"title":"${"a".repeat(PageReports.MAX_MESSAGE_CHARS)}"}""", true))
    }

    @Test
    fun pageDataIsBoundedAndCheckedAgain() {
        // one overlong source only, so the whole message stays under MAX_MESSAGE_CHARS
        val tooLong = "https://cdn.example.com/" + "a".repeat(2100)
        val videos = (1..30).joinToString(",") { i ->
            val extra = if (i == 1) ""","$tooLong"""" else ""
            """{"srcs":["https://cdn.example.com/$i.mp4","data:x"$extra],"area":1e12,"w":99999,"h":-5}"""
        }
        val report = PageReports.parse("""{"v":1,"title":"${"t".repeat(400)}","videos":[$videos],"eme":"yes"}""", mainFrame = false)!!
        assertEquals(PageReports.MAX_ITEMS, report.videos.size)
        assertEquals(listOf("https://cdn.example.com/1.mp4"), report.videos[0].urls)
        assertEquals(100_000_000L, report.videos[0].area)
        assertEquals(16_384, report.videos[0].width)
        assertEquals(0, report.videos[0].height)
        assertEquals(300, report.title!!.length)
        assertEquals(false, report.eme)
        assertNull(report.frameUrl)
    }
}

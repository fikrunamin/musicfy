package com.music.innertube

import com.music.innertube.pages.WebVideoItem
import com.music.innertube.pages.WebVideoSearchPage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebVideoSearchPageTest {

    // Trimmed from a real WEB search response (sp=EgIQAQ%3D%3D): a shelf, an ad slot, a video, a live stream.
    private val response = """
        {"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
          {"itemSectionRenderer":{"contents":[
            {"adSlotRenderer":{}},
            {"videoRenderer":{"videoId":"dQw4w9WgXcQ",
              "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg?sqp=abc","width":480,"height":270}]},
              "title":{"runs":[{"text":"Rick Astley - Never Gonna Give You Up"}]},
              "ownerText":{"runs":[{"text":"Rick Astley"}]},
              "lengthText":{"simpleText":"3:33"}}},
            {"videoRenderer":{"videoId":"jfKfPfyJRdk",
              "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/jfKfPfyJRdk/hq720_live.jpg"}]},
              "title":{"runs":[{"text":"lofi hip hop radio"}]},
              "longBylineText":{"runs":[{"text":"Lofi Girl"}]},
              "badges":[{"metadataBadgeRenderer":{"style":"BADGE_STYLE_TYPE_LIVE_NOW","label":"LIVE"}}]}}
          ]}},
          {"continuationItemRenderer":{}}
        ]}}}}}
    """.trimIndent()

    @Test
    fun videosAndLiveStreamsAreRead() {
        val items = WebVideoSearchPage.parse(Json.parseToJsonElement(response).jsonObject)
        assertEquals(
            listOf(
                WebVideoItem("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", "Rick Astley", 213, "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", live = false),
                WebVideoItem("jfKfPfyJRdk", "lofi hip hop radio", "Lofi Girl", null, "https://i.ytimg.com/vi/jfKfPfyJRdk/hq720_live.jpg", live = true),
            ),
            items,
        )
    }

    @Test
    fun lengths() {
        assertEquals(213, WebVideoSearchPage.parseLength("3:33"))
        assertEquals(3_723, WebVideoSearchPage.parseLength("1:02:03"))
        assertNull(WebVideoSearchPage.parseLength("LIVE"))
        assertNull(WebVideoSearchPage.parseLength(null))
    }
}

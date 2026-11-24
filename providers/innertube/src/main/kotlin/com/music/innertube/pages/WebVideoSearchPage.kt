package com.music.innertube.pages

import com.music.innertube.models.Thumbnail
import com.music.innertube.models.firstAtLeast
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A video from YouTube's general search (client WEB), for Ritme's "Add video" (spec U3). */
data class WebVideoItem(
    val id: String,
    val title: String,
    val channel: String,
    val durationSeconds: Int?,
    val thumbnailUrl: String?,
    val live: Boolean,
)

/** Reads `twoColumnSearchResultsRenderer` → `itemSectionRenderer` → `videoRenderer`; everything else (ads, shelves) is skipped. */
object WebVideoSearchPage {

    /** Ritme's rows show a result at 78 x 44 dp and every member loads it: the first this wide (Thumbnail.firstAtLeast). */
    const val THUMBNAIL_MIN_WIDTH = 320

    fun parse(root: JsonObject): List<WebVideoItem> {
        val sections = root.obj("contents")?.obj("twoColumnSearchResultsRenderer")?.obj("primaryContents")
            ?.obj("sectionListRenderer")?.arr("contents").orEmpty()
        return sections
            .flatMap { (it as? JsonObject)?.obj("itemSectionRenderer")?.arr("contents").orEmpty() }
            .mapNotNull { (it as? JsonObject)?.obj("videoRenderer")?.let(::video) }
    }

    private fun video(r: JsonObject): WebVideoItem? {
        val id = r.str("videoId") ?: return null
        val title = r.obj("title")?.firstRun() ?: r.obj("title")?.str("simpleText") ?: return null
        val channel = (r.obj("ownerText") ?: r.obj("longBylineText"))?.firstRun().orEmpty()
        val length = r.obj("lengthText")?.str("simpleText")
        val liveBadge = r.arr("badges").orEmpty().any {
            (it as? JsonObject)?.obj("metadataBadgeRenderer")?.str("style") == "BADGE_STYLE_TYPE_LIVE_NOW"
        }
        val seconds = parseLength(length)
        val live = liveBadge || seconds == null
        val thumbnails = r.obj("thumbnail")?.arr("thumbnails").orEmpty().mapNotNull { t ->
            val o = t as? JsonObject ?: return@mapNotNull null
            Thumbnail(o.str("url") ?: return@mapNotNull null, o.int("width"), o.int("height"))
        }
        val thumbnail = thumbnails.firstAtLeast(THUMBNAIL_MIN_WIDTH)?.url?.substringBefore('?')
        return WebVideoItem(id, title, channel, if (live) null else seconds, thumbnail, live)
    }

    /** "3:33" → 213, "1:02:03" → 3723; anything else null. */
    fun parseLength(text: String?): Int? {
        val parts = text?.split(':') ?: return null
        if (parts.size !in 2..3) return null
        return parts.fold(0) { acc, part -> acc * 60 + (part.toIntOrNull() ?: return null) }
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull()
    private fun JsonObject.firstRun(): String? = (arr("runs")?.firstOrNull() as? JsonObject)?.str("text")
}

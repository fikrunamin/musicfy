package com.example.musicfy.ritme.media

import com.example.musicfy.ritme.protocol.MediaUrlRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** One `<video>` as a frame reported it. */
data class PageVideo(
    /** Its http(s) sources (currentSrc, src, `<source>`s), at most 5. */
    val urls: List<String>,
    /** It plays a `blob:` (MSE): the stream behind it is only visible to the request sniffer. */
    val blob: Boolean,
    val durationMs: Long?,
    val live: Boolean,
    val width: Int,
    val height: Int,
    /** On-screen size in CSS px². */
    val area: Long,
    val playing: Boolean,
)

data class PageLdVideo(val url: String?, val durationMs: Long?, val name: String?)

/** A frame's report from page-media.js, checked again here: the page is untrusted. */
data class PageReport(
    val frameUrl: String?,
    val mainFrame: Boolean,
    val title: String?,
    val ogTitle: String?,
    val siteName: String?,
    val ogVideos: List<String>,
    val ldVideos: List<PageLdVideo>,
    val videos: List<PageVideo>,
    val eme: Boolean,
)

object PageReports {
    const val MAX_MESSAGE_CHARS = 64_000
    const val MAX_ITEMS = 20
    private const val MAX_SRCS = 5
    private const val MAX_TEXT = 300
    private const val MAX_DURATION_MS = 86_400_000L
    private const val MAX_SIDE = 16_384
    private const val MAX_AREA = 100_000_000L

    /** [message] as a report, or null when it is not one (too long, not JSON, another version). */
    fun parse(message: String, mainFrame: Boolean): PageReport? {
        if (message.length > MAX_MESSAGE_CHARS) return null
        val root = runCatching { Json.parseToJsonElement(message) }.getOrNull() as? JsonObject ?: return null
        if (root.number("v") != 1.0) return null
        return PageReport(
            frameUrl = address(root["page"]),
            mainFrame = mainFrame,
            title = root.text("title"),
            ogTitle = root.text("ogTitle"),
            siteName = root.text("site"),
            ogVideos = root.list("og").mapNotNull(::address).distinct().take(MAX_ITEMS),
            ldVideos = root.list("ld").mapNotNull { it as? JsonObject }.take(MAX_ITEMS).map { ld ->
                PageLdVideo(address(ld["url"]), ld.text("duration")?.let(PlaylistSniffer::isoDurationMs)?.coerceAtMost(MAX_DURATION_MS), ld.text("name"))
            },
            videos = root.list("videos").mapNotNull { it as? JsonObject }.take(MAX_ITEMS).map(::video),
            eme = root.flag("eme"),
        )
    }

    private fun video(o: JsonObject): PageVideo {
        val seconds = o.number("duration")
        return PageVideo(
            urls = o.list("srcs").mapNotNull(::address).distinct().take(MAX_SRCS),
            blob = o.flag("blob"),
            durationMs = seconds?.takeIf { it > 0 && it.isFinite() }?.let { (it * 1000).toLong().coerceAtMost(MAX_DURATION_MS) },
            live = seconds == -1.0,
            width = (o.number("w") ?: 0.0).toInt().coerceIn(0, MAX_SIDE),
            height = (o.number("h") ?: 0.0).toInt().coerceIn(0, MAX_SIDE),
            area = (o.number("area") ?: 0.0).toLong().coerceIn(0, MAX_AREA),
            playing = o.flag("playing"),
        )
    }

    private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

    private fun JsonObject.text(key: String): String? =
        primitive(key)?.takeIf { it.isString }?.content?.trim()?.take(MAX_TEXT)?.takeIf { it.isNotEmpty() }

    private fun JsonObject.number(key: String): Double? = primitive(key)?.takeUnless { it.isString }?.doubleOrNull

    private fun JsonObject.flag(key: String): Boolean = primitive(key)?.takeUnless { it.isString }?.booleanOrNull ?: false

    private fun JsonObject.list(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

    private fun address(element: JsonElement?): String? {
        val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val lower = value.lowercase()
        if (value.length > MediaUrlRules.MAX_URL_LENGTH || !(lower.startsWith("https://") || lower.startsWith("http://"))) return null
        return value
    }
}

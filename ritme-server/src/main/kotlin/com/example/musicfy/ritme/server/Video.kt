package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.RitmeJson
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import java.net.URI

/** GET /api/video's answer: what the web needs to preview and add a YOUTUBE_VIDEO entry. */
@Serializable
data class VideoInfo(
    val videoId: String,
    val title: String,
    /** First run of the byline: the channel (or artist). */
    val channel: String,
    /** 0 for a live stream (see [InnerTubeVideoParser.lengthMs]). */
    val durationMs: Long,
    /** Only on a YouTube image host ([isYouTubeImageUrl]), else null. */
    val thumbnailUrl: String? = null,
    val live: Boolean,
)

/** Returns the raw InnerTube `music/get_queue` JSON for one video. */
fun interface VideoBackend {
    suspend fun fetch(videoId: String): String
}

/** One InnerTube `music/get_queue` call for one video (spec D5). Metadata only: never a stream URL. */
class InnerTubeVideoBackend(private val http: HttpClient, private val url: String = URL) : VideoBackend {
    override suspend fun fetch(videoId: String): String {
        val response = http.post(url) {
            innerTubeHeaders()
            setBody(TextContent(requestBody(videoId), ContentType.Application.Json))
        }
        // YouTube answers an unknown id with HTTP 404 {"error":{"code":404,"status":"NOT_FOUND"}}
        // (400 for some malformed ones), not an empty queue. "{}" reads as "no such video": the
        // endpoint answers 404 and the miss is cached like one.
        if (response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.BadRequest) return "{}"
        check(response.status.isSuccess()) { "InnerTube get_queue failed: ${response.status.value}" }
        return response.bodyAsText()
    }

    companion object {
        const val URL = "https://music.youtube.com/youtubei/v1/music/get_queue?prettyPrint=false"

        fun requestBody(videoId: String): String = buildJsonObject {
            putWebRemixContext()
            putJsonArray("videoIds") { add(videoId) }
        }.toString()
    }
}

/** Reads `queueDatas[0].content` like the app's NextPage.fromPlaylistPanelVideoRenderer. */
object InnerTubeVideoParser {
    /** A length this long or longer is taken for a live stream (see [lengthMs]). */
    const val LIVE_FROM_MS = 12 * 60 * 60_000L

    /**
     * The length of a video, or null for a live stream. get_queue has no live marker: a live
     * stream's renderer has the same keys as a video's, with the time since it started as
     * lengthText (e.g. "33,778:11:52", checked 2026-11-23). So a length counts only when it
     * parses as m:ss or h:mm:ss and is above 0 and under 12 h; anything else means live.
     */
    fun lengthMs(text: String?): Long? {
        if (text == null || !InnerTubeSearchParser.DURATION.matches(text)) return null
        return InnerTubeSearchParser.parseDuration(text).takeIf { it in 1 until LIVE_FROM_MS }
    }

    /** The video [videoId], or null when YouTube returned none (private, removed, unavailable) or another one. */
    fun parse(json: String, videoId: String): VideoInfo? {
        val content = RitmeJson.parseToJsonElement(json).at("queueDatas", 0, "content")
        val renderer = content.at("playlistPanelVideoRenderer")
            ?: content.at("playlistPanelVideoWrapperRenderer", "primaryRenderer", "playlistPanelVideoRenderer")
            ?: return null
        if (renderer.at("videoId").asString() != videoId) return null
        val title = renderer.at("title", "runs", 0, "text").asString()?.takeIf { it.isNotBlank() } ?: return null
        val channel = renderer.at("longBylineText", "runs", 0, "text").asString().orEmpty()
        val durationMs = lengthMs(renderer.at("lengthText", "runs", 0, "text").asString()) ?: 0L
        val thumbnail = renderer.at("thumbnail", "thumbnails").asArray().lastOrNull()?.at("url").asString()
            ?.takeIf(::isYouTubeImageUrl)
        return VideoInfo(videoId, title, channel, durationMs, thumbnail, live = durationMs == 0L)
    }
}

/**
 * Cached, single-flight video info on the [budget] search uses too. Videos stay 6 h; live ones
 * (their status can change) and missing ones (so a bogus id cannot drain the budget) 5 min.
 */
class VideoInfoService(
    private val backend: VideoBackend,
    clock: Clock,
    budget: UpstreamBudget = UpstreamBudget(clock = clock),
    maxEntries: Int = 1_000,
) {
    private val cache = SingleFlightCache<String, VideoInfo?>(clock, budget, maxEntries) { info ->
        if (info == null || info.live) SHORT_TTL_MS else TTL_MS
    }

    suspend fun lookup(videoId: String): VideoInfo? =
        cache.get(videoId) { InnerTubeVideoParser.parse(backend.fetch(videoId), videoId) }

    companion object {
        const val TTL_MS = 6 * 60 * 60_000L
        const val SHORT_TTL_MS = 5 * 60_000L
        private val ID = Regex("^[A-Za-z0-9_-]{11}$")

        fun isVideoId(id: String): Boolean = ID.matches(id)
    }
}

private val YOUTUBE_IMAGE_HOST_SUFFIXES = listOf(".ytimg.com", ".googleusercontent.com", ".ggpht.com")

/** https, at most 512 chars, on a YouTube image host: what RoomLogic accepts as a YouTube entry's thumbnail. */
internal fun isYouTubeImageUrl(url: String): Boolean {
    if (url.length > Limits.MAX_THUMBNAIL_URL_LENGTH || !url.startsWith("https://")) return false
    val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
    return host == "i.ytimg.com" || YOUTUBE_IMAGE_HOST_SUFFIXES.any { host.endsWith(it) }
}

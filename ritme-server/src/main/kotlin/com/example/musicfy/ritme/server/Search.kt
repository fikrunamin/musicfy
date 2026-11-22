package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.RitmeJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class SearchResult(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val thumbnailUrl: String? = null,
)

/** Returns the raw InnerTube search JSON for [query]. */
fun interface SearchBackend {
    suspend fun fetch(query: String): String
}

fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) { requestTimeoutMillis = 10_000 }
}

/** One InnerTube `search` call, songs only. Metadata only: no stream URLs are ever requested. */
class InnerTubeSearchBackend(private val http: HttpClient) : SearchBackend {
    override suspend fun fetch(query: String): String {
        val response = http.post(URL) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header("X-Goog-Api-Format-Version", "1")
            header("X-YouTube-Client-Name", CLIENT_ID)
            header("X-YouTube-Client-Version", CLIENT_VERSION)
            header("X-Origin", ORIGIN)
            header(HttpHeaders.Referrer, "$ORIGIN/")
            setBody(TextContent(requestBody(query), ContentType.Application.Json))
        }
        check(response.status.isSuccess()) { "InnerTube search failed: ${response.status.value}" }
        return response.bodyAsText()
    }

    companion object {
        const val URL = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
        const val ORIGIN = "https://music.youtube.com"
        const val CLIENT_NAME = "WEB_REMIX"
        const val CLIENT_ID = "67"

        // Keep in sync with providers/innertube YouTubeClient.WEB_REMIX.
        const val CLIENT_VERSION = "1.20260707.12.00"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        // providers/innertube YouTube.SearchFilter.FILTER_SONG
        const val SONGS_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"

        fun requestBody(query: String): String = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", CLIENT_NAME)
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                }
            }
            put("query", query)
            put("params", SONGS_FILTER)
        }.toString()
    }
}

object InnerTubeSearchParser {
    private val durationPattern = Regex("""^\d{1,2}(:\d{2}){1,2}$""")
    private const val ARTIST_PAGE = "MUSIC_PAGE_TYPE_ARTIST"

    fun parse(json: String): List<SearchResult> {
        val sections = RitmeJson.parseToJsonElement(json)
            .at("contents", "tabbedSearchResultsRenderer", "tabs", 0, "tabRenderer", "content", "sectionListRenderer", "contents")
            .asArray()
        return sections
            .flatMap { it.at("musicShelfRenderer", "contents").asArray() }
            .mapNotNull { item -> item.at("musicResponsiveListItemRenderer")?.let(::parseItem) }
    }

    fun parseDuration(text: String): Long = text.split(':').fold(0L) { acc, part -> acc * 60 + part.toLong() } * 1_000

    private fun parseItem(item: JsonElement): SearchResult? {
        val videoId = item.at("playlistItemData", "videoId").asString() ?: return null
        val title = column(item, 0).firstOrNull()?.at("text").asString() ?: return null
        val details = column(item, 1)
        val artists = details
            .filter {
                it.at("navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType")
                    .asString() == ARTIST_PAGE
            }
            .mapNotNull { it.at("text").asString() }
        val artist = artists.ifEmpty { listOfNotNull(details.firstOrNull()?.at("text").asString()) }.joinToString(", ")
        val duration = details.mapNotNull { it.at("text").asString() }.lastOrNull { durationPattern.matches(it) } ?: return null
        val thumbnail = item.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails").asArray().lastOrNull()?.at("url").asString()
        return SearchResult(videoId, title, artist, parseDuration(duration), thumbnail)
    }

    private fun column(item: JsonElement, index: Int): List<JsonElement> =
        item.at("flexColumns", index, "musicResponsiveListItemFlexColumnRenderer", "text", "runs").asArray()

    private fun JsonElement?.at(vararg path: Any): JsonElement? = path.fold(this) { node, key ->
        when (key) {
            is String -> (node as? JsonObject)?.get(key)
            is Int -> (node as? JsonArray)?.getOrNull(key)
            else -> null
        }
    }

    private fun JsonElement?.asArray(): List<JsonElement> = (this as? JsonArray).orEmpty()

    private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}

class SearchService(
    private val backend: SearchBackend,
    private val clock: Clock,
    private val ttlMs: Long = 10 * 60_000L,
    private val maxEntries: Int = 1_000,
) {
    private class Cached(val at: Long, val results: List<SearchResult>)

    private val cache = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>): Boolean = size > maxEntries
    }

    suspend fun search(query: String): List<SearchResult> {
        val key = query.trim().lowercase()
        synchronized(cache) {
            cache[key]?.takeIf { clock.now() - it.at < ttlMs }?.let { return it.results }
        }
        val results = InnerTubeSearchParser.parse(backend.fetch(query.trim())).take(MAX_RESULTS)
        synchronized(cache) { cache[key] = Cached(clock.now(), results) }
        return results
    }

    companion object {
        const val MAX_RESULTS = 20
    }
}

class IpRateLimiter(private val clock: Clock, private val perMinute: Int = Limits.SEARCH_PER_MINUTE) {
    private val buckets = ConcurrentHashMap<String, RateLimiter>()

    fun tryAcquire(ip: String): Boolean {
        if (buckets.size > MAX_TRACKED_IPS) buckets.clear()
        val limiter = buckets.computeIfAbsent(ip) { RateLimiter(perMinute, perMinute / 60.0, clock) }
        return synchronized(limiter) { limiter.tryAcquire() }
    }

    private companion object {
        const val MAX_TRACKED_IPS = 10_000
    }
}

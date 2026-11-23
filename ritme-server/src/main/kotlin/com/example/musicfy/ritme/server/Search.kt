package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.RitmeJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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

/** The WEB_REMIX client's headers, as YouTube Music's web app sends them (search and video info). */
internal fun HttpRequestBuilder.innerTubeHeaders() {
    header(HttpHeaders.UserAgent, InnerTubeSearchBackend.USER_AGENT)
    header("X-Goog-Api-Format-Version", "1")
    header("X-YouTube-Client-Name", InnerTubeSearchBackend.CLIENT_ID)
    header("X-YouTube-Client-Version", InnerTubeSearchBackend.CLIENT_VERSION)
    header("X-Origin", InnerTubeSearchBackend.ORIGIN)
    header(HttpHeaders.Referrer, "${InnerTubeSearchBackend.ORIGIN}/")
}

/** `"context": {"client": {WEB_REMIX, hl en, gl US}}`, the first key of every request body. */
internal fun JsonObjectBuilder.putWebRemixContext() {
    putJsonObject("context") {
        putJsonObject("client") {
            put("clientName", InnerTubeSearchBackend.CLIENT_NAME)
            put("clientVersion", InnerTubeSearchBackend.CLIENT_VERSION)
            put("hl", "en")
            put("gl", "US")
        }
    }
}

/** One InnerTube `search` call, songs only. Metadata only: no stream URLs are ever requested. */
class InnerTubeSearchBackend(private val http: HttpClient) : SearchBackend {
    override suspend fun fetch(query: String): String {
        val response = http.post(URL) {
            innerTubeHeaders()
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
            putWebRemixContext()
            put("query", query)
            put("params", SONGS_FILTER)
        }.toString()
    }
}

object InnerTubeSearchParser {
    /** `m:ss` or `h:mm:ss`, as InnerTube writes lengths. */
    internal val DURATION = Regex("""^\d{1,2}(:\d{2}){1,2}$""")
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
        val duration = details.mapNotNull { it.at("text").asString() }.lastOrNull { DURATION.matches(it) } ?: return null
        val thumbnail = item.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails").asArray().lastOrNull()?.at("url").asString()
        return SearchResult(videoId, title, artist, parseDuration(duration), thumbnail)
    }

    private fun column(item: JsonElement, index: Int): List<JsonElement> =
        item.at("flexColumns", index, "musicResponsiveListItemFlexColumnRenderer", "text", "runs").asArray()
}

/**
 * Cached, single-flight search: identical uncached queries running at the same time share one
 * upstream call, and every uncached query spends a token of the [budget] that video info shares.
 */
class SearchService(
    private val backend: SearchBackend,
    clock: Clock,
    ttlMs: Long = 10 * 60_000L,
    maxEntries: Int = 1_000,
    globalPerMinute: Int = Limits.SEARCH_GLOBAL_PER_MINUTE,
    budget: UpstreamBudget = UpstreamBudget(globalPerMinute, clock),
) {
    private val cache = SingleFlightCache<String, List<SearchResult>>(clock, budget, maxEntries) { ttlMs }

    suspend fun search(query: String): List<SearchResult> {
        val trimmed = query.trim()
        return cache.get(trimmed.lowercase()) { InnerTubeSearchParser.parse(backend.fetch(trimmed)).take(MAX_RESULTS) }
    }

    companion object {
        const val MAX_RESULTS = 20
    }
}

/**
 * Token bucket per client: IPv4 address or IPv6 /64 (see [ipKey]); [burst] tokens, refilled at
 * [perMinute]. Buckets idle long enough to have refilled are dropped (nothing is forgotten);
 * beyond [maxTracked] the least recently used goes first, so active clients keep their state.
 */
class IpRateLimiter(
    private val clock: Clock,
    private val perMinute: Int = Limits.SEARCH_PER_MINUTE,
    private val burst: Int = perMinute,
    private val idleMs: Long = maxOf(2 * 60_000L, burst * 60_000L / perMinute + 60_000L),
    private val maxTracked: Int = 10_000,
) {
    private class Bucket(val limiter: RateLimiter, var lastSeen: Long)

    private val buckets = LinkedHashMap<String, Bucket>(16, 0.75f, true) // access order: LRU first

    val tracked: Int get() = synchronized(buckets) { buckets.size }

    fun tryAcquire(remoteHost: String): Boolean = synchronized(buckets) { bucket(remoteHost).tryAcquire() }

    /** True when [tryAcquire] would succeed; spends nothing. */
    fun hasToken(remoteHost: String): Boolean = synchronized(buckets) { bucket(remoteHost).hasToken() }

    private fun bucket(remoteHost: String): RateLimiter {
        val now = clock.now()
        val key = ipKey(remoteHost)
        val idle = buckets.values.iterator()
        while (idle.hasNext()) {
            if (now - idle.next().lastSeen > idleMs) idle.remove() else break
        }
        val bucket = buckets[key] ?: run {
            if (buckets.size >= maxTracked) buckets.values.iterator().apply { next(); remove() }
            Bucket(RateLimiter(burst, perMinute / 60.0, clock), now).also { buckets[key] = it }
        }
        bucket.lastSeen = now
        return bucket.limiter
    }
}

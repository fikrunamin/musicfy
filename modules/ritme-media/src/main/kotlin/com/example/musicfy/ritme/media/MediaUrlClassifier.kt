package com.example.musicfy.ritme.media

import com.example.musicfy.ritme.protocol.MediaUrlRules
import com.example.musicfy.ritme.protocol.StreamType

/** What one request the in-app browser saw says about the page's video (spec 4.7 step 1). */
sealed interface Sniffed {
    /** A video or stream worth offering; [mediaRequest]: known only from a media element's request headers. */
    data class Media(val url: String, val streamType: StreamType, val mediaRequest: Boolean) : Sniffed

    /** A piece of a stream (.ts, .m4s, …): the page plays a stream, but this is not one to offer. */
    data object Segment : Sniffed

    data object Ignored : Sniffed
}

/**
 * Layer 1 of the detection (spec 4.7): a request's url and headers → a candidate, a segment, or
 * nothing. Only GETs; YouTube's own hosts (offered as YOUTUBE_VIDEO instead), ad hosts and urls the
 * server would refuse (MediaUrlRules) are never candidates. Fragments are dropped.
 */
object MediaUrlClassifier {
    private val FILE_EXTENSIONS = setOf("mp4", "m4v", "webm", "mov", "ogv", "mkv")
    private val SEGMENT_EXTENSIONS = setOf("ts", "m4s", "aac", "key", "vtt")
    private val NOT_MEDIA_EXTENSIONS = setOf(
        "json", "js", "css", "html", "htm", "xml", "webmanifest", "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "woff", "woff2",
    )
    private val YOUTUBE_DOMAINS = listOf("youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com", "ytimg.com")

    fun classify(url: String, method: String = "GET", headers: Map<String, String> = emptyMap()): Sniffed {
        if (!method.equals("GET", ignoreCase = true)) return Sniffed.Ignored
        val clean = url.substringBefore('#')
        val host = hostOf(clean) ?: return Sniffed.Ignored
        if (isYouTubeHost(host) || AdHosts.isAd(host)) return Sniffed.Ignored
        val extension = extensionOf(clean)
        if (extension in SEGMENT_EXTENSIONS) return Sniffed.Segment
        val byName = streamTypeOf(clean)
        val mediaRequest = byName == null && extension !in NOT_MEDIA_EXTENSIONS && isMediaElementRequest(headers)
        if (byName == null && !mediaRequest) return Sniffed.Ignored
        if (MediaUrlRules.check(clean) != null) return Sniffed.Ignored
        return Sniffed.Media(clean, byName ?: StreamType.UNKNOWN, mediaRequest)
    }

    /**
     * What [url]'s name says: the extension, query hints (`format=m3u8`, `type=hls`,
     * `mime=video/…`), or a stream-ish path (`/manifest`, `.urlset/`) as UNKNOWN (the probe decides).
     * Null when nothing hints at media.
     */
    fun streamTypeOf(url: String): StreamType? {
        val clean = url.substringBefore('#')
        when (extensionOf(clean)) {
            "m3u8" -> return StreamType.HLS
            "mpd" -> return StreamType.DASH
            in FILE_EXTENSIONS -> return StreamType.FILE
            in NOT_MEDIA_EXTENSIONS -> return null
        }
        for ((name, value) in UrlParts.query(clean)) {
            when (name) {
                "format", "type", "manifest_type", "protocol" -> when (value) {
                    "m3u8", "hls" -> return StreamType.HLS
                    "mpd", "dash" -> return StreamType.DASH
                }
                "mime", "mimetype", "mime_type" -> when {
                    "mpegurl" in value -> return StreamType.HLS
                    "dash+xml" in value -> return StreamType.DASH
                    value.startsWith("video/") -> return StreamType.FILE
                }
            }
        }
        val path = UrlParts.path(clean)
        val segments = path.split('/')
        if (segments.any { it == "manifest" || it.startsWith("manifest(") } || ".urlset/" in path) return StreamType.UNKNOWN
        return null
    }

    /** Chromium's media element asks for `Range: bytes=0-` with `Accept-Encoding: identity;q=1, *;q=0`. */
    fun isMediaElementRequest(headers: Map<String, String>): Boolean {
        val lower = headers.entries.associate { it.key.lowercase() to it.value.trim().lowercase() }
        return lower["range"] == "bytes=0-" && lower["accept-encoding"]?.replace(" ", "") == "identity;q=1,*;q=0"
    }

    fun isYouTubeHost(host: String): Boolean {
        val name = host.lowercase().removeSuffix(".")
        return YOUTUBE_DOMAINS.any { name == it || name.endsWith(".$it") }
    }

    fun hostOf(url: String): String? = UrlParts.host(url)

    fun extensionOf(url: String): String = UrlParts.extension(url)

    /** The last path segment, percent-decoded and trimmed; null when empty. */
    fun fileNameOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        val rest = if (schemeEnd < 0) url else url.substring(schemeEnd + 3)
        val last = rest.dropWhile { it != '/' && it != '?' && it != '#' }.substringBefore('#').substringBefore('?').substringAfterLast('/')
        return UrlParts.decode(last).trim().takeIf { it.isNotEmpty() }
    }
}

package com.example.musicfy.ritme.protocol

import java.net.URI
import java.net.URLDecoder

/** A YouTube video named by a pasted link; [music] for a music.youtube.com link (added as a song). */
data class YouTubeLink(val videoId: String, val music: Boolean)

/**
 * The video in a YouTube link (spec 4.1): watch?v=, youtu.be/, /shorts/, /live/, /embed/ on
 * youtube.com (www., m., music.) and youtube-nocookie.com, and vnd.youtube:. A host may end in one
 * dot, path keywords match in any case, the id is case-sensitive; t= and list= are ignored and
 * /embed/videoseries is a playlist. Kotlin twin of ritme-web's parseYouTubeLink, checked against the
 * same fixture (fixtures/rules/youtube-links.json).
 */
object YouTubeLinks {
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val HAS_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val VND = Regex("^vnd\\.youtube:(?://)?([A-Za-z0-9_-]{11})(?:[?&#].*)?$", RegexOption.IGNORE_CASE)
    private val HOSTS = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
        "youtube-nocookie.com", "www.youtube-nocookie.com", "youtu.be",
    )
    private val PATH_ID_PREFIXES = setOf("shorts", "live", "embed")
    private const val PLAYLIST_EMBED = "videoseries"

    fun parse(raw: String): YouTubeLink? {
        val text = raw.trim()
        VND.matchEntire(text)?.let { return YouTubeLink(it.groupValues[1], music = false) }
        if (text.isEmpty()) return null
        val uri = runCatching { URI(if (HAS_SCHEME.containsMatchIn(text)) text else "https://$text") }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host?.lowercase()?.removeSuffix(".") ?: return null
        if (host !in HOSTS) return null
        val segments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }
        val keyword = segments.firstOrNull()?.lowercase()
        val id = when {
            host == "youtu.be" -> segments.singleOrNull()
            segments.size == 1 && keyword == "watch" -> queryParam(uri.rawQuery, "v")
            segments.size == 2 && keyword in PATH_ID_PREFIXES ->
                segments[1].takeUnless { keyword == "embed" && it.lowercase() == PLAYLIST_EMBED }
            else -> null
        }
        return id?.takeIf(VIDEO_ID::matches)?.let { YouTubeLink(it, music = host == "music.youtube.com") }
    }

    private fun queryParam(rawQuery: String?, name: String): String? =
        rawQuery?.split('&')?.firstNotNullOfOrNull { part ->
            if (part.substringBefore('=') == name) URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8) else null
        }
}

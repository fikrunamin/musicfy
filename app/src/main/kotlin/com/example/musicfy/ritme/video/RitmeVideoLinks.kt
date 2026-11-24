package com.example.musicfy.ritme.video

import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.protocol.MediaUrlRules
import com.example.musicfy.ritme.protocol.YouTubeLinks
import java.net.URI
import java.net.URLDecoder

/** What "Add video" makes of pasted text (spec 4.6). */
sealed interface VideoLink {
    data class YouTube(val videoId: String, val music: Boolean) : VideoLink

    /** A media file or stream; [streaming] for HLS/DASH (no file length to read). */
    data class Direct(val url: String, val fileName: String, val domain: String, val streaming: Boolean) : VideoLink

    enum class Problem { UNRECOGNIZED, PAGE, UNSAFE }

    data class Rejected(val problem: Problem) : VideoLink
}

object RitmeVideoLinks {
    private val HAS_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val FILE = Regex("\\.(mp4|webm|m4v|mov)$", RegexOption.IGNORE_CASE)
    private val STREAM = Regex("\\.(m3u8|mpd)$", RegexOption.IGNORE_CASE)

    /** The link in [raw], or null when it is words (then the sheet searches for them). */
    fun classify(raw: String): VideoLink? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        YouTubeLinks.parse(text)?.let { return VideoLink.YouTube(it.videoId, it.music) }
        val hasScheme = HAS_SCHEME.containsMatchIn(text)
        // "lofi" or "never gonna give you up" are a search; "example.com/v.mp4" is a link
        val looksLikeLink = text.none(Char::isWhitespace) && (hasScheme || text.substringBefore('/').contains('.'))
        if (!looksLikeLink) return null
        val candidate = (if (hasScheme) text else "https://$text").substringBefore('#')
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return VideoLink.Rejected(VideoLink.Problem.UNRECOGNIZED)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return VideoLink.Rejected(VideoLink.Problem.UNRECOGNIZED)
        val path = uri.rawPath.orEmpty()
        val streaming = STREAM.containsMatchIn(path)
        if (!streaming && !FILE.containsMatchIn(path)) return VideoLink.Rejected(VideoLink.Problem.PAGE)
        if (MediaUrlRules.check(candidate) != null) return VideoLink.Rejected(VideoLink.Problem.UNSAFE)
        val domain = uri.host.orEmpty().lowercase().removeSuffix(".").removePrefix("www.")
        val last = path.substringAfterLast('/')
        val fileName = runCatching { URLDecoder.decode(last, Charsets.UTF_8) }.getOrDefault(last).trim().take(RitmeEntries.MAX_TEXT)
        return VideoLink.Direct(candidate, fileName, domain, streaming)
    }
}

package com.example.musicfy.ritme.media

import com.example.musicfy.ritme.protocol.MediaUrlRules
import com.example.musicfy.ritme.protocol.StreamType
import com.example.musicfy.ritme.protocol.YouTubeLinks

enum class FoundVia { NETWORK, MEDIA_ELEMENT, PAGE_VIDEO, OPEN_GRAPH, JSON_LD }

/** A video or stream found on the page, with what the page says about it. */
data class Candidate(
    val url: String,
    val streamType: StreamType,
    val via: Set<FoundVia>,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    /** A finite length from the page's `<video>` or JSON-LD; null when unknown. */
    val pageDurationMs: Long? = null,
    val pageLive: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    /** On-screen size (CSS px²) of the `<video>` showing it; 0 when none. */
    val area: Long = 0,
    val playing: Boolean = false,
    /** A JSON-LD VideoObject's name. */
    val name: String? = null,
)

/** Everything found on the current page, ranked (spec 4.7 steps 1–3). */
data class PageFindings(
    val pageUrl: String? = null,
    val candidates: List<Candidate> = emptyList(),
    /** A YouTube video on or behind this page: offered as YOUTUBE_VIDEO, never as a URL entry. */
    val youTubeVideoId: String? = null,
    /** Segments were requested: the page plays a stream. */
    val streamSeen: Boolean = false,
    /** Only `blob:` videos and nothing else: "Video di halaman ini tidak bisa dibagikan". */
    val blobOnly: Boolean = false,
    /** The page asked for DRM (page script or WebView's protected-media request). */
    val eme: Boolean = false,
    val title: String? = null,
    val ogTitle: String? = null,
    val siteName: String? = null,
)

/**
 * What the browser found on the current page, from requests ([onRequest], any thread) and page
 * reports ([onReport]). Page data is untrusted: at most [maxCandidates] per page and [MAX_FRAMES]
 * frames, every url checked again (MediaUrlRules, ad hosts, YouTube's hosts). Thread-safe.
 */
class CandidateCollector(private val maxCandidates: Int = MAX_CANDIDATES) {

    private class Found(
        val url: String,
        var streamType: StreamType,
        val via: MutableSet<FoundVia>,
        val firstSeenMs: Long,
        var lastSeenMs: Long,
    )

    private var pageUrl: String? = null
    private var pageYouTubeId: String? = null
    private var embeddedYouTubeId: String? = null
    private var streamSeen = false
    private var protectedMedia = false
    private val found = LinkedHashMap<String, Found>()
    private val reports = LinkedHashMap<String, PageReport>()

    /** A new page (a main-frame load): everything found so far belonged to the old one. */
    @Synchronized
    fun startPage(url: String) {
        pageUrl = url
        pageYouTubeId = youTubeIdOf(url)
        embeddedYouTubeId = null
        streamSeen = false
        protectedMedia = false
        found.clear()
        reports.clear()
    }

    /** Same document, new address (history.pushState): YouTube moves between videos like this. */
    @Synchronized
    fun urlChanged(url: String): Boolean {
        val id = youTubeIdOf(url)
        val changed = url != pageUrl || id != pageYouTubeId
        pageUrl = url
        pageYouTubeId = id
        return changed
    }

    /** True when the request added or changed something. */
    @Synchronized
    fun onRequest(url: String, method: String, headers: Map<String, String>, nowMs: Long): Boolean =
        when (val sniffed = MediaUrlClassifier.classify(url, method, headers)) {
            is Sniffed.Media -> add(sniffed.url, sniffed.streamType, if (sniffed.mediaRequest) FoundVia.MEDIA_ELEMENT else FoundVia.NETWORK, nowMs)
            Sniffed.Segment -> if (streamSeen) false else {
                streamSeen = true
                true
            }
            Sniffed.Ignored -> false
        }

    @Synchronized
    fun onReport(report: PageReport, nowMs: Long): Boolean {
        val key = report.frameUrl ?: if (report.mainFrame) MAIN_FRAME else OTHER_FRAME
        if (key !in reports && reports.size >= MAX_FRAMES) return false
        var changed = reports.put(key, report) != report
        for (video in report.videos) for (url in video.urls) changed = add(url, typeOf(url), FoundVia.PAGE_VIDEO, nowMs) || changed
        for (url in report.ogVideos) {
            val youTube = youTubeIdOf(url)
            if (youTube == null) {
                changed = add(url, typeOf(url), FoundVia.OPEN_GRAPH, nowMs) || changed
            } else if (embeddedYouTubeId == null) {
                embeddedYouTubeId = youTube
                changed = true
            }
        }
        for (ld in report.ldVideos) ld.url?.let { changed = add(it, typeOf(it), FoundVia.JSON_LD, nowMs) || changed }
        // An embedded YouTube player's own frame reports from youtube.com / youtube-nocookie.com.
        if (!report.mainFrame && embeddedYouTubeId == null) {
            report.frameUrl?.let(::youTubeIdOf)?.let {
                embeddedYouTubeId = it
                changed = true
            }
        }
        return changed
    }

    /** WebView was asked for protected media (DRM) by this page. */
    @Synchronized
    fun onProtectedMedia(): Boolean {
        if (protectedMedia) return false
        protectedMedia = true
        return true
    }

    @Synchronized
    fun findings(): PageFindings {
        val videos = reports.values.flatMap { it.videos }
        val streamVideo = videos.filter { it.blob && it.urls.isEmpty() }.maxWithOrNull(VIDEO_ORDER)
        val linked = reports.values.flatMap { it.ldVideos }.filter { it.url != null }
        val candidates = found.values.map { f ->
            // A stream the page plays through MSE shows as a blob: video; the stream is what was requested.
            val shown = videos.filter { f.url in it.urls }.maxWithOrNull(VIDEO_ORDER)
                ?: streamVideo?.takeIf { f.streamType != StreamType.FILE && FoundVia.NETWORK in f.via }
            val ld = linked.firstOrNull { it.url == f.url }
            Candidate(
                url = f.url,
                streamType = f.streamType,
                via = f.via.toSet(),
                firstSeenMs = f.firstSeenMs,
                lastSeenMs = f.lastSeenMs,
                pageDurationMs = shown?.durationMs ?: ld?.durationMs,
                pageLive = shown?.live == true,
                width = shown?.width ?: 0,
                height = shown?.height ?: 0,
                area = shown?.area ?: 0L,
                playing = shown?.playing == true,
                name = ld?.name,
            )
        }
        val main = reports.values.firstOrNull { it.mainFrame }
        val youTube = pageYouTubeId ?: embeddedYouTubeId
        val ranked = CandidateRanking.rank(candidates)
        return PageFindings(
            pageUrl = pageUrl,
            candidates = ranked,
            youTubeVideoId = youTube,
            streamSeen = streamSeen,
            blobOnly = ranked.isEmpty() && youTube == null && videos.any { it.blob },
            eme = protectedMedia || reports.values.any { it.eme },
            title = main?.title,
            ogTitle = main?.ogTitle,
            siteName = main?.siteName,
        )
    }

    private fun typeOf(url: String): StreamType = MediaUrlClassifier.streamTypeOf(url) ?: StreamType.UNKNOWN

    private fun add(rawUrl: String, type: StreamType, via: FoundVia, nowMs: Long): Boolean {
        val url = rawUrl.substringBefore('#')
        val host = MediaUrlClassifier.hostOf(url) ?: return false
        if (MediaUrlClassifier.isYouTubeHost(host) || AdHosts.isAd(host) || MediaUrlRules.check(url) != null) return false
        val existing = found[url]
        if (existing == null) {
            if (found.size >= maxCandidates) return false
            found[url] = Found(url, type, mutableSetOf(via), nowMs, nowMs)
            return true
        }
        existing.lastSeenMs = nowMs
        var changed = existing.via.add(via)
        if (existing.streamType == StreamType.UNKNOWN && type != StreamType.UNKNOWN) {
            existing.streamType = type
            changed = true
        }
        return changed
    }

    companion object {
        const val MAX_CANDIDATES = 50
        const val MAX_FRAMES = 20
        private const val MAIN_FRAME = "main"
        private const val OTHER_FRAME = "frame"
        private val VIDEO_ORDER = compareBy<PageVideo>({ it.playing }, { it.area })

        private fun youTubeIdOf(url: String): String? = YouTubeLinks.parse(url)?.takeUnless { it.music }?.videoId
    }
}

object CandidateRanking {
    /**
     * Spec 4.7 step 3: tied to the largest playing `<video>` first, then the largest shown one, then
     * the longest, then the newest; [hidden] (a checked master's variants, non-media) left out.
     */
    fun rank(candidates: List<Candidate>, hidden: Set<String> = emptySet()): List<Candidate> =
        candidates.filter { it.url !in hidden }.sortedWith(
            compareByDescending<Candidate> { if (it.playing) it.area + 1 else 0L }
                .thenByDescending { it.area }
                .thenByDescending { it.pageDurationMs ?: 0L }
                .thenByDescending { it.lastSeenMs },
        )
}

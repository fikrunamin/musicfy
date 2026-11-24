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

    /** One page-derived URL as the current reports show it. */
    private class PageEntry(val url: String, var type: StreamType, val via: MutableSet<FoundVia>, val playing: Boolean, val area: Long)

    private var pageUrl: String? = null
    private var pageYouTubeId: String? = null
    private var streamSeen = false
    private var protectedMedia = false

    /** Network-sniffed candidates (at most [networkCap]); page-derived ones are recomputed from the reports. */
    private val network = LinkedHashMap<String, Found>()
    private val networkCap = maxCandidates * 3 / 5
    private val pageCap = maxCandidates - networkCap

    /** First/last time each page-derived URL was reported; pruned to what the reports still show. */
    private val pageTimes = HashMap<String, LongArray>()
    private var mainReport: PageReport? = null
    private val subReports = LinkedHashMap<String, PageReport>()

    /** A new page (a main-frame load): everything found so far belonged to the old one. */
    @Synchronized
    fun startPage(url: String) {
        pageUrl = url
        pageYouTubeId = youTubeIdOf(url)
        streamSeen = false
        protectedMedia = false
        network.clear()
        pageTimes.clear()
        mainReport = null
        subReports.clear()
    }

    /**
     * Same document, new address (history.pushState / replaceState / fragment): YouTube moves between
     * videos like this. Requests, the stream flag and DRM stay (players rewrite `?t=` as they play; a
     * manifest may be fetched before the pushState). Only when the path itself changes is the old
     * main-frame report dropped as stale; a query or fragment change keeps it.
     */
    @Synchronized
    fun urlChanged(url: String): Boolean {
        val id = youTubeIdOf(url)
        val changed = url != pageUrl || id != pageYouTubeId
        val pathChanged = pageUrl?.let { withoutQueryAndFragment(it) != withoutQueryAndFragment(url) } ?: false
        pageUrl = url
        pageYouTubeId = id
        if (pathChanged) {
            mainReport = null
            prunePageTimes()
        }
        return changed
    }

    /** True when the request added or changed something. */
    @Synchronized
    fun onRequest(url: String, method: String, headers: Map<String, String>, nowMs: Long): Boolean =
        when (val sniffed = MediaUrlClassifier.classify(url, method, headers)) {
            is Sniffed.Media -> addNetwork(sniffed.url, sniffed.streamType, if (sniffed.mediaRequest) FoundVia.MEDIA_ELEMENT else FoundVia.NETWORK, nowMs)
            Sniffed.Segment -> if (streamSeen) false else {
                streamSeen = true
                true
            }
            Sniffed.Ignored -> false
        }

    @Synchronized
    fun onReport(report: PageReport, nowMs: Long): Boolean {
        val replaced: PageReport?
        if (report.mainFrame) {
            replaced = mainReport
            mainReport = report
        } else {
            val key = report.frameUrl ?: OTHER_FRAME
            if (key !in subReports && subReports.size >= MAX_FRAMES) return false
            replaced = subReports.put(key, report)
        }
        val before = embeddedYouTubeId()
        val fresh = entriesOf(report).map { it.url }.toSet()
        val current = pageEntries()
        for (entry in current) {
            val times = pageTimes[entry.url]
            if (times == null) pageTimes[entry.url] = longArrayOf(nowMs, nowMs) else if (entry.url in fresh) times[1] = nowMs
        }
        prunePageTimes(current)
        return replaced != report || before != embeddedYouTubeId()
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
        val reports = allReports()
        val videos = reports.flatMap { it.videos }
        val streamVideo = videos.filter { it.blob && it.urls.isEmpty() }.maxWithOrNull(VIDEO_ORDER)
        val linked = reports.flatMap { it.ldVideos }.filter { it.url != null }
        val merged = LinkedHashMap<String, Found>()
        for (entry in pageEntries()) {
            val times = pageTimes[entry.url]
            merged[entry.url] = Found(entry.url, entry.type, entry.via.toMutableSet(), times?.get(0) ?: 0L, times?.get(1) ?: 0L)
        }
        for (f in network.values) {
            val m = merged[f.url]
            if (m == null) {
                merged[f.url] = Found(f.url, f.streamType, f.via.toMutableSet(), f.firstSeenMs, f.lastSeenMs)
            } else {
                m.via.addAll(f.via)
                if (m.streamType == StreamType.UNKNOWN) m.streamType = f.streamType
                merged[f.url] = Found(f.url, m.streamType, m.via, minOf(m.firstSeenMs, f.firstSeenMs), maxOf(m.lastSeenMs, f.lastSeenMs))
            }
        }
        val candidates = merged.values.take(maxCandidates).map { f ->
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
        val main = mainReport
        val youTube = pageYouTubeId ?: embeddedYouTubeId()
        val ranked = CandidateRanking.rank(candidates)
        return PageFindings(
            pageUrl = pageUrl,
            candidates = ranked,
            youTubeVideoId = youTube,
            streamSeen = streamSeen,
            blobOnly = ranked.isEmpty() && youTube == null && videos.any { it.blob },
            eme = protectedMedia || reports.any { it.eme },
            title = main?.title,
            ogTitle = main?.ogTitle,
            siteName = main?.siteName,
        )
    }

    private fun allReports(): List<PageReport> = listOfNotNull(mainReport) + subReports.values

    /** An embedded YouTube video: an og:video on YouTube, else a YouTube player's own frame. */
    private fun embeddedYouTubeId(): String? {
        val reports = allReports()
        reports.firstNotNullOfOrNull { r -> r.ogVideos.firstNotNullOfOrNull(::youTubeIdOf) }?.let { return it }
        return reports.firstNotNullOfOrNull { r -> if (r.mainFrame) null else r.frameUrl?.let(::youTubeIdOf) }
    }

    private fun entriesOf(report: PageReport): List<PageEntry> {
        val out = ArrayList<PageEntry>()
        fun put(raw: String, via: FoundVia, playing: Boolean, area: Long) {
            val url = accept(raw) ?: return
            out += PageEntry(url, typeOf(url), mutableSetOf(via), playing, area)
        }
        for (video in report.videos) for (url in video.urls) put(url, FoundVia.PAGE_VIDEO, video.playing, video.area)
        for (url in report.ogVideos) if (youTubeIdOf(url) == null) put(url, FoundVia.OPEN_GRAPH, false, 0)
        for (ld in report.ldVideos) ld.url?.let { put(it, FoundVia.JSON_LD, false, 0) }
        return out
    }

    /** Page-derived candidates from the current reports, best (playing, largest) first, at most [pageCap]. */
    private fun pageEntries(): List<PageEntry> {
        val byUrl = LinkedHashMap<String, PageEntry>()
        for (report in allReports()) for (e in entriesOf(report)) {
            val existing = byUrl[e.url]
            if (existing == null) byUrl[e.url] = e else {
                existing.via += e.via
                if (existing.type == StreamType.UNKNOWN) existing.type = e.type
                if (e.playing && !existing.playing || e.area > existing.area) byUrl[e.url] = PageEntry(e.url, existing.type, existing.via, e.playing || existing.playing, maxOf(e.area, existing.area))
            }
        }
        return byUrl.values.sortedWith(compareByDescending<PageEntry> { it.playing }.thenByDescending { it.area }).take(pageCap)
    }

    private fun prunePageTimes(current: List<PageEntry> = pageEntries()) {
        val keep = current.map { it.url }.toSet()
        pageTimes.keys.retainAll(keep)
    }

    private fun typeOf(url: String): StreamType = MediaUrlClassifier.streamTypeOf(url) ?: StreamType.UNKNOWN

    /** The url without its fragment when it may be offered at all, else null. */
    private fun accept(rawUrl: String): String? {
        val url = rawUrl.substringBefore('#')
        val host = MediaUrlClassifier.hostOf(url) ?: return null
        if (MediaUrlClassifier.isYouTubeHost(host) || AdHosts.isAd(host) || MediaUrlRules.check(url) != null) return null
        return url
    }

    private fun addNetwork(rawUrl: String, type: StreamType, via: FoundVia, nowMs: Long): Boolean {
        val url = accept(rawUrl) ?: return false
        val existing = network[url]
        if (existing != null) {
            existing.lastSeenMs = nowMs
            var changed = existing.via.add(via)
            if (existing.streamType == StreamType.UNKNOWN && type != StreamType.UNKNOWN) {
                existing.streamType = type
                changed = true
            }
            return changed
        }
        if (network.size >= networkCap) {
            val shown = allReports().flatMap { it.videos }
            val victim = network.values
                .filter { f -> shown.none { v -> f.url in v.urls && (v.playing || v.durationMs != null) } }
                .minByOrNull { it.lastSeenMs } ?: return false
            network.remove(victim.url)
        }
        network[url] = Found(url, type, mutableSetOf(via), nowMs, nowMs)
        return true
    }

    companion object {
        const val MAX_CANDIDATES = 50
        const val MAX_FRAMES = 20
        private const val OTHER_FRAME = "frame"
        private val VIDEO_ORDER = compareBy<PageVideo>({ it.playing }, { it.area })

        private fun withoutQueryAndFragment(url: String) = url.substringBefore('#').substringBefore('?')

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

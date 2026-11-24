package com.example.musicfy.ritme.browser

import com.example.musicfy.ritme.media.Candidate
import com.example.musicfy.ritme.media.CandidateCollector
import com.example.musicfy.ritme.media.CandidateRanking
import com.example.musicfy.ritme.media.FoundVia
import com.example.musicfy.ritme.media.PageFindings
import com.example.musicfy.ritme.media.PageReports
import com.example.musicfy.ritme.media.ProbeVerdict
import com.example.musicfy.ritme.media.Refusal
import com.example.musicfy.ritme.protocol.MediaUrlRules
import com.example.musicfy.ritme.protocol.StreamType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ProbeState {
    data object Checking : ProbeState

    data class Done(val verdict: ProbeVerdict) : ProbeState
}

/** A row of the found list: a find, and where its check stands (null: not asked yet). */
data class FoundItem(val candidate: Candidate, val state: ProbeState?)

/**
 * The Ritme browser's findings and checks (spec 4.7 steps 1–4). The WebView feeds it (requests on
 * any thread, reports and page changes on the main thread); the list and confirmation sheets read
 * it. Checks start when the list asks ([probeAll]), each find once per page; a new page drops
 * everything, and an answer about an old page is ignored.
 */
class RitmeBrowserModel(
    private val probe: suspend (url: String, pageUrl: String?) -> ProbeVerdict,
    private val fileLength: suspend (url: String, referrer: String?) -> Long?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val collector: CandidateCollector = CandidateCollector(),
) {
    private val _findings = MutableStateFlow(PageFindings())
    val findings: StateFlow<PageFindings> = _findings.asStateFlow()

    private val _probes = MutableStateFlow<Map<String, ProbeState>>(emptyMap())
    val probes: StateFlow<Map<String, ProbeState>> = _probes.asStateFlow()

    private val lock = Any()
    private var page = 0
    private val jobs = mutableListOf<Job>()

    fun pageStarted(url: String) {
        synchronized(lock) {
            page++
            jobs.forEach { it.cancel() }
            jobs.clear()
            collector.startPage(url)
            _probes.value = emptyMap()
        }
        refresh()
    }

    fun urlChanged(url: String) {
        if (collector.urlChanged(url)) refresh()
    }

    fun onRequest(url: String, method: String, headers: Map<String, String>) {
        if (collector.onRequest(url, method, headers, clock())) refresh()
    }

    fun onReport(message: String, mainFrame: Boolean) {
        val report = PageReports.parse(message, mainFrame) ?: return
        if (collector.onReport(report, clock())) refresh()
    }

    fun onProtectedMedia() {
        if (collector.onProtectedMedia()) refresh()
    }

    /** The list's rows, ranked; a checked master's variants and checked non-videos left out. */
    fun items(findings: PageFindings = this.findings.value, probes: Map<String, ProbeState> = this.probes.value): List<FoundItem> {
        val verdicts = probes.values.mapNotNull { (it as? ProbeState.Done)?.verdict }
        val hidden = verdicts.flatMap { it.variants }.toSet() + verdicts.filter { it.refusal == Refusal.NOT_MEDIA }.map { it.url }
        return CandidateRanking.rank(findings.candidates, hidden).map { FoundItem(it, probes[it.url]?.seenOn(findings.eme)) }
    }

    /**
     * What the pill counts (Decision 18): finds that are surely media (seen played or requested,
     * a media extension, or checked), plus the page's YouTube video. og:video / JSON-LD links
     * without an extension are listed and checked, but do not raise the pill alone.
     */
    fun offers(findings: PageFindings = this.findings.value, probes: Map<String, ProbeState> = this.probes.value): Int {
        val sure = items(findings, probes).count { item ->
            item.state is ProbeState.Done || item.candidate.streamType != StreamType.UNKNOWN || item.candidate.via.any { it in SEEN_AS_MEDIA }
        }
        return sure + if (findings.youTubeVideoId != null) 1 else 0
    }

    fun verdictOf(url: String): ProbeVerdict? = (probes.value[url]?.seenOn(findings.value.eme) as? ProbeState.Done)?.verdict

    /** Checks every listed find not checked yet (spec 4.7 step 4: when the list opens). */
    fun probeAll(): List<Job> = items().filter { it.state == null }.mapNotNull { start(it.candidate) }

    /** "Coba lagi": checks [url] once more. */
    fun retry(url: String): Job? {
        val candidate = findings.value.candidates.firstOrNull { it.url == url } ?: return null
        synchronized(lock) {
            if (_probes.value[url] is ProbeState.Checking) return null
            _probes.update { it - url }
        }
        return start(candidate)
    }

    private fun start(candidate: Candidate): Job? = synchronized(lock) {
        if (_probes.value[candidate.url] != null) return null
        val generation = page
        val pageUrl = findings.value.pageUrl
        _probes.update { it + (candidate.url to ProbeState.Checking) }
        val job = scope.launch {
            val verdict = finish(candidate, probe(candidate.url, pageUrl), pageUrl)
            synchronized(lock) {
                if (generation == page) _probes.update { it + (candidate.url to ProbeState.Done(verdict)) }
            }
        }
        jobs += job
        job
    }

    /** The page's own signals on top of the check: DRM asked for by the page, and a file's length (Decision 10). */
    private suspend fun finish(candidate: Candidate, verdict: ProbeVerdict, pageUrl: String?): ProbeVerdict {
        if (verdict.refusal != null) return verdict
        if (verdict.streamType != StreamType.FILE || verdict.durationMs != null) return verdict
        val referrer = if (verdict.needsReferrer) pageUrl?.let(MediaUrlRules::referrerOf) else null
        val length = candidate.pageDurationMs ?: fileLength(candidate.url, referrer)
        return if (length != null && length > 0) verdict.copy(durationMs = length) else verdict.copy(refusal = Refusal.LENGTH_UNKNOWN)
    }

    /**
     * Controller decision 1: on a page that asked for DRM (`eme`), only a playlist or manifest the
     * check read and found clean stays as it is; every other find is DRM (a file, or a check that
     * failed or could not tell the length), except non-videos and sign-in refusals. Applied when
     * read, so a signal arriving after the check, or a new page clearing it, counts at once.
     */
    private fun ProbeState.seenOn(eme: Boolean): ProbeState {
        if (!eme || this !is ProbeState.Done) return this
        val v = verdict
        val clean = v.refusal == null && (v.streamType == StreamType.HLS || v.streamType == StreamType.DASH)
        return when {
            clean || v.refusal == Refusal.NOT_MEDIA || v.refusal == Refusal.NEEDS_LOGIN || v.refusal == Refusal.DRM -> this
            else -> ProbeState.Done(v.copy(refusal = Refusal.DRM, webPlayable = false))
        }
    }

    private fun refresh() {
        synchronized(lock) { _findings.value = collector.findings() }
    }

    private companion object {
        val SEEN_AS_MEDIA = setOf(FoundVia.NETWORK, FoundVia.MEDIA_ELEMENT, FoundVia.PAGE_VIDEO)
    }
}

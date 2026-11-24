package com.example.musicfy.ritme.browser

import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.media.Candidate
import com.example.musicfy.ritme.media.ExpiryHints
import com.example.musicfy.ritme.media.MediaUrlClassifier
import com.example.musicfy.ritme.media.PageFindings
import com.example.musicfy.ritme.media.ProbeVerdict
import com.example.musicfy.ritme.media.SensitiveParams
import com.example.musicfy.ritme.protocol.MediaUrlRules
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.StreamType

/** What the confirmation sheet says besides the title and source (spec 4.7 steps 4 and 6). */
enum class BrowserNote { SENSITIVE, EXPIRES, EXPIRED, IP_LOCKED, APP_ONLY, NOT_ON_WEB, KEY_MAY_NEED_LOGIN }

/** The found list's and the confirmation's words, and the entry the confirmation sends. Pure. */
object RitmeBrowserEntries {

    /** The confirmation's default title: og:title → document.title → the JSON-LD name → the file name → the page's domain. */
    fun defaultTitle(candidate: Candidate, findings: PageFindings): String =
        listOfNotNull(
            findings.ogTitle,
            findings.title,
            candidate.name,
            MediaUrlClassifier.fileNameOf(candidate.url),
            domainOf(findings.pageUrl ?: candidate.url),
        ).firstOrNull(RitmeEntries::hasVisibleText).orEmpty().take(RitmeEntries.MAX_TEXT)

    /** A list row's title: the find's own name (JSON-LD, file name) so rows differ, else [defaultTitle]. */
    fun rowTitle(candidate: Candidate, findings: PageFindings): String =
        listOfNotNull(candidate.name, MediaUrlClassifier.fileNameOf(candidate.url)).firstOrNull(RitmeEntries::hasVisibleText)
            ?: defaultTitle(candidate, findings)

    /** The entry's "artist" (spec 4.7 step 6): og:site_name, else the page's domain without www. */
    fun source(findings: PageFindings, candidate: Candidate): String =
        findings.siteName?.takeIf(RitmeEntries::hasVisibleText)?.take(RitmeEntries.MAX_TEXT)
            ?: domainOf(findings.pageUrl ?: candidate.url)

    /**
     * What "Tambah"/"Putar berikutnya" send, or null when it may not be added (U12: DRM, needs a
     * sign-in, failed, unknown file length) or the server would refuse it. Fragment stripped, no
     * picture, length from the check (0 = live or unknown stream length), the page's origin as
     * referrer only when the host needs it.
     */
    fun entryOf(candidate: Candidate, verdict: ProbeVerdict, title: String, source: String, pageUrl: String?): NewEntry? {
        if (verdict.refusal != null) return null
        val referrer = if (verdict.needsReferrer) pageUrl?.let(MediaUrlRules::referrerOf) ?: return null else null
        val durationMs = if (verdict.live) 0L else verdict.durationMs ?: 0L
        return RitmeEntries.toUrlEntry(candidate.url.substringBefore('#'), title, source, durationMs, verdict.streamType, referrer)
    }

    fun notes(url: String, verdict: ProbeVerdict, nowMs: Long): List<BrowserNote> = buildList {
        if (SensitiveParams.has(url)) add(BrowserNote.SENSITIVE)
        val hints = ExpiryHints.of(url, nowMs)
        hints.expiresInMinutes?.let { add(if (it <= 0) BrowserNote.EXPIRED else BrowserNote.EXPIRES) }
        if (hints.ipLocked) add(BrowserNote.IP_LOCKED)
        if (verdict.needsReferrer) add(BrowserNote.APP_ONLY) else if (!verdict.webPlayable) add(BrowserNote.NOT_ON_WEB)
        if (verdict.aes128) add(BrowserNote.KEY_MAY_NEED_LOGIN)
    }

    /** "HLS", "DASH", "MP4", "WebM", …; null when nothing says. */
    fun typeLabel(url: String, streamType: StreamType): String? = when (streamType) {
        StreamType.HLS -> "HLS"
        StreamType.DASH -> "DASH"
        StreamType.FILE, StreamType.UNKNOWN -> when (MediaUrlClassifier.extensionOf(url)) {
            "mp4", "m4v" -> "MP4"
            "webm" -> "WebM"
            "mov" -> "MOV"
            "mkv" -> "MKV"
            "ogv" -> "Ogg"
            else -> null
        }
    }

    fun domainOf(url: String): String = MediaUrlClassifier.hostOf(url)?.removePrefix("www.").orEmpty()
}

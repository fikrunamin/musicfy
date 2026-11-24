package com.example.musicfy.ritme.media

import java.net.URI

/** What a response is, from its first bytes, else its Content-Type (spec 4.7 step 4). */
enum class MediaFormat { HLS, DASH, MP4, WEBM, VIDEO, HTML, UNKNOWN }

data class HlsInfo(
    val master: Boolean,
    /** A master's variant playlists, absolute, in order. */
    val variants: List<String>,
    val maxHeight: Int?,
    /** A media playlist with #EXT-X-ENDLIST: a finished (VOD) stream. */
    val ended: Boolean,
    /** A media playlist's #EXTINF sum; null for a master. */
    val durationMs: Long?,
    val drm: Boolean,
    val aes128: Boolean,
)

data class DashInfo(val live: Boolean, val durationMs: Long?, val drm: Boolean, val maxHeight: Int?)

/**
 * Small, forgiving readers for what a probe gets back: the first 4 KiB of a response, or a whole
 * playlist. Never a full parser: just live or not, length, DRM, and the variants of a master.
 */
object PlaylistSniffer {
    private const val MAX_DURATION_MS = 604_800_000L // 7 days
    private const val MAX_BOX_DEPTH = 8
    private const val BOM_AS_LATIN1 = "\u00EF\u00BB\u00BF"
    private const val BOM = "\uFEFF"
    private val MP4_CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "sinf", "schi", "moof", "traf")
    private val CENC_SCHEMES = setOf("cenc", "cbcs", "cens", "cbc1")
    private val MP4_BOXES = setOf("ftyp", "styp", "moov")
    private val WEBM_MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
    private val RESOLUTION = Regex("""RESOLUTION=(\d+)x(\d+)""")
    private val METHOD = Regex("""METHOD=([A-Za-z0-9-]+)""")
    private val KEYFORMAT = Regex("""KEYFORMAT="([^"]*)"""")
    private val EXTINF = Regex("""^#EXTINF:\s*([0-9]+(?:\.[0-9]+)?)""")
    private val DRM_METHODS = setOf("SAMPLE-AES", "SAMPLE-AES-CTR", "SAMPLE-AES-CENC")
    private val DRM_KEYFORMATS = listOf(
        "com.widevine", "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed", "com.apple.streamingkeydelivery",
        "com.microsoft.playready", "9a04f079-9840-4286-ab92-e65be0885f95",
    )
    private val MPD_TAG = Regex("""<MPD\b[^>]*>""")
    private val TYPE_ATTRIBUTE = Regex("""\stype\s*=\s*["']([^"']*)["']""")
    private val DURATION_ATTRIBUTE = Regex("""\smediaPresentationDuration\s*=\s*["']([^"']*)["']""")
    private val XML_COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val CONTENT_PROTECTION = Regex("""<(?:[A-Za-z0-9_]+:)?ContentProtection\b""")
    private val HEIGHT_ATTRIBUTE = Regex("""\sheight\s*=\s*["'](\d+)["']""")
    private val ISO_DURATION =
        Regex("""^P(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$""")

    fun detect(head: ByteArray, contentType: String?): MediaFormat {
        // Latin-1 keeps every byte as one char, so magic numbers and markup compare exactly.
        val text = String(head, 0, minOf(head.size, 4096), Charsets.ISO_8859_1)
        // A UTF-8 byte order mark, as Latin-1 sees it.
        val start = text.removePrefix(BOM_AS_LATIN1).removePrefix(BOM).trimStart()
        when {
            start.startsWith("#EXTM3U") -> return MediaFormat.HLS
            start.lowercase().let { it.startsWith("<!doctype html") || it.startsWith("<html") } -> return MediaFormat.HTML
            "<MPD" in text -> return MediaFormat.DASH
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) in MP4_BOXES -> return MediaFormat.MP4
            head.size >= 4 && head.copyOfRange(0, 4).contentEquals(WEBM_MAGIC) -> return MediaFormat.WEBM
        }
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            "mpegurl" in type -> MediaFormat.HLS
            type == "application/dash+xml" -> MediaFormat.DASH
            type == "video/mp4" -> MediaFormat.MP4
            type == "video/webm" -> MediaFormat.WEBM
            type.startsWith("video/") && type != "video/mp2t" && type != "video/iso.segment" -> MediaFormat.VIDEO
            type == "text/html" -> MediaFormat.HTML
            else -> MediaFormat.UNKNOWN
        }
    }

    fun parseHls(text: String, baseUrl: String): HlsInfo {
        val variants = mutableListOf<String>()
        var maxHeight: Int? = null
        var master = false
        var expectVariant = false
        var drm = false
        var aes128 = false
        var ended = false
        var totalSeconds = 0.0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    master = true
                    expectVariant = true
                    val height = RESOLUTION.find(line)?.groupValues?.get(2)?.toIntOrNull()
                    if (height != null) maxHeight = maxOf(maxHeight ?: 0, height)
                }
                line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-SESSION-KEY") -> {
                    val method = METHOD.find(line)?.groupValues?.get(1)?.uppercase()
                    val format = KEYFORMAT.find(line)?.groupValues?.get(1)?.lowercase().orEmpty()
                    if (method in DRM_METHODS || DRM_KEYFORMATS.any { it in format }) {
                        drm = true
                    } else if (method == "AES-128") {
                        aes128 = true
                    }
                }
                line.startsWith("#EXT-X-ENDLIST") -> ended = true
                line.startsWith("#EXTINF") -> EXTINF.find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.let { totalSeconds += it }
                line.startsWith("#") -> Unit
                expectVariant -> {
                    resolve(baseUrl, line)?.let(variants::add)
                    expectVariant = false
                }
            }
        }
        return HlsInfo(master, variants, maxHeight, ended, if (master) null else clampMs(totalSeconds * 1000), drm, aes128)
    }

    fun parseDash(text: String): DashInfo {
        val clean = text.replace(XML_COMMENT, "")
        val root = MPD_TAG.find(clean)?.value.orEmpty()
        val live = TYPE_ATTRIBUTE.find(root)?.groupValues?.get(1) == "dynamic"
        val duration = DURATION_ATTRIBUTE.find(root)?.groupValues?.get(1)?.let(::isoDurationMs)
        val maxHeight = HEIGHT_ATTRIBUTE.findAll(clean).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
        return DashInfo(live, if (live) null else duration, CONTENT_PROTECTION.containsMatchIn(clean), maxHeight)
    }

    /** An ISO 8601 duration (`PT1H2M3.5S`, `P1DT1S`) in ms; null when malformed or zero. */
    fun isoDurationMs(text: String): Long? {
        val match = ISO_DURATION.matchEntire(text.trim().uppercase()) ?: return null
        val (days, hours, minutes, seconds) = match.destructured
        if (days.isEmpty() && hours.isEmpty() && minutes.isEmpty() && seconds.isEmpty()) return null
        val total = (days.toDoubleOrNull() ?: 0.0) * 86_400 + (hours.toDoubleOrNull() ?: 0.0) * 3_600 +
            (minutes.toDoubleOrNull() ?: 0.0) * 60 + (seconds.toDoubleOrNull() ?: 0.0)
        return clampMs(total * 1000)
    }

    /** A faststart MP4's length from its `moov/mvhd` box (versions 0 and 1); null when not in [head]. */
    fun mp4DurationMs(head: ByteArray): Long? {
        val body = Mp4Scan().also { walkBoxes(head, 0, head.size, 0, it) }.mvhd ?: return null
        val timescale: Long?
        val duration: Long?
        when (head.getOrNull(body)?.toInt()) {
            0 -> {
                timescale = unsigned(head, body + 12, 4)
                duration = unsigned(head, body + 16, 4)?.takeIf { it != 0xFFFFFFFFL }
            }
            1 -> {
                timescale = unsigned(head, body + 20, 4)
                duration = unsigned(head, body + 24, 8)
            }
            else -> return null
        }
        if (timescale == null || duration == null || timescale <= 0 || duration <= 0 || duration > Long.MAX_VALUE / 1000) return null
        return clampMs(duration * 1000.0 / timescale)?.let { duration * 1000 / timescale }
    }

    /** Protection in an MP4's header: a `pssh` box, `encv`/`enca` entries or a CENC scheme, found by walking the boxes present. */
    fun mp4Encrypted(head: ByteArray): Boolean = Mp4Scan().also { walkBoxes(head, 0, head.size, 0, it) }.encrypted

    private class Mp4Scan {
        var encrypted = false
        /** Offset of the `mvhd` body (its version byte). */
        var mvhd: Int? = null
    }

    /** Walks the boxes in [from, to), tolerating truncation and nonsense sizes; never throws. */
    private fun walkBoxes(bytes: ByteArray, from: Int, to: Int, depth: Int, scan: Mp4Scan) {
        if (depth > MAX_BOX_DEPTH) return
        val limit = minOf(to, bytes.size)
        var pos = from
        while (pos >= 0 && pos + 8 <= limit) {
            val size32 = unsigned(bytes, pos, 4) ?: return
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            val size: Long = when (size32) {
                1L -> { header = 16; unsigned(bytes, pos + 8, 8) ?: return }
                0L -> (limit - pos).toLong()
                else -> size32
            }
            if (size < header) return
            val body = pos + header
            val end = if (size > limit - pos) limit else (pos + size).toInt()
            when (type) {
                "pssh", "encv", "enca", "tenc" -> scan.encrypted = true
                "schm" -> if (String(bytes, minOf(body + 4, limit), maxOf(0, minOf(4, limit - body - 4)), Charsets.ISO_8859_1) in CENC_SCHEMES) scan.encrypted = true
                "mvhd" -> if (scan.mvhd == null) scan.mvhd = body
                "stsd" -> walkBoxes(bytes, body + 8, end, depth + 1, scan)
                in MP4_CONTAINERS -> walkBoxes(bytes, body, end, depth + 1, scan)
            }
            if (end <= pos) return
            pos = end
        }
    }

    /** [ms] as whole milliseconds, or null when not finite, not positive or longer than 7 days. */
    private fun clampMs(ms: Double): Long? = ms.takeIf { it.isFinite() && it >= 1 && it <= MAX_DURATION_MS }?.toLong()

    private fun resolve(base: String, reference: String): String? =
        runCatching { URI(base).resolve(reference).toString() }.getOrNull()

    /** A big-endian unsigned number of [size] bytes at [at]; null when [bytes] ends first (or it overflows a Long). */
    private fun unsigned(bytes: ByteArray, at: Int, size: Int): Long? {
        if (at < 0 || at + size > bytes.size) return null
        val value = (0 until size).fold(0L) { acc, i -> (acc shl 8) or (bytes[at + i].toLong() and 0xFF) }
        return value.takeIf { it >= 0 }
    }
}

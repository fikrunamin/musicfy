package com.example.musicfy.ritme.media

import com.example.musicfy.ritme.protocol.MediaUrlRules
import com.example.musicfy.ritme.protocol.StreamType
import java.io.IOException

/** One HTTP answer, cut to the bytes the probe asked for. */
class ProbeResponse(
    val status: Int,
    val contentType: String?,
    val body: ByteArray,
    /** After redirects: the base for a playlist's relative links. */
    val finalUrl: String,
    /** The Access-Control-Allow-Origin header, for the web check. */
    val allowOrigin: String?,
) {
    val ok: Boolean get() = status in 200..299
}

/**
 * How a probe reaches the network: a plain GET with exactly [headers] (nothing else, no cookies),
 * redirects followed, the body cut at [maxBytes]. Android: OkHttp through RitmeAddressGuard;
 * desktop later: its own client. Throws IOException when there is no answer.
 */
fun interface ProbeHttp {
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>, maxBytes: Int): ProbeResponse
}

/** Why a find cannot be added (U12), or why it is not listed at all ([NOT_MEDIA]). */
enum class Refusal { DRM, NEEDS_LOGIN, FAILED, NOT_MEDIA, LENGTH_UNKNOWN }

data class ProbeVerdict(
    val url: String,
    val streamType: StreamType,
    val format: MediaFormat,
    val live: Boolean = false,
    /** A finished stream's or a file's length; null when live or not known. */
    val durationMs: Long? = null,
    val height: Int? = null,
    /** Refused without a Referer, fine with the page's origin as one: Android only ("Hanya di aplikasi"). */
    val needsReferrer: Boolean = false,
    /** The web client could play it: no Referer needed, https, CORS for HLS, not DASH. */
    val webPlayable: Boolean = true,
    /** AES-128 keys: allowed, but the key server may want cookies. */
    val aes128: Boolean = false,
    /** A master playlist's variants: listed only through their master. */
    val variants: List<String> = emptyList(),
    val refusal: Refusal? = null,
) {
    companion object {
        fun refused(
            url: String,
            refusal: Refusal,
            streamType: StreamType = StreamType.UNKNOWN,
            format: MediaFormat = MediaFormat.UNKNOWN,
        ) = ProbeVerdict(url, streamType, format, webPlayable = false, refusal = refusal)
    }
}

/**
 * Checks a find the way another member's device will fetch it (spec 4.7 step 4): no cookies, no
 * Referer, the first [HEAD_BYTES] only; then a playlist in full (and a master's first variant).
 * Refused without headers but fine with the page's origin as Referer → [ProbeVerdict.needsReferrer];
 * 401/403 either way → [Refusal.NEEDS_LOGIN]. HLS also gets an `Origin: <web client>` request: no
 * matching Access-Control-Allow-Origin → not playable on the web. Blocking: callers bound it in time.
 */
class MediaProber(private val http: ProbeHttp, private val webOrigin: String) {

    fun probe(url: String, pageUrl: String?): ProbeVerdict {
        if (!MediaUrlRules.isAcceptableMediaUrl(url)) return ProbeVerdict.refused(url, Refusal.FAILED)
        val plain = fetch(url, RANGE, HEAD_BYTES)
        var response = plain
        var extra = emptyMap<String, String>()
        val referrer = pageUrl?.let(MediaUrlRules::referrerOf)
        // a Referer can only turn a refusal (4xx) into an answer: not a 5xx, not a missing answer
        if (plain != null && plain.status in 400..499 && referrer != null) {
            val withReferrer = fetch(url, RANGE + (REFERER to referrer), HEAD_BYTES)
            if (withReferrer?.ok == true) {
                response = withReferrer
                extra = mapOf(REFERER to referrer)
            }
        }
        if (response == null || !response.ok) {
            val denied = plain != null && plain.status in DENIED
            return ProbeVerdict.refused(url, if (denied) Refusal.NEEDS_LOGIN else Refusal.FAILED)
        }
        val needsReferrer = extra.isNotEmpty()
        return when (val format = PlaylistSniffer.detect(response.body, response.contentType)) {
            MediaFormat.HLS -> hls(url, response, extra, needsReferrer)
            MediaFormat.DASH -> dash(url, response, extra, needsReferrer)
            MediaFormat.MP4, MediaFormat.WEBM, MediaFormat.VIDEO -> file(url, format, response, needsReferrer)
            MediaFormat.HTML, MediaFormat.UNKNOWN -> ProbeVerdict.refused(url, Refusal.NOT_MEDIA)
        }
    }

    private fun hls(url: String, head: ProbeResponse, extra: Map<String, String>, needsReferrer: Boolean): ProbeVerdict {
        val failed = ProbeVerdict.refused(url, Refusal.FAILED, StreamType.HLS, MediaFormat.HLS)
        val top = whole(url, head, extra)?.let { PlaylistSniffer.parseHls(it.text, it.baseUrl) } ?: return failed
        val media = if (!top.master) {
            top
        } else {
            val variant = top.variants.firstOrNull(MediaUrlRules::isAcceptableMediaUrl) ?: return failed
            val answer = fetch(variant, extra, PLAYLIST_BYTES)?.takeIf { it.ok } ?: return failed
            PlaylistSniffer.parseHls(answer.body.decodeToString(), answer.finalUrl)
        }
        if (top.drm || media.drm) return ProbeVerdict.refused(url, Refusal.DRM, StreamType.HLS, MediaFormat.HLS)
        val live = !media.ended
        return ProbeVerdict(
            url = url,
            streamType = StreamType.HLS,
            format = MediaFormat.HLS,
            live = live,
            durationMs = if (live) null else media.durationMs?.takeIf { it > 0 },
            height = top.maxHeight,
            needsReferrer = needsReferrer,
            webPlayable = !needsReferrer && isHttps(url) && corsAllows(url, extra),
            aes128 = top.aes128 || media.aes128,
            variants = top.variants.filter(MediaUrlRules::isAcceptableMediaUrl).take(MAX_VARIANTS),
        )
    }

    private fun dash(url: String, head: ProbeResponse, extra: Map<String, String>, needsReferrer: Boolean): ProbeVerdict {
        val info = whole(url, head, extra)?.let { PlaylistSniffer.parseDash(it.text) }
            ?: return ProbeVerdict.refused(url, Refusal.FAILED, StreamType.DASH, MediaFormat.DASH)
        if (info.drm) return ProbeVerdict.refused(url, Refusal.DRM, StreamType.DASH, MediaFormat.DASH)
        // The web client plays no DASH (D11).
        return ProbeVerdict(url, StreamType.DASH, MediaFormat.DASH, info.live, info.durationMs, info.maxHeight, needsReferrer, webPlayable = false)
    }

    private fun file(url: String, format: MediaFormat, head: ProbeResponse, needsReferrer: Boolean): ProbeVerdict {
        if (format == MediaFormat.MP4 && PlaylistSniffer.mp4Encrypted(head.body)) {
            return ProbeVerdict.refused(url, Refusal.DRM, StreamType.FILE, format)
        }
        return ProbeVerdict(
            url = url,
            streamType = StreamType.FILE,
            format = format,
            durationMs = if (format == MediaFormat.MP4) PlaylistSniffer.mp4DurationMs(head.body) else null,
            needsReferrer = needsReferrer,
            // <video> plays a cross-origin file without CORS; only a Referer it cannot send stops it.
            webPlayable = !needsReferrer && isHttps(url),
        )
    }

    private class Text(val text: String, val baseUrl: String)

    /** The whole playlist: the head when it was shorter than the range asked for, else fetched in full. */
    private fun whole(url: String, head: ProbeResponse, extra: Map<String, String>): Text? {
        val answer = if (head.body.size < HEAD_BYTES) head else fetch(url, extra, PLAYLIST_BYTES)?.takeIf { it.ok } ?: return null
        return Text(answer.body.decodeToString(), answer.finalUrl)
    }

    private fun corsAllows(url: String, extra: Map<String, String>): Boolean {
        val answer = fetch(url, extra + RANGE + (ORIGIN to webOrigin), HEAD_BYTES) ?: return false
        val allowed = answer.allowOrigin?.trim() ?: return false
        return answer.ok && (allowed == "*" || allowed.equals(webOrigin, ignoreCase = true))
    }

    private fun fetch(url: String, headers: Map<String, String>, maxBytes: Int): ProbeResponse? = try {
        http.get(url, headers, maxBytes).let {
            // whatever the client does, never trust it to have cut the body
            if (it.body.size > maxBytes) ProbeResponse(it.status, it.contentType, it.body.copyOf(maxBytes), it.finalUrl, it.allowOrigin) else it
        }
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        // includes a url the HTTP client cannot even build a request for: nothing escapes probe
        null
    }

    private fun isHttps(url: String) = url.startsWith("https://", ignoreCase = true)

    companion object {
        const val HEAD_BYTES = 4_096
        const val PLAYLIST_BYTES = 1 shl 20
        const val MAX_VARIANTS = 20
        private const val REFERER = "Referer"
        private const val ORIGIN = "Origin"
        private val RANGE = mapOf("Range" to "bytes=0-${HEAD_BYTES - 1}")
        private val DENIED = setOf(401, 403)
    }
}

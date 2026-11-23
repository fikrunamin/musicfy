package com.example.musicfy.ritme

import com.example.musicfy.ritme.net.socketHttpUrl
import java.net.URI

/** Room codes, links and server URLs. Pure, so it runs in JVM unit tests. */
object RitmeLinks {
    const val CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    const val CODE_LENGTH = 6
    const val MAX_NAME_LENGTH = 32

    private val LINK = Regex(
        """(?:musicfy://ritme/|https?://([^\s/?#]+)/r/)([A-Za-z0-9]{6})(?![A-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )

    /** Upper-cased code, or null unless it is 6 characters of [CODE_ALPHABET]. */
    fun normalizeCode(input: String): String? {
        val code = input.trim().uppercase()
        return code.takeIf { it.length == CODE_LENGTH && it.all { c -> c in CODE_ALPHABET } }
    }

    /**
     * The room code in a `musicfy://ritme/{code}` or `http(s)://<host>/r/{code}` link, also inside
     * shared text. An `http(s)` link only counts when its host is one of [serverUrls]' (any port),
     * so shared text such as `reddit.com/r/travel` never opens a room.
     */
    fun findRoomCode(text: String, serverUrls: Collection<String>): String? {
        val hosts = serverUrls.mapNotNullTo(HashSet()) { hostOf(it) }
        return LINK.findAll(text).firstNotNullOfOrNull { match ->
            val authority = match.groupValues[1]
            val known = authority.isEmpty() || hostOf("http://$authority") in hosts
            if (known) normalizeCode(match.groupValues[2]) else null
        }
    }

    private fun hostOf(url: String): String? =
        runCatching { URI(url.trim()).host }.getOrNull()?.lowercase()?.takeIf { it.isNotEmpty() }

    /**
     * `http(s)://host[:port][/path]` without a trailing slash, query or fragment; null if it is not
     * one, if its port is empty, or if OkHttp could not open its socket URL (e.g. a port above 65535).
     */
    fun normalizeServerUrl(input: String): String? {
        val trimmed = input.trim().trimEnd('/')
        val http = trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)
        if (!http) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank() || uri.rawQuery != null || uri.rawFragment != null) return null
        // "host:" with no port: both parsers shrug it off, but it would end up in every share link
        if (uri.rawAuthority.endsWith(':')) return null
        if (socketHttpUrl(socketUrl(trimmed)) == null) return null
        return trimmed
    }

    /**
     * What Settings stores for a typed server URL: blank for [defaultUrl] (so a later change of the
     * official server reaches this user too), the normalised URL otherwise, null when it is invalid.
     */
    fun serverUrlToStore(input: String, defaultUrl: String): String? {
        val normalized = normalizeServerUrl(input) ?: return null
        return if (normalized.equals(defaultUrl.trimEnd('/'), ignoreCase = true)) "" else normalized
    }

    /** `https://x` → `wss://x/ritme`, `http://x` → `ws://x/ritme`. */
    fun socketUrl(serverUrl: String): String {
        val base = serverUrl.trimEnd('/')
        val socket = when {
            base.startsWith("https://", ignoreCase = true) -> "wss://" + base.substring("https://".length)
            base.startsWith("http://", ignoreCase = true) -> "ws://" + base.substring("http://".length)
            else -> base
        }
        return "$socket/ritme"
    }

    fun shareLink(serverUrl: String, code: String): String = "${serverUrl.trimEnd('/')}/r/$code"

    /** Trimmed and 1..32 characters, or null (the server answers INVALID otherwise). */
    fun cleanDisplayName(input: String): String? =
        input.trim().takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }
}

package com.example.musicfy.ritme

import java.net.URI

/** Room codes, links and server URLs. Pure, so it runs in JVM unit tests. */
object RitmeLinks {
    const val CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    const val CODE_LENGTH = 6
    const val MAX_NAME_LENGTH = 32

    private val LINK = Regex(
        """(?:musicfy://ritme/|https?://[^\s/?#]+/r/)([A-Za-z0-9]{6})(?![A-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )

    /** Upper-cased code, or null unless it is 6 characters of [CODE_ALPHABET]. */
    fun normalizeCode(input: String): String? {
        val code = input.trim().uppercase()
        return code.takeIf { it.length == CODE_LENGTH && it.all { c -> c in CODE_ALPHABET } }
    }

    /** The room code in a `musicfy://ritme/{code}` or `http(s)://<host>/r/{code}` link, also inside shared text. */
    fun findRoomCode(text: String): String? = LINK.find(text)?.groupValues?.get(1)?.let(::normalizeCode)

    /** `http(s)://host[:port][/path]` without a trailing slash, query or fragment; null if it is not one. */
    fun normalizeServerUrl(input: String): String? {
        val trimmed = input.trim().trimEnd('/')
        val http = trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)
        if (!http) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank() || uri.rawQuery != null || uri.rawFragment != null) return null
        return trimmed
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

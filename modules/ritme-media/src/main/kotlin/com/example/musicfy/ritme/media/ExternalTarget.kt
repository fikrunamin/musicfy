package com.example.musicfy.ritme.media

import java.net.URI
import java.util.Locale

/** What the "open another app" confirmation shows about a link, from text alone (no Android Intent). */
object ExternalTarget {
    private const val OPAQUE_MAX = 60

    fun describe(url: String): String {
        val trimmed = url.trim()
        if (trimmed.startsWith("intent:", ignoreCase = true)) return describeIntent(trimmed)
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed.substringBefore('?').take(80)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return trimmed.substringBefore('?').take(80)
        if (uri.isOpaque) {
            return "$scheme:" + uri.rawSchemeSpecificPart.substringBefore('?').take(OPAQUE_MAX)
        }
        return "$scheme://" + uri.host.orEmpty()
    }

    /** `intent://host/path#Intent;scheme=x;package=p;end`: the package if named, else the data scheme (and host). */
    private fun describeIntent(url: String): String {
        val hashAt = url.indexOf("#Intent;", ignoreCase = true)
        val extras = if (hashAt >= 0) url.substring(hashAt + "#Intent;".length).split(';') else emptyList()
        fun extra(key: String) = extras.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.takeIf { it.isNotBlank() }
        extra("package")?.let { return it.take(OPAQUE_MAX) }
        val scheme = extra("scheme")?.lowercase(Locale.ROOT) ?: return "intent:"
        val head = if (hashAt >= 0) url.substring(0, hashAt) else url
        val host = head.removePrefix("intent:").removePrefix("//").substringBefore('/').substringBefore('?')
        return if (host.isNotBlank()) "$scheme://$host".take(OPAQUE_MAX) else "$scheme:"
    }
}

package com.example.musicfy.ritme.media

import java.net.URLDecoder

/**
 * Hand-written URL pieces, like MediaUrlRules' parser: java.net.URI refuses many real-world links
 * (a `|` or an unencoded space in a query), and these only need to be good enough for heuristics.
 */
internal object UrlParts {

    /** The lowercased host of an http(s) [url] (an IPv6 literal keeps its brackets, a final dot is dropped), or null. */
    fun host(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return null
        val scheme = url.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val authority = url.substring(schemeEnd + 3).takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
        val host = if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            authority.substring(0, close + 1)
        } else {
            authority.substringBefore(':')
        }
        return host.lowercase().removeSuffix(".").takeIf { it.isNotEmpty() }
    }

    /** The lowercased path, without query or fragment ("" when there is none). */
    fun path(url: String): String {
        val schemeEnd = url.indexOf("://")
        val rest = if (schemeEnd < 0) url else url.substring(schemeEnd + 3)
        return rest.dropWhile { it != '/' && it != '?' && it != '#' }.substringBefore('#').substringBefore('?').lowercase()
    }

    /** The last path segment's extension, lowercased ("" when it has none). */
    fun extension(url: String): String {
        val last = path(url).substringAfterLast('/')
        return if ('.' in last) last.substringAfterLast('.') else ""
    }

    /** The query's parameters, names and values percent-decoded and lowercased, in order. */
    fun query(url: String): List<Pair<String, String>> {
        val query = url.substringBefore('#').substringAfter('?', "")
        if (query.isEmpty()) return emptyList()
        return query.split('&').filter { it.isNotEmpty() }.map { part ->
            decode(part.substringBefore('=')).lowercase() to decode(part.substringAfter('=', "")).lowercase()
        }
    }

    fun decode(text: String): String = runCatching { URLDecoder.decode(text, Charsets.UTF_8) }.getOrDefault(text)
}

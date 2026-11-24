package com.example.musicfy.ritme.media

import com.example.musicfy.ritme.protocol.MediaUrlRules
import java.net.URLEncoder

/** What the address bar does with what was typed (user decision 2026-11-23). */
sealed interface AddressInput {
    data class Open(val url: String) : AddressInput

    data class Refused(val reason: AddressRefusal) : AddressInput
}

enum class AddressRefusal {
    EMPTY,

    /** javascript:, file:, content:, intent:, data:, and every scheme but http(s). */
    SCHEME,

    /** localhost, a private or local address, or a local name (MediaUrlRules). */
    LOCAL,

    /** `user:password@` in the address. */
    CREDENTIALS,
    MALFORMED,
}

/** What the browser does with a navigation the page starts. */
enum class Navigation { ALLOW, REFUSE_LOCAL, ASK_EXTERNAL, BLOCK }

/**
 * The Ritme browser's address rules: a full http(s) URL opens as typed, a bare domain
 * (`example.com/video`) gets `https://`, anything else is a DuckDuckGo search (U11). Other schemes
 * and local or private hosts (MediaUrlRules, the same rules as room URLs) are refused.
 */
object BrowserAddress {
    const val START_URL = "https://duckduckgo.com/"
    private const val SEARCH_URL = "https://duckduckgo.com/?q="
    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")

    /** Schemes written without "//": never a host name followed by a port. */
    private val OPAQUE_SCHEMES = setOf(
        "javascript", "data", "file", "content", "intent", "about", "blob", "mailto", "tel", "sms", "market",
        "view-source", "chrome", "ws", "wss", "ftp", "vnd.youtube",
    )

    fun resolve(input: String): AddressInput {
        val text = input.trim()
        if (text.isEmpty()) return AddressInput.Refused(AddressRefusal.EMPTY)
        val scheme = SCHEME.find(text)?.groupValues?.get(1)?.lowercase()
        val typedScheme = scheme != null && (text.substring(scheme.length + 1).startsWith("//") || scheme in OPAQUE_SCHEMES)
        if (typedScheme) {
            if (scheme != "http" && scheme != "https") return AddressInput.Refused(AddressRefusal.SCHEME)
            return verdict(text, guessed = false) ?: AddressInput.Refused(AddressRefusal.MALFORMED)
        }
        if (looksLikeHost(text)) verdict("https://$text", guessed = true)?.let { return it }
        return AddressInput.Open(SEARCH_URL + URLEncoder.encode(text, Charsets.UTF_8))
    }

    fun navigation(url: String, hasGesture: Boolean): Navigation {
        val scheme = SCHEME.find(url)?.groupValues?.get(1)?.lowercase() ?: return Navigation.BLOCK
        return when (scheme) {
            "http", "https" -> hostNavigation(url)
            "about" -> if (url.equals("about:blank", ignoreCase = true)) Navigation.ALLOW else Navigation.BLOCK
            "javascript", "file", "content", "data", "blob", "chrome", "view-source" -> Navigation.BLOCK
            // intent:, market:, another app's scheme: only when the user tapped, and only after asking
            else -> if (hasGesture) Navigation.ASK_EXTERNAL else Navigation.BLOCK
        }
    }

    /**
     * Decides on the host alone (browsing is not sharing: length, fragment and userinfo do not matter),
     * failing closed: a local host is REFUSE_LOCAL even behind `user@`; a host or port that cannot be
     * read cleanly (backslash, ideographic dots, port > 65535, no host, no `//`) is BLOCK.
     */
    private fun hostNavigation(url: String): Navigation {
        val rest = url.substring(url.indexOf(':') + 1)
        if (!rest.startsWith("//")) return Navigation.BLOCK
        val authority = rest.substring(2).takeWhile { it != '/' && it != '?' && it != '#' }
        if (MediaUrlRules.check("https://$authority/") == MediaUrlRules.Rejection.FORBIDDEN_CHARACTER) return Navigation.BLOCK
        return when (MediaUrlRules.check("https://${authority.substringAfterLast('@')}/")) {
            null -> Navigation.ALLOW
            MediaUrlRules.Rejection.LOCAL_HOST, MediaUrlRules.Rejection.NON_CANONICAL_IP -> Navigation.REFUSE_LOCAL
            else -> Navigation.BLOCK
        }
    }

    /** Null for a guessed host that is not one after all (`3.5`): the caller searches instead. */
    private fun verdict(url: String, guessed: Boolean): AddressInput? =
        when (MediaUrlRules.check(url.substringBefore('#'))) {
            null -> AddressInput.Open(url)
            MediaUrlRules.Rejection.LOCAL_HOST -> AddressInput.Refused(AddressRefusal.LOCAL)
            MediaUrlRules.Rejection.NON_CANONICAL_IP -> if (guessed) null else AddressInput.Refused(AddressRefusal.LOCAL)
            MediaUrlRules.Rejection.USERINFO -> AddressInput.Refused(AddressRefusal.CREDENTIALS)
            MediaUrlRules.Rejection.SCHEME -> AddressInput.Refused(AddressRefusal.SCHEME)
            else -> if (guessed) null else AddressInput.Refused(AddressRefusal.MALFORMED)
        }

    private fun looksLikeHost(text: String): Boolean {
        if (text.any(Char::isWhitespace)) return false
        val authority = text.takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
        if (authority.startsWith("[")) return true
        val name = authority.substringBefore(':')
        return name.equals("localhost", ignoreCase = true) ||
            ('.' in name && !name.startsWith('.') && !name.endsWith('.'))
    }
}

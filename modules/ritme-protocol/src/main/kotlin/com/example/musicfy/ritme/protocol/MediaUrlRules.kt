package com.example.musicfy.ritme.protocol

/**
 * Shape rules for the `url` of a [MediaKind.URL] entry (spec 3.2), shared by the server
 * (RoomLogic) and every client, which apply them before sending so one bad entry never fails a
 * whole Add. Pure string checks: nothing is resolved or fetched (guarding against DNS rebinding is
 * the playing device's job). The goal: a member cannot make other members' devices reach their
 * router or LAN, and credentials never get broadcast to a room.
 *
 * Cases: fixtures/rules/media-urls.json, also read by ritme-web, whose
 * src/net/media-url-rules.ts is this file's TypeScript twin. Change both together.
 */
object MediaUrlRules {
    const val MAX_URL_LENGTH = 2_048

    enum class Rejection {
        TOO_LONG,

        /**
         * A C0/C1 control character, a backslash, Unicode whitespace (U+00A0, U+2028, U+3000...) or
         * U+FEFF anywhere: exactly what JS `/[\s\p{Cc}\\]/u` matches.
         */
        FORBIDDEN_CHARACTER,

        /** A `#fragment`: clients strip it before sending. */
        FRAGMENT,
        MALFORMED,

        /** Anything but http/https. */
        SCHEME,

        /** `user:pass@`: credentials would be broadcast to the room. */
        USERINFO,
        NO_HOST,

        /** A numeric host that is not a dotted quad (`2130706433`, `0x7f.0.0.1`, `127.1`, `010.0.0.1`): browsers read these as IPv4. */
        NON_CANONICAL_IP,

        /** Loopback, private, link-local, CGNAT, unspecified, multicast or reserved addresses, and local names. */
        LOCAL_HOST,
    }

    fun isAcceptableMediaUrl(url: String): Boolean = check(url) == null

    /** Why [url] is refused, or null when it is acceptable. */
    fun check(url: String): Rejection? {
        if (url.length > MAX_URL_LENGTH) return Rejection.TOO_LONG
        if (url.any(::isForbiddenCharacter)) return Rejection.FORBIDDEN_CHARACTER
        if ('#' in url) return Rejection.FRAGMENT
        val scheme = SCHEME.find(url)?.groupValues?.get(1) ?: return Rejection.MALFORMED
        val lower = scheme.lowercase()
        if (lower != "http" && lower != "https") return Rejection.SCHEME
        val rest = url.substring(scheme.length + 1)
        if (!rest.startsWith("//")) return Rejection.MALFORMED
        val authority = rest.substring(2).takeWhile { it != '/' && it != '?' }
        if ('@' in authority) return Rejection.USERINFO
        val host = hostOf(authority) ?: return Rejection.MALFORMED
        if (host.isEmpty()) return Rejection.NO_HOST
        return hostRejection(host)
    }

    /** C0, space, DEL, C1 and U+00A0; the other Unicode White_Space characters; U+FEFF; backslash. */
    private fun isForbiddenCharacter(c: Char): Boolean =
        c <= ' ' || c in '\u007F'..'\u00A0' || c == '\\' || c == '\u1680' || c in '\u2000'..'\u200A' ||
            c == '\u2028' || c == '\u2029' || c == '\u202F' || c == '\u205F' || c == '\u3000' || c == '\uFEFF'

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val PORT = Regex(":([0-9]{1,5})?")
    private val HOST_NAME = Regex("^[a-z0-9_-]+(\\.[a-z0-9_-]+)*\\.?$")
    private val NUMERIC_LABEL = Regex("^(0x[0-9a-f]*|[0-9]+)$")
    private val IPV4_PART = Regex("^(0|[1-9][0-9]{0,2})$")
    private val LOCAL_NAMES = setOf("home.arpa", "localdomain", "localdomain4", "localdomain6")

    /** `*.localdomain` too: Linux resolves `localhost.localdomain` (and RHEL `localhost4.localdomain4`) to loopback. */
    private val LOCAL_SUFFIXES = listOf(
        ".localhost", ".local", ".internal", ".lan", ".home.arpa", ".localdomain", ".localdomain4", ".localdomain6",
    )

    /** The host part of [authority] (an IPv6 literal keeps its brackets), lowercased; null when malformed. */
    private fun hostOf(authority: String): String? {
        val hostEnd = if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            close + 1
        } else {
            authority.lastIndexOf(':').takeIf { it >= 0 } ?: authority.length
        }
        if (hostEnd < authority.length) {
            val port = PORT.matchEntire(authority.substring(hostEnd)) ?: return null
            val digits = port.groupValues[1]
            if (digits.isNotEmpty() && digits.toInt() > 65_535) return null
        }
        return authority.substring(0, hostEnd).lowercase()
    }

    private fun hostRejection(host: String): Rejection? {
        if (host.startsWith("[")) {
            val bytes = parseIpv6(host.substring(1, host.length - 1)) ?: return Rejection.MALFORMED
            return if (isLocalIpv6(bytes)) Rejection.LOCAL_HOST else null
        }
        if (!HOST_NAME.matches(host)) return Rejection.MALFORMED
        val name = host.removeSuffix(".")
        if (NUMERIC_LABEL.matches(name.substringAfterLast('.'))) {
            val v4 = parseIpv4(name) ?: return Rejection.NON_CANONICAL_IP
            return if (isLocalIpv4(v4)) Rejection.LOCAL_HOST else null
        }
        // Single-label names resolve through the LAN's search domain (router, nas, printer).
        if ('.' !in name || name in LOCAL_NAMES || LOCAL_SUFFIXES.any { name.endsWith(it) }) return Rejection.LOCAL_HOST
        return null
    }

    /** Four decimal parts 0-255 without leading zeros, or null. */
    private fun parseIpv4(text: String): IntArray? {
        val parts = text.split('.')
        if (parts.size != 4 || parts.any { !IPV4_PART.matches(it) }) return null
        val bytes = IntArray(4) { parts[it].toInt() }
        return bytes.takeIf { b -> b.all { it <= 255 } }
    }

    /**
     * 0/8, 10/8, 127/8, 100.64/10, 169.254/16, 172.16/12, 192.0.0/24 (IETF protocol assignments),
     * 192.168/16, 198.18/15 (benchmarking, also the fake-IP range of Clash/Surge proxies), and
     * 224/4 + 240/4 (multicast, reserved, broadcast).
     */
    private fun isLocalIpv4(a: IntArray): Boolean =
        a[0] == 0 || a[0] == 10 || a[0] == 127 || a[0] >= 224 ||
            (a[0] == 100 && a[1] in 64..127) || (a[0] == 169 && a[1] == 254) ||
            (a[0] == 172 && a[1] in 16..31) || (a[0] == 192 && a[1] == 168) ||
            (a[0] == 192 && a[1] == 0 && a[2] == 0) || (a[0] == 198 && a[1] in 18..19)

    /** 16 bytes, or null. Accepts `::` and a dotted IPv4 tail; no zone ids. */
    private fun parseIpv6(text: String): IntArray? {
        if (text.isEmpty() || text.any { !(it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '.') }) return null
        val halves = text.split("::")
        if (halves.size > 2 || (halves.size == 2 && '.' in halves[0])) return null
        val head = ipv6Words(halves[0]) ?: return null
        val tail = if (halves.size == 2) ipv6Words(halves[1]) ?: return null else emptyList()
        val missing = 8 - head.size - tail.size
        val fits = if (halves.size == 2) missing >= 1 else missing == 0
        if (!fits) return null
        val words = head + List(missing) { 0 } + tail
        return IntArray(16) { i -> if (i % 2 == 0) words[i / 2] shr 8 else words[i / 2] and 0xFF }
    }

    private fun ipv6Words(part: String): List<Int>? {
        if (part.isEmpty()) return emptyList()
        val pieces = part.split(':')
        val words = mutableListOf<Int>()
        for ((i, piece) in pieces.withIndex()) {
            if (i == pieces.lastIndex && '.' in piece) {
                val v4 = parseIpv4(piece) ?: return null
                words += (v4[0] shl 8) or v4[1]
                words += (v4[2] shl 8) or v4[3]
            } else {
                if (piece.isEmpty() || piece.length > 4 || '.' in piece) return null
                words += piece.toInt(16)
            }
        }
        return words
    }

    private fun isLocalIpv6(b: IntArray): Boolean {
        val embeddedV4 = b.copyOfRange(12, 16)
        return when {
            // ::, ::1 and the deprecated IPv4-compatible ::a.b.c.d
            (0 until 12).all { b[it] == 0 } -> true
            // IPv4-mapped ::ffff:a.b.c.d
            (0 until 10).all { b[it] == 0 } && b[10] == 0xFF && b[11] == 0xFF -> isLocalIpv4(embeddedV4)
            // SIIT IPv4-translated ::ffff:0:a.b.c.d
            (0 until 8).all { b[it] == 0 } && b[8] == 0xFF && b[9] == 0xFF && b[10] == 0 && b[11] == 0 -> isLocalIpv4(embeddedV4)
            // NAT64 64:ff9b::/96
            b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xFF && b[3] == 0x9B && (4 until 12).all { b[it] == 0 } -> isLocalIpv4(embeddedV4)
            // Local-use NAT64 64:ff9b:1::/48 (RFC 8215): a local translator by definition
            b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xFF && b[3] == 0x9B && b[4] == 0x00 && b[5] == 0x01 -> true
            // 6to4 2002:a.b.c.d::/48
            b[0] == 0x20 && b[1] == 0x02 -> isLocalIpv4(b.copyOfRange(2, 6))
            // fc00::/7 unique local
            (b[0] and 0xFE) == 0xFC -> true
            // fe80::/10 link-local and fec0::/10 site-local
            b[0] == 0xFE && b[1] >= 0x80 -> true
            // ff00::/8 multicast
            b[0] == 0xFF -> true
            else -> false
        }
    }
}

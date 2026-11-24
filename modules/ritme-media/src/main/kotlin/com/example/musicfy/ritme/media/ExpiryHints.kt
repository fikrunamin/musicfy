package com.example.musicfy.ritme.media

/** What a link's query says about how long and where it works (spec 4.7 step 4). */
data class LinkHints(
    /** Minutes until an `expires`/`exp` time in the link; 0 or less: already expired. Null: no such time. */
    val expiresInMinutes: Long?,
    /** An `ip=` parameter: the link may only work from the network that opened it. */
    val ipLocked: Boolean,
)

object ExpiryHints {
    private val EXPIRY_NAMES = setOf("expires", "exp", "expire", "expiry", "expiration")
    private val TOKEN_NAMES = setOf("hdnts", "hdnea", "__token__")
    private val TOKEN_EXPIRY = Regex("""(?:^|[~&])exp=(\d{9,13})""")
    private val IP_NAMES = setOf("ip", "ipaddr", "clientip", "client_ip")

    /** 2001-09-09: smaller numbers are lifetimes in seconds, not times. */
    private const val MIN_EPOCH_SECONDS = 1_000_000_000L

    fun of(url: String, nowMs: Long): LinkHints {
        val query = UrlParts.query(url)
        val expiresAt = query.mapNotNull { (name, value) ->
            when (name) {
                in EXPIRY_NAMES -> epochMs(value)
                in TOKEN_NAMES -> TOKEN_EXPIRY.find(value)?.groupValues?.get(1)?.let(::epochMs)
                else -> null
            }
        }.minOrNull()
        val minutes = expiresAt?.let { Math.floorDiv(it - nowMs + 59_999, 60_000L) }
        return LinkHints(minutes, query.any { it.first in IP_NAMES && it.second.isNotEmpty() })
    }

    /** Seconds (or milliseconds, 13 digits) since the epoch, or null when it is not a plausible time. */
    private fun epochMs(value: String): Long? {
        if (value.isEmpty() || value.length > 13 || !value.all(Char::isDigit)) return null
        val number = value.toLong()
        val ms = if (value.length == 13) number else number * 1000
        return ms.takeIf { it >= MIN_EPOCH_SECONDS * 1000 }
    }
}

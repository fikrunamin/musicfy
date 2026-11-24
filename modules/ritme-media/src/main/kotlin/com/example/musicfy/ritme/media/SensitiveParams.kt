package com.example.musicfy.ritme.media

/**
 * Query parameters that look like a private key to the stream (spec 4.7 step 6): the confirmation
 * warns "Link ini mungkin berisi kunci akses pribadi." before the link is shown to a room.
 */
object SensitiveParams {
    private val EXACT = setOf(
        "token", "auth", "key", "session", "jwt", "sig", "sid", "hmac", "policy", "key-pair-id", "pass",
        "apikey", "api_key", "hdnts", "hdnea",
    )
    private val PARTS = listOf("token", "signature", "session", "jwt", "secret", "password", "credential")

    /** The sensitive parameter names in [url]'s query, lowercased, in order, without repeats. */
    fun names(url: String): List<String> =
        UrlParts.query(url).map { it.first }.filter { name -> name in EXACT || PARTS.any { it in name } }.distinct()

    fun has(url: String): Boolean = names(url).isNotEmpty()
}

package com.example.musicfy.ritme.media

import java.net.URI
import java.util.Locale

/** Binds what a page says about itself to who really sent it: reports are untrusted. */
object SenderBinding {
    /** scheme://host:port with default ports filled in, lower-cased; null when [url] is not an http(s) address. */
    fun originOf(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val port = if (uri.port != -1) uri.port else when (scheme) {
            "https" -> 443
            "http" -> 80
            else -> return null
        }
        if (scheme != "https" && scheme != "http") return null
        return "$scheme://$host:$port"
    }

    /** [frameUrl] when it belongs to [senderOrigin], else null (the page cannot name another site's frame). */
    fun boundFrameUrl(frameUrl: String?, senderOrigin: String): String? {
        if (frameUrl == null) return null
        val claimed = originOf(frameUrl) ?: return null
        return frameUrl.takeIf { claimed == originOf(senderOrigin) }
    }
}

/** At most [limit] events per [windowMs] per key; the rest are refused. When the table is full a new key evicts stale keys, else the least recently used one. Thread-safe. */
class RateLimiter(
    private val limit: Int = 10,
    private val windowMs: Long = 1_000L,
    private val maxKeys: Int = 64,
) {
    private val recent = HashMap<String, ArrayDeque<Long>>()

    /** Keys currently tracked. */
    internal val size: Int @Synchronized get() = recent.size

    @Synchronized
    fun allow(key: String, nowMs: Long): Boolean {
        if (recent.size >= maxKeys && key !in recent) {
            recent.entries.removeAll { (_, times) -> times.isEmpty() || nowMs - times.last() >= windowMs }
            if (recent.size >= maxKeys) {
                recent.entries.minByOrNull { (_, times) -> times.lastOrNull() ?: Long.MIN_VALUE }?.let { recent.remove(it.key) }
            }
        }
        val times = recent.getOrPut(key) { ArrayDeque() }
        while (times.isNotEmpty() && nowMs - times.first() >= windowMs) times.removeFirst()
        if (times.size >= limit) return false
        times.addLast(nowMs)
        return true
    }
}

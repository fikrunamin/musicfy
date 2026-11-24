package com.example.musicfy.ritme.video

import android.media.MediaMetadataRetriever
import com.example.musicfy.ritme.playback.RitmeAddressGuard
import kotlinx.coroutines.ensureActive
import java.net.InetAddress
import java.net.URI

/**
 * A progressive file's length, read on this (the adder's) device by MediaMetadataRetriever (#2b
 * Decision 13, spec 4.7): no cookies, and a Referer only when the browser's check found that the
 * host needs one. At most [TIMEOUT_MS]; null on any failure. Only when every address of the host
 * is public (RitmeAddressGuard): this does not go through the room's guarded OkHttp client.
 */
object RitmeFileLength {
    private const val TIMEOUT_MS = 8_000L

    /** Process-wide: a probe outlives the sheet or page that asked for it. */
    private val gate = RitmeProbeGate(TIMEOUT_MS)

    suspend fun read(url: String, referrer: String? = null): Long? = gate.run {
        // setDataSource blocks and cannot be interrupted: RitmeProbeGate runs it apart, at most two
        // at a time, and gives up waiting after the timeout.
        runCatching {
            val host = URI(url).host ?: return@runCatching null
            if (!InetAddress.getAllByName(host).all(RitmeAddressGuard::isPublic)) return@runCatching null
            ensureActive()
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(url, referrer?.let { mapOf("Referer" to it) } ?: emptyMap())
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }
}

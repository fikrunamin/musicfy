package com.example.musicfy.ritme.browser

import com.example.musicfy.ritme.media.MediaProber
import com.example.musicfy.ritme.media.ProbeVerdict
import com.example.musicfy.ritme.media.Refusal
import com.example.musicfy.ritme.playback.RitmeItems
import com.example.musicfy.ritme.playback.RitmeUrlHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Checks found and pasted links on this device (spec 4.7 step 4), the way another member's device
 * will fetch them: the room's guarded OkHttp client (RitmeUrlHttp), never WebView or YouTube
 * cookies. At most [MAX_PARALLEL] at once, each given up after [TIMEOUT_MS] (a failure then).
 */
object RitmeProbes {
    const val MAX_PARALLEL = 4
    const val TIMEOUT_MS = 8_000L

    /** The Chrome user agent members' players send; the browser fills it in from its WebView. */
    @Volatile
    var userAgent: String = RitmeItems.FALLBACK_USER_AGENT

    private val slots = Semaphore(MAX_PARALLEL)
    private val http by lazy {
        RitmeProbeHttp(RitmeUrlHttp.client { userAgent }.newBuilder().callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).build())
    }

    /** [webOrigin]: the Ritme web client's origin (RitmeController.webOrigin), for the CORS check. */
    suspend fun probe(url: String, pageUrl: String?, webOrigin: String): ProbeVerdict = slots.withPermit {
        withTimeoutOrNull(TIMEOUT_MS) {
            // Blocking OkHttp calls, interrupted when the timeout (or the caller) gives up.
            runInterruptible(Dispatchers.IO) { MediaProber(http, webOrigin).probe(url, pageUrl) }
        } ?: ProbeVerdict.refused(url, Refusal.FAILED)
    }
}

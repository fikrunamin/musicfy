package com.example.musicfy.ritme.browser

import com.example.musicfy.ritme.media.MediaProber
import com.example.musicfy.ritme.media.ProbeVerdict
import com.example.musicfy.ritme.media.Refusal
import com.example.musicfy.ritme.playback.RitmeItems
import com.example.musicfy.ritme.playback.RitmeUrlHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    private val client by lazy {
        RitmeUrlHttp.client { userAgent }.newBuilder().callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    }

    /** [webOrigin]: the Ritme web client's origin (RitmeController.webOrigin), for the CORS check. */
    suspend fun probe(url: String, pageUrl: String?, webOrigin: String): ProbeVerdict = slots.withPermit {
        withTimeoutOrNull(TIMEOUT_MS) { run(url, pageUrl, webOrigin) } ?: ProbeVerdict.refused(url, Refusal.FAILED)
    }

    /**
     * The blocking probe on an IO thread. When the timeout or the caller cancels, the OkHttp calls
     * are cancelled too (a socket read ignores thread interruption), so the permit is freed only
     * once the network is really no longer used.
     */
    private suspend fun run(url: String, pageUrl: String?, webOrigin: String): ProbeVerdict = coroutineScope {
        val calls = ConcurrentLinkedQueue<Call>()
        val cancelled = AtomicBoolean(false)
        // A call the prober starts after the cancel (its Referer retry) is cancelled at once.
        val track = { call: Call -> calls.add(call); if (cancelled.get()) call.cancel() }
        val prober = MediaProber(RitmeProbeHttp(client, track), webOrigin)
        val work = async(Dispatchers.IO) { runInterruptible { prober.probe(url, pageUrl) } }
        try {
            work.await()
        } catch (e: CancellationException) {
            cancelled.set(true)
            calls.forEach(Call::cancel)
            throw e
        }
    }
}

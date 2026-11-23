package com.example.musicfy.ritme.net

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean

/** Main-thread timers on the monotonic elapsedRealtime clock (spec 2.4). */
class HandlerRitmeTimers(private val handler: Handler = Handler(Looper.getMainLooper())) : RitmeTimers {
    override fun now(): Long = SystemClock.elapsedRealtime()

    override fun schedule(delayMs: Long, block: () -> Unit): RitmeTimer {
        val runnable = Runnable { block() }
        handler.postDelayed(runnable, delayMs.coerceAtLeast(0))
        return RitmeTimer { handler.removeCallbacks(runnable) }
    }
}

/**
 * OkHttp WebSocket, with every callback re-posted to the timers' (main) thread in arrival order,
 * since RitmeConnection is single-threaded on that thread (and the listener is never called from
 * inside [open]). OkHttp has no inbound frame cap, so ~260 KB snapshots are fine; close() sends
 * queued frames (e.g. Leave) before the close frame. A URL OkHttp cannot parse (e.g. a server URL
 * without a scheme) never throws: the socket reports [RitmeSocketListener.onClosed] without
 * opening, so the connection ends as Unreachable.
 */
class OkHttpRitmeSocketFactory(
    private val client: OkHttpClient,
    private val timers: RitmeTimers,
) : RitmeSocketFactory {
    override fun open(url: String, listener: RitmeSocketListener): RitmeSocket {
        val httpUrl = socketHttpUrl(url)
        if (httpUrl == null) {
            timers.schedule(0) { listener.onClosed() }
            return DeadSocket
        }
        val closedOnce = AtomicBoolean(false)
        fun closed() {
            if (closedOnce.compareAndSet(false, true)) timers.schedule(0) { listener.onClosed() }
        }
        val webSocket = client.newWebSocket(
            Request.Builder().url(httpUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    timers.schedule(0) { listener.onOpen() }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    timers.schedule(0) { listener.onMessage(text) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    closed()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed()

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closed()
            },
        )
        return object : RitmeSocket {
            override fun send(text: String): Boolean = webSocket.send(text)
            override fun close() {
                webSocket.close(1000, "bye")
            }

            override fun cancel() {
                webSocket.cancel()
            }
        }
    }
}

/** A socket that never opened: sends fail, close/cancel have nothing to do. */
private object DeadSocket : RitmeSocket {
    override fun send(text: String): Boolean = false
    override fun close() = Unit
    override fun cancel() = Unit
}

/** `ws(s)://…` as the `http(s)://…` URL OkHttp's WebSocket request takes; null if it does not parse. */
internal fun socketHttpUrl(url: String): HttpUrl? {
    val http = when {
        url.startsWith("ws://", ignoreCase = true) -> "http://" + url.substring("ws://".length)
        url.startsWith("wss://", ignoreCase = true) -> "https://" + url.substring("wss://".length)
        else -> url
    }
    return http.toHttpUrlOrNull()
}

class DefaultRitmeConnector(
    private val sockets: RitmeSocketFactory,
    private val timers: RitmeTimers,
) : RitmeConnector {
    override fun connect(url: String, onEvent: (ConnectionEvent) -> Unit): ConnectionLike =
        RitmeConnection(url, sockets, timers, onEvent)
}

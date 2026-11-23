package com.example.musicfy.ritme.net

/**
 * One WebSocket. [send] queues a text frame; [close] starts a normal close (queued frames go
 * first); [cancel] drops the connection at once (a socket that never opened has nothing to flush).
 */
interface RitmeSocket {
    fun send(text: String): Boolean
    fun close()
    fun cancel()
}

/** Called on the connection's thread. [onClosed] covers a normal close and any failure, once. */
interface RitmeSocketListener {
    fun onOpen()
    fun onMessage(text: String)
    fun onClosed()
}

/** Opens sockets. Must never call the listener from inside [open]. */
fun interface RitmeSocketFactory {
    fun open(url: String, listener: RitmeSocketListener): RitmeSocket
}

fun interface RitmeTimer {
    fun cancel()
}

/** Monotonic clock + delayed tasks on the connection's thread. Never runs a block from inside [schedule]. */
interface RitmeTimers {
    fun now(): Long
    fun schedule(delayMs: Long, block: () -> Unit): RitmeTimer
}

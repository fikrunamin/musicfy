package com.example.musicfy.ritme.testing

import com.example.musicfy.ritme.net.RitmeSocket
import com.example.musicfy.ritme.net.RitmeSocketFactory
import com.example.musicfy.ritme.net.RitmeSocketListener
import com.example.musicfy.ritme.net.RitmeTimer
import com.example.musicfy.ritme.net.RitmeTimers
import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.ServerMessage
import com.example.musicfy.ritme.protocol.decodeClientMessage
import com.example.musicfy.ritme.protocol.encode

/** Manual clock: tasks run in time order (then scheduling order) during [advanceBy]. */
class FakeTimers(var time: Long = 0) : RitmeTimers {
    private class Task(val at: Long, val seq: Long, val block: () -> Unit) {
        var cancelled = false
    }

    private val tasks = mutableListOf<Task>()
    private var seq = 0L

    override fun now(): Long = time

    override fun schedule(delayMs: Long, block: () -> Unit): RitmeTimer {
        val task = Task(time + delayMs.coerceAtLeast(0), seq++, block)
        tasks += task
        return RitmeTimer { task.cancelled = true }
    }

    fun advanceBy(ms: Long) {
        val target = time + ms
        while (true) {
            val next = tasks
                .filter { !it.cancelled && it.at <= target }
                .minWithOrNull(compareBy<Task>({ it.at }, { it.seq }))
                ?: break
            tasks.remove(next)
            time = next.at
            next.block()
        }
        time = target
        tasks.removeAll { it.cancelled }
    }

    /** Scheduled tasks that have neither run nor been cancelled. */
    fun pendingCount(): Int = tasks.count { !it.cancelled }
}

class FakeSocket(val url: String, private val listener: RitmeSocketListener) : RitmeSocket {
    val sent = mutableListOf<String>()
    var closed = false
        private set

    /** When true, [send] fails like a socket whose link is gone (nothing is recorded). */
    var refuseSends = false

    /** Called with every frame [send] accepts (e.g. to answer Pings like a live server). */
    var onSend: ((String) -> Unit)? = null

    override fun send(text: String): Boolean {
        if (closed || refuseSends) return false
        sent += text
        onSend?.invoke(text)
        return true
    }

    override fun close() {
        closed = true
    }

    var cancelled = false
        private set

    override fun cancel() {
        cancelled = true
        closed = true
    }

    fun open() = listener.onOpen()

    fun receive(message: ServerMessage) = listener.onMessage(message.encode())

    fun receiveRaw(text: String) = listener.onMessage(text)

    fun drop() = listener.onClosed()

    fun sentMessages(): List<ClientMessage> = sent.map(::decodeClientMessage)

    /** Answers every Ping with a Pong [delayMs] later, like a live server (keeps the watchdog quiet). */
    fun autoPong(timers: RitmeTimers, delayMs: Long = 30) {
        onSend = { text ->
            val message = decodeClientMessage(text)
            if (message is ClientMessage.Ping) {
                timers.schedule(delayMs) { receive(ServerMessage.Pong(clientTime = message.clientTime, serverTime = 1)) }
            }
        }
    }
}

class FakeSocketFactory : RitmeSocketFactory {
    val sockets = mutableListOf<FakeSocket>()
    val last: FakeSocket get() = sockets.last()

    override fun open(url: String, listener: RitmeSocketListener): RitmeSocket =
        FakeSocket(url, listener).also { sockets += it }
}

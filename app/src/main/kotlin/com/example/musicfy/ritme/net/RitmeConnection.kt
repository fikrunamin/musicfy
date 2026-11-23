package com.example.musicfy.ritme.net

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.PROTOCOL_VERSION
import com.example.musicfy.ritme.protocol.ServerMessage
import com.example.musicfy.ritme.protocol.decodeServerMessageOrNull
import com.example.musicfy.ritme.protocol.encode

/**
 * One Ritme session over WebSocket, mirroring ritme-web/src/net/connection.ts: Create/Join the
 * moment each socket opens (Join with the resume token after the first Welcome), Ack every
 * Welcome/State with its exact version, clock pings, reconnect with backoff, and exactly one
 * Ended event. Unknown or malformed frames are skipped. A liveness watchdog drops a socket that
 * went silent (see [PONG_TIMEOUT_MS], [IDLE_TIMEOUT_MS]). Single-threaded: every call and callback
 * runs on the timers' thread.
 */
class RitmeConnection(
    private val url: String,
    private val socketFactory: RitmeSocketFactory,
    private val timers: RitmeTimers,
    private val onEvent: (ConnectionEvent) -> Unit,
) : ConnectionLike {
    private var socket: RitmeSocket? = null
    private var intent: JoinIntent? = null
    private var welcomed = false
    private var joined = false
    private var ended = false
    private var attempt = 0
    private var droppedAt: Long? = null
    private var resumeCode: String? = null
    private var resumeToken: String? = null
    private var nextReqId = 1
    /** Local time of the last frame / last Pong on the current socket (watchdog). */
    private var lastInboundAt = 0L
    private var lastPongAt = Long.MIN_VALUE
    private val socketTimers = mutableListOf<RitmeTimer>()
    private var reconnectTimer: RitmeTimer? = null

    override fun start(intent: JoinIntent) {
        check(this.intent == null) { "RitmeConnection.start called twice" }
        this.intent = intent
        emit(ConnectionEvent.Status(ConnectionStatus.CONNECTING))
        open()
    }

    override fun command(cmd: Command): String? {
        if (!joined) return null
        val reqId = "r${nextReqId++}"
        send(ClientMessage.Send(reqId, cmd))
        return reqId
    }

    override fun trackEnded(entryId: String): Boolean {
        if (!joined) return false
        return send(ClientMessage.TrackEnded(entryId))
    }

    override fun leave() {
        if (ended) return
        // Before the first Welcome there is no member to remove: just close.
        if (joined) send(ClientMessage.Leave)
        finish(EndReason.Left)
    }

    private fun open() {
        joined = false
        lastInboundAt = timers.now()
        lastPongAt = Long.MIN_VALUE
        var opened = false
        lateinit var current: RitmeSocket
        val listener = object : RitmeSocketListener {
            override fun onOpen() {
                if (socket !== current) return
                opened = true
                send(joinMessage())
                later(JOIN_TIMEOUT_MS) { if (!joined) drop(current) }
                for (i in 0 until PING_BURST) later(i * PING_BURST_GAP_MS) { ping() }
                later(PING_INTERVAL_MS) { pingLoop() }
            }

            override fun onMessage(text: String) {
                if (socket !== current) return
                lastInboundAt = timers.now()
                decodeServerMessageOrNull(text)?.let(::handle)
            }

            override fun onClosed() = drop(current)
        }
        current = socketFactory.open(url, listener)
        socket = current
        later(CONNECT_TIMEOUT_MS) { if (!opened) drop(current, abort = true) }
    }

    private fun joinMessage(): ClientMessage = when (val i = intent!!) {
        is JoinIntent.Create -> ClientMessage.Create(PROTOCOL_VERSION, i.name, i.initialQueue, i.startIndex, i.startPositionMs)
        is JoinIntent.Join -> ClientMessage.Join(PROTOCOL_VERSION, i.code, i.name, resumeToken.takeIf { resumeCode == i.code })
    }

    private fun handle(message: ServerMessage) {
        when (message) {
            is ServerMessage.Welcome -> {
                joined = true
                welcomed = true
                attempt = 0
                droppedAt = null
                // Every reconnect joins the room this Welcome is for, with its token.
                intent = JoinIntent.Join(message.state.code, intent!!.name)
                resumeCode = message.state.code
                resumeToken = message.resumeToken
                val current = socket
                // finally: a throwing welcome listener must not leave the UI offline, nor skip the Ack
                // (the server withholds every newer snapshot); the exception still propagates.
                try {
                    emit(ConnectionEvent.Welcome(message, timers.now()))
                } finally {
                    try {
                        // Ended is the last event: a listener may have left during the Welcome.
                        if (!ended) emit(ConnectionEvent.Status(ConnectionStatus.ONLINE))
                    } finally {
                        ack(message.state.version)
                        // Not if a listener already left: no timers may outlive the socket.
                        if (current != null && socket === current) watchIdle(current)
                    }
                }
            }
            is ServerMessage.State -> if (joined) {
                // Acked even when the reducer drops it as stale: the server gates on the exact version it sent.
                try {
                    emit(ConnectionEvent.State(message.state))
                } finally {
                    ack(message.state.version)
                }
            }
            is ServerMessage.Pong -> {
                lastPongAt = timers.now()
                emit(ConnectionEvent.Pong(message.clientTime, message.serverTime, timers.now()))
            }
            is ServerMessage.Error -> when {
                joined -> emit(ConnectionEvent.Failure(message.reqId, message.code))
                // A reconnect hit a connection cap: the server closes, and the backoff tries again.
                welcomed && message.code == ErrorCode.RATE_LIMITED -> Unit
                else -> finish(if (welcomed) EndReason.Lost else EndReason.Rejected(message.code))
            }
            ServerMessage.Kicked -> finish(EndReason.Kicked)
            ServerMessage.RoomClosed -> finish(EndReason.Closed)
        }
    }

    private fun drop(dropped: RitmeSocket, abort: Boolean = false) {
        if (socket !== dropped) return
        detach(dropped, abort)
        if (ended) return
        if (!welcomed) {
            finish(EndReason.Unreachable)
            return
        }
        val now = timers.now()
        val since = droppedAt ?: now
        if (droppedAt == null) {
            droppedAt = now
            emit(ConnectionEvent.Status(ConnectionStatus.RECONNECTING))
        }
        // Give up 30 s after the first drop: delays are clamped so a last attempt lands at the 30 s mark.
        val remaining = since + RECONNECT_GIVE_UP_MS - now
        if (remaining <= 0) {
            finish(EndReason.Lost)
            return
        }
        val delay = minOf(BACKOFF_MS[minOf(attempt, BACKOFF_MS.lastIndex)], remaining)
        attempt++
        reconnectTimer = timers.schedule(delay) {
            reconnectTimer = null
            if (!ended) open()
        }
    }

    private fun finish(reason: EndReason) {
        if (ended) return
        ended = true
        resumeCode = null
        resumeToken = null
        reconnectTimer?.cancel()
        reconnectTimer = null
        socket?.let(::detach)
        emit(ConnectionEvent.Ended(reason))
    }

    /** Forgets a socket: its timers cancelled, its callbacks ignored from now on, closed (or cancelled when [abort]). */
    private fun detach(s: RitmeSocket, abort: Boolean = false) {
        socketTimers.forEach { it.cancel() }
        socketTimers.clear()
        if (socket === s) socket = null
        joined = false
        if (abort) s.cancel() else s.close()
    }

    private fun ack(version: Long) {
        if (!ended) send(ClientMessage.Ack(version))
    }

    /** Sends a Ping and drops the socket if no Pong (for it or a later Ping) arrives in time. */
    private fun ping() {
        val current = socket ?: return
        val sentAt = timers.now()
        if (!current.send(ClientMessage.Ping(sentAt).encode())) return
        // A silent socket is presumed dead: cancelled, since a close handshake would only hang.
        later(PONG_TIMEOUT_MS) { if (lastPongAt < sentAt) drop(current, abort = true) }
    }

    /** Drops the socket once nothing at all has arrived for [IDLE_TIMEOUT_MS] (started on Welcome). */
    private fun watchIdle(current: RitmeSocket) {
        fun check() {
            val left = lastInboundAt + IDLE_TIMEOUT_MS - timers.now()
            if (left <= 0) drop(current, abort = true) else later(left) { check() }
        }
        later(IDLE_TIMEOUT_MS) { check() }
    }

    private fun pingLoop() {
        ping()
        later(PING_INTERVAL_MS) { pingLoop() }
    }

    private fun later(delayMs: Long, block: () -> Unit) {
        lateinit var timer: RitmeTimer
        timer = timers.schedule(delayMs) {
            socketTimers.remove(timer)
            block()
        }
        socketTimers += timer
    }

    private fun send(message: ClientMessage): Boolean = socket?.send(message.encode()) ?: false

    private fun emit(event: ConnectionEvent) = onEvent(event)

    companion object {
        /** Reconnect delays (spec 4.1). */
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)
        const val RECONNECT_GIVE_UP_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 8_000L
        /** The server closes a socket that has not joined within 10 s. */
        const val JOIN_TIMEOUT_MS = 10_000L
        const val PING_BURST = 5
        const val PING_BURST_GAP_MS = 200L
        const val PING_INTERVAL_MS = 30_000L
        /**
         * Liveness watchdog (as in ritme-web): a half-open TCP connection (e.g. after a network
         * switch) may never report a close. A Ping with no Pong this long after it, or no inbound
         * frame at all for [IDLE_TIMEOUT_MS] after the Welcome (Pings every 30 s keep a healthy
         * socket well inside it), drops the socket into the normal backoff and resume path.
         * OkHttp's own pingInterval cannot see these application-level Pongs.
         */
        const val PONG_TIMEOUT_MS = 10_000L
        const val IDLE_TIMEOUT_MS = 75_000L
    }
}

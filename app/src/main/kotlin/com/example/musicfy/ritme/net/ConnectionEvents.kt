package com.example.musicfy.ritme.net

import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.protocol.ServerMessage

enum class ConnectionStatus { CONNECTING, ONLINE, RECONNECTING }

/** Why a session ended. Exactly one Ended event is emitted per connection. */
sealed interface EndReason {
    data object Left : EndReason
    data object Kicked : EndReason
    data object Closed : EndReason
    /** The connection dropped after a Welcome and could not resume within 30 s. */
    data object Lost : EndReason
    /** No Welcome was ever received (server unreachable, timed out). */
    data object Unreachable : EndReason
    /** The server refused Create/Join before any Welcome. */
    data class Rejected(val code: ErrorCode) : EndReason
}

sealed interface JoinIntent {
    val name: String

    data class Create(
        override val name: String,
        val initialQueue: List<NewEntry> = emptyList(),
        val startIndex: Int = 0,
        val startPositionMs: Long = 0,
    ) : JoinIntent

    data class Join(val code: String, override val name: String) : JoinIntent
}

sealed interface ConnectionEvent {
    data class Status(val status: ConnectionStatus) : ConnectionEvent
    data class Welcome(val welcome: ServerMessage.Welcome, val receivedAt: Long) : ConnectionEvent
    data class State(val state: RoomState) : ConnectionEvent
    data class Pong(val clientTime: Long, val serverTime: Long, val receivedAt: Long) : ConnectionEvent
    /** A server Error while joined (a rejected command). Named Failure to stay clear of kotlin.Error. */
    data class Failure(val reqId: String?, val code: ErrorCode) : ConnectionEvent
    data class Ended(val reason: EndReason) : ConnectionEvent
}

/** What RitmeSession needs from a connection (a fake in tests). */
interface ConnectionLike {
    fun start(intent: JoinIntent)

    /** Sends a command and returns its reqId; null (dropped) while not joined. */
    fun command(cmd: Command): String?

    fun trackEnded(entryId: String): Boolean

    fun leave()
}

fun interface RitmeConnector {
    fun connect(url: String, onEvent: (ConnectionEvent) -> Unit): ConnectionLike
}

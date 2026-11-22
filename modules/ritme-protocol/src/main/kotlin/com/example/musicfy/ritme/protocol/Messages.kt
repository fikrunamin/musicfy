package com.example.musicfy.ritme.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ROOM_FULL also means the server is at its room cap; RATE_LIMITED also means a connection cap
 * was hit (try again later).
 */
@Serializable(with = ErrorCodeSerializer::class)
enum class ErrorCode {
    ROOM_NOT_FOUND,
    ROOM_FULL,
    FORBIDDEN,
    RATE_LIMITED,
    UPGRADE_REQUIRED,
    INVALID,
    QUEUE_FULL,

    /** A code added by a newer protocol; treat it as a generic failure. */
    UNKNOWN,
}

internal object ErrorCodeSerializer : LenientEnumSerializer<ErrorCode>("ErrorCode", ErrorCode.entries, ErrorCode.UNKNOWN)

@Serializable
sealed interface Command {
    @Serializable @SerialName("Play")
    data object Play : Command

    @Serializable @SerialName("Pause")
    data object Pause : Command

    @Serializable @SerialName("Seek")
    data class Seek(val positionMs: Long) : Command

    @Serializable @SerialName("Next")
    data object Next : Command

    @Serializable @SerialName("Previous")
    data object Previous : Command

    @Serializable @SerialName("PlayEntry")
    data class PlayEntry(val entryId: String) : Command

    @Serializable @SerialName("Add")
    data class Add(val entries: List<NewEntry>, val playNext: Boolean = false) : Command

    @Serializable @SerialName("Remove")
    data class Remove(val entryId: String) : Command

    /** `afterEntryId = null` means "right after the current entry". */
    @Serializable @SerialName("Move")
    data class Move(val entryId: String, val afterEntryId: String? = null) : Command

    @Serializable @SerialName("SetSettings")
    data class SetSettings(val settings: RoomSettings) : Command

    @Serializable @SerialName("Kick")
    data class Kick(val memberId: String) : Command

    @Serializable @SerialName("TransferHost")
    data class TransferHost(val memberId: String) : Command

    @Serializable @SerialName("EndRoom")
    data object EndRoom : Command
}

@Serializable
sealed interface ClientMessage {
    /**
     * Inbound frames are limited to 256 KB. Send at most 200 [initialQueue] entries here and the
     * rest as [Command.Add] batches of at most 200 after Welcome. Entries beyond the 512 KB room
     * state cap are dropped here and rejected with QUEUE_FULL in Add.
     */
    @Serializable @SerialName("Create")
    data class Create(
        val protocolVersion: Int,
        val name: String,
        val initialQueue: List<NewEntry> = emptyList(),
        val startIndex: Int = 0,
        val startPositionMs: Long = 0,
    ) : ClientMessage

    @Serializable @SerialName("Join")
    data class Join(
        val protocolVersion: Int,
        val code: String,
        val name: String,
        val resumeToken: String? = null,
    ) : ClientMessage

    @Serializable @SerialName("Leave")
    data object Leave : ClientMessage

    @Serializable @SerialName("Ping")
    data class Ping(val clientTime: Long) : ClientMessage

    /** On the wire `"type":"Command"`; named Send so it does not clash with [Command]. */
    @Serializable @SerialName("Command")
    data class Send(val reqId: String, val cmd: Command) : ClientMessage

    @Serializable @SerialName("TrackEnded")
    data class TrackEnded(val entryId: String) : ClientMessage
}

@Serializable
sealed interface ServerMessage {
    /** [protocolVersion] is the server's [PROTOCOL_VERSION]. */
    @Serializable @SerialName("Welcome")
    data class Welcome(
        val memberId: String,
        val resumeToken: String,
        val state: RoomState,
        val serverTime: Long,
        val protocolVersion: Int = PROTOCOL_VERSION,
    ) : ServerMessage

    /**
     * A full snapshot. The server caps a room's state at 512 KB of JSON (a typical 500-entry queue
     * is ~200 KB), so Welcome/State frames stay below ~520 KB, above the 256 KB inbound limit:
     * clients must accept inbound frames of at least 1 MB. The same holds for [Welcome].
     */
    @Serializable @SerialName("State")
    data class State(val state: RoomState) : ServerMessage

    @Serializable @SerialName("Pong")
    data class Pong(val clientTime: Long, val serverTime: Long) : ServerMessage

    @Serializable @SerialName("Error")
    data class Error(val reqId: String? = null, val code: ErrorCode) : ServerMessage

    @Serializable @SerialName("Kicked")
    data object Kicked : ServerMessage

    @Serializable @SerialName("RoomClosed")
    data object RoomClosed : ServerMessage
}

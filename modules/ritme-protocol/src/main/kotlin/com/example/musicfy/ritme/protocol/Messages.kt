package com.example.musicfy.ritme.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ErrorCode { ROOM_NOT_FOUND, ROOM_FULL, FORBIDDEN, RATE_LIMITED, UPGRADE_REQUIRED, INVALID, QUEUE_FULL }

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
    @Serializable @SerialName("Welcome")
    data class Welcome(val memberId: String, val resumeToken: String, val state: RoomState, val serverTime: Long) : ServerMessage

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

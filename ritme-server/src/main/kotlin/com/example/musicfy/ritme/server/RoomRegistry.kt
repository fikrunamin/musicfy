package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.PROTOCOL_VERSION
import java.util.concurrent.ConcurrentHashMap

sealed interface JoinResult {
    data class Joined(val room: Room, val memberId: String) : JoinResult

    data class Rejected(val code: ErrorCode) : JoinResult
}

class RoomRegistry(private val clock: Clock, private val ids: IdGen) {
    private val rooms = ConcurrentHashMap<String, Room>()

    val size: Int get() = rooms.size

    fun get(code: String): Room? = rooms[normalize(code)]

    suspend fun create(msg: ClientMessage.Create, peer: Peer): JoinResult {
        if (msg.protocolVersion != PROTOCOL_VERSION) return JoinResult.Rejected(ErrorCode.UPGRADE_REQUIRED)
        val name = RoomLogic.cleanName(msg.name) ?: return JoinResult.Rejected(ErrorCode.INVALID)
        val hostId = ids.memberId()
        repeat(CODE_ATTEMPTS) {
            val code = ids.roomCode()
            val room = Room(RoomLogic.newRoom(code, hostId, name, msg, clock.now(), ids), clock, ids)
            if (rooms.putIfAbsent(code, room) == null) {
                room.attachCreator(hostId, peer)
                return JoinResult.Joined(room, hostId)
            }
        }
        error("no free room code after $CODE_ATTEMPTS attempts")
    }

    suspend fun join(msg: ClientMessage.Join, peer: Peer): JoinResult {
        if (msg.protocolVersion != PROTOCOL_VERSION) return JoinResult.Rejected(ErrorCode.UPGRADE_REQUIRED)
        val name = RoomLogic.cleanName(msg.name) ?: return JoinResult.Rejected(ErrorCode.INVALID)
        val room = get(msg.code) ?: return JoinResult.Rejected(ErrorCode.ROOM_NOT_FOUND)
        return room.join(name, msg.resumeToken, peer)
    }

    suspend fun sweep() {
        for (room in rooms.values) {
            if (room.sweep()) rooms.remove(room.code, room)
        }
    }

    private fun normalize(code: String) = code.trim().uppercase()

    private companion object {
        const val CODE_ATTEMPTS = 20
    }
}

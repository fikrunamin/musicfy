package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.MIN_SUPPORTED_PROTOCOL_VERSION
import com.example.musicfy.ritme.protocol.PROTOCOL_VERSION
import java.util.concurrent.ConcurrentHashMap

sealed interface JoinResult {
    /** [peer] is the connection that joined; room calls made for it must pass it back. */
    data class Joined(val room: Room, val memberId: String, val peer: Peer) : JoinResult

    data class Rejected(val code: ErrorCode) : JoinResult
}

class RoomRegistry(
    private val clock: Clock,
    private val ids: IdGen,
    private val maxRooms: Int = Limits.MAX_ROOMS,
    val stateBudget: StateBudget = StateBudget(),
    private val maxRoomsPerIp: Int = Limits.MAX_ROOMS_PER_IP,
) {
    private val rooms = ConcurrentHashMap<String, Room>()
    private val roomsPerIp = ConcurrentHashMap<String, Int>()

    val size: Int get() = rooms.size

    fun get(code: String): Room? = rooms[normalize(code)]

    /** [creatorHost]: the creator's address; at most [maxRoomsPerIp] live rooms per [ipKey] (RATE_LIMITED). */
    suspend fun create(msg: ClientMessage.Create, peer: Peer, creatorHost: String = ""): JoinResult {
        if (msg.protocolVersion !in SUPPORTED_VERSIONS) return JoinResult.Rejected(ErrorCode.UPGRADE_REQUIRED)
        val name = RoomLogic.cleanName(msg.name) ?: return JoinResult.Rejected(ErrorCode.INVALID)
        // Server-wide room cap; reported as ROOM_FULL ("no room for a new room right now").
        if (rooms.size >= maxRooms) return JoinResult.Rejected(ErrorCode.ROOM_FULL)
        // Near the server-wide room-state budget the initial queue is trimmed; with almost nothing
        // left, no new room.
        val left = stateBudget.remaining()
        if (left < MIN_ROOM_BYTES) return JoinResult.Rejected(ErrorCode.ROOM_FULL)
        val maxBytes = minOf(Limits.MAX_STATE_BYTES.toLong(), left).toInt()
        val creatorKey = ipKey(creatorHost)
        if (!reserveRoomSlot(creatorKey)) return JoinResult.Rejected(ErrorCode.RATE_LIMITED)
        val hostId = ids.memberId()
        repeat(CODE_ATTEMPTS) {
            val code = ids.roomCode()
            val state = RoomLogic.newRoom(code, hostId, name, msg, clock.now(), ids, maxBytes)
            val room = Room(state, clock, ids, stateBudget, creatorKey)
            if (rooms.putIfAbsent(code, room) == null) {
                // From here the creator's slot belongs to the room and is released by retire().
                if (!room.attachCreator(hostId, peer)) {
                    rooms.remove(code, room)
                    retire(room)
                    return JoinResult.Rejected(ErrorCode.INVALID)
                }
                return JoinResult.Joined(room, hostId, peer)
            }
            room.dispose() // never registered: only its budget bytes to give back
        }
        releaseRoomSlot(creatorKey)
        // Practically unreachable (32^6 codes); treated like a full server.
        return JoinResult.Rejected(ErrorCode.ROOM_FULL)
    }

    suspend fun join(msg: ClientMessage.Join, peer: Peer): JoinResult {
        if (msg.protocolVersion !in SUPPORTED_VERSIONS) return JoinResult.Rejected(ErrorCode.UPGRADE_REQUIRED)
        val name = RoomLogic.cleanName(msg.name) ?: return JoinResult.Rejected(ErrorCode.INVALID)
        val room = get(msg.code) ?: return JoinResult.Rejected(ErrorCode.ROOM_NOT_FOUND)
        return room.join(name, msg.resumeToken, peer)
    }

    suspend fun sweep() {
        for (room in rooms.values) {
            if (room.sweep() && rooms.remove(room.code, room)) retire(room)
        }
    }

    /** Live rooms created from [key] (tests and diagnostics). */
    fun roomsCreatedBy(key: String): Int = roomsPerIp[key] ?: 0

    private fun retire(room: Room) {
        if (room.dispose()) releaseRoomSlot(room.creatorKey)
    }

    private fun reserveRoomSlot(key: String): Boolean {
        var reserved = false
        roomsPerIp.compute(key) { _, n ->
            val count = n ?: 0
            if (count >= maxRoomsPerIp) {
                n
            } else {
                reserved = true
                count + 1
            }
        }
        return reserved
    }

    private fun releaseRoomSlot(key: String) {
        roomsPerIp.computeIfPresent(key) { _, n -> if (n <= 1) null else n - 1 }
    }

    private fun normalize(code: String) = code.trim().uppercase()

    private companion object {
        const val CODE_ATTEMPTS = 20

        /** Below this much room-state budget left, Create is refused (an empty room is ~0.3 KB). */
        const val MIN_ROOM_BYTES = 4 * 1024L
        val SUPPORTED_VERSIONS = MIN_SUPPORTED_PROTOCOL_VERSION..PROTOCOL_VERSION
    }
}

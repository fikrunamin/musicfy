package com.example.musicfy.ritme.session

import com.example.musicfy.ritme.net.ConnectionStatus
import com.example.musicfy.ritme.net.EndReason
import com.example.musicfy.ritme.protocol.RoomState

enum class RitmePhase { IDLE, CONNECTING, IN_ROOM, ENDED }

enum class NoticeKey { FORBIDDEN, ADD_FORBIDDEN, RATE_LIMITED, QUEUE_FULL, INVALID, FAILED }

/** A server-side rejection to show once; [id] grows so equal keys still count as new. */
data class RitmeNotice(val id: Long, val key: NoticeKey)

data class RitmeUiState(
    val phase: RitmePhase = RitmePhase.IDLE,
    val connection: ConnectionStatus = ConnectionStatus.CONNECTING,
    val memberId: String? = null,
    val room: RoomState? = null,
    val serverProtocolVersion: Int? = null,
    val ended: EndReason? = null,
    val notice: RitmeNotice? = null,
) {
    val inRoom: Boolean get() = phase == RitmePhase.IN_ROOM && room != null
}

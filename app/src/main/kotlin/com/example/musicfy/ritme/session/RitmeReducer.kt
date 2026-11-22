package com.example.musicfy.ritme.session

import com.example.musicfy.ritme.net.ConnectionEvent
import com.example.musicfy.ritme.protocol.ErrorCode

/**
 * Pure session state machine. The server snapshot is the only truth: no optimistic updates, and a
 * State whose version is not newer than the applied one, or that is for another room, is ignored
 * (spec 2.2).
 */
object RitmeReducer {
    fun start(state: RitmeUiState): RitmeUiState = RitmeUiState(phase = RitmePhase.CONNECTING, notice = state.notice)

    fun reset(state: RitmeUiState): RitmeUiState = RitmeUiState(notice = state.notice)

    fun withNotice(state: RitmeUiState, key: NoticeKey): RitmeUiState =
        state.copy(notice = RitmeNotice(id = (state.notice?.id ?: 0L) + 1, key = key))

    fun noticeFor(code: ErrorCode): NoticeKey = when (code) {
        ErrorCode.FORBIDDEN -> NoticeKey.FORBIDDEN
        ErrorCode.RATE_LIMITED -> NoticeKey.RATE_LIMITED
        ErrorCode.QUEUE_FULL -> NoticeKey.QUEUE_FULL
        ErrorCode.INVALID -> NoticeKey.INVALID
        else -> NoticeKey.FAILED
    }

    fun reduce(state: RitmeUiState, event: ConnectionEvent): RitmeUiState = when (event) {
        is ConnectionEvent.Status -> state.copy(connection = event.status)
        is ConnectionEvent.Welcome -> state.copy(
            phase = RitmePhase.IN_ROOM,
            memberId = event.welcome.memberId,
            room = event.welcome.state,
            serverProtocolVersion = event.welcome.protocolVersion,
            ended = null,
        )
        is ConnectionEvent.State -> {
            val room = state.room
            if (room == null || event.state.code != room.code || event.state.version <= room.version) {
                state
            } else {
                state.copy(room = event.state)
            }
        }
        is ConnectionEvent.Pong -> state
        is ConnectionEvent.Failure -> withNotice(state, noticeFor(event.code))
        is ConnectionEvent.Ended -> state.copy(
            phase = RitmePhase.ENDED,
            ended = event.reason,
            room = null,
            memberId = null,
        )
    }
}

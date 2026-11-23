package com.example.musicfy.ritme.session

import com.example.musicfy.ritme.net.ConnectionEvent
import com.example.musicfy.ritme.net.ConnectionStatus
import com.example.musicfy.ritme.net.EndReason
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.testing.room
import com.example.musicfy.ritme.testing.welcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RitmeReducerTest {

    private val inRoom = RitmeReducer.reduce(
        RitmeReducer.start(RitmeUiState()),
        ConnectionEvent.Welcome(welcome(room(version = 5)), receivedAt = 0),
    )

    @Test
    fun startConnects() {
        assertEquals(RitmePhase.CONNECTING, RitmeReducer.start(RitmeUiState()).phase)
    }

    @Test
    fun welcomeEntersTheRoom() {
        assertEquals(RitmePhase.IN_ROOM, inRoom.phase)
        assertEquals("m1", inRoom.memberId)
        assertEquals(5L, inRoom.room?.version)
        assertTrue(inRoom.inRoom)
    }

    @Test
    fun aNewerStateReplacesTheRoom() {
        val next = RitmeReducer.reduce(inRoom, ConnectionEvent.State(room(version = 6)))
        assertEquals(6L, next.room?.version)
    }

    @Test
    fun staleOrEqualVersionsAreIgnored() {
        assertSame(inRoom, RitmeReducer.reduce(inRoom, ConnectionEvent.State(room(version = 5))))
        assertSame(inRoom, RitmeReducer.reduce(inRoom, ConnectionEvent.State(room(version = 4))))
    }

    @Test
    fun aStateForAnotherRoomIsIgnored() {
        assertSame(inRoom, RitmeReducer.reduce(inRoom, ConnectionEvent.State(room(code = "ZZZZZZ", version = 9))))
    }

    @Test
    fun failuresBecomeNumberedNotices() {
        val a = RitmeReducer.reduce(inRoom, ConnectionEvent.Failure("r1", ErrorCode.FORBIDDEN))
        val b = RitmeReducer.reduce(a, ConnectionEvent.Failure(null, ErrorCode.UNKNOWN))
        assertEquals(RitmeNotice(1, NoticeKey.FORBIDDEN), a.notice)
        assertEquals(RitmeNotice(2, NoticeKey.FAILED), b.notice)
    }

    @Test
    fun anEndDropsTheRoom() {
        val ended = RitmeReducer.reduce(inRoom, ConnectionEvent.Ended(EndReason.Kicked))
        assertEquals(RitmePhase.ENDED, ended.phase)
        assertNull(ended.room)
        assertNull(ended.memberId)
        assertEquals(EndReason.Kicked, ended.ended)
    }

    @Test
    fun statusOnlyChangesTheConnection() {
        val next = RitmeReducer.reduce(inRoom, ConnectionEvent.Status(ConnectionStatus.RECONNECTING))
        assertEquals(ConnectionStatus.RECONNECTING, next.connection)
        assertEquals(inRoom.room, next.room)
    }

    @Test
    fun resetAndStartKeepTheLastNotice() {
        val noticed = RitmeReducer.withNotice(RitmeUiState(), NoticeKey.QUEUE_FULL)
        assertEquals(noticed.notice, RitmeReducer.reset(noticed).notice)
        assertEquals(noticed.notice, RitmeReducer.start(noticed).notice)
        assertEquals(RitmePhase.IDLE, RitmeReducer.reset(noticed).phase)
    }
}

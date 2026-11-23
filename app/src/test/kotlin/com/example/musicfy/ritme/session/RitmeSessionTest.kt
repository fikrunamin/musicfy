package com.example.musicfy.ritme.session

import com.example.musicfy.ritme.entries.InitialQueue
import com.example.musicfy.ritme.net.ConnectionEvent
import com.example.musicfy.ritme.net.ConnectionStatus
import com.example.musicfy.ritme.net.EndReason
import com.example.musicfy.ritme.net.JoinIntent
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.testing.FakeConnector
import com.example.musicfy.ritme.testing.room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RitmeSessionTest {

    private var now = 0L
    private val connector = FakeConnector()
    private val session = RitmeSession(connector) { now }

    private fun joinedRoom(state: RoomState = room(version = 5), memberId: String = "m1") {
        session.join("https://ritme.dubeli.id", state.code, "Budi")
        connector.last.welcome(state, memberId)
    }

    private fun newEntry(id: String) = NewEntry(videoId = id, title = "Song $id", artist = "A", durationMs = 1_000)

    @Test
    fun joinOpensTheRoomSocket() {
        session.join("https://ritme.dubeli.id/", "K7M2QX", "Budi")
        assertEquals("wss://ritme.dubeli.id/ritme", connector.last.url)
        assertEquals(JoinIntent.Join("K7M2QX", "Budi"), connector.last.intent)
        assertEquals(RitmePhase.CONNECTING, session.state.value.phase)
    }

    @Test
    fun theWelcomeEntersTheRoom() {
        joinedRoom()
        assertTrue(session.state.value.inRoom)
        assertEquals(ConnectionStatus.ONLINE, session.state.value.connection)
    }

    @Test
    fun staleSnapshotsAreIgnored() {
        joinedRoom(room(version = 5))
        connector.last.emit(ConnectionEvent.State(room(version = 4, currentEntryId = "x")))
        assertEquals(5L, session.state.value.room?.version)
    }

    @Test
    fun commandsAreOnlySentWhileOnlineInARoom() {
        session.join("https://ritme.dubeli.id", "K7M2QX", "Budi")
        assertFalse(session.play())
        connector.last.welcome(room())
        assertTrue(session.play())
        connector.last.emit(ConnectionEvent.Status(ConnectionStatus.RECONNECTING))
        assertFalse(session.next())
        assertEquals(listOf<Command>(Command.Play), connector.last.commands)
    }

    @Test
    fun overflowEntriesFollowTheWelcomeAsAddBatches() {
        val a = newEntry("aaaa")
        val b = newEntry("bbbb")
        val c = newEntry("cccc")
        session.create(
            "https://ritme.dubeli.id",
            "Budi",
            InitialQueue(first = listOf(a), startIndex = 0, startPositionMs = 0, overflow = listOf(listOf(b), listOf(c)), skipped = 0),
        )
        assertEquals(JoinIntent.Create("Budi", listOf(a), 0, 0), connector.last.intent)
        assertTrue(connector.last.commands.isEmpty())
        connector.last.welcome(room())
        assertEquals(listOf(Command.Add(listOf(b)), Command.Add(listOf(c))), connector.last.commands)
        connector.last.emit(ConnectionEvent.Status(ConnectionStatus.RECONNECTING))
        connector.last.emit(ConnectionEvent.Status(ConnectionStatus.ONLINE))
        assertEquals(2, connector.last.commands.size) // sent once only
    }

    @Test
    fun overflowOfAnAbandonedCreateNeverReachesALaterRoom() {
        session.create(
            "https://ritme.dubeli.id",
            "Budi",
            InitialQueue(first = listOf(newEntry("aaaa")), startIndex = 0, startPositionMs = 0, overflow = listOf(listOf(newEntry("bbbb"))), skipped = 0),
        )
        connector.last.emit(ConnectionEvent.Ended(EndReason.Unreachable))
        session.dismiss()
        joinedRoom()
        assertTrue(connector.last.commands.isEmpty())
    }

    @Test
    fun kickedEndsTheSession() {
        joinedRoom()
        connector.last.emit(ConnectionEvent.Ended(EndReason.Kicked))
        with(session.state.value) {
            assertEquals(RitmePhase.ENDED, phase)
            assertNull(room)
            assertEquals(EndReason.Kicked, ended)
        }
    }

    @Test
    fun dismissOnlyResetsAnEndedSession() {
        joinedRoom()
        session.dismiss()
        assertEquals(RitmePhase.IN_ROOM, session.state.value.phase)
        session.leave()
        assertTrue(connector.last.left)
        assertEquals(RitmePhase.ENDED, session.state.value.phase)
        session.dismiss()
        assertEquals(RitmePhase.IDLE, session.state.value.phase)
    }

    @Test
    fun pongsMoveTheServerClock() {
        joinedRoom()
        connector.last.emit(ConnectionEvent.Pong(clientTime = 900, serverTime = 5_000, receivedAt = 1_100))
        now = 2_000
        assertEquals(6_000L, session.serverNow())
    }

    @Test
    fun theWelcomeSeedsTheServerClock() {
        session.join("https://ritme.dubeli.id", "K7M2QX", "Budi")
        connector.last.welcome(room(), serverTime = 10_000, receivedAt = 400)
        now = 1_000
        assertEquals(10_600L, session.serverNow())
    }

    @Test
    fun aResumeWelcomeResetsAndReseedsTheServerClock() {
        session.join("https://ritme.dubeli.id", "K7M2QX", "Budi")
        connector.last.welcome(room(version = 5), serverTime = 10_000, receivedAt = 0)
        connector.last.emit(ConnectionEvent.Pong(clientTime = 900, serverTime = 5_000, receivedAt = 1_100))
        now = 2_000
        assertEquals(6_000L, session.serverNow())
        // The old socket's samples are dropped: the resume Welcome's seed rules until its own Pongs arrive.
        connector.last.emit(ConnectionEvent.Status(ConnectionStatus.RECONNECTING))
        connector.last.welcome(room(version = 6), serverTime = 50_000, receivedAt = 3_000)
        now = 4_000
        assertEquals(51_000L, session.serverNow())
        connector.last.emit(ConnectionEvent.Pong(clientTime = 4_000, serverTime = 60_000, receivedAt = 4_100))
        now = 5_000
        assertEquals(60_950L, session.serverNow())
    }

    @Test
    fun aSecondJoinWhileConnectingIsIgnored() {
        session.join("https://ritme.dubeli.id", "K7M2QX", "Budi")
        session.join("https://ritme.dubeli.id", "ABCDEF", "Budi")
        assertEquals(1, connector.connections.size)
    }

    @Test
    fun eventsFromAReplacedConnectionAreIgnored() {
        joinedRoom()
        val old = connector.last
        old.emit(ConnectionEvent.Ended(EndReason.Lost))
        session.dismiss()
        joinedRoom(room(code = "ABCDEF", version = 1))
        old.emit(ConnectionEvent.State(room(code = "ABCDEF", version = 9)))
        assertEquals(1L, session.state.value.room?.version)
    }

    @Test
    fun rejectedCommandsBecomeNotices() {
        joinedRoom()
        connector.last.emit(ConnectionEvent.Failure("r1", ErrorCode.QUEUE_FULL))
        assertEquals(NoticeKey.QUEUE_FULL, session.state.value.notice?.key)
    }

    @Test
    fun trackEndedNeedsAnOnlineRoom() {
        joinedRoom()
        assertTrue(session.trackEnded("e1"))
        connector.last.emit(ConnectionEvent.Status(ConnectionStatus.RECONNECTING))
        assertFalse(session.trackEnded("e2"))
        assertEquals(listOf("e1"), connector.last.trackEnds)
    }
}

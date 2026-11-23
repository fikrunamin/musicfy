package com.example.musicfy.ritme.session

import com.example.musicfy.ritme.entries.InitialQueue
import com.example.musicfy.ritme.net.ConnectionEvent
import com.example.musicfy.ritme.net.DefaultRitmeConnector
import com.example.musicfy.ritme.net.OkHttpRitmeSocketFactory
import com.example.musicfy.ritme.net.RitmeSocketFactory
import com.example.musicfy.ritme.net.ConnectionStatus
import com.example.musicfy.ritme.net.EndReason
import com.example.musicfy.ritme.net.JoinIntent
import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.testing.FakeConnector
import com.example.musicfy.ritme.testing.FakeSocketFactory
import com.example.musicfy.ritme.testing.FakeTimers
import com.example.musicfy.ritme.testing.room
import com.example.musicfy.ritme.testing.welcome
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RitmeSessionTest {

    private val timers = FakeTimers()
    private val connector = FakeConnector()
    private val session = RitmeSession(connector, timers)

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
        // Not inside the Welcome: the Ack and the ping burst go first.
        assertTrue(connector.last.commands.isEmpty())
        timers.advanceBy(RitmeSession.OVERFLOW_DELAY_MS - 1)
        assertTrue(connector.last.commands.isEmpty())
        timers.advanceBy(1)
        assertEquals(listOf<Command>(Command.Add(listOf(b))), connector.last.commands)
        timers.advanceBy(RitmeSession.OVERFLOW_GAP_MS - 1)
        assertEquals(1, connector.last.commands.size)
        timers.advanceBy(1)
        assertEquals(listOf(Command.Add(listOf(b)), Command.Add(listOf(c))), connector.last.commands)
        connector.last.emit(ConnectionEvent.Status(ConnectionStatus.RECONNECTING))
        connector.last.welcome(room(version = 2))
        timers.advanceBy(10_000)
        assertEquals(2, connector.last.commands.size) // sent once only
        assertEquals(0, timers.pendingCount())
    }

    @Test
    fun overflowPausedByADropResumesAfterTheResumeWelcome() {
        val batches = listOf(listOf(newEntry("bbbb")), listOf(newEntry("cccc")), listOf(newEntry("dddd")))
        session.create(
            "https://ritme.dubeli.id",
            "Budi",
            InitialQueue(first = listOf(newEntry("aaaa")), startIndex = 0, startPositionMs = 0, overflow = batches, skipped = 0),
        )
        connector.last.welcome(room())
        timers.advanceBy(RitmeSession.OVERFLOW_DELAY_MS)
        connector.last.emit(ConnectionEvent.Status(ConnectionStatus.RECONNECTING))
        timers.advanceBy(5_000)
        assertEquals(listOf<Command>(Command.Add(batches[0])), connector.last.commands)
        // The resume Welcome restarts the pacing: nothing inside it, the rest after the delay.
        connector.last.welcome(room(version = 2))
        assertEquals(1, connector.last.commands.size)
        timers.advanceBy(RitmeSession.OVERFLOW_DELAY_MS + RitmeSession.OVERFLOW_GAP_MS)
        assertEquals(batches.map { Command.Add(it) }, connector.last.commands)
    }

    @Test
    fun leavingBeforeTheOverflowDelayCancelsIt() {
        session.create(
            "https://ritme.dubeli.id",
            "Budi",
            InitialQueue(first = listOf(newEntry("aaaa")), startIndex = 0, startPositionMs = 0, overflow = listOf(listOf(newEntry("bbbb"))), skipped = 0),
        )
        connector.last.welcome(room())
        session.leave()
        assertEquals(0, timers.pendingCount())
        assertTrue(connector.last.commands.isEmpty())
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
        timers.advanceBy(10_000)
        assertTrue(connector.last.commands.isEmpty())
    }

    @Test
    fun anUnparseableServerUrlEndsUnreachableAndALaterJoinWorks() {
        // The real OkHttp factory for the bad URL (it never reaches the network), fakes for the good one.
        val okHttp = OkHttpRitmeSocketFactory(OkHttpClient(), timers)
        val fakes = FakeSocketFactory()
        val sockets = RitmeSocketFactory { url, listener ->
            if (url.startsWith("wss://")) fakes.open(url, listener) else okHttp.open(url, listener)
        }
        val real = RitmeSession(DefaultRitmeConnector(sockets, timers), timers)

        real.join("ritme.dubeli.id", "K7M2QX", "Budi") // no scheme: socketUrl passes it through
        assertEquals(RitmePhase.CONNECTING, real.state.value.phase)
        timers.advanceBy(0)
        assertEquals(RitmePhase.ENDED, real.state.value.phase)
        assertEquals(EndReason.Unreachable, real.state.value.ended)
        assertEquals(0, timers.pendingCount())

        real.join("https://ritme.dubeli.id", "K7M2QX", "Budi")
        fakes.last.open()
        assertEquals(ClientMessage.Join(1, "K7M2QX", "Budi", null), fakes.last.sentMessages().first())
        fakes.last.receive(welcome(room()))
        assertTrue(real.state.value.inRoom)
    }

    @Test
    fun publicEntryPointsRunTheThreadCheck() {
        var calls = 0
        val checked = RitmeSession(connector, timers) { calls++ }
        checked.join("https://ritme.dubeli.id", "K7M2QX", "Budi")
        connector.last.welcome(room())
        checked.play()
        checked.trackEnded("e1")
        checked.serverNow()
        checked.leave()
        checked.dismiss()
        assertEquals(6, calls)

        val offMain = RitmeSession(FakeConnector(), timers) { error("not on the main thread") }
        assertThrows(IllegalStateException::class.java) { offMain.join("https://ritme.dubeli.id", "K7M2QX", "Budi") }
        assertThrows(IllegalStateException::class.java) { offMain.play() }
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
        timers.time = 2_000
        assertEquals(6_000L, session.serverNow())
    }

    @Test
    fun theWelcomeSeedsTheServerClock() {
        session.join("https://ritme.dubeli.id", "K7M2QX", "Budi")
        connector.last.welcome(room(), serverTime = 10_000, receivedAt = 400)
        timers.time = 1_000
        assertEquals(10_600L, session.serverNow())
    }

    @Test
    fun aResumeWelcomeResetsAndReseedsTheServerClock() {
        session.join("https://ritme.dubeli.id", "K7M2QX", "Budi")
        connector.last.welcome(room(version = 5), serverTime = 10_000, receivedAt = 0)
        connector.last.emit(ConnectionEvent.Pong(clientTime = 900, serverTime = 5_000, receivedAt = 1_100))
        timers.time = 2_000
        assertEquals(6_000L, session.serverNow())
        // The old socket's samples are dropped: the resume Welcome's seed rules until its own Pongs arrive.
        connector.last.emit(ConnectionEvent.Status(ConnectionStatus.RECONNECTING))
        connector.last.welcome(room(version = 6), serverTime = 50_000, receivedAt = 3_000)
        timers.time = 4_000
        assertEquals(51_000L, session.serverNow())
        connector.last.emit(ConnectionEvent.Pong(clientTime = 4_000, serverTime = 60_000, receivedAt = 4_100))
        timers.time = 5_000
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

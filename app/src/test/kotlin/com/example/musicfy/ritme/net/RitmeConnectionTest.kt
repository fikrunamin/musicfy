package com.example.musicfy.ritme.net

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.PROTOCOL_VERSION
import com.example.musicfy.ritme.protocol.ServerMessage
import com.example.musicfy.ritme.testing.FakeSocketFactory
import com.example.musicfy.ritme.testing.FakeTimers
import com.example.musicfy.ritme.testing.room
import com.example.musicfy.ritme.testing.welcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RitmeConnectionTest {

    private val timers = FakeTimers()
    private val sockets = FakeSocketFactory()
    private val events = mutableListOf<ConnectionEvent>()

    private fun connection(onEvent: (ConnectionEvent) -> Unit = { events += it }) =
        RitmeConnection("wss://ritme.test/ritme", sockets, timers, onEvent)

    private fun joined(): RitmeConnection = connection().also {
        it.start(JoinIntent.Join("K7M2QX", "Budi"))
        sockets.last.open()
        sockets.last.receive(welcome(room(version = 3)))
    }

    private fun sent(): List<ClientMessage> = sockets.sockets.flatMap { it.sentMessages() }

    private fun ended(): EndReason? = events.filterIsInstance<ConnectionEvent.Ended>().singleOrNull()?.reason

    @Test
    fun joinsAsSoonAsTheSocketOpensAndPingsInABurst() {
        connection().start(JoinIntent.Join("K7M2QX", "Budi"))
        assertEquals("wss://ritme.test/ritme", sockets.last.url)
        assertTrue(sockets.last.sent.isEmpty())
        sockets.last.open()
        assertEquals(ClientMessage.Join(PROTOCOL_VERSION, "K7M2QX", "Budi", null), sockets.last.sentMessages().first())
        timers.advanceBy(800)
        assertEquals(5, sockets.last.sentMessages().count { it is ClientMessage.Ping })
    }

    @Test
    fun theWelcomeAndEveryStateAreAcknowledgedWithTheirOwnVersion() {
        joined()
        sockets.last.receive(ServerMessage.State(room(version = 5)))
        sockets.last.receive(ServerMessage.State(room(version = 4))) // stale, still acknowledged
        assertEquals(listOf(3L, 5L, 4L), sent().filterIsInstance<ClientMessage.Ack>().map { it.version })
        assertTrue(events.contains(ConnectionEvent.Status(ConnectionStatus.ONLINE)))
    }

    @Test
    fun theAckIsSentEvenWhenTheListenerThrows() {
        val c = connection { if (it is ConnectionEvent.State) throw IllegalStateException("boom") }
        c.start(JoinIntent.Join("K7M2QX", "Budi"))
        sockets.last.open()
        sockets.last.receive(welcome(room(version = 3)))
        assertThrows(IllegalStateException::class.java) {
            sockets.last.receive(ServerMessage.State(room(version = 7)))
        }
        assertTrue(sent().contains(ClientMessage.Ack(7)))
    }

    @Test
    fun aDropAfterTheWelcomeReconnectsWithTheResumeToken() {
        joined()
        sockets.last.drop()
        assertEquals(ConnectionEvent.Status(ConnectionStatus.RECONNECTING), events.last())
        timers.advanceBy(999)
        assertEquals(1, sockets.sockets.size)
        timers.advanceBy(1)
        assertEquals(2, sockets.sockets.size)
        sockets.last.open()
        assertEquals(ClientMessage.Join(PROTOCOL_VERSION, "K7M2QX", "Budi", "tok"), sockets.last.sentMessages().first())
    }

    @Test
    fun reconnectBacksOffAndGivesUpThirtySecondsAfterTheDrop() {
        joined()
        sockets.last.drop()
        for (delay in listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)) {
            val before = sockets.sockets.size
            timers.advanceBy(delay - 1)
            assertEquals(before, sockets.sockets.size)
            timers.advanceBy(1)
            assertEquals(before + 1, sockets.sockets.size)
            assertNull(ended())
            sockets.last.drop()
        }
        assertEquals(EndReason.Lost, ended())
    }

    @Test
    fun aDropBeforeTheFirstWelcomeMeansUnreachable() {
        connection().start(JoinIntent.Join("K7M2QX", "Budi"))
        sockets.last.open()
        sockets.last.drop()
        assertEquals(EndReason.Unreachable, ended())
    }

    @Test
    fun aSocketThatNeverOpensTimesOut() {
        connection().start(JoinIntent.Join("K7M2QX", "Budi"))
        timers.advanceBy(8_000)
        assertEquals(EndReason.Unreachable, ended())
        assertTrue(sockets.last.cancelled) // cancelled, not closed: nothing to flush
    }

    @Test
    fun anOpenSocketThatIsNeverWelcomedTimesOut() {
        connection().start(JoinIntent.Join("K7M2QX", "Budi"))
        sockets.last.open()
        timers.advanceBy(9_999)
        assertNull(ended())
        timers.advanceBy(1)
        assertEquals(EndReason.Unreachable, ended())
    }

    @Test
    fun anErrorBeforeTheWelcomeRejects() {
        connection().start(JoinIntent.Join("K7M2QX", "Budi"))
        sockets.last.open()
        sockets.last.receive(ServerMessage.Error(code = ErrorCode.ROOM_NOT_FOUND))
        assertEquals(EndReason.Rejected(ErrorCode.ROOM_NOT_FOUND), ended())
    }

    @Test
    fun rateLimitedWhileResumingIsLeftToTheBackoff() {
        joined()
        sockets.last.drop()
        timers.advanceBy(1_000)
        sockets.last.open()
        sockets.last.receive(ServerMessage.Error(code = ErrorCode.RATE_LIMITED))
        assertNull(ended())
        sockets.last.drop()
        timers.advanceBy(2_000)
        assertEquals(3, sockets.sockets.size)
    }

    @Test
    fun kickedAndRoomClosedEndTheSession() {
        joined()
        sockets.last.receive(ServerMessage.Kicked)
        assertEquals(EndReason.Kicked, ended())
        assertTrue(sockets.last.closed)

        events.clear()
        joined()
        sockets.last.receive(ServerMessage.RoomClosed)
        assertEquals(EndReason.Closed, ended())
    }

    @Test
    fun commandsAndTrackEndsNeedAJoinedSocket() {
        val c = connection()
        c.start(JoinIntent.Join("K7M2QX", "Budi"))
        assertNull(c.command(Command.Play))
        assertFalse(c.trackEnded("e1"))
        sockets.last.open()
        sockets.last.receive(welcome(room()))
        assertEquals("r1", c.command(Command.Play))
        assertEquals("r2", c.command(Command.Next))
        assertTrue(c.trackEnded("e1"))
        assertEquals(
            listOf(ClientMessage.Send("r1", Command.Play), ClientMessage.Send("r2", Command.Next), ClientMessage.TrackEnded("e1")),
            sent().filter { it is ClientMessage.Send || it is ClientMessage.TrackEnded },
        )
    }

    @Test
    fun leaveSendsLeaveThenCloses() {
        val c = joined()
        c.leave()
        assertEquals(ClientMessage.Leave, sockets.last.sentMessages().last())
        assertTrue(sockets.last.closed)
        assertEquals(EndReason.Left, ended())
    }

    @Test
    fun createTurnsIntoJoinAfterTheWelcome() {
        val newEntry = NewEntry(videoId = "dQw4w9WgXcQ", title = "Song", artist = "Artist", durationMs = 200_000)
        connection().start(JoinIntent.Create("Budi", listOf(newEntry), 0, 5_000))
        sockets.last.open()
        assertEquals(
            ClientMessage.Create(PROTOCOL_VERSION, "Budi", listOf(newEntry), 0, 5_000),
            sockets.last.sentMessages().first(),
        )
        sockets.last.receive(welcome(room(code = "K7M2QX"), resumeToken = "tok2"))
        sockets.last.drop()
        timers.advanceBy(1_000)
        sockets.last.open()
        assertEquals(ClientMessage.Join(PROTOCOL_VERSION, "K7M2QX", "Budi", "tok2"), sockets.last.sentMessages().first())
    }

    @Test
    fun unknownOrBrokenFramesAreSkipped() {
        joined()
        val before = events.size
        sockets.last.receiveRaw("""{"type":"FromTheFuture"}""")
        sockets.last.receiveRaw("not json")
        assertEquals(before, events.size)
        assertNull(ended())
    }

    @Test
    fun pongsCarryTheirArrivalTime() {
        joined()
        timers.advanceBy(150)
        sockets.last.receive(ServerMessage.Pong(clientTime = 0, serverTime = 5_000))
        assertEquals(ConnectionEvent.Pong(0, 5_000, 150), events.last())
    }

    @Test
    fun aThrowingWelcomeListenerStillGoesOnlineAndAcks() {
        val c = connection {
            events += it
            if (it is ConnectionEvent.Welcome) throw IllegalStateException("boom")
        }
        c.start(JoinIntent.Join("K7M2QX", "Budi"))
        sockets.last.open()
        assertThrows(IllegalStateException::class.java) {
            sockets.last.receive(welcome(room(version = 3)))
        }
        assertTrue(events.contains(ConnectionEvent.Status(ConnectionStatus.ONLINE)))
        assertTrue(sent().contains(ClientMessage.Ack(3)))
    }

    // Liveness watchdog (mirrors ritme-web PONG_TIMEOUT_MS / IDLE_TIMEOUT_MS): a half-open TCP
    // connection may never report a close, so silence drops the socket into the resume path.

    private fun statuses(): List<ConnectionStatus> = events.filterIsInstance<ConnectionEvent.Status>().map { it.status }

    @Test
    fun aPingWithoutAPongWithinTenSecondsDropsTheSocketAndResumes() {
        joined() // burst Pings at 0..800 ms, never answered
        sockets.last.receive(ServerMessage.State(room(version = 4))) // other traffic is not a Pong
        timers.advanceBy(9_999)
        assertFalse(sockets.last.closed)
        assertFalse(statuses().contains(ConnectionStatus.RECONNECTING))
        timers.advanceBy(1)
        assertTrue(sockets.sockets[0].cancelled) // presumed dead: dropped, not closed
        assertEquals(ConnectionStatus.RECONNECTING, statuses().last())
        timers.advanceBy(1_000)
        assertEquals(2, sockets.sockets.size)
        sockets.last.open()
        assertEquals(ClientMessage.Join(PROTOCOL_VERSION, "K7M2QX", "Budi", "tok"), sockets.last.sentMessages().first())
        assertNull(ended())
    }

    @Test
    fun aPongForALaterPingAlsoCountsAndTheIntervalPingIsWatchedToo() {
        joined()
        timers.advanceBy(9_000)
        sockets.last.receive(ServerMessage.Pong(clientTime = 800, serverTime = 1)) // answers the whole burst
        timers.advanceBy(30_000 - 9_000 + 9_999) // interval Ping at 30 000, unanswered
        assertFalse(sockets.last.closed)
        timers.advanceBy(1)
        assertTrue(sockets.sockets[0].cancelled)
        assertEquals(ConnectionStatus.RECONNECTING, statuses().last())
    }

    @Test
    fun nothingReceivedForSeventyFiveSecondsDropsTheSocketEvenWhenNoPingCouldBeSent() {
        joined()
        sockets.last.refuseSends = true // no Ping goes out, so no Pong deadline either
        timers.advanceBy(74_999)
        assertFalse(sockets.last.closed)
        timers.advanceBy(1)
        assertTrue(sockets.sockets[0].cancelled)
        assertEquals(ConnectionStatus.RECONNECTING, statuses().last())
    }

    @Test
    fun theIdleDeadlineRestartsWithEveryInboundFrameKnownOrNot() {
        joined()
        sockets.last.refuseSends = true
        timers.advanceBy(60_000)
        sockets.last.receiveRaw("""{"type":"FromTheFuture"}""")
        timers.advanceBy(74_999)
        assertFalse(sockets.last.closed)
        timers.advanceBy(1)
        assertTrue(sockets.sockets[0].cancelled)
    }

    @Test
    fun aHealthyConnectionThatAnswersEveryPingIsNeverDropped() {
        connection().start(JoinIntent.Join("K7M2QX", "Budi"))
        sockets.last.autoPong(timers, delayMs = 9_000)
        sockets.last.open()
        sockets.last.receive(welcome(room(version = 3)))
        timers.advanceBy(10 * 60_000L)
        assertEquals(1, sockets.sockets.size)
        assertFalse(sockets.last.closed)
        assertFalse(statuses().contains(ConnectionStatus.RECONNECTING))
        assertTrue(sent().count { it is ClientMessage.Ping } >= 5 + 19) // burst + one every 30 s
    }

    @Test
    fun theWatchdogTimersDieWithTheSocket() {
        val c = joined()
        c.leave()
        assertEquals(0, timers.pendingCount())
        timers.advanceBy(10 * 60_000L)
        assertEquals(1, sockets.sockets.size)
        assertEquals(listOf(ConnectionEvent.Ended(EndReason.Left)), events.filterIsInstance<ConnectionEvent.Ended>())
    }

    @Test
    fun aListenerThatLeavesOnTheWelcomeLeavesNoTimersBehind() {
        lateinit var c: RitmeConnection
        c = connection {
            events += it
            if (it is ConnectionEvent.Welcome) c.leave()
        }
        c.start(JoinIntent.Join("K7M2QX", "Budi"))
        sockets.last.open()
        sockets.last.receive(welcome(room(version = 3)))
        assertEquals(EndReason.Left, ended())
        assertEquals(ConnectionEvent.Ended(EndReason.Left), events.last()) // nothing after Ended
        assertEquals(0, timers.pendingCount())
    }
}

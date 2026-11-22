package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.ServerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerOutboxTest {
    private fun state(version: Long, queue: Int = 0) =
        Outgoing(ServerMessage.State(roomState(queue = List(queue) { entry("e$it") }).copy(version = version)))

    private fun error(reqId: String) = Outgoing(ServerMessage.Error(reqId, ErrorCode.INVALID))

    @Test
    fun aNewSnapshotReplacesThePendingOneAndKeepsOtherMessagesInOrder() {
        val outbox = PeerOutbox()
        val s1 = state(1)
        val e1 = error("a")
        val e2 = error("b")
        val s2 = state(2)
        listOf(s1, e1, e2, s2).forEach { outbox.add(it) }
        assertEquals(3, outbox.size)
        assertSame(e1, outbox.take())
        assertSame(e2, outbox.take())
        assertSame(s2, outbox.take())
        assertNull(outbox.take())
        assertEquals(0, outbox.pendingBytes)
    }

    @Test
    fun pendingBytesFollowAddsReplacementsAndTakes() {
        val outbox = PeerOutbox()
        val small = state(1)
        val big = state(2, queue = 50)
        assertNull(outbox.add(small))
        assertEquals(small.size.toLong(), outbox.pendingBytes)
        assertSame(small, outbox.add(big)) // the replaced snapshot is handed back
        assertEquals(big.size.toLong(), outbox.pendingBytes)
        assertEquals(listOf(big), outbox.clear())
        assertEquals(0, outbox.pendingBytes)
    }

    @Test
    fun snapshotsAloneNeverGoOverBudgetButOtherMessagesDo() {
        val big = state(1, queue = 50)
        val outbox = PeerOutbox(maxBytes = big.size * 2L, maxMessages = 64)
        repeat(1_000) { outbox.add(state(it.toLong(), queue = 50)) }
        assertFalse(outbox.overBudget)
        assertEquals(1, outbox.size)

        val bytes = PeerOutbox(maxBytes = 1_000, maxMessages = 64)
        repeat(10) { bytes.add(error("r$it")) }
        bytes.add(state(1, queue = 50))
        assertTrue(bytes.overBudget)

        val count = PeerOutbox(maxBytes = Long.MAX_VALUE, maxMessages = 3)
        repeat(3) { count.add(error("r$it")) }
        assertFalse(count.overBudget)
        count.add(error("one too many"))
        assertTrue(count.overBudget)
    }

    @Test
    fun peerStatsTracksThePeak() {
        val stats = PeerStats()
        stats.add(100)
        stats.add(50)
        stats.add(-120)
        assertEquals(30, stats.pendingBytes.get())
        assertEquals(150, stats.peakPendingBytes.get())
        val budgeted = PeerStats(budget = 100)
        budgeted.add(100)
        assertFalse(budgeted.overBudget)
        budgeted.add(1)
        assertTrue(budgeted.overBudget)
    }

    @Test
    fun aBroadcastHeldByManyClientsIsCountedOnce() {
        val stats = PeerStats()
        val shared = state(1, queue = 20)
        repeat(20) { stats.hold(shared) }
        assertEquals(shared.size.toLong(), stats.pendingBytes.get())
        repeat(19) { stats.drop(shared) }
        assertEquals(shared.size.toLong(), stats.pendingBytes.get())
        stats.drop(shared)
        assertEquals(0, stats.pendingBytes.get())
    }

    private fun welcome(version: Long) =
        Outgoing(ServerMessage.Welcome("m1", "t", roomState().copy(version = version), 0))

    @Test
    fun snapshotsWaitForTheAckWhileOtherMessagesPassAndNewerSnapshotsCoalesce() {
        val outbox = PeerOutbox()
        val w = welcome(5)
        outbox.add(w)
        assertSame(w, outbox.take())
        assertTrue(outbox.awaitingAck)
        assertEquals(5, outbox.lastSentVersion)

        val s6 = state(6)
        val s7 = state(7)
        val err = error("x")
        outbox.add(s6)
        assertNull(outbox.take()) // gated
        assertFalse(outbox.hasSendable)
        outbox.add(err)
        assertSame(err, outbox.take()) // small messages are not gated
        assertSame(s6, outbox.add(s7)) // coalesced: s6 replaced
        assertNull(outbox.take())

        assertFalse(outbox.ack(4)) // stale
        assertFalse(outbox.ack(Long.MAX_VALUE)) // never sent: no blind acks
        assertTrue(outbox.awaitingAck)
        assertTrue(outbox.ack(5))
        assertTrue(outbox.hasSendable)
        assertSame(s7, outbox.take())
        assertTrue(outbox.awaitingAck)
        assertEquals(7, outbox.lastSentVersion)
        assertTrue(outbox.ack(7)) // the right Ack opens the gate again
        assertNull(outbox.take())
        assertEquals(0, outbox.pendingBytes)
    }
}

package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.MIN_SUPPORTED_PROTOCOL_VERSION
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.PROTOCOL_VERSION
import com.example.musicfy.ritme.protocol.ServerMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomRegistryTest {
    private val clock = FakeClock()
    private val ids = SeqIds()
    private val registry = RoomRegistry(clock, ids)

    private suspend fun create(peer: FakePeer = FakePeer(), queue: List<NewEntry> = emptyList()): JoinResult.Joined =
        registry.create(ClientMessage.Create(PROTOCOL_VERSION, "Host", queue), peer) as JoinResult.Joined

    private suspend fun join(code: String, peer: Peer = FakePeer(), token: String? = null): JoinResult =
        registry.join(ClientMessage.Join(PROTOCOL_VERSION, code, "Guest", token), peer)

    @Test
    fun createWelcomesTheHost() = runBlocking {
        val peer = FakePeer()
        val host = create(peer)
        val welcome = peer.last<ServerMessage.Welcome>()
        assertEquals(host.memberId, welcome.memberId)
        assertEquals(host.memberId, welcome.state.hostId)
        assertEquals(clock.now, welcome.serverTime)
        assertSame(host.room, registry.get(welcome.state.code))
    }

    @Test
    fun createAndJoinValidateVersionNameAndCode() = runBlocking {
        assertEquals(JoinResult.Rejected(ErrorCode.UPGRADE_REQUIRED), registry.create(ClientMessage.Create(PROTOCOL_VERSION + 1, "Host"), FakePeer()))
        assertEquals(JoinResult.Rejected(ErrorCode.INVALID), registry.create(ClientMessage.Create(PROTOCOL_VERSION, "  "), FakePeer()))
        val code = create().room.code
        assertEquals(JoinResult.Rejected(ErrorCode.UPGRADE_REQUIRED), registry.join(ClientMessage.Join(PROTOCOL_VERSION + 1, code, "G"), FakePeer()))
        assertEquals(JoinResult.Rejected(ErrorCode.ROOM_NOT_FOUND), join("NOPE00"))
    }

    @Test
    fun joinIsCaseInsensitiveAndBroadcastsToExistingMembers() = runBlocking {
        val hostPeer = FakePeer()
        val code = create(hostPeer).room.code
        val guest = join(" ${code.lowercase()} ") as JoinResult.Joined
        val seenByHost = hostPeer.last<ServerMessage.State>().state
        assertEquals(listOf("Host", "Guest"), seenByHost.members.map { it.name })
        assertTrue(seenByHost.members.single { it.id == guest.memberId }.connected)
    }

    @Test
    fun roomRejectsTheMemberAfterTheLimit() = runBlocking {
        val code = create().room.code
        repeat(Limits.MAX_MEMBERS - 1) { assertTrue(join(code) is JoinResult.Joined) }
        assertEquals(JoinResult.Rejected(ErrorCode.ROOM_FULL), join(code))
    }

    @Test
    fun resumeTokenRestoresTheSameMemberWithinGrace() = runBlocking {
        val code = create().room.code
        val firstPeer = FakePeer()
        val guest = join(code, firstPeer) as JoinResult.Joined
        val token = firstPeer.last<ServerMessage.Welcome>().resumeToken
        guest.room.disconnected(guest.memberId, firstPeer)
        assertFalse(guest.room.state.members.single { it.id == guest.memberId }.connected)

        clock.now += Limits.GRACE_MS - 1
        guest.room.sweep()
        val secondPeer = FakePeer()
        val again = join(code, secondPeer, token) as JoinResult.Joined
        assertEquals(guest.memberId, again.memberId)
        assertTrue(again.room.state.members.single { it.id == guest.memberId }.connected)
        assertEquals(token, secondPeer.last<ServerMessage.Welcome>().resumeToken)
    }

    @Test
    fun staleDisconnectFromAReplacedConnectionIsIgnored() = runBlocking {
        val code = create().room.code
        val oldPeer = FakePeer()
        val guest = join(code, oldPeer) as JoinResult.Joined
        val token = oldPeer.last<ServerMessage.Welcome>().resumeToken
        join(code, FakePeer(), token)
        assertTrue(oldPeer.closed)
        guest.room.disconnected(guest.memberId, oldPeer)
        assertTrue(guest.room.state.members.single { it.id == guest.memberId }.connected)
    }

    @Test
    fun hostRoleMovesWhenTheHostMissesTheGracePeriod() = runBlocking {
        val hostPeer = FakePeer()
        val host = create(hostPeer)
        val guest = join(host.room.code) as JoinResult.Joined
        host.room.disconnected(host.memberId, hostPeer)
        clock.now += Limits.GRACE_MS
        assertFalse(host.room.sweep())
        assertEquals(guest.memberId, host.room.state.hostId)
        assertEquals(listOf(guest.memberId), host.room.state.members.map { it.id })
    }

    @Test
    fun hostLeavingHandsOverImmediately() = runBlocking {
        val host = create()
        val guest = join(host.room.code) as JoinResult.Joined
        host.room.leave(host.memberId, host.peer)
        assertEquals(guest.memberId, host.room.state.hostId)
    }

    @Test
    fun emptyRoomIsDeletedSixtySecondsAfterTheLastConnectionDrops() = runBlocking {
        val hostPeer = FakePeer()
        val host = create(hostPeer)
        val code = host.room.code
        host.room.disconnected(host.memberId, hostPeer)
        registry.sweep()
        clock.now += Limits.EMPTY_ROOM_MS - 1
        registry.sweep()
        assertNotNull(registry.get(code))
        clock.now += 1
        registry.sweep()
        assertNull(registry.get(code))
    }

    @Test
    fun commandsBeyondTheRateLimitAreRejected() = runBlocking {
        val hostPeer = FakePeer()
        val host = create(hostPeer, queue = listOf(newEntry()))
        repeat(Limits.COMMANDS_PER_SECOND) { host.room.command(host.memberId, host.peer, "r$it", Command.Pause) }
        host.room.command(host.memberId, host.peer, "over", Command.Pause)
        assertEquals(ServerMessage.Error("over", ErrorCode.RATE_LIMITED), hostPeer.last<ServerMessage.Error>())
        clock.now += 1_000
        host.room.command(host.memberId, host.peer, "later", Command.Play)
        assertFalse(hostPeer.received.contains(ServerMessage.Error("later", ErrorCode.RATE_LIMITED)))
    }

    @Test
    fun kickedMemberIsDisconnectedAndItsTokenStopsWorking() = runBlocking {
        val host = create()
        val guestPeer = FakePeer()
        val guest = join(host.room.code, guestPeer) as JoinResult.Joined
        val token = guestPeer.last<ServerMessage.Welcome>().resumeToken
        host.room.command(host.memberId, host.peer, "k", Command.Kick(guest.memberId))
        assertEquals(ServerMessage.Kicked, guestPeer.received.last())
        assertTrue(guestPeer.closed)
        val rejoined = join(host.room.code, FakePeer(), token) as JoinResult.Joined
        assertNotEquals(guest.memberId, rejoined.memberId)
    }

    @Test
    fun endRoomTellsEveryoneAndTheRoomIsGoneAfterSweep() = runBlocking {
        val hostPeer = FakePeer()
        val host = create(hostPeer)
        val guestPeer = FakePeer()
        join(host.room.code, guestPeer)
        host.room.command(host.memberId, host.peer, "end", Command.EndRoom)
        assertEquals(ServerMessage.RoomClosed, guestPeer.received.last())
        assertTrue(hostPeer.closed && guestPeer.closed)
        assertEquals(JoinResult.Rejected(ErrorCode.ROOM_NOT_FOUND), join(host.room.code))
        registry.sweep()
        assertNull(registry.get(host.room.code))
    }

    @Test
    fun trackEndedNearTheEndAdvancesForEveryone() = runBlocking {
        val hostPeer = FakePeer()
        val host = create(hostPeer, queue = listOf(newEntry("a"), newEntry("b")))
        val guest = join(host.room.code) as JoinResult.Joined
        val first = host.room.state.queue[0].entryId
        clock.now += Limits.START_DELAY_MS + 195_000
        guest.room.trackEnded(guest.memberId, guest.peer, first)
        assertEquals(host.room.state.queue[1].entryId, hostPeer.last<ServerMessage.State>().state.currentEntryId)
    }

    @Test
    fun sweepRunsTheFallbackAdvance() = runBlocking {
        val host = create(queue = listOf(newEntry("a", 10_000), newEntry("b", 10_000)))
        clock.now += Limits.START_DELAY_MS + 10_000 + Limits.FALLBACK_SLACK_MS
        host.room.sweep()
        assertEquals(host.room.state.queue[1].entryId, host.room.state.currentEntryId)
    }

    @Test
    fun reconnectingGuestTakesOverAHostlessRoom() = runBlocking {
        val hostPeer = FakePeer()
        val host = create(hostPeer)
        val guestPeer = FakePeer()
        val guest = join(host.room.code, guestPeer) as JoinResult.Joined
        val token = guestPeer.last<ServerMessage.Welcome>().resumeToken
        host.room.disconnected(host.memberId, hostPeer)
        clock.now += 10_000
        guest.room.disconnected(guest.memberId, guestPeer)
        clock.now += Limits.GRACE_MS - 10_000 // host expired, guest still within grace
        host.room.sweep()
        val back = join(host.room.code, FakePeer(), token) as JoinResult.Joined
        assertEquals(guest.memberId, back.memberId)
        assertEquals(guest.memberId, host.room.state.hostId)
        assertTrue(host.room.state.members.single().isHost)
    }

    @Test
    fun creatorWhoseSocketDiedLeavesNoRoomBehind() = runBlocking {
        assertEquals(JoinResult.Rejected(ErrorCode.INVALID), registry.create(ClientMessage.Create(PROTOCOL_VERSION, "Host"), DeadPeer()))
        assertEquals(0, registry.size)
    }

    @Test
    fun joinerWhoseSocketDiedIsDisconnectedAndExpires() = runBlocking {
        val host = create()
        assertEquals(JoinResult.Rejected(ErrorCode.INVALID), join(host.room.code, DeadPeer()))
        val ghost = host.room.state.members.single { it.id != host.memberId }
        assertFalse(ghost.connected)
        clock.now += Limits.GRACE_MS
        host.room.sweep()
        assertEquals(listOf(host.memberId), host.room.state.members.map { it.id })
    }

    @Test
    fun aSweptRoomRefusesLateJoins() = runBlocking {
        val hostPeer = FakePeer()
        val host = create(hostPeer)
        host.room.disconnected(host.memberId, hostPeer)
        registry.sweep()
        clock.now += Limits.EMPTY_ROOM_MS
        registry.sweep()
        assertNull(registry.get(host.room.code))
        assertEquals(JoinResult.Rejected(ErrorCode.ROOM_NOT_FOUND), host.room.join("Late", null, FakePeer()))
    }

    @Test
    fun aBroadcastIsEncodedOnceForAllPeers() = runBlocking {
        val hostPeer = FakePeer()
        val host = create(hostPeer, queue = listOf(newEntry()))
        val guestPeer = FakePeer()
        join(host.room.code, guestPeer)
        host.room.command(host.memberId, host.peer, "p", Command.Pause)
        val toHost = hostPeer.outgoing.last()
        val toGuest = guestPeer.outgoing.last()
        assertTrue(toHost.message is ServerMessage.State)
        assertSame(toHost, toGuest)
        assertSame(toHost.text, toGuest.text)
    }

    @Test
    fun trackEndedSharesTheCommandBudget() = runBlocking {
        val hostPeer = FakePeer()
        val host = create(hostPeer, queue = listOf(newEntry("a"), newEntry("b")))
        val first = host.room.state.queue[0].entryId
        clock.now += Limits.START_DELAY_MS + 195_000
        // Play while playing changes nothing but still spends the budget.
        repeat(Limits.COMMANDS_PER_SECOND) { host.room.command(host.memberId, host.peer, "r$it", Command.Play) }
        host.room.trackEnded(host.memberId, host.peer, first)
        assertEquals(first, host.room.state.currentEntryId)
        clock.now += 1_000
        host.room.trackEnded(host.memberId, host.peer, first)
        assertEquals(host.room.state.queue[1].entryId, host.room.state.currentEntryId)
    }

    @Test
    fun theServerWideRoomCapIsEnforced() = runBlocking {
        val small = RoomRegistry(clock, ids, maxRooms = 1)
        assertTrue(small.create(ClientMessage.Create(PROTOCOL_VERSION, "A"), FakePeer()) is JoinResult.Joined)
        assertEquals(JoinResult.Rejected(ErrorCode.ROOM_FULL), small.create(ClientMessage.Create(PROTOCOL_VERSION, "B"), FakePeer()))
    }

    @Test
    fun runningOutOfRoomCodesIsARejectionNotACrash() = runBlocking {
        val sameCode = object : IdGen by SeqIds() {
            override fun roomCode() = "SAME00"
        }
        val stuck = RoomRegistry(clock, sameCode)
        assertTrue(stuck.create(ClientMessage.Create(PROTOCOL_VERSION, "A"), FakePeer()) is JoinResult.Joined)
        assertEquals(JoinResult.Rejected(ErrorCode.ROOM_FULL), stuck.create(ClientMessage.Create(PROTOCOL_VERSION, "B"), FakePeer()))
    }

    @Test
    fun everySupportedProtocolVersionIsAcceptedAndOthersMustUpgrade() = runBlocking {
        val code = create().room.code
        for (version in MIN_SUPPORTED_PROTOCOL_VERSION..PROTOCOL_VERSION) {
            assertTrue(registry.create(ClientMessage.Create(version, "Host"), FakePeer()) is JoinResult.Joined)
            assertTrue(registry.join(ClientMessage.Join(version, code, "G"), FakePeer()) is JoinResult.Joined)
        }
        for (version in listOf(MIN_SUPPORTED_PROTOCOL_VERSION - 1, PROTOCOL_VERSION + 1)) {
            assertEquals(JoinResult.Rejected(ErrorCode.UPGRADE_REQUIRED), registry.create(ClientMessage.Create(version, "Host"), FakePeer()))
            assertEquals(JoinResult.Rejected(ErrorCode.UPGRADE_REQUIRED), registry.join(ClientMessage.Join(version, code, "G"), FakePeer()))
        }
    }

    @Test
    fun welcomeCarriesTheServerProtocolVersion() = runBlocking {
        val peer = FakePeer()
        create(peer)
        assertEquals(PROTOCOL_VERSION, peer.last<ServerMessage.Welcome>().protocolVersion)
    }

    @Test
    fun aReplacedConnectionCanNoLongerActForTheMember() = runBlocking {
        val hostPeer = FakePeer()
        val host = create(hostPeer, queue = listOf(newEntry("a"), newEntry("b")))
        val oldPeer = FakePeer()
        val guest = join(host.room.code, oldPeer) as JoinResult.Joined
        val token = oldPeer.last<ServerMessage.Welcome>().resumeToken
        val resumed = join(host.room.code, FakePeer(), token) as JoinResult.Joined
        assertEquals(guest.memberId, resumed.memberId)
        val before = host.room.state

        guest.room.command(guest.memberId, oldPeer, "p", Command.Pause)
        clock.now += Limits.START_DELAY_MS + 195_000
        guest.room.trackEnded(guest.memberId, oldPeer, before.queue[0].entryId)
        guest.room.leave(guest.memberId, oldPeer)
        assertEquals(before.version, host.room.state.version)
        assertTrue(host.room.state.members.single { it.id == guest.memberId }.connected)
        assertFalse(oldPeer.received.any { it is ServerMessage.Error })

        resumed.room.command(resumed.memberId, resumed.peer, "p", Command.Pause)
        assertFalse(host.room.state.playback.isPlaying)
        resumed.room.leave(resumed.memberId, resumed.peer)
        assertEquals(listOf(host.memberId), host.room.state.members.map { it.id })
    }

    @Test
    fun namesAreCleanedOfInvisibleCharactersBeforeTheLengthCheck() = runBlocking {
        val peer = FakePeer()
        registry.create(ClientMessage.Create(PROTOCOL_VERSION, "\u202EBu\u200Bdi\n"), peer)
        assertEquals("Budi", peer.last<ServerMessage.Welcome>().state.members.single().name)
        assertEquals(
            JoinResult.Rejected(ErrorCode.INVALID),
            registry.create(ClientMessage.Create(PROTOCOL_VERSION, "\u200B\u200D\u2028\t"), FakePeer()),
        )
    }
}

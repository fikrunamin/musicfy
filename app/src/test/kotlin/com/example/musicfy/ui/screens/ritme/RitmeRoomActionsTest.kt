package com.example.musicfy.ui.screens.ritme

import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.RoomSettings
import com.example.musicfy.ritme.session.MoveTarget
import com.example.musicfy.ritme.testing.entry
import com.example.musicfy.ritme.testing.member
import com.example.musicfy.ritme.testing.room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RitmeRoomActionsTest {

    // m1 hosts; m2 and m3 are guests (m3 disconnected). "b" is playing.
    private val queue = listOf(
        entry("a", addedBy = "m2"),
        entry("b"),
        entry("c", addedBy = "m2"),
        entry("d"),
        entry("e", addedBy = "m2", kind = MediaKind.URL),
    )
    private val members = listOf(member("m1", isHost = true), member("m2"), member("m3", connected = false))
    private val open = room(queue = queue, currentEntryId = "b", members = members)
    private val locked = open.copy(settings = RoomSettings(guestsCanControl = false))

    private fun upcoming(room: com.example.musicfy.ritme.protocol.RoomState, me: String?, id: String, online: Boolean = true) =
        RitmeRoomActions.forUpcoming(room, me, room.queue.first { it.entryId == id }, online)

    @Test
    fun theHostGetsEveryRowActionEvenWhenControlsAreLocked() {
        val c = upcoming(locked, "m1", "c")
        assertTrue(c.canPlay)
        assertTrue(c.canRemove)
        assertNull(c.moveUp) // already first after the current entry
        assertEquals(MoveTarget("d"), c.moveDown)

        val d = upcoming(locked, "m1", "d")
        assertEquals(MoveTarget(null), d.moveUp)
        assertEquals(MoveTarget("e"), d.moveDown)
    }

    @Test
    fun aGuestWithControlMayPlayMoveAndRemoveAnything() {
        val d = upcoming(open, "m2", "d")
        assertTrue(d.canPlay)
        assertTrue(d.canRemove)
        assertEquals(MoveTarget(null), d.moveUp)
        assertEquals(MoveTarget("e"), d.moveDown)
    }

    @Test
    fun aLockedGuestMayOnlyRemoveTheirOwnEntries() {
        val own = upcoming(locked, "m2", "c")
        assertFalse(own.canPlay)
        assertTrue(own.canRemove)
        assertNull(own.moveUp)
        assertNull(own.moveDown)

        assertEquals(EntryActions.NONE, upcoming(locked, "m2", "d"))
    }

    @Test
    fun theLastEntryCannotMoveDownAndUnsupportedEntriesCannotBePlayed() {
        val e = upcoming(open, "m1", "e")
        assertNull(e.moveDown)
        assertEquals(MoveTarget("c"), e.moveUp)
        assertFalse(e.canPlay)
        assertTrue(e.canRemove)
    }

    @Test
    fun withNothingPlayingTheFirstEntryMovesToTheFront() {
        val idle = open.copy(currentEntryId = null)
        assertNull(upcoming(idle, "m1", "a").moveUp)
        assertEquals(MoveTarget(null), upcoming(idle, "m1", "b").moveUp)
        assertEquals(MoveTarget("b"), upcoming(idle, "m1", "a").moveDown)
    }

    @Test
    fun theCurrentEntryCanOnlyBeRemoved() {
        val current = queue[1]
        val host = RitmeRoomActions.forCurrent(open, "m1", current, online = true)
        assertEquals(EntryActions.NONE.copy(canRemove = true), host)
        // m1's entry, controls locked: m2 may not remove it.
        assertEquals(EntryActions.NONE, RitmeRoomActions.forCurrent(locked, "m2", current, online = true))
        assertTrue(RitmeRoomActions.forCurrent(open, "m2", current, online = true).canRemove)
    }

    @Test
    fun nothingIsOfferedWhileOffline() {
        assertEquals(EntryActions.NONE, upcoming(open, "m1", "c", online = false))
        assertEquals(EntryActions.NONE, RitmeRoomActions.forCurrent(open, "m1", queue[1], online = false))
        assertFalse(RitmeRoomActions.forMember(open, "m1", members[1], online = false).any)
        assertFalse(RitmeRoomActions.canManageRoom(open, "m1", online = false))
    }

    @Test
    fun theHostMayKickAnyoneElseButHandOverOnlyToConnectedMembers() {
        val connected = RitmeRoomActions.forMember(open, "m1", members[1], online = true)
        assertTrue(connected.canTransferHost)
        assertTrue(connected.canKick)

        val away = RitmeRoomActions.forMember(open, "m1", members[2], online = true)
        assertFalse(away.canTransferHost)
        assertTrue(away.canKick)
        assertTrue(away.any)
    }

    @Test
    fun nobodyActsOnThemselvesAndGuestsActOnNobody() {
        assertFalse(RitmeRoomActions.forMember(open, "m1", members[0], online = true).any)
        assertFalse(RitmeRoomActions.forMember(open, "m2", members[0], online = true).any)
        assertFalse(RitmeRoomActions.forMember(open, "m2", members[2], online = true).any)
        assertFalse(RitmeRoomActions.forMember(open, null, members[1], online = true).any)
    }

    @Test
    fun aMemberWhoLeftTheRoomOffersNothing() {
        val gone = open.copy(members = members.take(2))
        assertFalse(RitmeRoomActions.forMember(gone, "m1", members[2], online = true).any)
    }

    @Test
    fun onlyTheHostManagesTheRoom() {
        assertTrue(RitmeRoomActions.canManageRoom(open, "m1", online = true))
        assertFalse(RitmeRoomActions.canManageRoom(open, "m2", online = true))
        assertFalse(RitmeRoomActions.canManageRoom(open, null, online = true))
    }

    @Test
    fun theAvatarInitialIsOneWholeCodePoint() {
        assertEquals("B", initialOf("  budi "))
        assertEquals("?", initialOf("   "))
        assertEquals("🎵", initialOf("🎵 music"))
    }
}

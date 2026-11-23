package com.example.musicfy.ritme.session

import com.example.musicfy.ritme.protocol.RoomSettings
import com.example.musicfy.ritme.testing.entry
import com.example.musicfy.ritme.testing.room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RitmePermissionsTest {

    private val queue = listOf(entry("a", addedBy = "m2"), entry("b"), entry("c", addedBy = "m2"), entry("d"))
    private val open = room(queue = queue, currentEntryId = "b")
    private val locked = open.copy(settings = RoomSettings(guestsCanControl = false))

    @Test
    fun theHostAlwaysControls() {
        assertTrue(RitmePermissions.isHost(locked, "m1"))
        assertTrue(RitmePermissions.canControl(locked, "m1"))
        assertFalse(RitmePermissions.isHost(locked, null))
    }

    @Test
    fun guestsControlOnlyWhileAllowed() {
        assertTrue(RitmePermissions.canControl(open, "m2"))
        assertFalse(RitmePermissions.canControl(locked, "m2"))
    }

    @Test
    fun anyoneMayRemoveTheirOwnEntries() {
        assertTrue(RitmePermissions.canRemove(locked, "m2", queue[2]))
        assertFalse(RitmePermissions.canRemove(locked, "m2", queue[3]))
        assertTrue(RitmePermissions.canRemove(open, "m2", queue[3]))
    }

    @Test
    fun theQueueSplitsAroundTheCurrentEntry() {
        val view = RitmePermissions.split(open)
        assertEquals(listOf("a"), view.played.map { it.entryId })
        assertEquals("b", view.current?.entryId)
        assertEquals(listOf("c", "d"), view.upcoming.map { it.entryId })
    }

    @Test
    fun withNothingCurrentEverythingIsUpcoming() {
        val view = RitmePermissions.split(open.copy(currentEntryId = null))
        assertNull(view.current)
        assertEquals(4, view.upcoming.size)
    }

    @Test
    fun moveTargetsMatchTheServerReading() {
        assertNull(RitmePermissions.moveUpTarget(open, "c")) // already right after the current entry
        assertEquals(MoveTarget(null), RitmePermissions.moveUpTarget(open, "d"))
        assertEquals(MoveTarget("d"), RitmePermissions.moveDownTarget(open, "c"))
        assertNull(RitmePermissions.moveDownTarget(open, "d")) // last
        assertNull(RitmePermissions.moveDownTarget(open, "b")) // the current entry never moves
    }

    @Test
    fun theHostAlwaysAddsAndGuestsOnlyWhileAllowed() {
        val noAdds = open.copy(settings = RoomSettings(guestsCanAdd = false))
        assertTrue(RitmePermissions.canAdd(open, "m2"))
        assertFalse(RitmePermissions.canAdd(noAdds, "m2"))
        assertTrue(RitmePermissions.canAdd(noAdds, "m1"))
        assertFalse(RitmePermissions.canAdd(noAdds, null))
    }
}

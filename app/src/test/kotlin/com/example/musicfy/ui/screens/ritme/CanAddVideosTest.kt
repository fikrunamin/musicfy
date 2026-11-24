package com.example.musicfy.ui.screens.ritme

import com.example.musicfy.ritme.protocol.RoomSettings
import com.example.musicfy.ritme.session.RitmePhase
import com.example.musicfy.ritme.session.RitmeUiState
import com.example.musicfy.ritme.testing.room
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanAddVideosTest {

    private fun inRoom(guestsCanAdd: Boolean, memberId: String) = RitmeUiState(
        phase = RitmePhase.IN_ROOM,
        memberId = memberId,
        room = room(settings = RoomSettings(guestsCanAdd = guestsCanAdd)),
    )

    @Test
    fun outsideARoomOnlyStartModeMayAdd() {
        assertTrue(canAddVideos(RitmeUiState(), roomMode = false))
        assertFalse(canAddVideos(RitmeUiState(), roomMode = true))
    }

    @Test
    fun inARoomItFollowsTheAddRight() {
        assertTrue(canAddVideos(inRoom(guestsCanAdd = true, memberId = "m2"), roomMode = true))
        assertTrue(canAddVideos(inRoom(guestsCanAdd = true, memberId = "m2"), roomMode = false))
        assertFalse(canAddVideos(inRoom(guestsCanAdd = false, memberId = "m2"), roomMode = true))
        assertTrue(canAddVideos(inRoom(guestsCanAdd = false, memberId = "m1"), roomMode = true))
    }
}

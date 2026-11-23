package com.example.musicfy.ritme.session

import com.example.musicfy.ritme.net.ConnectionStatus
import com.example.musicfy.ritme.net.EndReason
import com.example.musicfy.ritme.testing.room
import org.junit.Assert.assertEquals
import org.junit.Test

class RitmeModeTransitionsTest {

    @Test
    fun aWelcomeEntersRitmeMode() {
        assertEquals(RitmeTransition.Enter, RitmeModeTransitions.next(false, RitmeUiState(phase = RitmePhase.IN_ROOM, room = room())))
    }

    @Test
    fun reconnectingStaysInRitmeMode() {
        val state = RitmeUiState(phase = RitmePhase.IN_ROOM, connection = ConnectionStatus.RECONNECTING, room = room())
        assertEquals(RitmeTransition.None, RitmeModeTransitions.next(true, state))
    }

    @Test
    fun anyEndLeavesRitmeModeWithItsReason() {
        val state = RitmeUiState(phase = RitmePhase.ENDED, ended = EndReason.Kicked)
        assertEquals(RitmeTransition.Exit(EndReason.Kicked), RitmeModeTransitions.next(true, state))
        assertEquals(RitmeTransition.Exit(null), RitmeModeTransitions.next(true, RitmeUiState()))
    }

    @Test
    fun failuresBeforeTheWelcomeNeverEnteredRitmeMode() {
        val state = RitmeUiState(phase = RitmePhase.ENDED, ended = EndReason.Unreachable)
        assertEquals(RitmeTransition.None, RitmeModeTransitions.next(false, state))
    }

    @Test
    fun aRoomPhaseWithoutARoomIsNotARoom() {
        assertEquals(RitmeTransition.None, RitmeModeTransitions.next(false, RitmeUiState(phase = RitmePhase.IN_ROOM, room = null)))
    }
}

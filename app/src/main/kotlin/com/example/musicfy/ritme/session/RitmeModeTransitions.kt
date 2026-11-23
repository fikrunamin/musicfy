package com.example.musicfy.ritme.session

import com.example.musicfy.ritme.net.EndReason

sealed interface RitmeTransition {
    data object None : RitmeTransition
    /** First Welcome: save the local queue and hand the player to the room. */
    data object Enter : RitmeTransition
    /** Left, kicked, closed or lost: restore the local queue (paused). */
    data class Exit(val reason: EndReason?) : RitmeTransition
}

/** When the playback bridge enters and leaves "Ritme mode". Reconnecting stays in the mode. */
object RitmeModeTransitions {
    fun next(inRitme: Boolean, state: RitmeUiState): RitmeTransition = when {
        !inRitme && state.inRoom -> RitmeTransition.Enter
        inRitme && !state.inRoom -> RitmeTransition.Exit(state.ended)
        else -> RitmeTransition.None
    }
}

package com.example.musicfy.ritme

import com.example.musicfy.models.MediaMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Something the user asked the local player to do. While in a room it goes to the room instead. */
sealed interface RitmeLocalAction {
    data object Play : RitmeLocalAction
    data object Pause : RitmeLocalAction
    data object TogglePlayPause : RitmeLocalAction
    data class Seek(val positionMs: Long) : RitmeLocalAction
    data object Next : RitmeLocalAction
    data object Previous : RitmeLocalAction
    /** "Add to queue" (playNext = false) or "Play next" (playNext = true). */
    data class AddItems(val songs: List<MediaMetadata?>, val playNext: Boolean) : RitmeLocalAction
    /** Tapping a song/album to play it: the songs it would start with. */
    data class PlayQueue(val songs: List<MediaMetadata?>) : RitmeLocalAction
    /**
     * Play/Shuffle on a playlist, album or artist page (or those menus): every song of it, in the
     * order given (already shuffled for Shuffle), to the end of the room queue.
     */
    data class AddAll(val songs: List<MediaMetadata?>) : RitmeLocalAction
    /** Reordering or removing in the local queue page. */
    data object QueueEdit : RitmeLocalAction
    data object Shuffle : RitmeLocalAction
    data object Repeat : RitmeLocalAction
    data object Speed : RitmeLocalAction
}

/**
 * The single switch between the normal player and Ritme. Every local control path calls
 * [intercept] first: closed (not in a room) it returns false and the caller runs its usual code;
 * open it hands the action to [handler] (RitmeController) and returns true. Main thread only.
 */
@Singleton
class RitmeGate @Inject constructor() {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()
    val isActive: Boolean get() = _active.value

    @Volatile
    var handler: ((RitmeLocalAction) -> Unit)? = null

    fun intercept(action: RitmeLocalAction): Boolean {
        if (!_active.value) return false
        handler?.invoke(action)
        return true
    }

    fun activate() {
        _active.value = true
    }

    fun deactivate() {
        _active.value = false
    }
}

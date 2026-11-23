package com.example.musicfy.ritme

import com.example.musicfy.models.MediaMetadata
import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.session.RitmePermissions
import com.example.musicfy.ritme.session.RitmeSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The local player, as the controller needs it. RitmePlaybackBridge implements it. */
interface RitmeLocalPlayback {
    val isPlayingLocally: Boolean
    val isCasting: Boolean
    /** The room is playing but this device is paused locally: just play again and re-sync. */
    fun resumeLocally()
    /** Pause this device only (a member without control); the room plays on and nothing is sent. */
    fun pauseLocally()
    fun playOrderQueue(): LocalPlayOrder
}

/** The local queue in play order (shuffle applied), for a room's starting queue. */
data class LocalPlayOrder(val songs: List<MediaMetadata?>, val currentIndex: Int, val positionMs: Long)

interface RitmeSettings {
    val serverUrl: String
    val displayName: String
}

/** A request for the UI to show a Ritme sheet; [id] makes repeated requests distinct. */
data class RitmeSheetRequest(val id: Long, val kind: Kind) {
    sealed interface Kind {
        data object Room : Kind
        data class Entry(val code: String? = null, val startWith: List<MediaMetadata?>? = null) : Kind
    }
}

/**
 * Turns local intents into room commands, checking the permission table first (spec 2.3, 3.4):
 * refused controls show "The host locked the controls"; local-only features show "Not available
 * during Ritme". A member without control may still pause/resume their own device (no command;
 * "Paused on this device only"). Also starts/joins rooms and asks the UI for sheets. Main thread only.
 */
@Singleton
class RitmeController @Inject constructor(
    private val session: RitmeSession,
    gate: RitmeGate,
    private val notifier: RitmeNotifier,
    private val settings: RitmeSettings,
) {
    var localPlayback: RitmeLocalPlayback? = null

    private val _sheetRequest = MutableStateFlow<RitmeSheetRequest?>(null)
    val sheetRequest: StateFlow<RitmeSheetRequest?> = _sheetRequest.asStateFlow()
    private var requestIds = 0L

    init {
        gate.handler = ::handle
    }

    fun openSheet() = request(if (session.state.value.inRoom) RitmeSheetRequest.Kind.Room else RitmeSheetRequest.Kind.Entry())

    fun openJoin(code: String) =
        request(if (session.state.value.inRoom) RitmeSheetRequest.Kind.Room else RitmeSheetRequest.Kind.Entry(code = code))

    fun openStartWith(songs: List<MediaMetadata?>) =
        request(if (session.state.value.inRoom) RitmeSheetRequest.Kind.Room else RitmeSheetRequest.Kind.Entry(startWith = songs))

    fun consumeSheetRequest(id: Long) {
        if (_sheetRequest.value?.id == id) _sheetRequest.value = null
    }

    private fun request(kind: RitmeSheetRequest.Kind) {
        _sheetRequest.value = RitmeSheetRequest(++requestIds, kind)
    }

    fun shareLink(code: String): String = RitmeLinks.shareLink(settings.serverUrl, code)

    /** Creates a room from [startWith], or else from the local queue (current song onward, play order). */
    fun startRoom(name: String, startWith: List<MediaMetadata?>? = null) {
        if (localPlayback?.isCasting == true) {
            notifier.show(RitmeToast.CastingBlocked)
            return
        }
        val order = startWith?.let { LocalPlayOrder(it, 0, 0) }
            ?: localPlayback?.playOrderQueue()
            ?: LocalPlayOrder(emptyList(), 0, 0)
        val initial = RitmeEntries.buildInitialQueue(order.songs, order.currentIndex, order.positionMs)
        if (initial.skipped > 0) notifier.show(RitmeToast.SkippedLocal(initial.skipped))
        session.create(settings.serverUrl, name, initial)
    }

    fun joinRoom(code: String, name: String) {
        if (localPlayback?.isCasting == true) {
            notifier.show(RitmeToast.CastingBlocked)
            return
        }
        session.join(settings.serverUrl, code, name)
    }

    fun handle(action: RitmeLocalAction) {
        val state = session.state.value
        val room = state.room ?: return
        val me = state.memberId
        when (action) {
            RitmeLocalAction.Play -> play(room, me)
            RitmeLocalAction.Pause -> pause(room, me)
            RitmeLocalAction.TogglePlayPause ->
                if (localPlayback?.isPlayingLocally == true) pause(room, me) else play(room, me)
            is RitmeLocalAction.Seek -> control(room, me) { session.seek(action.positionMs) }
            RitmeLocalAction.Next -> control(room, me) { session.next() }
            RitmeLocalAction.Previous -> control(room, me) { session.previous() }
            is RitmeLocalAction.AddItems -> add(action.songs, action.playNext)
            is RitmeLocalAction.PlayQueue ->
                if (action.songs.isEmpty()) notifier.show(RitmeToast.NotAvailable) else add(action.songs, playNext = false)
            RitmeLocalAction.QueueEdit,
            RitmeLocalAction.Shuffle,
            RitmeLocalAction.Repeat,
            RitmeLocalAction.Speed -> notifier.show(RitmeToast.NotAvailable)
        }
    }

    private fun play(room: RoomState, me: String?) {
        // Already playing for the room: this device was paused locally, so only it resumes.
        // Paused room: only the Play command goes out (no optimistic local play, or the planner
        // would pause it again until the snapshot lands); without control it is refused.
        if (room.playback.isPlaying) localPlayback?.resumeLocally() else control(room, me) { session.play() }
    }

    private fun pause(room: RoomState, me: String?) {
        if (RitmePermissions.canControl(room, me)) {
            control(room, me) { session.pause() }
            return
        }
        // No control: pause this device only. The room plays on; the next room change re-syncs it.
        val local = localPlayback ?: return
        if (!local.isPlayingLocally) return
        local.pauseLocally()
        notifier.show(RitmeToast.PausedLocally)
    }

    private inline fun control(room: RoomState, me: String?, send: () -> Boolean) {
        when {
            !RitmePermissions.canControl(room, me) -> notifier.show(RitmeToast.ControlsLocked)
            !send() -> notifier.show(RitmeToast.Reconnecting)
        }
    }

    private fun add(songs: List<MediaMetadata?>, playNext: Boolean) {
        val batches = RitmeEntries.toAddBatches(songs)
        if (batches.count == 0) {
            notifier.show(if (batches.skipped > 0) RitmeToast.SkippedLocal(batches.skipped) else RitmeToast.NotAvailable)
            return
        }
        // Each "play next" batch lands right after the current entry, so the last batch goes first.
        val ordered = if (playNext) batches.batches.asReversed() else batches.batches
        val sent = ordered.all { session.add(it, playNext) }
        notifier.show(if (sent) RitmeToast.Added(batches.count) else RitmeToast.Reconnecting)
    }
}

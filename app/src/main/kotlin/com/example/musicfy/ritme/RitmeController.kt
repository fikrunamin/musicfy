package com.example.musicfy.ritme

import com.example.musicfy.models.MediaMetadata
import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.protocol.MediaUrlRules
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.session.NoticeKey
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
        data class Entry(val code: String? = null, val startWith: List<MediaMetadata?>? = null, val startWithEntries: List<NewEntry>? = null) : Kind
        /** "Add video"; [text] is a shared link or words to fill in. */
        data class AddVideo(val text: String? = null) : Kind
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
        // The host of a room just created gets the room sheet right away, to share the link (once:
        // never on a resume). Joining someone else's room opens nothing; the pill is enough.
        session.onRoomCreated = { room ->
            if (RitmePermissions.isHost(room, session.state.value.memberId)) request(RitmeSheetRequest.Kind.Room)
        }
    }

    fun openSheet() = request(if (session.state.value.inRoom) RitmeSheetRequest.Kind.Room else RitmeSheetRequest.Kind.Entry())

    fun openJoin(code: String) =
        request(if (session.state.value.inRoom) RitmeSheetRequest.Kind.Room else RitmeSheetRequest.Kind.Entry(code = code))

    /**
     * A room link from a deep link or shared text (`musicfy://ritme/{code}`, or `/r/{code}` on the
     * official or the configured server): opens the join form with the code filled in. Joining
     * still takes a tap. False if [text] holds no room link.
     */
    fun openJoinLink(text: String): Boolean {
        val code = RitmeLinks.findRoomCode(text, listOf(RitmeConfig.DEFAULT_SERVER_URL, settings.serverUrl)) ?: return false
        openJoin(code)
        return true
    }

    /**
     * The start sheet with [songs] as the room's starting queue (in a room: the room sheet). A list
     * with nothing a room can take (empty, a failed fetch, local files only) opens nothing and says
     * "Not available during Ritme", instead of starting an empty room.
     */
    fun openStartWith(songs: List<MediaMetadata?>) {
        if (session.state.value.inRoom) return request(RitmeSheetRequest.Kind.Room)
        if (!RitmeEntries.hasShareable(songs)) return notifier.show(RitmeToast.NotAvailable)
        request(RitmeSheetRequest.Kind.Entry(startWith = songs))
    }

    /** The start sheet with ready-made [entries] (a shared video, spec 4.6); in a room, the room sheet. */
    fun openStartWithEntries(entries: List<NewEntry>) {
        if (session.state.value.inRoom) return request(RitmeSheetRequest.Kind.Room)
        if (entries.isEmpty()) return notifier.show(RitmeToast.NotAvailable)
        request(RitmeSheetRequest.Kind.Entry(startWithEntries = entries))
    }

    /**
     * "Add video"'s start-mode button: the start sheet with [entries]. Start mode is fixed when that
     * sheet opens, so if this device has joined a room since, they are added to the room instead
     * (same permission check and toasts as its "Add" button), never dropped for the room sheet.
     */
    fun startOrAddEntries(entries: List<NewEntry>) {
        if (session.state.value.inRoom) return addEntries(entries, playNext = false)
        openStartWithEntries(entries)
    }

    /**
     * Entries from "Add video" (spec 4.6): only ever from its buttons, never automatically. The same
     * permission check, batching, play-next stacking and toasts as songs.
     */
    fun addEntries(entries: List<NewEntry>, playNext: Boolean) {
        val state = session.state.value
        val room = state.room ?: return
        if (!addAllowed(room, state.memberId, videos = true)) return
        if (entries.isEmpty()) return notifier.show(RitmeToast.NotAvailable)
        val batches = entries.take(RitmeEntries.MAX_QUEUE).chunked(RitmeEntries.MAX_BATCH)
        val stacked = playNext && room.currentEntryId != null
        val ordered = if (stacked) batches.asReversed() else batches
        val sent = ordered.all { session.add(it, playNext) }
        notifier.show(if (sent) RitmeToast.Added(entries.size.coerceAtMost(RitmeEntries.MAX_QUEUE), videos = true) else RitmeToast.Reconnecting)
    }

    /**
     * The "Add video" sheet (spec 4.6), with [text] (a shared link) filled in. In a room it adds to
     * the room, so a member who may not add gets the ADD_FORBIDDEN toast instead of the sheet;
     * outside a room it opens in start mode (Decision 12): its button leads to the start sheet.
     */
    fun openAddVideo(text: String? = null) {
        val state = session.state.value
        val room = state.room
        if (state.inRoom && room != null && !addAllowed(room, state.memberId, videos = true)) return
        request(RitmeSheetRequest.Kind.AddVideo(text))
    }

    fun consumeSheetRequest(id: Long) {
        if (_sheetRequest.value?.id == id) _sheetRequest.value = null
    }

    private fun request(kind: RitmeSheetRequest.Kind) {
        _sheetRequest.value = RitmeSheetRequest(++requestIds, kind)
    }

    /** The room's link on the server this session was started or joined with, not the current setting. */
    fun shareLink(code: String): String = RitmeLinks.shareLink(session.serverUrl ?: settings.serverUrl, code)

    /**
     * The Ritme web client's origin (where web members play from), for the probes' CORS check:
     * the room's server, or the default server when that is a local development address.
     */
    fun webOrigin(): String {
        val origin = MediaUrlRules.referrerOf(session.serverUrl ?: settings.serverUrl)
            ?: MediaUrlRules.referrerOf(RitmeConfig.DEFAULT_SERVER_URL)
        return origin?.removeSuffix("/") ?: RitmeConfig.DEFAULT_SERVER_URL.trimEnd('/')
    }

    /** Creates a room from [startWith], or else from the local queue (current song onward, play order). */
    fun startRoom(name: String, startWith: List<MediaMetadata?>? = null, startWithEntries: List<NewEntry>? = null) {
        if (localPlayback?.isCasting == true) {
            notifier.show(RitmeToast.CastingBlocked)
            return
        }
        if (startWithEntries != null) {
            session.create(settings.serverUrl, name, RitmeEntries.initialQueueOf(startWithEntries))
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
            is RitmeLocalAction.AddItems -> add(room, me, action.songs, action.playNext)
            is RitmeLocalAction.PlayQueue ->
                if (action.songs.isEmpty()) notifier.show(RitmeToast.NotAvailable) else add(room, me, action.songs, playNext = false)
            is RitmeLocalAction.AddAll -> addAll(room, me, action.songs)
            RitmeLocalAction.QueueEdit,
            RitmeLocalAction.Shuffle,
            RitmeLocalAction.Repeat,
            RitmeLocalAction.Speed -> notifier.show(RitmeToast.NotAvailable)
        }
    }

    /**
     * A command from the room sheet (remove, move, play an entry, settings, kick, make host, end):
     * dropped while offline, it says "Reconnecting" like the player controls do. True if it was sent.
     */
    fun roomCommand(send: RitmeSession.() -> Boolean): Boolean =
        session.send().also { sent -> if (!sent) notifier.show(RitmeToast.Reconnecting) }

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

    /**
     * Play/Shuffle on a playlist, album or artist page: the list goes to the end of the room queue
     * in the order given, as much of it as the room still holds, the batches after the first paced
     * by the session. The server's [RitmeEntries.MAX_QUEUE] counts every entry, played ones too, and
     * refuses a whole Add past it, so the room's queue and what this session still owes it (overflow
     * not sent yet, Adds not yet in a snapshot) are both taken. Local files are skipped; only local
     * files: the skipped toast. A list cut short says the rest did not fit. Without add rights it is
     * refused up front, like a single add.
     */
    private fun addAll(room: RoomState, me: String?, songs: List<MediaMetadata?>) {
        if (!addAllowed(room, me)) return
        val space = RitmeEntries.MAX_QUEUE - room.queue.size - session.owedEntries
        if (space <= 0) {
            notifier.show(RitmeToast.CommandFailed(NoticeKey.QUEUE_FULL))
            return
        }
        val batches = RitmeEntries.toAddBatches(songs, limit = space)
        if (batches.count == 0) {
            notifier.show(if (batches.skipped > 0) RitmeToast.SkippedLocal(batches.skipped) else RitmeToast.NotAvailable)
            return
        }
        val cutShort = songs.count { RitmeEntries.toNewEntry(it) != null } > batches.count
        notifier.show(
            when {
                !session.addPaced(batches.batches) -> RitmeToast.Reconnecting
                cutShort -> RitmeToast.AddedRestFull(batches.count)
                else -> RitmeToast.Added(batches.count)
            },
        )
    }

    private fun add(room: RoomState, me: String?, songs: List<MediaMetadata?>, playNext: Boolean) {
        if (!addAllowed(room, me)) return
        val batches = RitmeEntries.toAddBatches(songs)
        if (batches.count == 0) {
            notifier.show(if (batches.skipped > 0) RitmeToast.SkippedLocal(batches.skipped) else RitmeToast.NotAvailable)
            return
        }
        // Each "play next" batch lands right after the current entry, so the last batch goes first.
        // With nothing current there is no such spot: the batches go in their own order.
        val stacked = playNext && session.state.value.room?.currentEntryId != null
        val ordered = if (stacked) batches.batches.asReversed() else batches.batches
        val sent = ordered.all { session.add(it, playNext) }
        notifier.show(if (sent) RitmeToast.Added(batches.count) else RitmeToast.Reconnecting)
    }

    /** The server would answer FORBIDDEN; say it right away, with the same message (spec 8), about [videos] or songs. */
    private fun addAllowed(room: RoomState, me: String?, videos: Boolean = false): Boolean {
        if (RitmePermissions.canAdd(room, me)) return true
        notifier.show(RitmeToast.CommandFailed(NoticeKey.ADD_FORBIDDEN, videos))
        return false
    }
}

package com.example.musicfy.ritme.session

import com.example.musicfy.ritme.RitmeLinks
import com.example.musicfy.ritme.entries.InitialQueue
import com.example.musicfy.ritme.net.ConnectionEvent
import com.example.musicfy.ritme.net.ConnectionLike
import com.example.musicfy.ritme.net.ConnectionStatus
import com.example.musicfy.ritme.net.JoinIntent
import com.example.musicfy.ritme.net.RitmeConnector
import com.example.musicfy.ritme.net.RitmeTimer
import com.example.musicfy.ritme.net.RitmeTimers
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.RoomSettings
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.sync.ClockSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Asserts that the caller is on the main thread (a no-op in JVM tests). */
fun interface RitmeThreadCheck {
    fun check()

    companion object {
        val NONE = RitmeThreadCheck {}
    }
}

/**
 * The one owner of the room connection, the server clock and the session state. Main thread only:
 * every public entry point runs [threadCheck], and connection callbacks arrive on the [timers]'
 * (main) thread. Commands are dropped (returning false) unless the session is in a room and online;
 * nothing is applied optimistically - the next server snapshot is what changes [state].
 *
 * [timers] is the same monotonic clock the connection stamps Pongs and Welcomes with, so
 * [serverNow] and the [clock] samples share one time base.
 */
@Singleton
class RitmeSession @Inject constructor(
    private val connector: RitmeConnector,
    private val timers: RitmeTimers,
    private val threadCheck: RitmeThreadCheck = RitmeThreadCheck.NONE,
) {
    private val _state = MutableStateFlow(RitmeUiState())
    val state: StateFlow<RitmeUiState> = _state.asStateFlow()

    val clock = ClockSync()

    private var conn: ConnectionLike? = null
    /**
     * Add batches still owed to the room: a Create's entries past the first 200, and the rest of a
     * paced list add ([addPaced]).
     */
    private val pendingOverflow = ArrayDeque<List<NewEntry>>()
    private var overflowTimer: RitmeTimer? = null

    /** reqIds of recent Adds, so a FORBIDDEN answer to one gets its own message (spec U6). */
    private val addReqIds = LinkedHashSet<String>()

    /** The settings last sent, with the room and version they were built on (see [changeSettings]). */
    private var sentSettings: SentSettings? = null

    private data class SentSettings(val code: String, val afterVersion: Long, val settings: RoomSettings)

    /**
     * An Add sent and not yet seen in a snapshot. There is no per-command ack, so it counts as
     * landed once the room holds at least [expected] entries of this member with its last videoId:
     * those in the room when it was sent, plus the ones this Add and the Adds still in flight before
     * it bring. A refusal (Failure with its reqId), a new Welcome or the end drop it; so does
     * [IN_FLIGHT_EXPIRY_MS], for an Add whose entries someone removed before a snapshot showed them.
     */
    private class InFlightAdd(val reqId: String, val videoIds: List<String>, var expected: Int, val sentAt: Long) {
        val lastVideoId: String get() = videoIds.last()
    }
    private val inFlightAdds = ArrayList<InFlightAdd>()

    /**
     * The server URL this session was started or joined with. Share links use it, so a server URL
     * changed in Settings mid-room only applies to the next Ritme.
     */
    var serverUrl: String? = null
        private set

    /** True from [create] until that room's first Welcome (or the end of the attempt). */
    private var awaitingCreatedRoom = false

    /** Called once per room this device created, on its first Welcome (never on a resume). Main thread. */
    var onRoomCreated: ((RoomState) -> Unit)? = null

    fun serverNow(): Long {
        threadCheck.check()
        return clock.serverNow(timers.now())
    }

    fun create(serverUrl: String, name: String, initial: InitialQueue) {
        val c = begin(serverUrl) ?: return
        awaitingCreatedRoom = true
        pendingOverflow.addAll(initial.overflow)
        c.start(JoinIntent.Create(name, initial.first, initial.startIndex, initial.startPositionMs))
    }

    fun join(serverUrl: String, code: String, name: String) {
        val c = begin(serverUrl) ?: return
        c.start(JoinIntent.Join(code, name))
    }

    fun leave() {
        threadCheck.check()
        conn?.leave()
    }

    /** Back to IDLE after the session ended (the entry sheet calls it before retrying or closing). */
    fun dismiss() {
        threadCheck.check()
        if (_state.value.phase == RitmePhase.ENDED) _state.value = RitmeReducer.reset(_state.value)
    }

    fun play(): Boolean = send(Command.Play)
    fun pause(): Boolean = send(Command.Pause)
    fun seek(positionMs: Long): Boolean = send(Command.Seek(positionMs.coerceAtLeast(0)))
    fun next(): Boolean = send(Command.Next)
    fun previous(): Boolean = send(Command.Previous)
    fun playEntry(entryId: String): Boolean = send(Command.PlayEntry(entryId))
    fun add(entries: List<NewEntry>, playNext: Boolean): Boolean {
        val reqId = sendCommand(Command.Add(entries, playNext)) ?: return false
        addReqIds += reqId
        if (addReqIds.size > MAX_TRACKED_ADDS) addReqIds.remove(addReqIds.first())
        if (entries.isNotEmpty()) trackAdd(reqId, entries)
        return true
    }

    /**
     * Entries this session still owes the room: overflow batches not sent yet plus Adds sent but not
     * yet in a snapshot. The room's [RoomState.queue] does not hold them, but the server's 500-entry
     * cap will, so a list add counts them as taken.
     */
    val owedEntries: Int
        get() {
            threadCheck.check()
            pruneExpiredAdds()
            return pendingOverflow.sumOf { it.size } + inFlightAdds.sumOf { it.videoIds.size }
        }

    /**
     * A long list for the end of the queue (a whole playlist or album): the first batch now, the
     * rest through the same pump as a big Create's overflow, [OVERFLOW_GAP_MS] apart, so a few
     * hundred songs do not go out as back-to-back Adds (each one a full snapshot to every member).
     * Behind overflow still owed, so the order holds. A drop pauses the pump; the resume Welcome
     * picks it up again. True if the batches are on their way (the first one sent, or queued
     * behind overflow); false while offline, when nothing is sent or kept.
     */
    fun addPaced(batches: List<List<NewEntry>>): Boolean {
        threadCheck.check()
        if (batches.isEmpty() || !canSend()) return false
        val c = conn ?: return false
        val code = _state.value.room?.code ?: return false
        if (pendingOverflow.isEmpty()) {
            if (!add(batches.first(), playNext = false)) return false
            pendingOverflow.addAll(batches.drop(1))
        } else {
            pendingOverflow.addAll(batches)
        }
        if (overflowTimer == null && pendingOverflow.isNotEmpty()) {
            overflowTimer = timers.schedule(OVERFLOW_GAP_MS) { pumpOverflow(c, code) }
        }
        return true
    }

    fun remove(entryId: String): Boolean = send(Command.Remove(entryId))
    fun move(entryId: String, afterEntryId: String?): Boolean = send(Command.Move(entryId, afterEntryId))
    fun setGuestsCanControl(value: Boolean): Boolean = changeSettings { it.copy(guestsCanControl = value) }
    fun setGuestsCanAdd(value: Boolean): Boolean = changeSettings { it.copy(guestsCanAdd = value) }
    fun kick(memberId: String): Boolean = send(Command.Kick(memberId))
    fun transferHost(memberId: String): Boolean = send(Command.TransferHost(memberId))
    fun endRoom(): Boolean = send(Command.EndRoom)

    fun trackEnded(entryId: String): Boolean {
        threadCheck.check()
        return canSend() && conn?.trackEnded(entryId) == true
    }

    private fun begin(url: String): ConnectionLike? {
        threadCheck.check()
        val phase = _state.value.phase
        if (phase == RitmePhase.CONNECTING || phase == RitmePhase.IN_ROOM) return null
        serverUrl = url
        awaitingCreatedRoom = false
        clock.reset()
        clearOverflow()
        inFlightAdds.clear()
        addReqIds.clear()
        sentSettings = null
        _state.value = RitmeReducer.start(_state.value)
        lateinit var c: ConnectionLike
        c = connector.connect(RitmeLinks.socketUrl(url)) { event -> if (conn === c) onEvent(event) }
        conn = c
        return c
    }

    private fun mineWith(room: RoomState?, videoId: String): Int {
        val me = _state.value.memberId ?: return 0
        return room?.queue?.count { it.addedBy == me && it.videoId == videoId } ?: 0
    }

    private fun trackAdd(reqId: String, entries: List<NewEntry>) {
        val ids = entries.map { it.videoId }
        val last = ids.last()
        val expected = mineWith(_state.value.room, last) +
            inFlightAdds.sumOf { add -> add.videoIds.count { it == last } } +
            ids.count { it == last }
        inFlightAdds += InFlightAdd(reqId, ids, expected, timers.now())
    }

    /** Drops [gone] and lowers what the Adds sent after it wait for by what it would have brought. */
    private fun dropInFlight(gone: InFlightAdd) {
        val index = inFlightAdds.indexOf(gone)
        if (index < 0) return
        inFlightAdds.removeAt(index)
        for (later in inFlightAdds.subList(index, inFlightAdds.size)) {
            later.expected -= gone.videoIds.count { it == later.lastVideoId }
        }
    }

    private fun confirmInFlight(room: RoomState?) {
        if (room == null) return
        inFlightAdds.filter { mineWith(room, it.lastVideoId) >= it.expected }.forEach { inFlightAdds.remove(it) }
    }

    private fun pruneExpiredAdds() {
        val now = timers.now()
        inFlightAdds.filter { now - it.sentAt >= IN_FLIGHT_EXPIRY_MS }.forEach(::dropInFlight)
    }

    private fun canSend(): Boolean =
        _state.value.phase == RitmePhase.IN_ROOM && _state.value.connection == ConnectionStatus.ONLINE

    private fun send(cmd: Command): Boolean = sendCommand(cmd) != null

    /** The reqId the command went out with, or null when it was not sent (not online in a room). */
    private fun sendCommand(cmd: Command): String? {
        threadCheck.check()
        return if (canSend()) conn?.command(cmd) else null
    }

    /**
     * SetSettings replaces all settings. The others come from the settings this device last sent
     * until a snapshot newer than the one those were built on arrives (two quick toggles must not
     * undo each other), and from the latest snapshot after that.
     */
    private fun changeSettings(change: (RoomSettings) -> RoomSettings): Boolean {
        val room = _state.value.room ?: return false
        val pending = sentSettings?.takeIf { it.code == room.code && room.version <= it.afterVersion }
        val next = change(pending?.settings ?: room.settings)
        if (!send(Command.SetSettings(next))) return false
        sentSettings = SentSettings(room.code, room.version, next)
        return true
    }

    private fun onEvent(event: ConnectionEvent) {
        when (event) {
            is ConnectionEvent.Pong -> clock.addSample(event.clientTime, event.receivedAt, event.serverTime)
            is ConnectionEvent.Welcome -> {
                // Every Welcome (also a resume) starts a fresh clock window: the connection sends a new
                // ping burst after it, and the old socket's samples may predate a network change.
                // reset() also clears the seed, so seed again right away (serverNow is raw local time otherwise).
                clock.reset()
                clock.seed(event.welcome.serverTime, event.receivedAt)
                // Command answers and the room version start over with a new connection.
                addReqIds.clear()
                sentSettings = null
            }
            is ConnectionEvent.Ended -> {
                conn = null
                awaitingCreatedRoom = false
                clearOverflow()
                inFlightAdds.clear()
            }
            is ConnectionEvent.Failure -> inFlightAdds.firstOrNull { it.reqId == event.reqId }?.let(::dropInFlight)
            else -> Unit
        }
        // "The host locked the controls" would be wrong for a refused Add (spec U6).
        val addRefused = event is ConnectionEvent.Failure && event.reqId != null &&
            addReqIds.remove(event.reqId) && event.code == ErrorCode.FORBIDDEN
        _state.value = if (addRefused) {
            RitmeReducer.withNotice(_state.value, NoticeKey.ADD_FORBIDDEN)
        } else {
            RitmeReducer.reduce(_state.value, event)
        }
        when (event) {
            is ConnectionEvent.State -> confirmInFlight(_state.value.room)
            // A Welcome (also a resume) is the room as the server has it: what it lacks was lost with the old socket.
            is ConnectionEvent.Welcome -> {
                confirmInFlight(_state.value.room)
                inFlightAdds.clear()
            }
            else -> Unit
        }
        if (event is ConnectionEvent.Welcome) {
            scheduleOverflow(event.welcome.state.code)
            if (awaitingCreatedRoom) {
                awaitingCreatedRoom = false
                onRoomCreated?.invoke(event.welcome.state)
            }
        }
    }

    /**
     * The rest of a big starting queue goes out [OVERFLOW_DELAY_MS] after the Welcome, not inside it:
     * the connection still has to Ack and run its ping burst, and every Add makes the server send a
     * snapshot (up to ~260 KB) that the burst's Pongs would queue behind, skewing the first clock
     * samples. Batches are [OVERFLOW_GAP_MS] apart. A pump that finds the session offline stops and
     * keeps the rest for the next (resume) Welcome.
     */
    private fun scheduleOverflow(code: String) {
        overflowTimer?.cancel()
        overflowTimer = null
        if (pendingOverflow.isEmpty()) return
        val c = conn ?: return
        overflowTimer = timers.schedule(OVERFLOW_DELAY_MS) { pumpOverflow(c, code) }
    }

    private fun pumpOverflow(c: ConnectionLike, code: String) {
        overflowTimer = null
        if (conn !== c || _state.value.room?.code != code) return
        val batch = pendingOverflow.firstOrNull() ?: return
        if (!add(batch, playNext = false)) return
        pendingOverflow.removeFirst()
        if (pendingOverflow.isNotEmpty()) {
            overflowTimer = timers.schedule(OVERFLOW_GAP_MS) { pumpOverflow(c, code) }
        }
    }

    private fun clearOverflow() {
        overflowTimer?.cancel()
        overflowTimer = null
        pendingOverflow.clear()
    }

    companion object {
        /** After the Welcome's Ack and the 5-ping burst (4 x 200 ms) have gone out and mostly come back. */
        const val OVERFLOW_DELAY_MS = 1_200L
        const val OVERFLOW_GAP_MS = 100L
        /** An Add not seen in any snapshot by then is taken as lost (or removed before it showed). */
        const val IN_FLIGHT_EXPIRY_MS = 10_000L
        /** Adds remembered for [addReqIds]; an answer comes long before this many more go out. */
        const val MAX_TRACKED_ADDS = 64
    }
}

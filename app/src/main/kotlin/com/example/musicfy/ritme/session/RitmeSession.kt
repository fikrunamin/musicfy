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
    /** Add batches still owed to the room that Create made (the Create held at most 200 entries). */
    private val pendingOverflow = ArrayDeque<List<NewEntry>>()
    private var overflowTimer: RitmeTimer? = null

    /** reqIds of recent Adds, so a FORBIDDEN answer to one gets its own message (spec U6). */
    private val addReqIds = LinkedHashSet<String>()

    /** The settings last sent, with the room and version they were built on (see [changeSettings]). */
    private var sentSettings: SentSettings? = null

    private data class SentSettings(val code: String, val afterVersion: Long, val settings: RoomSettings)

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
        addReqIds.clear()
        sentSettings = null
        _state.value = RitmeReducer.start(_state.value)
        lateinit var c: ConnectionLike
        c = connector.connect(RitmeLinks.socketUrl(url)) { event -> if (conn === c) onEvent(event) }
        conn = c
        return c
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
            }
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
        /** Adds remembered for [addReqIds]; an answer comes long before this many more go out. */
        const val MAX_TRACKED_ADDS = 64
    }
}

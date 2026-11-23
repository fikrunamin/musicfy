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
    fun add(entries: List<NewEntry>, playNext: Boolean): Boolean = send(Command.Add(entries, playNext))

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
    fun setGuestsCanControl(value: Boolean): Boolean = send(Command.SetSettings(RoomSettings(guestsCanControl = value)))
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
        _state.value = RitmeReducer.start(_state.value)
        lateinit var c: ConnectionLike
        c = connector.connect(RitmeLinks.socketUrl(url)) { event -> if (conn === c) onEvent(event) }
        conn = c
        return c
    }

    private fun canSend(): Boolean =
        _state.value.phase == RitmePhase.IN_ROOM && _state.value.connection == ConnectionStatus.ONLINE

    private fun send(cmd: Command): Boolean {
        threadCheck.check()
        return canSend() && conn?.command(cmd) != null
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
            }
            is ConnectionEvent.Ended -> {
                conn = null
                awaitingCreatedRoom = false
                clearOverflow()
            }
            else -> Unit
        }
        _state.value = RitmeReducer.reduce(_state.value, event)
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
    }
}

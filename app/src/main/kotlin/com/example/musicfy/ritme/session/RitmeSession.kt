package com.example.musicfy.ritme.session

import com.example.musicfy.ritme.RitmeLinks
import com.example.musicfy.ritme.entries.InitialQueue
import com.example.musicfy.ritme.net.ConnectionEvent
import com.example.musicfy.ritme.net.ConnectionLike
import com.example.musicfy.ritme.net.ConnectionStatus
import com.example.musicfy.ritme.net.JoinIntent
import com.example.musicfy.ritme.net.RitmeConnector
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.RoomSettings
import com.example.musicfy.ritme.sync.ClockSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Monotonic local clock shared with the connection (SystemClock.elapsedRealtime in the app). */
fun interface RitmeClock {
    fun now(): Long
}

/**
 * The one owner of the room connection, the server clock and the session state. Main thread only.
 * Commands are dropped (returning false) unless the session is in a room and online; nothing is
 * applied optimistically - the next server snapshot is what changes [state].
 */
@Singleton
class RitmeSession @Inject constructor(
    private val connector: RitmeConnector,
    private val clockSource: RitmeClock,
) {
    private val _state = MutableStateFlow(RitmeUiState())
    val state: StateFlow<RitmeUiState> = _state.asStateFlow()

    val clock = ClockSync()

    private var conn: ConnectionLike? = null
    private var pendingOverflow: List<List<NewEntry>> = emptyList()

    fun serverNow(): Long = clock.serverNow(clockSource.now())

    fun create(serverUrl: String, name: String, initial: InitialQueue) {
        val c = begin(serverUrl) ?: return
        pendingOverflow = initial.overflow
        c.start(JoinIntent.Create(name, initial.first, initial.startIndex, initial.startPositionMs))
    }

    fun join(serverUrl: String, code: String, name: String) {
        val c = begin(serverUrl) ?: return
        pendingOverflow = emptyList()
        c.start(JoinIntent.Join(code, name))
    }

    fun leave() {
        conn?.leave()
    }

    /** Back to IDLE after the session ended (the entry sheet calls it before retrying or closing). */
    fun dismiss() {
        if (_state.value.phase == RitmePhase.ENDED) _state.value = RitmeReducer.reset(_state.value)
    }

    fun play(): Boolean = send(Command.Play)
    fun pause(): Boolean = send(Command.Pause)
    fun seek(positionMs: Long): Boolean = send(Command.Seek(positionMs.coerceAtLeast(0)))
    fun next(): Boolean = send(Command.Next)
    fun previous(): Boolean = send(Command.Previous)
    fun playEntry(entryId: String): Boolean = send(Command.PlayEntry(entryId))
    fun add(entries: List<NewEntry>, playNext: Boolean): Boolean = send(Command.Add(entries, playNext))
    fun remove(entryId: String): Boolean = send(Command.Remove(entryId))
    fun move(entryId: String, afterEntryId: String?): Boolean = send(Command.Move(entryId, afterEntryId))
    fun setGuestsCanControl(value: Boolean): Boolean = send(Command.SetSettings(RoomSettings(guestsCanControl = value)))
    fun kick(memberId: String): Boolean = send(Command.Kick(memberId))
    fun transferHost(memberId: String): Boolean = send(Command.TransferHost(memberId))
    fun endRoom(): Boolean = send(Command.EndRoom)

    fun trackEnded(entryId: String): Boolean = canSend() && conn?.trackEnded(entryId) == true

    private fun begin(serverUrl: String): ConnectionLike? {
        val phase = _state.value.phase
        if (phase == RitmePhase.CONNECTING || phase == RitmePhase.IN_ROOM) return null
        clock.reset()
        _state.value = RitmeReducer.start(_state.value)
        lateinit var c: ConnectionLike
        c = connector.connect(RitmeLinks.socketUrl(serverUrl)) { event -> if (conn === c) onEvent(event) }
        conn = c
        return c
    }

    private fun canSend(): Boolean =
        _state.value.phase == RitmePhase.IN_ROOM && _state.value.connection == ConnectionStatus.ONLINE

    private fun send(cmd: Command): Boolean = canSend() && conn?.command(cmd) != null

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
                pendingOverflow = emptyList()
            }
            else -> Unit
        }
        _state.value = RitmeReducer.reduce(_state.value, event)
        // The rest of a big starting queue goes out once the room exists (Create holds at most 200).
        if (event is ConnectionEvent.Status && event.status == ConnectionStatus.ONLINE && pendingOverflow.isNotEmpty()) {
            val batches = pendingOverflow
            pendingOverflow = emptyList()
            batches.forEach { add(it, playNext = false) }
        }
    }
}

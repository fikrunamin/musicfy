package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.Member
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.protocol.ServerMessage
import com.example.musicfy.ritme.protocol.encode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A server message together with its JSON, encoded exactly once however many peers receive it
 * (a full room snapshot can be hundreds of KB).
 */
class Outgoing(val message: ServerMessage) {
    val text: String = message.encode()
}

/**
 * One client connection, as the room sees it. send/close are called while the room lock is held:
 * implementations must not suspend for long (buffer or drop, close asynchronously).
 */
interface Peer {
    suspend fun send(message: Outgoing)

    suspend fun close()
}

suspend fun Peer.send(message: ServerMessage) = send(Outgoing(message))

/**
 * Live room: applies [RoomLogic] under a mutex, tracks connections, resume tokens and grace
 * periods, and fans state out to every connected peer.
 */
class Room(initial: RoomState, private val clock: Clock, private val ids: IdGen) {
    private val mutex = Mutex()
    private val peers = HashMap<String, Peer>()
    private val tokens = HashMap<String, String>() // resumeToken -> memberId
    private val disconnectedAt = HashMap<String, Long>()
    private val limiters = HashMap<String, RateLimiter>()
    private var emptySince: Long? = null

    @Volatile
    var state: RoomState = initial
        private set

    @Volatile
    var closed: Boolean = false
        private set

    val code: String get() = state.code

    /** The creator's Member is already in the initial state; this wires up their connection. */
    suspend fun attachCreator(memberId: String, peer: Peer): Boolean = mutex.withLock {
        val token = ids.resumeToken()
        tokens[token] = memberId
        peers[memberId] = peer
        welcome(memberId, token, peer, clock.now())
    }

    suspend fun join(name: String, resumeToken: String?, peer: Peer): JoinResult = mutex.withLock {
        if (closed) return@withLock JoinResult.Rejected(ErrorCode.ROOM_NOT_FOUND)
        val now = clock.now()
        val resumedId = resumeToken?.let { tokens[it] }?.takeIf { id -> state.members.any { it.id == id } }
        val memberId: String
        val token: String
        if (resumedId != null) {
            memberId = resumedId
            token = resumeToken
            val previous = peers.put(memberId, peer)
            if (previous != null && previous !== peer) runCatching { previous.close() }
            disconnectedAt.remove(memberId)
            state = RoomLogic.setConnected(state, memberId, true)
        } else {
            if (state.members.size >= Limits.MAX_MEMBERS) return@withLock JoinResult.Rejected(ErrorCode.ROOM_FULL)
            memberId = ids.memberId()
            token = ids.resumeToken()
            tokens[token] = memberId
            peers[memberId] = peer
            state = RoomLogic.addMember(state, Member(memberId, name, isHost = false, connected = true, joinedAt = now))
        }
        if (state.members.none { it.id == state.hostId }) state = RoomLogic.withHost(state, memberId)
        emptySince = null
        val sent = welcome(memberId, token, peer, now)
        broadcast(ServerMessage.State(state), except = memberId)
        if (sent) JoinResult.Joined(this, memberId, peer) else JoinResult.Rejected(ErrorCode.INVALID)
    }

    /**
     * leave, command and trackEnded are ignored unless [peer] is still this member's live
     * connection: a connection replaced by a resume must not act for the member any more.
     */
    suspend fun leave(memberId: String, peer: Peer) = mutex.withLock {
        if (peers[memberId] !== peer) return@withLock
        forget(memberId)
        if (state.members.any { it.id == memberId }) {
            state = RoomLogic.removeMembers(state, setOf(memberId))
            broadcast(ServerMessage.State(state))
        }
    }

    /** Ignored unless [peer] is still this member's live connection (a reconnect replaces it). */
    suspend fun disconnected(memberId: String, peer: Peer) = mutex.withLock {
        if (peers[memberId] !== peer) return@withLock
        peers.remove(memberId)
        if (state.members.none { it.id == memberId }) return@withLock
        disconnectedAt[memberId] = clock.now()
        state = RoomLogic.setConnected(state, memberId, false)
        broadcast(ServerMessage.State(state))
    }

    suspend fun command(memberId: String, peer: Peer, reqId: String, cmd: Command) = mutex.withLock {
        val sender = peers[memberId]?.takeIf { it === peer } ?: return@withLock
        if (!limiter(memberId).tryAcquire()) {
            sender.send(ServerMessage.Error(reqId, ErrorCode.RATE_LIMITED))
            return@withLock
        }
        when (val outcome = RoomLogic.apply(state, memberId, cmd, clock.now(), ids)) {
            is Outcome.Rejected -> sender.send(ServerMessage.Error(reqId, outcome.code))
            is Outcome.Ok -> {
                val changed = outcome.state !== state
                state = outcome.state
                when (val effect = outcome.effect) {
                    is Effect.Kicked -> {
                        val kicked = peers[effect.memberId]
                        forget(effect.memberId)
                        if (kicked != null) runCatching {
                            kicked.send(ServerMessage.Kicked)
                            kicked.close()
                        }
                    }
                    Effect.Closed -> {
                        closeAll()
                        return@withLock
                    }
                    null -> Unit
                }
                if (changed) broadcast(ServerMessage.State(state))
            }
        }
    }

    suspend fun trackEnded(memberId: String, peer: Peer, entryId: String) = mutex.withLock {
        if (peers[memberId] !== peer) return@withLock
        // Shares the command budget, so end reports cannot be used to spin the room.
        if (!limiter(memberId).tryAcquire()) return@withLock
        val next = RoomLogic.onTrackEnded(state, entryId, clock.now()) ?: return@withLock
        state = next
        broadcast(ServerMessage.State(next))
    }

    /** Expires members past grace, runs the fallback advance; true when the room should be deleted. */
    suspend fun sweep(): Boolean = mutex.withLock {
        if (closed) return@withLock true
        val now = clock.now()
        var next = state
        val expired = disconnectedAt.filterValues { now - it >= Limits.GRACE_MS }.keys.toSet()
        if (expired.isNotEmpty()) {
            expired.forEach { forget(it) }
            next = RoomLogic.removeMembers(next, expired)
        }
        RoomLogic.tick(next, now)?.let { next = it }
        if (next !== state) {
            state = next
            broadcast(ServerMessage.State(next))
        }
        if (next.members.any { it.connected }) {
            emptySince = null
        } else if (emptySince == null) {
            emptySince = now
        }
        val delete = emptySince?.let { now - it >= Limits.EMPTY_ROOM_MS } ?: false
        if (delete) closed = true
        delete
    }

    /** Sends Welcome; if the socket is already gone, start the member's grace period right away. */
    private suspend fun welcome(memberId: String, token: String, peer: Peer, now: Long): Boolean {
        val sent = runCatching { peer.send(ServerMessage.Welcome(memberId, token, state, now)) }.isSuccess
        if (!sent) {
            if (peers[memberId] === peer) peers.remove(memberId)
            disconnectedAt[memberId] = now
            state = RoomLogic.setConnected(state, memberId, false)
        }
        return sent
    }

    private fun limiter(memberId: String): RateLimiter = limiters.getOrPut(memberId) {
        RateLimiter(Limits.COMMANDS_PER_SECOND, Limits.COMMANDS_PER_SECOND.toDouble(), clock)
    }

    private fun forget(memberId: String) {
        peers.remove(memberId)
        tokens.entries.removeIf { it.value == memberId }
        disconnectedAt.remove(memberId)
        limiters.remove(memberId)
    }

    private suspend fun closeAll() {
        closed = true
        val roomClosed = Outgoing(ServerMessage.RoomClosed)
        peers.values.forEach { peer ->
            runCatching {
                peer.send(roomClosed)
                peer.close()
            }
        }
        peers.clear()
        tokens.clear()
    }

    private suspend fun broadcast(message: ServerMessage, except: String? = null) {
        val outgoing = Outgoing(message)
        peers.forEach { (id, peer) -> if (id != except) runCatching { peer.send(outgoing) } }
    }
}


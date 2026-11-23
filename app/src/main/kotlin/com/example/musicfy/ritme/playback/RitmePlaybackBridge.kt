package com.example.musicfy.ritme.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.musicfy.extensions.toMediaItem
import com.example.musicfy.ritme.LocalPlayOrder
import com.example.musicfy.ritme.RitmeController
import com.example.musicfy.ritme.RitmeGate
import com.example.musicfy.ritme.RitmeLocalPlayback
import com.example.musicfy.ritme.RitmeNotifier
import com.example.musicfy.ritme.RitmeToast
import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.net.ConnectionStatus
import com.example.musicfy.ritme.net.EndReason
import com.example.musicfy.ritme.net.RitmeTimer
import com.example.musicfy.ritme.net.RitmeTimers
import com.example.musicfy.ritme.protocol.QueueEntry
import com.example.musicfy.ritme.session.RitmeModeTransitions
import com.example.musicfy.ritme.session.RitmeSession
import com.example.musicfy.ritme.session.RitmeTransition
import com.example.musicfy.ritme.session.RitmeUiState
import com.example.musicfy.ritme.sync.LocalPlayerView
import com.example.musicfy.ritme.sync.PlayerOp
import com.example.musicfy.ritme.sync.RitmeSyncPlanner
import com.example.musicfy.ritme.sync.SyncInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

/**
 * Makes MusicService's player follow the room (spec 1.2, 2.5, 2.6). Attached by MusicService for
 * its lifetime. On the first Welcome it saves the local queue (RitmePlayerHost.enterRitmeMode) and
 * opens the gate; on any end it closes the gate and restores the queue paused. In between it runs
 * RitmeSyncPlanner on every snapshot, every second and at the start anchor, reports TrackEnded
 * once per entry, and toasts rejected commands. Main thread only.
 *
 * Injecting [controller] here creates it with MusicService, so the gate's handler is always set
 * before the gate can open. The session state is collected on [stateContext], which in the app is
 * Dispatchers.Main.immediate: the gate opens and closes in the same main-thread step that puts the
 * room into, or takes it out of, the state, so no local control can slip in between.
 */
@Singleton
class RitmePlaybackBridge internal constructor(
    private val session: RitmeSession,
    private val controller: RitmeController,
    private val gate: RitmeGate,
    private val notifier: RitmeNotifier,
    private val timers: RitmeTimers,
    private val stateContext: CoroutineContext,
    private val itemFor: (QueueEntry) -> MediaItem,
) : RitmeLocalPlayback {

    @Inject
    constructor(
        session: RitmeSession,
        controller: RitmeController,
        gate: RitmeGate,
        notifier: RitmeNotifier,
        timers: RitmeTimers,
    ) : this(
        session,
        controller,
        gate,
        notifier,
        timers,
        Dispatchers.Main.immediate,
        { entry -> RitmeEntries.toMediaMetadata(entry).toMediaItem() },
    )

    private var scope: CoroutineScope? = null
    private var host: RitmePlayerHost? = null
    private var listening: Player? = null

    private var inRitme = false
    private var snapshot: LocalQueueSnapshot? = null
    private var loadedEntryId: String? = null
    private var endedEntryId: String? = null
    private var reportedEndFor: String? = null
    private var appliedKey: String? = null
    private var lastSeekAt: Long? = null
    private var lastVersion = Long.MIN_VALUE
    private var lastConnection: ConnectionStatus? = null
    private var lastNoticeId = 0L

    private var tickTimer: RitmeTimer? = null
    private var wakeTimer: RitmeTimer? = null
    private var soonTimer: RitmeTimer? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) onLocalEnded()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Deferred: this can fire from inside our own play()/pause() while ops are executing.
            if (inRitme) {
                soonTimer?.cancel()
                soonTimer = timers.schedule(0) {
                    soonTimer = null
                    sync(force = false)
                }
            }
        }
    }

    fun attach(host: RitmePlayerHost) {
        detach()
        this.host = host
        controller.localPlayback = this
        lastNoticeId = session.state.value.notice?.id ?: 0L
        scope = CoroutineScope(SupervisorJob() + stateContext).also { s -> s.launch { session.state.collect(::onState) } }
    }

    fun detach() {
        scope?.cancel()
        scope = null
        stopTimers()
        listening?.removeListener(playerListener)
        listening = null
        if (inRitme) {
            // The service is going away, so nothing would play the room here any more.
            inRitme = false
            gate.deactivate()
            session.leave()
        }
        if (controller.localPlayback === this) controller.localPlayback = null
        host = null
    }

    private fun onState(state: RitmeUiState) {
        state.notice?.takeIf { it.id != lastNoticeId }?.let {
            lastNoticeId = it.id
            notifier.show(RitmeToast.CommandFailed(it.key))
        }
        when (val transition = RitmeModeTransitions.next(inRitme, state)) {
            RitmeTransition.Enter -> enter()
            is RitmeTransition.Exit -> exit(transition.reason)
            RitmeTransition.None -> Unit
        }
        if (!inRitme) return
        val room = state.room ?: return
        val reconnected = lastConnection == ConnectionStatus.RECONNECTING && state.connection == ConnectionStatus.ONLINE
        lastConnection = state.connection
        if (reconnected && loadedEntryId != null && endedEntryId == loadedEntryId) onLocalEnded()
        if (room.version != lastVersion || reconnected) {
            lastVersion = room.version
            sync(force = reconnected)
        }
    }

    private fun enter() {
        val h = host ?: return
        // Order matters: persist the user's queue while the gate is closed, then open the gate so
        // nothing done while entering (shuffle/repeat off, the room's item) is persisted.
        h.saveLocalQueue()
        gate.activate()
        snapshot = h.enterRitmeMode()
        inRitme = true
        resetTracking()
        if (snapshot?.items?.isNotEmpty() == true) notifier.show(RitmeToast.QueueSaved)
        tickTimer?.cancel()
        tickTimer = timers.schedule(0, ::tick)
    }

    private fun exit(reason: EndReason?) {
        stopTimers()
        // Closed in the same step that took the room away: from here local controls are local again.
        inRitme = false
        gate.deactivate()
        host?.exitRitmeMode(snapshot ?: LocalQueueSnapshot.EMPTY)
        snapshot = null
        resetTracking()
        reason?.let { notifier.show(RitmeToast.Ended(it)) }
        session.dismiss()
    }

    private fun tick() {
        tickTimer = null
        if (!inRitme) return
        sync(force = false)
        if (inRitme) tickTimer = timers.schedule(TICK_MS, ::tick)
    }

    private fun stopTimers() {
        tickTimer?.cancel()
        tickTimer = null
        wakeTimer?.cancel()
        wakeTimer = null
        soonTimer?.cancel()
        soonTimer = null
    }

    private fun resetTracking() {
        loadedEntryId = null
        endedEntryId = null
        reportedEndFor = null
        appliedKey = null
        lastSeekAt = null
        lastVersion = Long.MIN_VALUE
        lastConnection = null
    }

    private fun sync(force: Boolean) {
        if (!inRitme) return
        val h = host ?: return
        val player = h.player
        if (listening !== player) {
            listening?.removeListener(playerListener)
            player.addListener(playerListener)
            listening = player
        }
        val localNow = timers.now()
        val local = LocalPlayerView(
            loadedEntryId = loadedEntryId,
            positionMs = player.currentPosition,
            isAdvancing = player.isPlaying,
            playWhenReady = player.playWhenReady,
            ended = loadedEntryId != null && endedEntryId == loadedEntryId,
            speed = player.playbackParameters.speed,
        )
        val plan = RitmeSyncPlanner.plan(
            SyncInput(session.state.value.room, session.serverNow(), local, appliedKey, localNow, lastSeekAt, force),
        )
        appliedKey = plan.appliedKey
        plan.ops.forEach { execute(h, player, it, localNow) }
        wakeTimer?.cancel()
        wakeTimer = plan.wakeAtServerMs?.let { at ->
            timers.schedule((at - session.serverNow()).coerceAtLeast(0)) {
                wakeTimer = null
                sync(force = false)
            }
        }
    }

    private fun execute(h: RitmePlayerHost, player: ExoPlayer, op: PlayerOp, localNow: Long) {
        when (op) {
            is PlayerOp.Load -> {
                loadedEntryId = op.entry.entryId
                endedEntryId = null
                reportedEndFor = null
                lastSeekAt = null
                h.loadRitmeItem(itemFor(op.entry), op.startMs)
            }
            PlayerOp.Play -> {
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            }
            PlayerOp.Pause -> player.pause()
            is PlayerOp.SeekTo -> {
                player.seekTo(op.positionMs)
                lastSeekAt = localNow
                endedEntryId = null
                reportedEndFor = null // a replayed entry reports its end again
            }
            is PlayerOp.SetSpeed -> player.playbackParameters = PlaybackParameters(op.speed, 1f)
            PlayerOp.Halt -> {
                player.pause()
                if (player.playbackParameters != PlaybackParameters.DEFAULT) player.playbackParameters = PlaybackParameters.DEFAULT
            }
        }
    }

    /** Android reports the end of each entry once (spec 2.6); the server ignores stale reports. */
    private fun onLocalEnded() {
        val id = loadedEntryId ?: return
        endedEntryId = id
        if (session.state.value.room?.currentEntryId == id && reportedEndFor != id && session.trackEnded(id)) {
            reportedEndFor = id
        }
    }

    override val isPlayingLocally: Boolean get() = host?.player?.playWhenReady == true

    override val isCasting: Boolean get() = host?.isCasting == true

    /**
     * Plays this device again in sync. Never a bare play(): the forced re-sync lets the planner
     * decide, so during the start anchor the player stays paused and starts at the anchor with
     * everyone else, and otherwise it is seeked to the room position and played.
     */
    override fun resumeLocally() {
        sync(force = true)
    }

    /** Pause on this device only; the planner then leaves it alone until the room changes. */
    override fun pauseLocally() {
        host?.player?.pause()
    }

    override fun playOrderQueue(): LocalPlayOrder = host?.playOrderQueue() ?: LocalPlayOrder(emptyList(), 0, 0)

    private companion object {
        const val TICK_MS = 1_000L
    }
}

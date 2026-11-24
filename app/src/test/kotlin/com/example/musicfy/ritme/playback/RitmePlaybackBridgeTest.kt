package com.example.musicfy.ritme.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.musicfy.ritme.LocalPlayOrder
import com.example.musicfy.ritme.RitmeController
import com.example.musicfy.ritme.RitmeGate
import com.example.musicfy.ritme.RitmeLocalAction
import com.example.musicfy.ritme.RitmeSettings
import com.example.musicfy.ritme.RitmeToast
import com.example.musicfy.ritme.net.ConnectionEvent
import com.example.musicfy.ritme.net.ConnectionStatus
import com.example.musicfy.ritme.net.EndReason
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.Playback
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.session.NoticeKey
import com.example.musicfy.ritme.session.RitmePhase
import com.example.musicfy.ritme.session.RitmeSession
import com.example.musicfy.ritme.testing.FakeConnector
import com.example.musicfy.ritme.testing.FakeNotifier
import com.example.musicfy.ritme.testing.FakeTimers
import com.example.musicfy.ritme.testing.entry
import com.example.musicfy.ritme.testing.room
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/** The parts of ExoPlayer the bridge touches; anything else fails the test loudly. */
private class FakePlayer {
    var positionMs = 0L
    var playWhenReady = false
    var state = Player.STATE_IDLE
    var params: PlaybackParameters = PlaybackParameters.DEFAULT
    var itemCount = 0
    val listeners = mutableListOf<Player.Listener>()
    val ops = mutableListOf<String>()

    val player: ExoPlayer = Proxy.newProxyInstance(
        ExoPlayer::class.java.classLoader,
        arrayOf(ExoPlayer::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getCurrentPosition" -> positionMs
            "isPlaying" -> playWhenReady && state == Player.STATE_READY
            "getPlayWhenReady" -> playWhenReady
            "getPlaybackState" -> state
            "getMediaItemCount" -> itemCount
            "getPlaybackParameters" -> params
            "setPlaybackParameters" -> {
                params = args!![0] as PlaybackParameters
                ops += "speed ${params.speed}"
                null
            }
            "play" -> {
                playWhenReady = true
                ops += "play"
                null
            }
            "pause" -> {
                playWhenReady = false
                ops += "pause"
                null
            }
            "seekTo" -> {
                positionMs = args!!.last() as Long
                if (state == Player.STATE_ENDED) state = Player.STATE_READY
                ops += "seek $positionMs"
                null
            }
            "prepare" -> {
                if (state == Player.STATE_IDLE) state = Player.STATE_READY
                ops += "prepare"
                null
            }
            "seekToDefaultPosition" -> {
                ops += "live edge"
                null
            }
            "addListener" -> {
                listeners += args!![0] as Player.Listener
                null
            }
            "removeListener" -> {
                listeners -= args!![0] as Player.Listener
                null
            }
            "equals" -> proxy === args!![0]
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "FakePlayer"
            else -> throw UnsupportedOperationException("FakePlayer.${method.name}")
        }
    } as ExoPlayer

    /** The player reaches the end of the loaded item. */
    fun end() {
        state = Player.STATE_ENDED
        listeners.toList().forEach { it.onPlaybackStateChanged(Player.STATE_ENDED) }
    }

    /** clearMediaItems() on a READY player: it reports STATE_ENDED synchronously, with no items left. */
    fun clear() {
        itemCount = 0
        state = Player.STATE_ENDED
        listeners.toList().forEach { it.onPlaybackStateChanged(Player.STATE_ENDED) }
    }
}

private class FakeHost(private val fake: FakePlayer, private val gate: RitmeGate) : RitmePlayerHost {
    override val player: ExoPlayer get() = fake.player
    override var isCasting = false
    val calls = mutableListOf<String>()
    var snapshot = LocalQueueSnapshot.EMPTY
    var exitedWith: LocalQueueSnapshot? = null

    override fun playOrderQueue() = LocalPlayOrder(emptyList(), 0, 0)

    override fun saveLocalQueue() {
        calls += "save gate=${gate.isActive}"
    }

    override fun enterRitmeMode(): LocalQueueSnapshot {
        calls += "enter gate=${gate.isActive}"
        fake.playWhenReady = false
        return snapshot
    }

    override fun loadRitmeItem(item: MediaItem, startPositionMs: Long?) {
        calls += "load ${item.mediaId}@$startPositionMs"
        fake.positionMs = startPositionMs ?: 0
        fake.itemCount = 1
        fake.state = Player.STATE_READY
        fake.playWhenReady = false
    }

    override fun exitRitmeMode(snapshot: LocalQueueSnapshot) {
        calls += "exit gate=${gate.isActive}"
        exitedWith = snapshot
    }
}

class RitmePlaybackBridgeTest {

    private val timers = FakeTimers()
    private val connector = FakeConnector()
    private val session = RitmeSession(connector, timers)
    private val gate = RitmeGate()
    private val toasts = FakeNotifier()
    private val settings = object : RitmeSettings {
        override val serverUrl = "https://ritme.dubeli.id"
        override val displayName = "Budi"
    }
    private val controller = RitmeController(session, gate, toasts, settings)
    private val fake = FakePlayer()
    private val host = FakeHost(fake, gate)
    private val bridge = RitmePlaybackBridge(session, controller, gate, toasts, timers, Dispatchers.Unconfined) {
        MediaItem.Builder().setMediaId(it.entryId).build()
    }

    /** e1 is current and starts at the anchor (server time 1500; the test clock starts at 0). */
    private val starting = room(
        queue = listOf(entry("e1"), entry("e2")),
        currentEntryId = "e1",
        playback = Playback(isPlaying = true, positionMs = 0, anchorServerTimeMs = 1_500),
    )

    @Before
    fun setUp() {
        bridge.attach(host)
        bridge.onServiceReady(host)
    }

    private fun join(state: RoomState, memberId: String = "m1") {
        session.join(settings.serverUrl, state.code, "Budi")
        connector.last.welcome(state, memberId, serverTime = timers.time, receivedAt = timers.time)
    }

    private fun update(state: RoomState) = connector.last.emit(ConnectionEvent.State(state))

    /** Joined, the anchor passed: e1 plays from 0. */
    private fun joinPlaying() {
        join(starting)
        timers.advanceBy(1_500)
        assertTrue(fake.playWhenReady)
    }

    @Test
    fun enteringSavesTheLocalQueueWithTheGateClosedThenHandsThePlayerToTheRoom() {
        host.snapshot = LocalQueueSnapshot.EMPTY.copy(items = listOf(MediaItem.Builder().setMediaId("mine").build()))

        join(starting)

        assertEquals(listOf("save gate=false", "enter gate=true", "load e1@0"), host.calls)
        assertTrue(gate.isActive)
        assertTrue(RitmeToast.QueueSaved in toasts.shown)
        assertSame(bridge, controller.localPlayback)
    }

    @Test
    fun anEmptyLocalQueueIsNotAnnouncedAsSaved() {
        join(starting)

        assertFalse(RitmeToast.QueueSaved in toasts.shown)
    }

    @Test
    fun everyDeviceStartsAtTheAnchor() {
        join(starting)
        timers.advanceBy(1_499)
        assertFalse(fake.playWhenReady)

        timers.advanceBy(1)

        assertTrue(fake.playWhenReady)
    }

    @Test
    fun controlsGoToTheRoomOnceInside() {
        joinPlaying()

        assertTrue(gate.intercept(RitmeLocalAction.Pause))

        assertEquals(listOf<Command>(Command.Pause), connector.last.commands)
    }

    @Test
    fun theEndOfAnEntryIsReportedOnce() {
        joinPlaying()

        fake.end()
        fake.end()
        timers.advanceBy(3_000)

        assertEquals(listOf("e1"), connector.last.trackEnds)
    }

    @Test
    fun anEntryReplayedByARoomSeekReportsItsEndAgain() {
        joinPlaying()
        fake.end()

        update(starting.copy(version = 2, playback = Playback(isPlaying = true, positionMs = 10_000, anchorServerTimeMs = timers.time)))
        assertEquals("seek 10000", fake.ops.first { it.startsWith("seek") })
        fake.end()

        assertEquals(listOf("e1", "e1"), connector.last.trackEnds)
    }

    @Test
    fun anEndReachedOfflineIsReportedOnReconnect() {
        joinPlaying()
        connector.last.emit(ConnectionEvent.Status(ConnectionStatus.RECONNECTING))
        fake.end()
        assertEquals(emptyList<String>(), connector.last.trackEnds)

        connector.last.emit(ConnectionEvent.Status(ConnectionStatus.ONLINE))

        assertEquals(listOf("e1"), connector.last.trackEnds)
    }

    @Test
    fun anEndIsReportedForTheEntryLoadedNow() {
        joinPlaying()
        // The room moved on (and the new entry starts at a future anchor) before the old one ended here.
        val moved = starting.copy(
            version = 2,
            currentEntryId = "e2",
            playback = Playback(isPlaying = true, positionMs = 0, anchorServerTimeMs = timers.time + 1_500),
        )
        update(moved)
        assertEquals("load e2@0", host.calls.last())

        fake.end()

        assertEquals(listOf("e2"), connector.last.trackEnds) // reported for what is loaded now, not e1
    }

    @Test
    fun leavingClosesTheGateInTheSameStepAndRestoresTheQueuePaused() {
        val saved = LocalQueueSnapshot.EMPTY.copy(items = listOf(MediaItem.Builder().setMediaId("mine").build()), index = 0)
        host.snapshot = saved
        joinPlaying()

        session.leave()

        // No main-thread turn in between: a tap right after the room is gone reaches the local player.
        assertFalse(gate.isActive)
        assertFalse(gate.intercept(RitmeLocalAction.Pause))
        assertEquals("exit gate=false", host.calls.last())
        assertSame(saved, host.exitedWith)
        assertTrue(RitmeToast.Ended(EndReason.Left) in toasts.shown)
        assertEquals(RitmePhase.IDLE, session.state.value.phase)
    }

    @Test
    fun nothingTouchesThePlayerAfterLeaving() {
        joinPlaying()
        session.leave()
        val opsAfterLeave = fake.ops.size

        timers.advanceBy(10_000)

        assertEquals(opsAfterLeave, fake.ops.size)
        assertEquals(0, timers.pendingCount())
    }

    @Test
    fun resumeDuringTheStartAnchorWaitsForTheAnchor() {
        join(starting)

        bridge.resumeLocally()
        assertFalse(fake.playWhenReady)

        timers.advanceBy(1_500)
        assertTrue(fake.playWhenReady)
    }

    @Test
    fun aLocalPauseIsLeftAloneUntilResumedThenPlaysInSync() {
        joinPlaying()
        timers.advanceBy(5_000)
        fake.positionMs = 5_000

        bridge.pauseLocally()
        timers.advanceBy(3_000)
        assertFalse(fake.playWhenReady)

        bridge.resumeLocally()

        assertTrue(fake.playWhenReady)
        assertEquals(8_000, fake.positionMs)
    }

    @Test
    fun aMemberWithoutControlPausesOnlyThisDevice() {
        join(starting.copy(settings = starting.settings.copy(guestsCanControl = false)), memberId = "m2")
        timers.advanceBy(1_500)

        assertTrue(gate.intercept(RitmeLocalAction.Pause))
        timers.advanceBy(3_000)

        assertFalse(fake.playWhenReady)
        assertEquals(emptyList<Command>(), connector.last.commands)
        assertTrue(RitmeToast.PausedLocally in toasts.shown)
    }

    @Test
    fun aRejectedCommandIsToastedOnce() {
        joinPlaying()

        connector.last.emit(ConnectionEvent.Failure("r1", ErrorCode.FORBIDDEN))
        update(starting.copy(version = 2))

        assertEquals(1, toasts.shown.count { it == RitmeToast.CommandFailed(NoticeKey.FORBIDDEN) })
    }

    @Test
    fun detachingInsideARoomLeavesItAndClosesTheGate() {
        joinPlaying()

        bridge.detach()

        assertFalse(gate.isActive)
        assertTrue(connector.last.left)
        assertNull(controller.localPlayback)
        assertFalse(host.calls.any { it.startsWith("exit") }) // the service is going away; the saved queue stays on disk
        assertTrue(fake.listeners.isEmpty())
    }

    @Test
    fun aRoomAlreadyActiveIsEnteredOnlyOnceTheServiceIsReady() {
        bridge.detach()
        bridge.attach(host) // a new MusicService, still restoring its queue
        join(starting)
        assertEquals(emptyList<String>(), host.calls)
        assertFalse(gate.isActive)

        bridge.onServiceReady(host)

        assertEquals(listOf("save gate=false", "enter gate=true", "load e1@0"), host.calls)
        assertTrue(gate.isActive)
    }

    @Test
    fun castingThatStartsInsideARoomLeavesIt() {
        joinPlaying()

        host.isCasting = true
        timers.advanceBy(1_000)

        assertTrue(connector.last.left)
        assertTrue(RitmeToast.CastingStopped in toasts.shown)
        assertFalse(RitmeToast.Ended(EndReason.Left) in toasts.shown) // one toast says why, not two
        assertFalse(gate.isActive)
        assertEquals("exit gate=false", host.calls.last())
    }

    @Test
    fun onlyTheCastLeaveIsNotToastedAsLeft() {
        joinPlaying()
        host.isCasting = true
        timers.advanceBy(1_000)
        host.isCasting = false

        joinPlaying()
        session.leave()

        assertEquals(1, toasts.shown.count { it == RitmeToast.Ended(EndReason.Left) })
    }

    @Test
    fun clearingTheRoomItemSendsNoTrackEnded() {
        joinPlaying()

        // MusicService.onDestroy clearing a READY player fires STATE_ENDED synchronously; that is
        // not the end of the entry, and a TrackEnded would skip it for the whole room.
        fake.clear()
        bridge.detach()

        assertEquals(emptyList<String>(), connector.last.trackEnds)
        assertTrue(connector.last.left)
    }

    @Test
    fun detachingWhileTheCurrentEntryIsLoadedSendsNoTrackEnded() {
        joinPlaying()

        // onDestroy's order: detach (Leave) first, then clear the room item.
        bridge.detach()
        fake.clear()

        assertEquals(emptyList<String>(), connector.last.trackEnds)
        assertTrue(connector.last.left)
    }

    @Test
    fun detachingWhileConnectingGivesTheAttemptUp() {
        session.join(settings.serverUrl, starting.code, "Budi")
        assertEquals(RitmePhase.CONNECTING, session.state.value.phase)

        bridge.detach()

        assertTrue(connector.last.left)
    }

    @Test
    fun detachingInARoomNotYetEnteredLeavesIt() {
        bridge.detach()
        bridge.attach(host) // a new MusicService, still restoring its queue
        join(starting)
        assertFalse(gate.isActive)

        bridge.detach() // destroyed before it was ready: no ghost member stays behind

        assertTrue(connector.last.left)
    }

    @Test
    fun attachingToARoomAlreadyActiveDoesNotLeaveIt() {
        bridge.detach()
        join(starting) // joined while no service was running

        bridge.attach(host)
        assertFalse(connector.last.left)
        bridge.onServiceReady(host)

        assertTrue(gate.isActive)
    }

    @Test
    fun aStaleServiceSayingReadyIsIgnored() {
        val oldHost = host
        bridge.detach()
        val newHost = FakeHost(fake, gate)
        bridge.attach(newHost) // the new MusicService is still restoring
        join(starting)

        bridge.onServiceReady(oldHost) // the destroyed service's readiness check finishing late

        assertEquals(emptyList<String>(), newHost.calls)
        assertFalse(gate.isActive)
        bridge.onServiceReady(newHost)
        assertTrue(gate.isActive)
    }

    @Test
    fun aRepreparedStreamPlaysOnlyIfTheRoomDoes() {
        joinPlaying()
        // MusicService.failStream: paused, then re-prepared by the retry
        fake.playWhenReady = false
        fake.state = Player.STATE_IDLE

        bridge.resync()

        assertTrue(fake.playWhenReady)
        assertEquals(Player.STATE_READY, fake.state)
    }

    @Test
    fun aRepreparedStreamStaysPausedInAPausedRoom() {
        join(starting.copy(playback = Playback(isPlaying = false, positionMs = 4_000, anchorServerTimeMs = 0)))
        fake.state = Player.STATE_IDLE

        bridge.resync()

        assertFalse(fake.playWhenReady)
    }

    @Test
    fun outsideARoomTheBridgeDoesNothing() {
        timers.advanceBy(10_000)
        bridge.resumeLocally()

        assertEquals(emptyList<String>(), host.calls)
        assertEquals(emptyList<String>(), fake.ops)
        assertFalse(gate.isActive)
    }

    @Test
    fun aLiveEntryLoadsAtItsLiveEdgeAndJumpsBackThereAfterAPause() {
        val live = room(
            queue = listOf(entry("l", durationMs = 0)),
            currentEntryId = "l",
            playback = Playback(isPlaying = true, positionMs = 0, anchorServerTimeMs = 0),
        )
        join(live)
        assertEquals("load l@null", host.calls.last())
        assertTrue(fake.playWhenReady)

        update(live.copy(version = 2, playback = Playback(isPlaying = false, positionMs = 0, anchorServerTimeMs = timers.time)))
        assertFalse(fake.playWhenReady)
        fake.ops.clear()
        update(live.copy(version = 3, playback = Playback(isPlaying = true, positionMs = 0, anchorServerTimeMs = timers.time)))
        assertEquals(listOf("live edge", "play"), fake.ops)
    }
}
